import json
from pathlib import Path
import numpy as np
import pytest
import torch
from driftlock_ml.model import VirtualOdometer
from driftlock_ml.infer import OdometerPredictor
from driftlock_ml.utils import load_model

FIELDS = {'conventions_version':'1.1','model_contract_version':'driftlock-ml-1.1-centre-forward',
          'prediction_timestamp':'window_centre','speed_semantics':'vehicle_forward_signed','time_base':'seconds_since_boot'}

def config():
    return {**json.loads(Path('configs/baseline.json').read_text()), **FIELDS}

@pytest.fixture
def predictor(tmp_path):
    cfg=config(); torch.manual_seed(3); model=VirtualOdometer(cfg)
    with torch.no_grad():
        model.mean_head.weight.zero_();model.mean_head.bias.fill_(-.2)
    path=tmp_path/'new.pt'
    torch.save({'config':cfg,'model':model.state_dict(),'uncertainty_calibrated':True},path)
    return OdometerPredictor(str(path),'cpu')

def sample():
    x=np.zeros((20,6),np.float32);x[:,2]=9.80665
    return x,3600.+np.arange(20)*.1

def test_vehicle_forward_head_supports_reverse():
    model=VirtualOdometer(config())
    with torch.no_grad():
        model.mean_head.weight.zero_();model.mean_head.bias.fill_(-1.)
    output=model(torch.zeros(1,20,6))
    assert output[0,0]<0, 'Vehicle x velocity must represent reverse'
    assert output[0,1]>0

def test_exact_packet_with_actual_centre_clock(predictor):
    x,t=sample(); packet=predictor.predict_window(x,timestamps_s=t)
    assert set(packet)=={'t','speed_mps','sigma_mps','valid'}
    assert packet['t']==pytest.approx(3600.95)
    assert packet['valid'] is True and packet['speed_mps']<0 and packet['sigma_mps']>0

def test_full_timestamp_vector_required(predictor):
    with pytest.raises((TypeError,ValueError)):
        predictor.predict_window(sample()[0])

@pytest.mark.parametrize('bad_t',[np.full(20,np.nan),1790000000.+np.arange(20)*.1,np.arange(20)[::-1]*.1])
def test_invalid_clock_rejected(predictor,bad_t):
    with pytest.raises(ValueError): predictor.predict_window(sample()[0],timestamps_s=bad_t)

def test_warmup_gap_and_bad_imu_emit_invalid(predictor):
    x,t=sample()
    for k in range(19):
        packet=predictor.push_resampled(t[k],x[k]);assert packet['valid'] is False
        assert set(packet)=={'t','speed_mps','sigma_mps','valid'}
    assert predictor.push_resampled(t[-1],x[-1])['t']==pytest.approx(3600.95)
    assert predictor.push_resampled(3603.,x[-1])['valid'] is False
    assert predictor.push_resampled(3603.1,[np.nan,0,0,0,0,0])['valid'] is False

@pytest.mark.parametrize('kind',['nan','gravity_removed','gyro_degrees','grid_gap'])
def test_unusable_full_window_is_invalid(predictor,kind):
    x,t=sample()
    if kind=='nan': x[8,0]=np.nan
    if kind=='gravity_removed': x[:,:3]=0
    if kind=='gyro_degrees': x[:,5]=30
    if kind=='grid_gap': t[10:]+=.03
    packet=predictor.predict_window(x,timestamps_s=t)
    assert packet['valid'] is False and packet['sigma_mps']>0
    assert packet['t']==pytest.approx((t[0]+t[-1])/2)

def test_uncalibrated_and_bad_model_outputs_invalid(predictor):
    x,t=sample(); predictor.checkpoint['uncertainty_calibrated']=False
    assert predictor.predict_window(x,timestamps_s=t)['valid'] is False
    predictor.checkpoint['uncertainty_calibrated']=True
    with torch.no_grad(): predictor.model.mean_head.bias.fill_(100.)
    assert predictor.predict_window(x,timestamps_s=t)['valid'] is False

def test_legacy_checkpoint_rejected(tmp_path):
    legacy_path=tmp_path/'legacy.pt'
    torch.save({'config':{'window_samples':20},'model':{}},legacy_path)
    with pytest.raises(ValueError,match='contract|legacy|convention'):
        load_model(legacy_path)

@pytest.mark.parametrize('completed_before_bad',[False,True])
def test_bad_sample_completing_window_keeps_centre_timestamp(predictor,completed_before_bad):
    x,t=sample()
    count=20 if completed_before_bad else 19
    for k in range(count): predictor.push_resampled(t[k],x[k])
    packet=predictor.push_resampled(3600.+count*.1,[np.nan,0,9.80665,0,0,0])
    expected=3601.05 if completed_before_bad else 3600.95
    assert packet['valid'] is False
    assert packet['t']==pytest.approx(expected)
