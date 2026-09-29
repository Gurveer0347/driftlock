"""Versioned six-IMU latest-target candidates. No automatic production approval.

Synthetic export checks exercise numerical conversion, never vehicle performance.
Source review, model selection, calibration and physical Android parity are distinct gates.
"""
from __future__ import annotations

import argparse
import copy
from dataclasses import asdict, dataclass
import json
import re
from pathlib import Path
import sys
import time

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

from .utils import environment, sha256, speed_metrics, write_json

CONTRACT = 'driftlock-ml-2.0-causal-latest'
CHANNELS = ['ax', 'ay', 'az', 'gx', 'gy', 'gz']


@dataclass(frozen=True)
class MobileConfig:
    architecture: str = 'cnn_gru'
    window_samples: int = 20
    sample_hz: float = 10.0
    channels: int = 32
    hidden_size: int = 32
    speed_scale: float = 10.0
    sigma_floor_mps: float = .1
    model_contract_version: str = CONTRACT
    prediction_timestamp: str = 'latest_sample'

    def __post_init__(self):
        if self.model_contract_version != CONTRACT or self.prediction_timestamp != 'latest_sample':
            raise ValueError('Legacy/research checkpoints cannot be relabelled as causal latest.')
        if self.architecture not in ('cnn_gru', 'cnn', 'tcn'):
            raise ValueError('Choose a bounded cnn_gru, cnn or tcn candidate.')
        if not (2 <= self.window_samples <= 200 and self.sample_hz == 10 and self.hidden_size == 32 and self.channels == 32):
            raise ValueError('This comparison fixes 10 Hz, 32 channels and GRU32; window must be 2–200.')
        if not np.isfinite([self.speed_scale,self.sigma_floor_mps]).all() or min(self.speed_scale,self.sigma_floor_mps) <= 0:
            raise ValueError('Positive finite output scales required.')


class CausalLayer(nn.Module):
    def __init__(self, inputs, outputs, kernel, dilation=1, residual=False):
        super().__init__()
        self.left = (kernel-1)*dilation
        self.conv = nn.Conv1d(inputs, outputs, kernel, dilation=dilation)
        self.residual = residual
        self.skip = nn.Identity() if inputs == outputs else nn.Conv1d(inputs,outputs,1)

    def forward(self,x):
        y = self.conv(F.pad(x,(self.left,0)))
        return F.relu(y + self.skip(x) if self.residual else y)


class MobileOdometer(nn.Module):
    def __init__(self, config: MobileConfig):
        super().__init__()
        self.config = config
        c = config.channels
        self.register_buffer('input_mean',torch.zeros(1,1,6))
        self.register_buffer('input_std',torch.ones(1,1,6))
        self.register_buffer('uncertainty_scale',torch.tensor(1.0))
        if config.architecture == 'tcn':
            self.encoder = nn.Sequential(CausalLayer(6,c,3,1,True),CausalLayer(c,c,3,2,True),CausalLayer(c,c,3,4,True))
        else:
            layers = [CausalLayer(6,c,5),CausalLayer(c,c,3)]
            if config.architecture == 'cnn': layers.append(CausalLayer(c,c,3))
            self.encoder = nn.Sequential(*layers)
        self.gru = nn.GRU(c,32,batch_first=True) if config.architecture == 'cnn_gru' else None
        self.projection = nn.Linear(32,32)
        self.mean_head = nn.Linear(32,1)
        self.sigma_head = nn.Linear(32,1)
        nn.init.constant_(self.sigma_head.bias,-1.5)

    def set_normalization(self,mean,std):
        mean,std = np.asarray(mean,dtype=np.float32),np.asarray(std,dtype=np.float32)
        if mean.shape != (6,) or std.shape != (6,) or not np.isfinite(mean).all() or not np.isfinite(std).all() or np.any(std<=0):
            raise ValueError('Normalization requires six finite means and positive stds fitted on train only.')
        self.input_mean.copy_(torch.from_numpy(mean).reshape(1,1,6))
        self.input_std.copy_(torch.from_numpy(std).reshape(1,1,6))

    def features(self,raw):
        return self.encoder(((raw-self.input_mean)/self.input_std).transpose(1,2)).transpose(1,2)

    def heads(self,hidden):
        z = F.relu(self.projection(hidden))
        mean = self.mean_head(z)*self.config.speed_scale
        sigma = (F.softplus(self.sigma_head(z))*self.config.speed_scale+self.config.sigma_floor_mps)*self.uncertainty_scale
        return torch.cat([mean,sigma],dim=-1)

    def forward(self,raw):
        if not torch.jit.is_tracing() and (raw.ndim != 3 or raw.shape[1:] != (self.config.window_samples,6)):
            raise ValueError('Expected complete [batch,window,6] raw six-channel input.')
        features = self.features(raw)
        if self.gru is not None:
            _, hidden = self.gru(features)  # Implicit zero state for each independent window.
            hidden = hidden[-1]
        elif self.config.architecture == 'cnn':
            hidden = features.mean(dim=1)
        else:
            hidden = features[:,-1]
        return self.heads(hidden)


