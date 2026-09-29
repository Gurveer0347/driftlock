"""Synthetic software checks only. No source/final-test dataset is opened."""
import json
from pathlib import Path
import numpy as np
import pytest
import torch

from driftlock_ml import mobile as m


@pytest.mark.parametrize('architecture', ['cnn_gru', 'cnn', 'tcn'])
def test_six_channel_models_have_signed_mean_positive_sigma_and_reset_state(architecture):
    model = m.MobileOdometer(m.MobileConfig(architecture=architecture)).eval()
    x = torch.randn(2, 20, 6)
    out = model(x)
    assert out.shape == (2, 2) and torch.isfinite(out).all() and (out[:, 1] > 0).all()
    torch.testing.assert_close(out, torch.cat([model(x[i:i+1]) for i in range(2)]), atol=1e-5, rtol=1e-5)
    with torch.no_grad():
        model.mean_head.weight.zero_(); model.mean_head.bias.fill_(-1)
    assert (model(x)[:, 0] < 0).all()
    with pytest.raises(ValueError, match='six|6'):
        model(torch.randn(1, 20, 7))


@pytest.mark.parametrize('architecture', ['cnn_gru', 'cnn', 'tcn'])
def test_export_representation_matches_and_features_never_see_future(architecture):
    torch.manual_seed(15)
    model = m.MobileOdometer(m.MobileConfig(architecture=architecture)).eval()
    x = torch.randn(1,20,6)
    changed = x.clone(); changed[:,12:] += 50
    torch.testing.assert_close(model.features(x)[:,:12], model.features(changed)[:,:12])
    torch.testing.assert_close(model(x),m.ExportableMobile(model)(x),atol=2e-5,rtol=2e-5)


def test_normalization_train_only_and_latest_target():
    x = np.array([[0,1,2,3,4,5],[2,3,4,5,6,7]],dtype=np.float32)
    mean,std = m.fit_normalization(x,split='train')
    np.testing.assert_array_equal(mean,[1,2,3,4,5,6])
    np.testing.assert_array_equal(std,np.ones(6))
    with pytest.raises(ValueError,match='train'):
        m.fit_normalization(x,split='val')
    times=100+np.arange(20)*.1
    assert m.prediction_time(times)==pytest.approx(101.9)
    times[10]=times[9]
    with pytest.raises(ValueError,match='clock|timestamp'):
        m.prediction_time(times)


def test_research_legacy_and_unselected_smoke_cannot_be_reportable(tmp_path):
    cfg=m.MobileConfig()
    checkpoint=m.make_software_checkpoint(cfg,tmp_path/'untrained.pt')
    for altered in [checkpoint, {'model_contract_version':'driftlock-iovnbd-recorded-reference-v1'}, {'config':{'model_contract_version':'driftlock-ml-1.1-centre-forward'}}]:
        with pytest.raises(ValueError): m.require_reportable_checkpoint(altered)
    with pytest.raises(ValueError,match='reviewed|synthetic|selected'):
        m.export_onnx(tmp_path/'untrained.pt',tmp_path/'not_production',purpose='reportable')
    assert not (tmp_path/'not_production').exists()


def test_empty_proposal_and_wrong_approval_never_open_reserved_payload(tmp_path,monkeypatch):
    manifest=tmp_path/'manifest.json'
    value={'model_contract_version':m.CONTRACT,'prediction_timestamp':'latest_sample','status':'REQUIRES_SOURCE_REVIEW',
           'training_approved':False,'drives':[], 'reserved_final_test_groups':['reserved'], 'reserved_final_test_payloads_read':0}
    manifest.write_text(json.dumps(value)); manifest.with_suffix('.sha256').write_text(m.sha256(manifest))
    original=Path.open
    def guarded(path,*args,**kwargs):
        assert 'reserved.csv' not in str(path)
        return original(path,*args,**kwargs)
    monkeypatch.setattr(Path,'open',guarded)
    with pytest.raises(ValueError,match='approval|review|eligible'):
        m.load_reviewed_splits(manifest,{'status':'APPROVED_FOR_TRAINING','manifest_sha256':'0'*64})


