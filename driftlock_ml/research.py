"""Approved exploratory fit to recorded IO-VNBD numbers, not verified SI speed.

Reuses the existing VirtualOdometer architecture and training loss schedule.
Separate checkpoint/output names prevent production inference from accepting it.
"""
from pathlib import Path
import argparse
import datetime
import json
import math
import shutil
import sys
import time
import numpy as np
import pandas as pd
import torch
from torch import nn
from torch.utils.data import DataLoader
from .model import VirtualOdometer, ExportableOdometer
from .utils import device_for, environment, read_json, seed_all, sha256, write_json
from .research_inference import RESEARCH_CONTRACT, ResearchPredictor

WARNING = ('EXPLORATORY: agreement with unchanged recorded GPS SPEED numbers only. '
           'Physical speed units, native phone frame, fix freshness and direction remain unverified. '
           'Not verified vehicle speed, navigation drift, SIH accuracy or Android readiness.')


def fresh_directory(path):
    path=Path(path)
    if path.exists() and (not path.is_dir() or any(path.iterdir())):
        raise FileExistsError(f'Use a new output directory; refusing to overwrite {path}.')
    path.mkdir(parents=True,exist_ok=True)
    return path


def recorded_metrics(reference,prediction,sigma=None):
    reference,prediction=np.asarray(reference,float),np.asarray(prediction,float)
    if reference.shape != prediction.shape or not reference.size or not np.isfinite(reference).all() or not np.isfinite(prediction).all():
        raise ValueError('Metrics require matching nonempty finite reference/prediction arrays.')
    residual=prediction-reference
    result={'n':int(reference.size),'rmse_reference_units':float(np.sqrt(np.mean(residual**2))),
            'mae_reference_units':float(np.mean(np.abs(residual))),
            'bias_reference_units':float(np.mean(residual)),
            'p95_absolute_error_reference_units':float(np.quantile(np.abs(residual),.95))}
    if sigma is not None:
        sigma=np.asarray(sigma,float)
        if sigma.shape!=reference.shape or not np.isfinite(sigma).all() or np.any(sigma<=0):
            raise ValueError('Sigma must be matching, finite and positive; no failed output is omitted.')
        result.update(mean_sigma_reference_units=float(sigma.mean()),
                      coverage_95pct=float(np.mean(np.abs(residual)<=1.96*sigma)),
                      gaussian_nll=float(np.mean(np.log(sigma)+.5*(residual/sigma)**2+.5*math.log(2*math.pi))))
    return result


def fit_sigma_scale(residual,sigma):
    residual,sigma=np.asarray(residual,float),np.asarray(sigma,float)
    recorded_metrics(np.zeros_like(residual),residual,sigma)
    factor=max(float(np.sqrt(np.mean((residual/sigma)**2))),.01)
    if not np.isfinite(factor) or factor>100:
        raise ValueError('Unstable calibration scale; preserve the failure instead of publishing uncertainty.')
    return factor


def load_research_model(path,device='cpu'):
    checkpoint=torch.load(path,map_location='cpu',weights_only=True)
    if checkpoint.get('research_contract')!=RESEARCH_CONTRACT or checkpoint.get('valid_for_navigation') is not False:
        raise ValueError('Not an explicitly research-only checkpoint.')
    if 'config' in checkpoint:
        raise ValueError('Research checkpoint must not contain the production config key.')
    model=VirtualOdometer(checkpoint['architecture_config'])
    model.load_state_dict(checkpoint['model'])
    return model.to(device).eval(),checkpoint


def check_manifest_binding(path,checkpoint):
    if sha256(path)!=checkpoint.get('run',{}).get('manifest_sha256'):
        raise ValueError('Research manifest differs from training; cannot change split/provenance after fitting.')