class ExportableMobile(nn.Module):
    """Primitive GRU equations match the original PyTorch reset-gate placement."""
    def __init__(self,source):
        super().__init__(); self.base=copy.deepcopy(source).cpu().eval()

    def forward(self,raw):
        if self.base.gru is None: return self.base(raw)
        features=self.base.features(raw)
        hidden=torch.zeros_like(features[:,0,:1]).expand(-1,32)
        g=self.base.gru
        for i in range(self.base.config.window_samples):
            ir,iz,inn=F.linear(features[:,i],g.weight_ih_l0,g.bias_ih_l0).chunk(3,-1)
            hr,hz,hn=F.linear(hidden,g.weight_hh_l0,g.bias_hh_l0).chunk(3,-1)
            reset=torch.sigmoid(ir+hr); update=torch.sigmoid(iz+hz)
            new=torch.tanh(inn+reset*hn)
            hidden=(1-update)*new+update*hidden
        return self.base.heads(hidden)


def fit_normalization(imu_rows,*,split):
    if split != 'train': raise ValueError('Normalization may only fit train rows.')
    x=np.asarray(imu_rows,dtype=np.float64)
    if x.ndim != 2 or x.shape[1] != 6 or len(x)==0 or not np.isfinite(x).all():
        raise ValueError('Use finite unique resampled training rows with six channels.')
    return x.mean(0).astype(np.float32),np.maximum(x.std(0),1e-4).astype(np.float32)


def prediction_time(timestamps_s):
    t=np.asarray(timestamps_s,dtype=np.float64)
    if t.ndim != 1 or len(t)<2 or not np.isfinite(t).all() or np.any(t<0) or np.any(t>=1e7) or np.any(np.abs(np.diff(t)-.1)>.01):
        raise ValueError('Invalid 10 Hz window timestamp clock.')
    return float(t[-1])


def make_software_checkpoint(config,path,seed=17):
    """Explicitly UNTRAINED. May only enter numerical software export checks."""
    with torch.random.fork_rng():
        torch.manual_seed(seed); model=MobileOdometer(config).cpu().eval()
    checkpoint={'contract':CONTRACT,'config':asdict(config),'state_dict':model.state_dict(),
                'source_type':'synthetic_software_check','training_status':'UNTRAINED',
                'selected':False,'uncertainty_calibrated':False,'seed':seed,
                'environment':environment(),'source_sha256':sha256(__file__)}
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    with path.open('xb') as handle:torch.save(checkpoint,handle)
    return checkpoint


def load_checkpoint(path):
    checkpoint=torch.load(path,map_location='cpu',weights_only=True)
    if checkpoint.get('contract') != CONTRACT:raise ValueError('Incompatible legacy or research checkpoint.')
    config=MobileConfig(**checkpoint['config']);model=MobileOdometer(config)
    model.load_state_dict(checkpoint['state_dict']);model.eval()
    model.set_normalization(model.input_mean.flatten().numpy(),model.input_std.flatten().numpy())
    if not torch.isfinite(model.uncertainty_scale) or model.uncertainty_scale<=0 or not all(torch.isfinite(p).all() for p in model.parameters()):
        raise ValueError('Nonfinite model weights or invalid sigma scale.')
    return model,checkpoint