@pytest.mark.parametrize('architecture', ['cnn_gru','cnn','tcn'])
def test_synthetic_onnx_is_real_invocation_parity_but_never_approved(tmp_path,architecture):
    checkpoint=tmp_path/'untrained.pt'
    m.make_software_checkpoint(m.MobileConfig(architecture=architecture),checkpoint)
    report=m.export_onnx(checkpoint,tmp_path/'bundle',purpose='synthetic_software_check')
    manifest=json.loads((tmp_path/'bundle/manifest.json').read_text())
    assert report['onnx_invocations'] >= 4 and report['max_absolute_error'] < 1e-4
    assert manifest['review_status']=='SOFTWARE_CHECK_ONLY'
    assert manifest['model_contract_version']==m.CONTRACT and manifest['prediction_timestamp']=='latest_sample'
    assert manifest['input_shape']==[1,20,6] and manifest['output_shape']==[1,2]
    assert manifest['source_type']=='synthetic_software_check' and not manifest['uncertainty_calibrated']
    assert m.sha256(tmp_path/'bundle/model.onnx')==manifest['model_sha256']
    hashes=json.loads((tmp_path/'bundle/hashes.json').read_text())
    assert hashes['manifest.json']==m.sha256(tmp_path/'bundle/manifest.json')
    assert (tmp_path/'bundle/golden_input_f32.bin').stat().st_size==20*6*4


def test_benchmark_rejects_zero_calls_and_cannot_turn_speed_error_into_drift():
    with pytest.raises(ValueError,match='invocation'):
        m.speed_benchmark(np.ones(2),np.ones(2),np.ones(2),invocations=0)
    scores=m.speed_benchmark(np.array([0.,2.]),np.array([1.,1.]),np.ones(2),invocations=1)
    assert scores['rmse_mps']==1.0 and scores['r2']==0.0
    assert 'position_drift' not in scores


def prepared_fixture(tmp_path):
    import pandas as pd
    root=tmp_path; out=root/'prepared'; out.mkdir()
    evidence=root/'reports/review.md';evidence.parent.mkdir();evidence.write_text('SYNTHETIC SOFTWARE FIXTURE ONLY')
    evidence_refs={'fixture':{'path':'reports/review.md','sha256':m.sha256(evidence)}}
    manifest={'model_contract_version':m.CONTRACT,'prediction_timestamp':'latest_sample',
              'feature_channels':list(m.CHANNELS),'window_samples':20,'sample_hz':10.,
              'time_base':'seconds_since_boot','data_time_base':'seconds_since_recording_start',
              'frame':'phone','acceleration_includes_gravity':True,'acceleration_unit':'m/s2','gyro_unit':'rad/s','speed_unit':'m/s',
              'review_status':'PREPARED_REQUIRES_TRAINING_REVIEW','training_approved':False,
              'source_review_sha256':'a'*64,'source_review_evidence':evidence_refs,
              'reserved_final_test_groups':['reserved'],'reserved_final_test_payloads_read':0,'drives':[]}
    for i,split in enumerate(['train','val','calibration']):
        t=np.arange(24)/10.; frame=pd.DataFrame({'timestamp_s':t})
        for j,c in enumerate(m.CHANNELS):frame[c]=np.arange(24)/100.+i+(9.8 if c=='az' else j/10.)
        frame['speed_mps']=np.arange(24)/10.+i;frame['label_valid']=1
        frame['feature_source_time_s']=t;frame['phone_source_row']=np.arange(24)
        frame['reference_time_s']=t;frame['reference_source_row']=np.arange(24)
        frame['available_t']=t;frame['label_reason']='valid'
        csv=out/f'{split}.csv';frame.to_csv(csv,index=False)
        manifest['drives'].append({'path':csv.name,'group_id':split,'original_trip_id':f'trip{i}',
            'split':split,'drive_id':f'drive{i}','source_review_evidence':['fixture'],
            'processed_sha256':m.sha256(csv),'phone_source_sha256':str(i+1)*64,'reference_source_sha256':str(i+4)*64,
            'label_semantics':'vehicle_forward_signed','label_semantics_evidence':['fixture'],
            'data_time_base':'seconds_since_recording_start','time_base_evidence':['fixture']})
    path=out/'manifest.json';path.write_text(json.dumps(manifest));path.with_suffix('.sha256').write_text(m.sha256(path))
    approval={'status':'APPROVED_FOR_TRAINING','manifest_sha256':m.sha256(path),
              'source_type':'synthetic_software_check','review_evidence':evidence_refs,
              'reviewer':'synthetic_fixture','reviewed_at':'2026-09-20'}
    return path,approval,manifest