def predict_recorded(model,dataset,device,batch_size=512):
    rows=[]
    model.eval()
    with torch.inference_mode():
        for x,y,drive_indices,ends in DataLoader(dataset,batch_size=batch_size,num_workers=0):
            predictions=model(x.to(device)).cpu().numpy()
            if not np.isfinite(predictions).all() or np.any(predictions[:,1]<=0):
                raise FloatingPointError('Invalid model output; failing rather than omitting predictions.')
            for k in range(len(y)):
                drive=dataset.drives[int(drive_indices[k])]; end=int(ends[k])
                angular=np.linalg.norm(drive['x'][end-dataset.window+1:end+1,3:].astype(float),axis=1).mean()
                rows.append({'drive':drive['name'],'group_id':drive['entry']['group_id'],
                    't_recording_s':float(drive['target_t'][end]),'available_t_recording_s':float(drive['t'][end]),
                    'reference_value':float(y[k]),'predicted_reference_value':float(predictions[k,0]),
                    'sigma_reference_units':float(predictions[k,1]),'gyro_norm_recorded':float(angular),
                    'valid_for_navigation':False})
    if not rows: raise ValueError('No prediction windows.')
    return pd.DataFrame.from_records(rows)


def dataset_from_args(args,config,split,probe=False):
    from .research_data import RecordedWindows
    include=None
    if probe:
        include={'S-Vta1a.csv','S-Vta2.csv'} if split=='train' else {'S-Vfa01.csv'}
    stride=config['train_stride'] if split=='train' else config['eval_stride']
    return RecordedWindows(args.manifest,config,split,stride,include_files=include)


def train(args):
    config=read_json(args.config)
    config.update(epochs=args.epochs,window_samples=args.window_samples)
    if config['epochs']<1: raise ValueError('epochs must be positive.')
    seed_all(config['seed']); device=device_for(args.device)
    train_data=dataset_from_args(args,config,'train',args.probe)
    val_data=dataset_from_args(args,config,'val',args.probe)
    out=fresh_directory(args.out)
    model=VirtualOdometer(config)
    model.set_normalization(*train_data.normalization()); model=model.to(device)
    generator=torch.Generator().manual_seed(config['seed'])
    loader=DataLoader(train_data,batch_size=config['batch_size'],shuffle=True,generator=generator,num_workers=0)
    val_loader=DataLoader(val_data,batch_size=512,num_workers=0)
    model(next(iter(loader))[0][:2].to(device)).sum().backward(); model.zero_grad(set_to_none=True)
    references=np.asarray([train_data.drives[d]['y'][e] for d,e in train_data.index],float)
    run={'research_contract':RESEARCH_CONTRACT,'purpose':'exploratory_recorded_reference',
         'warning':WARNING,'architecture_config':config,
         'constructor_config_note':'Existing model constructor settings only; physical metadata do not establish research source meanings. Output scale 10 and floor0.1 are recorded-number units here.',
         'environment':environment(),'device':str(device),'command':sys.argv,
         'started_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
         'manifest_sha256':sha256(args.manifest),'manifest_snapshot':train_data.manifest,
         'source_sha256':{str(p):sha256(p) for p in sorted(Path(__file__).parent.glob('*.py'))},
         'train_mean_reference_value':float(references.mean()),'probe_only':bool(args.probe),
         'train_windows':len(train_data),'validation_windows':len(val_data),
         'train_files':[d['entry']['source_file'] for d in train_data.drives],
         'validation_files':[d['entry']['source_file'] for d in val_data.drives],
         'parameter_count':sum(p.numel() for p in model.parameters()),'final_test_evaluated':False}
    write_json(out/'run.json',run); write_json(out/'architecture_config.json',config)
    print(WARNING,flush=True)
    print(f"Device={device}, parameters={run['parameter_count']}, train windows={len(train_data)}, validation={len(val_data)}, probe={args.probe}",flush=True)
    optimizer=torch.optim.AdamW(model.parameters(),lr=config['learning_rate'],weight_decay=config['weight_decay'])
    nll=nn.GaussianNLLLoss(eps=1e-6)
    warmup=min(config['warmup_epochs'],max(0,config['epochs']-1))
    best=float('inf'); stale=0; history=[]
    for epoch in range(1,config['epochs']+1):
        start=time.perf_counter(); model.train(); total=count=0
        for x,y,_,_ in loader:
            x,y=x.to(device),y.to(device); optimizer.zero_grad(set_to_none=True)
            mean,sigma=model(x).unbind(-1); scale=model.speed_scale
            mean_loss=((mean-y)/scale).square().mean()
            loss=mean_loss if epoch<=warmup else nll(mean/scale,y/scale,(sigma/scale).square())+.1*mean_loss
            if not torch.isfinite(loss): raise FloatingPointError('Nonfinite training loss.')
            loss.backward(); nn.utils.clip_grad_norm_(model.parameters(),config['gradient_clip']); optimizer.step()
            total+=float(loss.detach().cpu())*len(y); count+=len(y)
        model.eval(); squared=val_loss_sum=val_count=0
        with torch.inference_mode():
            for x,y,_,_ in val_loader:
                y=y.to(device); mean,sigma=model(x.to(device)).unbind(-1)
                mean_loss=((mean-y)/model.speed_scale).square().mean()
                loss=mean_loss if epoch<=warmup else nll(mean/model.speed_scale,y/model.speed_scale,(sigma/model.speed_scale).square())+.1*mean_loss
                if not torch.isfinite(loss): raise FloatingPointError('Nonfinite validation loss.')
                squared+=float((mean-y).square().sum().cpu())
                val_loss_sum+=float(loss.cpu())*len(y); val_count+=len(y)
        rmse=(squared/val_count)**.5
        record={'epoch':epoch,'training_loss':total/count,'validation_loss':val_loss_sum/val_count,
                'validation_rmse_reference_units':rmse,'seconds':time.perf_counter()-start,
                'stage':'mean_warmup' if epoch<=warmup else 'gaussian_nll'}
        history.append(record); write_json(out/'history.json',history)
        print(f"Epoch {epoch:02d}/{config['epochs']} loss={record['training_loss']:.5f} val_RMSE(recorded units)={rmse:.5f} seconds={record['seconds']:.3f}",flush=True)
        if epoch>warmup and rmse<best:
            best=rmse; stale=0
            checkpoint={'research_contract':RESEARCH_CONTRACT,'valid_for_navigation':False,
                        'architecture_config':config,'model':{k:v.detach().cpu() for k,v in model.state_dict().items()},
                        'epoch':epoch,'validation_rmse_reference_units':rmse,'run':run,'uncertainty_calibrated':False}
            torch.save(checkpoint,out/'best.tmp'); (out/'best.tmp').replace(out/'best.pt')
        elif epoch>warmup: stale+=1
        if stale>=config['patience']:
            print('Early stopping on validation only.',flush=True); break
    write_json(out/'training_summary.json',{'best_validation_rmse_reference_units':best,
               'checkpoint_sha256':sha256(out/'best.pt'),'epochs_completed':len(history),
               'final_test_evaluated':False,'warning':WARNING})
    print(f'Saved {out}/best.pt. Final-test groups remain untouched.',flush=True)