def require_reportable_checkpoint(checkpoint):
    if checkpoint.get('contract') != CONTRACT:raise ValueError('Incompatible legacy/research contract.')
    if checkpoint.get('source_type') != 'reviewed_real':raise ValueError('Reportable work requires reviewed real data, never synthetic weights.')
    review=checkpoint.get('data_review',{})
    if review.get('status') != 'APPROVED_FOR_TRAINING' or not review.get('review_evidence') or len(review.get('manifest_sha256','')) != 64:
        raise ValueError('Missing hash-bound reviewed training provenance.')
    selection=checkpoint.get('selection',{})
    if not checkpoint.get('selected') or not selection.get('frozen') or int(selection.get('validation_invocations',0)) <= 0:
        raise ValueError('A selected frozen validation result with positive model invocations is required.')
    if selection.get('decision') != 'candidate_export':raise ValueError('Model selection rejected export or lacks baseline review.')
    calibration=checkpoint.get('calibration',{})
    if not checkpoint.get('uncertainty_calibrated') or calibration.get('split') != 'calibration' or not calibration.get('groups'):
        raise ValueError('Separate calibration is required before reportable export.')
    used=set(checkpoint.get('train_groups',[]))|set(checkpoint.get('validation_groups',[]))
    if not checkpoint.get('train_groups') or not checkpoint.get('validation_groups') or set(checkpoint['train_groups'])&set(checkpoint['validation_groups']) or used.intersection(calibration['groups']):raise ValueError('Calibration group leakage or missing group provenance.')
    scale=float(calibration.get('scale',0))
    if not np.isfinite(scale) or scale<=0:raise ValueError('Invalid calibrated sigma scale.')
    if int(calibration.get('invocations',0))<=0:raise ValueError('Calibration requires positive actual model invocations.')
    if not np.isclose(float(checkpoint['state_dict']['uncertainty_scale']),scale):raise ValueError('Embedded sigma scale does not match calibration.')
    if checkpoint.get('training_summary',{}).get('selection_status')!='REQUIRES_REVIEW':raise ValueError('Baseline rejection prevents reportable export.')
    gates=checkpoint.get('runtime_gates',{})
    if not gates.get('review_evidence') or any(not np.isfinite(gates.get(k,0)) or gates.get(k,0)<=0 for k in ('max_standardized_abs','max_sigma_mps')):
        raise ValueError('Reviewed positive runtime gates are required.')


def _verify_documents(evidence, root):
    if not isinstance(evidence,dict) or not evidence:
        raise ValueError('Hash-bound review evidence documents are required.')
    for item in evidence.values():
        source=(root/item['path']).resolve()
        if not any(source.is_relative_to(root/d) for d in ('docs','reports')) or source.suffix.lower() not in {'.md','.txt','.json','.pdf'}:
            raise ValueError('Review evidence must be a document under docs/ or reports/.')
        if sha256(source)!=item.get('sha256'):raise ValueError('Review evidence hash changed.')


