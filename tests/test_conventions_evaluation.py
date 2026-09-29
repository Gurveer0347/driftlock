import numpy as np
import pandas as pd
import pytest
import torch
from driftlock_ml.evaluate import predict_dataset
from driftlock_ml.blackout_eval import score_predictions

class TinyWindows(torch.utils.data.Dataset):
    def __init__(self):
        self.drives=[{'name':'synthetic','entry':{'group_id':'one'},'data_time_base':'seconds_since_boot',
                      't':3600.+np.arange(20)*.1,'target_t':3600.+np.arange(20)*.1-.95}]
    def __len__(self): return 1
    def __getitem__(self,index):return torch.zeros(20,6),torch.tensor(-3.),0,19

class FixedModel(torch.nn.Module):
    def forward(self,x):return torch.tensor([[-2.,1.]]).repeat(len(x),1)

def test_evaluation_reports_target_and_availability_separately():
    pred=predict_dataset(FixedModel(),TinyWindows(),'cpu')
    assert pred.loc[0,'t']==pytest.approx(3600.95)
    assert pred.loc[0,'available_t']==pytest.approx(3601.9)
    assert pred.loc[0,'speed_mps']==-2
    assert 'predicted_speed_mps' not in pred

def test_blackout_uses_actual_elapsed_time_and_no_future_offset():
    t=3600.+np.arange(1200)*.1
    # Small hardware-time irregularity is retained, not replaced by a fixed dt.
    t[1::2]+=.01
    pred=pd.DataFrame({'drive':'software_fixture','t':t,'available_t':t+.95,
                       'reference_speed_mps':10.,'speed_mps':8.,'sigma_mps':1.})
    result=score_predictions(pred,10.,30.,30.)
    first=result[(result.cut_s==3630.) & (result.method=='virtual_odometer')].iloc[0]
    during=t[(t>=3630.) & (t<3660.)]
    expected=-2*np.diff(np.r_[during,3660.]).sum()
    assert first.integrated_speed_difference_m==pytest.approx(expected)
    assert first.integrated_duration_s==pytest.approx(3660.-during[0])

@pytest.mark.parametrize('problem',['backwards','missing_availability','future_target'])
def test_blackout_rejects_bad_measurement_clock(problem):
    t=np.arange(1200)*.1
    pred=pd.DataFrame({'drive':'software_fixture','t':t,'available_t':t+.95,
                       'reference_speed_mps':10.,'speed_mps':8.,'sigma_mps':1.})
    if problem=='backwards': pred.loc[10,'t']=pred.loc[9,'t']
    if problem=='missing_availability': pred=pred.drop(columns=['available_t'])
    if problem=='future_target': pred['available_t']=pred.t-.1
    with pytest.raises(ValueError):score_predictions(pred)

def test_last_pre_cut_reference_is_not_delayed_like_model():
    t=np.arange(1200)*.1
    reference=np.where(t>=29.5,20.,10.)
    pred=pd.DataFrame({'drive':'software_fixture','t':t,'available_t':t+.95,
                       'reference_speed_mps':reference,'speed_mps':10.,'sigma_mps':1.})
    rows=score_predictions(pred)
    first=rows[rows.cut_s==30.]
    assert first[first.method=='last_preoutage_speed'].rmse_mps.iloc[0]==pytest.approx(0.)
    # Offset still cannot read the not-yet-available model predictions after 29.05.
    assert first[first.method=='virtual_odometer_plus_frozen_preoutage_offset'].rmse_mps.iloc[0]==pytest.approx(10.)