def held_reference_baselines(pred,duration=30.):
    results=[]
    for name,drive in pred.groupby('drive',sort=False):
        for cut in np.arange(float(drive.t_recording_s.iloc[0])+30.,float(drive.t_recording_s.iloc[-1])-duration,duration):
            before=drive[(drive.t_recording_s<cut)&(drive.t_recording_s>=cut-30.)]
            during=drive[(drive.t_recording_s>=cut)&(drive.t_recording_s<cut+duration)]
            if before.empty or len(during)<int(.95*duration*10): continue
            if cut-float(before.t_recording_s.iloc[-1])>.15 or np.max(np.diff(during.t_recording_s))>.15: continue
            held=float(before.reference_value.iloc[-1])
            for method,estimate in [('hold_last_recorded_pre_cut',np.full(len(during),held)),
                                    ('cnn_gru_delayed_centre',during.predicted_reference_value.to_numpy())]:
                results.append({'drive':name,'cut_recording_s':float(cut),'duration_s':duration,'method':method,
                                **recorded_metrics(during.reference_value,estimate)})
    return results


def evaluate(args):
    device=device_for(args.device); model,checkpoint=load_research_model(args.checkpoint,device)
    check_manifest_binding(args.manifest,checkpoint)
    data=dataset_from_args(args,checkpoint['architecture_config'],'val',checkpoint['run']['probe_only'])
    pred=predict_recorded(model,data,device); out=fresh_directory(args.out)
    pred.to_csv(out/'predictions.csv',index=False)
    metric=lambda p:recorded_metrics(p.reference_value,p.predicted_reference_value,p.sigma_reference_units)
    baseline=checkpoint['run']['train_mean_reference_value']
    report={'research_contract':RESEARCH_CONTRACT,'warning':WARNING,'split':'val','final_test_evaluated':False,
            'checkpoint_sha256':sha256(args.checkpoint),'manifest_sha256':sha256(args.manifest),
            'architecture_config':checkpoint['architecture_config'],'uncertainty_calibrated':checkpoint['uncertainty_calibrated'],
            'all_windows':metric(pred),'by_drive':{name:metric(v) for name,v in pred.groupby('drive')},
            'train_mean_baseline':recorded_metrics(pred.reference_value,np.full(len(pred),baseline)),
            'data_time_base':'seconds_since_recording_start','valid_for_navigation':False,
            'comparison_meaning':'Agreement with exported held GPS values; overlapping errors correlated; no true GPS-blackout or navigation claim.',
            'road_types':'No reviewed road-type labels; no road-type performance assertion.'}
    # Label-change and angular-motion proxies have explicit numerical meanings;
    # they do not establish physical stop/acceleration/braking/turning semantics.
    slope=np.full(len(pred),np.nan)
    for _,v in pred.groupby('drive',sort=False):
        dt=np.diff(v.t_recording_s); changes=np.diff(v.reference_value)
        ix=v.index.to_numpy()[1:]; keep=(dt>0)&(dt<=.15)
        slope[ix[keep]]=changes[keep]/dt[keep]
    masks={'near_zero_recorded_label':np.abs(pred.reference_value)<=.5,
           'increasing_recorded_label':slope>.5,'decreasing_recorded_label':slope<-.5,
           'angular_motion_proxy':pred.gyro_norm_recorded>.1}
    report['behaviour_proxies']={name:metric(pred[mask]) if np.any(mask) else {'n':0} for name,mask in masks.items()}
    report['proxy_definition']='Near-zero abs(raw reference)<=0.5; changes >0.5 or <-0.5 recorded units/s over consecutive <=0.15s rows; angular mean raw gyro norm>0.1. These are not authenticated driving maneuvers.'
    blackout=held_reference_baselines(pred,30.)+held_reference_baselines(pred,60.)
    pd.DataFrame(blackout).to_csv(out/'held_reference_baselines.csv',index=False)
    report['held_reference_baselines']={}
    for method in {r['method'] for r in blackout}:
        rows=[r for r in blackout if r['method']==method]
        report['held_reference_baselines'][method]={'cases':len(rows),'mean_case_rmse_reference_units':float(np.mean([r['rmse_reference_units'] for r in rows]))}
    worst=pred.assign(absolute_error_reference_units=np.abs(pred.predicted_reference_value-pred.reference_value))
    worst.nlargest(25,'absolute_error_reference_units').to_csv(out/'largest_errors.csv',index=False)
    pd.DataFrame([{'drive':name,**values} for name,values in report['by_drive'].items()]).to_csv(out/'per_drive.csv',index=False)
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    for number,(name,drive) in enumerate(pred.groupby('drive',sort=False),1):
        # Decimation affects drawing only; every prediction is scored/saved.
        draw=drive.iloc[::max(1,len(drive)//6000)]
        fig,ax=plt.subplots(figsize=(13,4.5))
        ax.plot(draw.t_recording_s,draw.reference_value,label='Recorded GPS-speed number',linewidth=1)
        ax.plot(draw.t_recording_s,draw.predicted_reference_value,label='CNN+GRU estimate',linewidth=1)
        ax.fill_between(draw.t_recording_s,draw.predicted_reference_value-1.96*draw.sigma_reference_units,
                        draw.predicted_reference_value+1.96*draw.sigma_reference_units,alpha=.12,label='Model ±1.96σ (recorded units)')
        ax.set(title=f'EXPLORATORY — {name} — unverified reference units / no SIH accuracy claim',
               xlabel='Window centre, seconds since recording start',ylabel='Recorded reference units (not verified m/s)')
        ax.legend(); ax.grid(alpha=.25); fig.tight_layout(); fig.savefig(out/f'speed_{number:02d}.png',dpi=140); plt.close(fig)
    write_json(out/'metrics.json',report)
    print(json.dumps({'all_windows':report['all_windows'],'train_mean_baseline':report['train_mean_baseline'],'report':str(out),'warning':WARNING},indent=2),flush=True)


def calibrate(args):
    device=device_for(args.device); model,checkpoint=load_research_model(args.checkpoint,device)
    if checkpoint.get('uncertainty_calibrated'): raise ValueError('Checkpoint is already calibrated.')
    selection=read_json(args.selection)
    if selection.get('checkpoint_sha256')!=sha256(args.checkpoint) or selection.get('manifest_sha256')!=sha256(args.manifest) or selection.get('frozen_before_calibration') is not True:
        raise ValueError('Freeze the actual validation-selected checkpoint and manifest before calibration.')
    if checkpoint['run']['probe_only']: raise ValueError('The timing probe is not a selected model.')
    check_manifest_binding(args.manifest,checkpoint)
    out=Path(args.out)
    if out.exists(): raise FileExistsError('Do not overwrite calibration evidence.')
    data=dataset_from_args(args,checkpoint['architecture_config'],'calibration')
    pred=predict_recorded(model,data,device)
    factor=fit_sigma_scale(pred.reference_value-pred.predicted_reference_value,pred.sigma_reference_units)
    model.uncertainty_scale.mul_(factor)
    checkpoint['model']={k:v.detach().cpu() for k,v in model.state_dict().items()}
    checkpoint['uncertainty_calibrated']=True
    checkpoint['calibration']={'split':'calibration','sigma_multiplier':factor,'selection_sha256':sha256(args.selection),
        'source_checkpoint_sha256':sha256(args.checkpoint),'manifest_sha256':sha256(args.manifest),
        'files':[d['entry']['source_file'] for d in data.drives],
        'definition':'Input-dependent Gaussian residual standard deviation against recorded GPS SPEED numbers, scaled by sqrt(mean((reference-mean)^2/sigma^2)) on calibration only. Units unresolved; no physical speed-accuracy guarantee.',
        'before':recorded_metrics(pred.reference_value,pred.predicted_reference_value,pred.sigma_reference_units),
        'after':recorded_metrics(pred.reference_value,pred.predicted_reference_value,pred.sigma_reference_units*factor),
        'warning':WARNING,'final_test_evaluated':False}
    out.parent.mkdir(parents=True,exist_ok=True); torch.save(checkpoint,out)
    write_json(out.with_suffix('.calibration.json'),checkpoint['calibration'])
    print(json.dumps(checkpoint['calibration'],indent=2),flush=True)


def export_bundle(checkpoint_path,out,sample,times,provenance):
    out=fresh_directory(out); model,checkpoint=load_research_model(checkpoint_path)
    if not checkpoint['uncertainty_calibrated']: raise ValueError('Calibrate selected research model before packaging sigma.')
    config=checkpoint['architecture_config']; window=config['window_samples']
    sample=np.asarray(sample,np.float32); times=np.asarray(times,np.float64)
    if sample.shape!=(1,window,6) or not np.isfinite(sample).all() or times.shape!=(window,) or not np.isfinite(times).all() or np.any(times<0) or not np.allclose(np.diff(times),.1,atol=.01,rtol=0):
        raise ValueError('Golden input requires a finite complete 10 Hz six-channel window and its timestamps.')
    primitive=ExportableOdometer(model).eval(); tensor=torch.from_numpy(sample)
    with torch.inference_mode():
        expected=model(tensor).numpy()
        np.testing.assert_allclose(primitive(tensor).numpy(),expected,atol=2e-5,rtol=2e-5)
    program=torch.export.export(primitive,(tensor,))
    portable=out/'recorded_reference.pt2'; torch.export.save(program,portable)
    loaded=torch.export.load(portable).module(); rng=np.random.default_rng(314)
    with torch.inference_mode():
        np.testing.assert_allclose(loaded(tensor).numpy(),expected,atol=2e-5,rtol=2e-5)
        for _ in range(4):
            other=(model.input_mean.numpy()+model.input_std.numpy()*rng.normal(size=sample.shape)).astype(np.float32)
            v=torch.from_numpy(other)
            np.testing.assert_allclose(loaded(v).numpy(),model(v).numpy(),atol=2e-5,rtol=2e-5)
    np.save(out/'golden_input.npy',sample); np.save(out/'golden_timestamps_s.npy',times); np.save(out/'golden_tensor_output.npy',expected)
    shutil.copyfile(checkpoint_path,out/'research_checkpoint.pt')
    shutil.copyfile(Path(__file__).with_name('research_inference.py'),out/'research_inference.py')
    contract={'research_contract':RESEARCH_CONTRACT,'valid_for_navigation':False,'warning':WARNING,
        'input_shape':[1,window,6],'input_dtype':'float32','channel_order':['ax','ay','az','gx','gy','gz'],
        'input_mean':model.input_mean.flatten().tolist(),'input_std':model.input_std.flatten().tolist(),
        'normalization':'embedded; do not normalize twice','recurrent_state':'reset each complete window',
        'sample_hz':10.,'time_order':'oldest_first','data_time_base':'seconds_since_recording_start',
        'nominal_delay_s':(window-1)/20.,'input_semantics':'Unchanged synchronized source-export XYZ values, no rotation or gravity removal. Native phone-frame compatibility unresolved.',
        'source_header_units':{'acceleration':'m/s² as labelled','gyro':'rad/s as labelled'},
        'output_order':['predicted_reference_value','sigma_reference_units'],'output_units':'unchanged recorded GPS SPEED numeric units; not established as m/s or km/h',
        'uncertainty_definition':'Gaussian predictive standard deviation against recorded reference values, calibration-split scale only; correlated held labels and overlapping windows limit interpretation.',
        'uncertainty_calibrated':True,'golden_input_provenance':provenance,
        'portable_sha256':sha256(portable),'checkpoint_sha256':sha256(checkpoint_path),
        'standalone_script_sha256':sha256(out/'research_inference.py'),'litert_status':'not_converted','android_parity':False}
    write_json(out/'research_contract.json',contract)
    packet=ResearchPredictor(out).predict_window(sample[0],times)
    write_json(out/'golden_output.json',packet)
    write_json(out/'export_verification.json',{'status':'PASS','atol':2e-5,'rtol':2e-5,'golden_and_additional_vectors':5,
               'environment':environment(),'checkpoint_sha256':sha256(checkpoint_path),'warning':WARNING})
    (out/'README.md').write_text('# DRIFTLOCK recorded-reference research model\n\n'+WARNING+'\n\n'
        'This is a trained IO-VNBD research artifact. Do not pass its numbers to the navigation filter as speed_mps. '
        'Read research_contract.json for axes, units, uncertainty and normalization. GRU state resets per window.\n\n'
        'Requires Python, NumPy and the PyTorch version in export_verification.json. No training-code import is required.\n\n'
        'Run from this directory:\n\n```bash\npython research_inference.py --bundle . --input-npy golden_input.npy --timestamps-npy golden_timestamps_s.npy\n```\n\n'
        'Expected result: golden_output.json. Output carries recorded-reference values, sigma in those same unresolved units, '
        'recording-relative centre/availability times and valid_for_navigation=false. The .pt2 file is not .tflite; Android parity is untested.\n')
    return contract


def export(args):
    from .research_data import RecordedWindows
    _,checkpoint=load_research_model(args.checkpoint)
    check_manifest_binding(args.manifest,checkpoint)
    data=RecordedWindows(args.manifest,checkpoint['architecture_config'],'train',stride=10)
    x,_,d,e=data[0]; drive=data.drives[d]
    times=drive['t'][e-data.window+1:e+1]
    export_bundle(args.checkpoint,args.out,x.numpy()[None],times,
                  {'source_file':drive['entry']['source_file'],'group_id':drive['entry']['group_id'],
                   'source_sha256':drive['entry']['source_sha256'],'split':'train','purpose':'numerical parity only'})
    print(f'Portable recorded-reference research bundle saved: {args.out}',flush=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    sub=parser.add_subparsers(dest='operation',required=True)
    for name in ['train','evaluate','calibrate','export']:
        command=sub.add_parser(name); command.add_argument('--manifest',required=True); command.add_argument('--out',required=True)
        command.add_argument('--device',choices=['auto','cpu','mps'],default='auto')
        if name=='train':
            command.add_argument('--config',default='configs/baseline.json'); command.add_argument('--epochs',type=int,default=30)
            command.add_argument('--window-samples',type=int,choices=[20,40],default=20); command.add_argument('--probe',action='store_true')
        else: command.add_argument('--checkpoint',required=True)
        if name=='calibrate': command.add_argument('--selection',required=True)
    args=parser.parse_args(); torch.set_num_threads(4)
    {'train':train,'evaluate':evaluate,'calibrate':calibrate,'export':export}[args.operation](args)


if __name__=='__main__': main()