def load_reviewed_splits(manifest_path,approval,*,root=None,read_splits=('train','val','calibration')):
    """Validate all metadata first; never open reserved final-test payloads.

    Approval is a separate human-reviewed document; preparation cannot mint it.
    Evidence assertions are validated for identity/integrity, not automatically
    promoted to proof of physical source meaning.
    """
    root=Path(root or Path(__file__).resolve().parents[1]).resolve()
    path=Path(manifest_path);digest=sha256(path)
    if approval.get('status')!='APPROVED_FOR_TRAINING' or approval.get('manifest_sha256')!=digest or not approval.get('review_evidence'):
        raise ValueError('Separate hash-bound training approval and review evidence required.')
    if approval.get('source_type') not in ('reviewed_real','synthetic_software_check') or not approval.get('reviewer') or not approval.get('reviewed_at'):
        raise ValueError('Explicit source class, reviewer and review date required.')
    if path.with_suffix('.sha256').read_text().strip()!=digest:raise ValueError('Manifest hash changed.')
    manifest=json.loads(path.read_text())
    required={'model_contract_version':CONTRACT,'prediction_timestamp':'latest_sample','feature_channels':CHANNELS,
              'window_samples':20,'sample_hz':10.,'time_base':'seconds_since_boot',
              'data_time_base':'seconds_since_recording_start','frame':'phone','acceleration_includes_gravity':True,
              'acceleration_unit':'m/s2','gyro_unit':'rad/s','speed_unit':'m/s',
              'review_status':'PREPARED_REQUIRES_TRAINING_REVIEW','reserved_final_test_payloads_read':0}
    if any(manifest.get(key)!=value for key,value in required.items()):raise ValueError('Paired manifest contract/status mismatch.')
    if not re.fullmatch('[0-9a-f]{64}',manifest.get('source_review_sha256','')):raise ValueError('Missing source review identity.')
    drives=manifest.get('drives',[])
    if not drives:raise ValueError('No eligible reviewed real drives; source review remains blocked.')
    from .paired_data import RESERVED_GROUPS
    reserved=set(manifest.get('reserved_final_test_groups',[]))|set(RESERVED_GROUPS)
    seen_groups={};seen_trips={};seen_hashes={}
    evidence=manifest.get('source_review_evidence',{})
    # Validate ALL metadata before any canonical CSV or evidence document is read.
    for entry in drives:
        split=entry.get('split');group=entry.get('group_id');trip=entry.get('original_trip_id')
        if split not in ('train','val','calibration') or group in reserved:raise ValueError('Reserved final-test payload access is forbidden.')
        if not group or not trip or not entry.get('source_review_evidence'):raise ValueError('Missing original-trip/source-review evidence.')
        for key,seen in ((group,seen_groups),(trip,seen_trips)):
            if key in seen and seen[key]!=split:raise ValueError('Original-group/trip leakage.')
            seen[key]=split
        for key in ('processed_sha256','phone_source_sha256','reference_source_sha256'):
            content=entry.get(key,'')
            if not re.fullmatch('[0-9a-f]{64}',content):raise ValueError('Missing processed/source data hash.')
            if content in seen_hashes and seen_hashes[content]!=split:raise ValueError('Duplicate source/data leakage.')
            seen_hashes[content]=split
        if entry.get('label_semantics') not in ('vehicle_forward_signed','verified_forward_only') or entry.get('data_time_base')!='seconds_since_recording_start' or not entry.get('time_base_evidence'):
            raise ValueError('Missing reviewed direction or clock semantics.')
        for refs in (entry.get('source_review_evidence'),entry.get('label_semantics_evidence')):
            if not isinstance(refs,list) or not refs or not all(ref in evidence for ref in refs):raise ValueError('Unbound per-drive review evidence.')
        source=(path.parent/entry['path']).resolve()
        if not source.is_relative_to(path.parent.resolve()) or source.suffix!='.csv':raise ValueError('Canonical path escapes prepared dataset.')
    if set(seen_groups.values())!={'train','val','calibration'}:raise ValueError('Independent train/val/calibration groups are required.')
    if not set(read_splits)<=set(seen_groups.values()):raise ValueError('Only development splits may be read.')
    _verify_documents(evidence,root);_verify_documents(approval['review_evidence'],root)
    import pandas as pd
    from .paired_data import causal_windows
    result={s:[] for s in read_splits}
    for entry in drives:
        if entry['split'] not in result:continue
        source=(path.parent/entry['path']).resolve()
        if sha256(source)!=entry['processed_sha256']:raise ValueError('Canonical payload hash changed.')
        frame=pd.read_csv(source,float_precision='round_trip')
        windows=list(causal_windows(frame,window_samples=20))
        if not windows:raise ValueError(f'No usable windows for {entry["path"]}')
        result[entry['split']].append({'entry':entry,'frame':frame,'windows':windows})
    return {'manifest':manifest,'manifest_sha256':digest,'approval':copy.deepcopy(approval),'splits':result}


def _arrays(records):
    windows=[window for record in records for window in record['windows']]
    return np.stack([window['imu'] for window in windows]),np.array([window['speed_mps'] for window in windows],np.float32)


def _predict(model,x,batch_size):
    values=[];calls=0
    with torch.inference_mode():
        for start in range(0,len(x),batch_size):
            values.append(model(torch.from_numpy(x[start:start+batch_size])).numpy());calls+=1
    return np.concatenate(values),calls


def fit_sigma_scale(y,mean,sigma,*,split,groups,used_groups,invocations):
    if split!='calibration':raise ValueError('Sigma scale may only fit the calibration partition.')
    if not groups or not used_groups or set(groups)&set(used_groups):raise ValueError('Calibration group leakage or missing provenance.')
    before=speed_benchmark(y,mean,sigma,invocations=invocations)
    scale=float(np.sqrt(np.mean(((np.asarray(y)-np.asarray(mean))/np.asarray(sigma))**2)))
    scale=max(scale,1e-3)  # Prevent a zero predicted sigma on a perfect finite fixture.
    return {'split':'calibration','groups':sorted(set(groups)),'scale':scale,'invocations':int(invocations),
            'method':'gaussian_nll_global_scale','before':before,
            'after':speed_benchmark(y,mean,np.asarray(sigma)*scale,invocations=invocations)}