@pytest.mark.parametrize('defect',['trip','source','final','channel','evidence'])
def test_loader_checks_all_metadata_and_evidence_before_any_payload(tmp_path,monkeypatch,defect):
    path,approval,manifest=prepared_fixture(tmp_path)
    if defect=='trip':manifest['drives'][1]['original_trip_id']='trip0'
    if defect=='source':manifest['drives'][1]['phone_source_sha256']='1'*64
    if defect=='final':manifest['drives'][2]['split']='test'
    if defect=='channel':manifest['feature_channels'][-1]='speed_mps'
    if defect=='evidence':(tmp_path/'reports/review.md').write_text('tampered')
    path.write_text(json.dumps(manifest));path.with_suffix('.sha256').write_text(m.sha256(path));approval['manifest_sha256']=m.sha256(path)
    original=Path.open
    def guarded(p,*args,**kwargs):
        assert p.suffix!='.csv','Invalid metadata must fail before CSV opens'
        return original(p,*args,**kwargs)
    monkeypatch.setattr(Path,'open',guarded)
    with pytest.raises(ValueError):m.load_reviewed_splits(path,approval,root=tmp_path)


def test_bounded_training_preserves_fixture_boundary_normalization_and_best_validation(tmp_path):
    path,approval,_=prepared_fixture(tmp_path)
    result=m.train_candidate(path,approval,tmp_path/'run',m.MobileConfig(architecture='cnn'),root=tmp_path,epochs=1,batch_size=2)
    model,checkpoint=m.load_checkpoint(tmp_path/'run/candidate.pt')
    assert checkpoint['source_type']=='synthetic_software_check' and not checkpoint['selected']
    assert result['validation_invocations']>0 and result['epochs']==1
    np.testing.assert_allclose(model.input_mean.flatten().numpy(),np.array([.115,.215,9.915,.415,.515,.615]),atol=1e-5)
    assert result['validation']['metric_scope']=='speed_only_not_position_drift'
    assert set(result['baselines'])=={'training_mean','ridge'}
    assert result['normalization_fit_rows']==24
    with pytest.raises(ValueError,match='reviewed real'):
        m.require_reportable_checkpoint(checkpoint)


def test_calibration_is_independent_and_uses_residual_standard_deviation():
    result=m.fit_sigma_scale(np.array([1.,2.]),np.array([0.,0.]),np.ones(2),split='calibration',groups=['c'],used_groups=['t','v'],invocations=2)
    assert result['scale']==pytest.approx(np.sqrt(2.5))
    with pytest.raises(ValueError,match='calibration'):
        m.fit_sigma_scale(np.ones(2),np.zeros(2),np.ones(2),split='val',groups=['v'],used_groups=['t','v'],invocations=1)
    with pytest.raises(ValueError,match='leak'):
        m.fit_sigma_scale(np.ones(2),np.zeros(2),np.ones(2),split='calibration',groups=['t'],used_groups=['t','v'],invocations=1)


def test_unselected_candidate_cannot_calibrate_or_export_even_with_approval(tmp_path):
    path,approval,_=prepared_fixture(tmp_path)
    m.train_candidate(path,approval,tmp_path/'run',m.MobileConfig(architecture='cnn'),root=tmp_path,epochs=1)
    with pytest.raises(ValueError,match='selection'):
        m.calibrate_candidate(tmp_path/'run/candidate.pt',path,approval,{},tmp_path/'calibrated',root=tmp_path)
    assert not (tmp_path/'calibrated').exists()


def test_nan_normalization_or_sigma_in_checkpoint_cannot_export(tmp_path):
    checkpoint=m.make_software_checkpoint(m.MobileConfig(),tmp_path/'original.pt')
    checkpoint['state_dict']['input_std'].fill_(float('nan'))
    torch.save(checkpoint,tmp_path/'bad.pt')
    with pytest.raises(ValueError,match='Normalization|normalization'):
        m.export_onnx(tmp_path/'bad.pt',tmp_path/'invalid_bundle',purpose='synthetic_software_check')
    assert not (tmp_path/'invalid_bundle').exists()