def train_candidate(manifest_path,approval,out,config=None,*,root=None,epochs=1,batch_size=64,seed=17):
    """Bounded CPU candidate training; selection and calibration are separate.

    Run a one-epoch probe first and report its timing before a longer approved run.
    Synthetic fixtures remain synthetic, including after fitting.
    """
    config=config or MobileConfig()
    if not (type(epochs) is int and 1<=epochs<=50 and type(batch_size) is int and 1<=batch_size<=1024):
        raise ValueError('Bound training epochs (1..50) and batch size (1..1024).')
    if config.window_samples!=20:raise ValueError('Reviewed canonical training uses 20-sample windows.')
    data=load_reviewed_splits(manifest_path,approval,root=root,read_splits=('train','val'))
    x,y=_arrays(data['splits']['train']);vx,vy=_arrays(data['splits']['val'])
    rows=np.concatenate([record['frame'][CHANNELS].to_numpy(np.float32) for record in data['splits']['train']])
    mean,std=fit_normalization(rows,split='train')
    out=Path(out);out.mkdir(parents=True,exist_ok=False)
    with torch.random.fork_rng():
        torch.manual_seed(seed);model=MobileOdometer(config).cpu();model.set_normalization(mean,std)
        optimizer=torch.optim.Adam(model.parameters(),lr=1e-3)
        generator=np.random.default_rng(seed);history=[];best=None;best_rmse=float('inf');total_calls=0
        for epoch in range(epochs):
            started=time.perf_counter();model.train();order=generator.permutation(len(x));losses=[]
            for offset in range(0,len(x),batch_size):
                indices=order[offset:offset+batch_size];prediction=model(torch.from_numpy(x[indices]))
                target=torch.from_numpy(y[indices]);mu,sigma=prediction[:,0],prediction[:,1]
                loss=(sigma.log()+.5*((target-mu)/sigma).square()).mean()
                if not torch.isfinite(loss):raise ValueError('Nonfinite training loss; run rejected.')
                optimizer.zero_grad();loss.backward();torch.nn.utils.clip_grad_norm_(model.parameters(),5.);optimizer.step()
                losses.append(float(loss.detach()))
            model.eval();prediction,calls=_predict(model,vx,batch_size);total_calls+=calls
            scores=speed_benchmark(vy,prediction[:,0],prediction[:,1],invocations=calls)
            history.append({'epoch':epoch+1,'seconds':time.perf_counter()-started,'loss':float(np.mean(losses)),'validation':scores})
            if scores['rmse_mps']<best_rmse:best_rmse=scores['rmse_mps'];best=copy.deepcopy(model.state_dict())
        model.load_state_dict(best);model.eval();prediction,calls=_predict(model,vx,batch_size);total_calls+=calls
    train_groups=sorted({r['entry']['group_id'] for r in data['splits']['train']})
    validation_groups=sorted({r['entry']['group_id'] for r in data['splits']['val']})
    # Baselines fit train only; flattening contains only the same six-channel window.
    tx=((x-mean)/std).reshape(len(x),-1).astype(np.float64);tv=((vx-mean)/std).reshape(len(vx),-1).astype(np.float64)
    centered=tx-tx.mean(0);target=y-y.mean()
    weights=np.linalg.solve(centered.T@centered+np.eye(tx.shape[1]),centered.T@target)
    baselines={'training_mean':speed_metrics(vy,np.full_like(vy,y.mean())),
               'ridge':speed_metrics(vy,(tv-tx.mean(0))@weights+y.mean())}
    per_drive=[]
    for record in data['splits']['val']:
        dx,dy=_arrays([record]);pred,dcalls=_predict(model,dx,batch_size);total_calls+=dcalls
        per_drive.append({'drive_id':record['entry']['drive_id'],'path':record['entry']['path'],
                          'group_id':record['entry']['group_id'],'metrics':speed_benchmark(dy,pred[:,0],pred[:,1],invocations=dcalls)})
    summary={'source_type':approval['source_type'],'epochs':epochs,'history':history,'config':asdict(config),
             'validation':speed_benchmark(vy,prediction[:,0],prediction[:,1],invocations=calls),
             'validation_invocations':total_calls,'per_drive_validation':per_drive,'baselines':baselines,
             'normalization_fit_rows':len(rows),'selected':False,'calibration_payloads_read':0,
             'reserved_final_test_payloads_read':0,'environment':environment(),'command':sys.argv,'source_sha256':sha256(__file__),
             'selection_status':'REQUIRES_REVIEW' if best_rmse<min(v['rmse_mps'] for v in baselines.values()) else 'REJECTED_BY_BASELINES'}
    write_json(out/'training.json',summary)
    checkpoint={'contract':CONTRACT,'config':asdict(config),'state_dict':model.state_dict(),'source_type':approval['source_type'],
                'training_status':'CANDIDATE','selected':False,'uncertainty_calibrated':False,
                'train_groups':train_groups,'validation_groups':validation_groups,'data_review':copy.deepcopy(approval),
                'training_report_sha256':sha256(out/'training.json'),'training_summary':summary,'seed':seed,
                'label_scope':'verified_forward_only' if any(d['label_semantics']=='verified_forward_only' for d in data['manifest']['drives']) else 'vehicle_forward_signed',
                'source_sha256':sha256(__file__),'environment':environment()}
    with (out/'candidate.pt').open('xb') as handle:torch.save(checkpoint,handle)
    write_json(out/'hashes.json',{p.name:sha256(p) for p in out.iterdir() if p.is_file()})
    return summary


def calibrate_candidate(checkpoint_path,manifest_path,approval,selection_review,out,*,root=None,batch_size=64):
    """Freeze a reviewed validation selection, then fit only calibration sigma.

    Rejected baseline comparisons cannot be overridden by this operation.
    This emits a candidate, never APPROVED_FOR_NAVIGATION or an allowlist entry.
    """
    model,checkpoint=load_checkpoint(checkpoint_path)
    selection=copy.deepcopy(selection_review)
    if selection.get('status')!='SELECTED_FOR_CALIBRATION' or selection.get('checkpoint_sha256')!=sha256(checkpoint_path) or not selection.get('review_evidence'):
        raise ValueError('Hash-bound validation selection review required.')
    if checkpoint.get('training_summary',{}).get('selection_status')!='REQUIRES_REVIEW':raise ValueError('Validation selection rejected by baselines.')
    if checkpoint.get('uncertainty_calibrated') or float(model.uncertainty_scale)!=1.:raise ValueError('Candidate already calibrated; do not fit sigma twice.')
    root=Path(root or Path(__file__).resolve().parents[1]).resolve()
    _verify_documents(selection['review_evidence'],root)
    if checkpoint['data_review']['manifest_sha256']!=sha256(manifest_path) or approval['source_type']!=checkpoint['source_type']:
        raise ValueError('Calibration source/checkpoint identity changed.')
    gates=selection.get('runtime_gates',{})
    if not gates.get('review_evidence') or any(not np.isfinite(gates.get(k,0)) or gates.get(k,0)<=0 for k in ('max_standardized_abs','max_sigma_mps')):
        raise ValueError('Validation-reviewed runtime thresholds required.')
    _verify_documents(gates['review_evidence'],root)
    data=load_reviewed_splits(manifest_path,approval,root=root,read_splits=('calibration',))
    records=data['splits']['calibration'];x,y=_arrays(records);prediction,calls=_predict(model,x,batch_size)
    groups=sorted({r['entry']['group_id'] for r in records})
    calibration=fit_sigma_scale(y,prediction[:,0],prediction[:,1],split='calibration',groups=groups,
                                used_groups=checkpoint['train_groups']+checkpoint['validation_groups'],invocations=calls)
    model.uncertainty_scale.fill_(calibration['scale'])
    checkpoint.update(state_dict=model.state_dict(),selected=True,uncertainty_calibrated=True,calibration=calibration,
                      runtime_gates=gates,selection={**selection,'frozen':True,'decision':'candidate_export',
                         'validation_invocations':checkpoint['training_summary']['validation_invocations']})
    out=Path(out);out.mkdir(parents=True,exist_ok=False)
    with (out/'calibrated_candidate.pt').open('xb') as handle:torch.save(checkpoint,handle)
    write_json(out/'calibration.json',{'source_type':checkpoint['source_type'],'calibration':calibration,
               'manifest_sha256':data['manifest_sha256'],'checkpoint_sha256':sha256(checkpoint_path),
               'selected_checkpoint_sha256':sha256(out/'calibrated_candidate.pt'),'selection':selection,
               'reserved_final_test_payloads_read':0,'source_sha256':sha256(__file__)})
    return calibration


def export_selected(checkpoint_path,manifest_path,approval,out,*,root=None):
    """Obtain goldens from reviewed validation windows; never reads final data."""
    _,checkpoint=load_checkpoint(checkpoint_path);require_reportable_checkpoint(checkpoint)
    data=load_reviewed_splits(manifest_path,approval,root=root,read_splits=('val',))
    if data['manifest_sha256']!=checkpoint['data_review']['manifest_sha256']:raise ValueError('Validation manifest identity changed.')
    chosen=[(record,window) for record in data['splits']['val'] for window in record['windows']][:8]
    x=np.stack([w['imu'] for _,w in chosen]);t=np.stack([w['timestamps_s'] for _,w in chosen])
    provenance={'split':'val','manifest_sha256':data['manifest_sha256'],'data_time_base':'seconds_since_recording_start',
                'groups':sorted({r['entry']['group_id'] for r,_ in chosen}),
                'windows':[{'path':r['entry']['path'],'processed_sha256':r['entry']['processed_sha256'],
                    'phone_source_rows':w['phone_source_rows'].tolist(),'reference_source_row':w['reference_source_row'],
                    'target_t':w['target_t'],'available_t':w['available_t']} for r,w in chosen]}
    return export_onnx(checkpoint_path,out,purpose='reportable',golden_windows=x,golden_timestamps=t,golden_provenance=provenance)


def speed_benchmark(y,mean,sigma,*,invocations):
    if int(invocations)<=0:raise ValueError('ML benchmarks require positive actual invocation counts.')
    y,mean,sigma=map(lambda x:np.asarray(x,dtype=np.float64),(y,mean,sigma))
    if y.shape!=mean.shape or y.shape!=sigma.shape or not all(np.isfinite(v).all() for v in (y,mean,sigma)) or np.any(sigma<=0):
        raise ValueError('Metrics require finite matched speed/sigma arrays.')
    scores=speed_metrics(y,mean,sigma)
    denominator=float(np.sum((y-y.mean())**2))
    scores['r2']=1-float(np.sum((y-mean)**2))/denominator if denominator>0 else None
    scores['model_invocations']=int(invocations)
    scores['nominal_68pct_interval_coverage']=float(np.mean(np.abs(y-mean)<=sigma))
    scores['metric_scope']='speed_only_not_position_drift'
    return scores


def export_onnx(checkpoint_path,out,*,purpose='reportable',golden_windows=None,golden_timestamps=None,golden_provenance=None):
    model,checkpoint=load_checkpoint(checkpoint_path)
    if purpose=='reportable':
        require_reportable_checkpoint(checkpoint)
        if golden_windows is None or golden_timestamps is None or not golden_provenance or golden_provenance.get('split')!='val' or golden_provenance.get('manifest_sha256')!=checkpoint['data_review']['manifest_sha256'] or not golden_provenance.get('windows') or not golden_provenance.get('groups') or not set(golden_provenance['groups'])<=set(checkpoint['validation_groups']):
            raise ValueError('Reportable export needs actual held-out validation golden windows/timestamps/provenance.')
    elif purpose!='synthetic_software_check' or checkpoint.get('source_type')!='synthetic_software_check':
        raise ValueError('Synthetic checks require explicitly synthetic software checkpoints.')
    config=model.config;w=config.window_samples
    if golden_windows is None:
        rng=np.random.default_rng(51)
        x=rng.normal(0,.2,(4,w,6)).astype(np.float32);x[:,:,2]+=9.80665
        t=np.broadcast_to(1000+np.arange(w)*.1,(len(x),w)).copy()
        golden_provenance={'source_type':'synthetic_software_check','data_time_base':'synthetic_boot_clock','purpose':'numerical_parity_only'}
    else:
        x=np.asarray(golden_windows,dtype=np.float32);t=np.asarray(golden_timestamps,dtype=np.float64)
    if x.ndim!=3 or x.shape[1:]!=(w,6) or len(x)<4 or t.shape!=(len(x),w) or not np.isfinite(x).all():
        raise ValueError('Supply at least four finite raw six-channel parity windows and actual timestamps.')
    for timestamps in t:prediction_time(timestamps)
    import onnx
    import onnxruntime as ort
    out=Path(out);out.mkdir(parents=True,exist_ok=False)
    export_model=ExportableMobile(model).eval()
    expected=[]
    with torch.inference_mode():
        for sample in x:
            raw=torch.from_numpy(sample[None])
            a=model(raw).numpy();b=export_model(raw).numpy()
            np.testing.assert_allclose(a,b,atol=2e-5,rtol=2e-5)
            expected.append(a[0])
        torch.onnx.export(export_model,(torch.from_numpy(x[:1]),),str(out/'model.onnx'),
                          input_names=['imu'],output_names=['speed_sigma'],opset_version=17,dynamo=False)
    onnx.checker.check_model(onnx.load(out/'model.onnx'))
    options=ort.SessionOptions();options.intra_op_num_threads=1;options.inter_op_num_threads=1
    session=ort.InferenceSession(str(out/'model.onnx'),sess_options=options,providers=['CPUExecutionProvider'])
    if session.get_inputs()[0].shape!=[1,w,6] or session.get_outputs()[0].shape!=[1,2]:raise ValueError('ONNX boundary shape mismatch.')
    actual=[];calls=0
    for sample in x:
        actual.append(session.run(['speed_sigma'],{'imu':sample[None]})[0][0]);calls+=1
    np.testing.assert_allclose(actual,expected,atol=2e-5,rtol=2e-5)
    for _ in range(5):session.run(None,{'imu':x[:1]});calls+=1
    latency=[]
    for _ in range(20):
        started=time.perf_counter();session.run(None,{'imu':x[:1]});latency.append((time.perf_counter()-started)*1000);calls+=1
    for name,value in [('golden_inputs.npy',x),('golden_timestamps_s.npy',t),('golden_outputs.npy',np.asarray(expected,dtype=np.float32))]:np.save(out/name,value)
    x[0].astype('<f4').tofile(out/'golden_input_f32.bin')
    manifest={
        'model_contract_version':CONTRACT,'architecture':config.architecture,'model_sha256':sha256(out/'model.onnx'),
        'review_status':'ANDROID_PARITY_PENDING' if purpose=='reportable' else 'SOFTWARE_CHECK_ONLY',
        'review_evidence':json.dumps(checkpoint['data_review']['review_evidence'],sort_keys=True) if purpose=='reportable' else 'Synthetic numerical check; no source or navigation approval.',
        'source_type':checkpoint['source_type'],'input_name':'imu','output_name':'speed_sigma','window_samples':w,'sample_hz':10.0,
        'time_base':'seconds_since_boot','prediction_timestamp':'latest_sample','time_order':'oldest_to_newest',
        'input_dtype':'float32','input_shape':[1,w,6],'output_shape':[1,2],'channel_order':CHANNELS,
        'frame':'phone','acceleration_includes_gravity':True,'acceleration_unit':'m/s2','gyro_unit':'rad/s','speed_unit':'m/s',
        'speed_semantics':'vehicle_forward_signed','output_order':['speed_mps','sigma_mps'],
        'normalization':'embedded_in_model_do_not_normalize_twice','input_mean':model.input_mean.flatten().tolist(),
        'input_std':model.input_std.flatten().tolist(),'recurrent_state':'reset_for_each_complete_window',
        'uncertainty_calibrated':bool(checkpoint.get('uncertainty_calibrated',False)),
        'max_standardized_abs':checkpoint.get('runtime_gates',{}).get('max_standardized_abs',20.0),
        'max_sigma_mps':checkpoint.get('runtime_gates',{}).get('max_sigma_mps',5.0),
        'gating_threshold_status':'validation_reviewed' if purpose=='reportable' else 'unvalidated_software_fixture',
        'checkpoint_sha256':sha256(checkpoint_path),'golden_provenance':golden_provenance,'safe_for_driver_guidance':False,
        'label_scope':checkpoint.get('label_scope','synthetic_software_only'),
        'android_parity_status':'NOT_TESTED','automatic_approval':False,'config':asdict(config),
    }
    write_json(out/'manifest.json',manifest)
    report={'purpose':purpose,'source_type':checkpoint['source_type'],'onnx_invocations':calls,'parity_invocations':len(x),
            'warmup_invocations':5,'latency_invocations':20,'max_absolute_error':float(np.max(np.abs(np.asarray(actual)-np.asarray(expected)))),
            'desktop_cpu_latency_median_ms':float(np.median(latency)),'desktop_cpu_latency_p95_ms':float(np.quantile(latency,.95)),
            'model_bytes':(out/'model.onnx').stat().st_size,'parameters':sum(p.numel() for p in model.parameters()),
            'android_device_measurement':False,'onnx':onnx.__version__,'onnxruntime':ort.__version__,
            'environment':environment(),'command':sys.argv,'source_sha256':sha256(__file__),'config':asdict(config)}
    write_json(out/'parity.json',report)
    write_json(out/'hashes.json',{p.name:sha256(p) for p in sorted(out.iterdir()) if p.is_file()})
    return report


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--software-check',action='store_true',help='UNTRAINED synthetic export parity only, not a production model')
    parser.add_argument('--architecture',choices=['cnn_gru','cnn','tcn'],default='cnn_gru')
    parser.add_argument('--out',required=True)
    args=parser.parse_args()
    if not args.software_check:parser.error('No approved real training source is available; explicitly request --software-check for numerical verification.')
    out=Path(args.out);out.mkdir(parents=True,exist_ok=False)
    make_software_checkpoint(MobileConfig(architecture=args.architecture),out/'UNTRAINED_SOFTWARE_CHECK.pt')
    report=export_onnx(out/'UNTRAINED_SOFTWARE_CHECK.pt',out/'onnx',purpose='synthetic_software_check')
    print(json.dumps(report,indent=2))


if __name__=='__main__':main()
