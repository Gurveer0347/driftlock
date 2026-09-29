from pathlib import Path
import copy
import json
import numpy as np
import pandas as pd
import pytest
import torch
from driftlock_ml.data import Windows, check_manifest, check_evaluation_provenance
from driftlock_ml.model import VirtualOdometer, ExportableOdometer
from driftlock_ml.infer import OdometerPredictor
from driftlock_ml.utils import speed_metrics
from driftlock_ml.blackout_eval import score_predictions
from tools.make_demo_data import make_demo
from driftlock_ml.config import CONTRACT_FIELDS
from driftlock_ml.rotations import GRAVITY_MPS2

CFG={**CONTRACT_FIELDS,"sample_hz":10.,"window_samples":20,"conv_channels":16,"hidden_size":16,
     "speed_scale":10.,"sigma_floor_mps":.1,"frame":"phone","acceleration_includes_gravity":True}
torch.set_num_threads(2)


def test_model_shape_and_backward():
    model=VirtualOdometer(CFG)
    x=torch.randn(4,20,6)
    y=model(x)
    assert y.shape==(4,2)
    assert torch.isfinite(y).all()
    assert torch.all(y[:,1]>0)
    y.sum().backward()
    assert model.conv1.weight.grad is not None


def test_export_gru_is_same_model():
    torch.manual_seed(8)
    model=VirtualOdometer(CFG).eval()
    export=ExportableOdometer(model).eval()
    x=torch.randn(3,20,6)
    with torch.no_grad():
        torch.testing.assert_close(model(x),export(x),rtol=2e-5,atol=2e-5)


def test_cnn_causal_features():
    model=VirtualOdometer(CFG).eval()
    x=torch.randn(1,20,6)
    changed=x.clone();changed[:,10:,:]+=100
    with torch.no_grad():
        torch.testing.assert_close(model.features(x)[:,:10],model.features(changed)[:,:10])


def test_only_six_imu_channels(tmp_path):
    path=make_demo(tmp_path,seconds=10)
    m=check_manifest(path)
    first=Windows(m,CFG,"train")[0][0]
    datafile=tmp_path/m["drives"][0]["path"]
    f=pd.read_csv(datafile);f["speed_mps"]+=1
    f["GPS_HEADING"]=999;f["latitude"]=55;f.to_csv(datafile,index=False)
    second=Windows(check_manifest(path),CFG,"train")[0][0]
    torch.testing.assert_close(first,second)


def test_group_leak_is_rejected(tmp_path):
    path=make_demo(tmp_path,seconds=10)
    m=json.loads(path.read_text())
    m["drives"][-1]["group_id"]=m["drives"][0]["group_id"]
    path.write_text(json.dumps(m))
    with pytest.raises(ValueError,match="leakage"):
        check_manifest(path)


def test_normalization_uses_only_train(tmp_path):
    path=make_demo(tmp_path,seconds=10)
    m=check_manifest(path)
    ds=Windows(m,CFG,"train")
    mean,std=ds.normalization()
    assert mean.shape==(6,) and np.all(std>0)
    with pytest.raises(ValueError,match="training"):
        Windows(m,CFG,"test").normalization()


def test_no_window_crosses_nan(tmp_path):
    path=make_demo(tmp_path,seconds=15)
    m=check_manifest(path)
    file=tmp_path/m["drives"][0]["path"]
    f=pd.read_csv(file);f.loc[40:45,"ax"]=np.nan;f.to_csv(file,index=False)
    ds=Windows(check_manifest(path),CFG,"train")
    for x,y,d,e in ds:
        assert torch.isfinite(x).all()
        if d==0: assert not (40<=e<=64)


def test_bad_time_rejected(tmp_path):
    path=make_demo(tmp_path,seconds=10)
    m=check_manifest(path)
    file=tmp_path/m["drives"][0]["path"]
    f=pd.read_csv(file);f.loc[5,"timestamp_s"]=f.loc[4,"timestamp_s"];f.to_csv(file,index=False)
    with pytest.raises(ValueError,match="strictly increasing"):
        Windows(check_manifest(path),CFG,"train")


def test_stream_reset_on_gap(tmp_path):
    model=VirtualOdometer(CFG)
    ckpt={"model":model.state_dict(),"config":CFG,"uncertainty_calibrated":True}
    path=tmp_path/"dummy.pt";torch.save(ckpt,path)
    pred=OdometerPredictor(str(path))
    for i in range(19): assert pred.push_resampled(i*.1,[0,0,GRAVITY_MPS2,0,0,0])["valid"] is False
    result=pred.push_resampled(1.9,[0,0,GRAVITY_MPS2,0,0,0])
    assert result["sigma_mps"]>0
    assert pred.push_resampled(3.,[0,0,GRAVITY_MPS2,0,0,0])["valid"] is False


def test_metric_units():
    result=speed_metrics([1,2],[2,3],[1,1])
    assert result["rmse_mps"]==1
    assert result["mae_mps"]==1


def test_blackout_offset_does_not_learn_future():
    t=np.arange(0,120,.1)
    reference=np.full(len(t),10.); reference[t>=30]=20.
    pred=pd.DataFrame({"drive":"one","t":t,"available_t":t+.95,
                       "reference_speed_mps":reference,"speed_mps":np.full(len(t),8.),"sigma_mps":2.})
    result=score_predictions(pred,10,30,30)
    first=result[result.cut_s==30.]
    # Preoutage offset=+2, NOT +12 from hidden in-outage labels.
    corrected=first[first.method=="virtual_odometer_plus_frozen_preoutage_offset"]
    assert corrected.rmse_mps.iloc[0]==pytest.approx(10.)


def test_cannot_relabel_training_drive_as_new_test(tmp_path):
    path=make_demo(tmp_path,seconds=10)
    original=check_manifest(path)
    ckpt={"run":{"manifest_snapshot":copy.deepcopy(original)}}
    changed=copy.deepcopy(original)
    changed["drives"][0]["split"]="test"
    with pytest.raises(ValueError,match="checkpoint history"):
        check_evaluation_provenance(changed,ckpt,"test")


def test_clean_evaluation_provenance_passes(tmp_path):
    path=make_demo(tmp_path,seconds=10)
    original=check_manifest(path)
    ckpt={"run":{"manifest_snapshot":copy.deepcopy(original)}}
    for split in ["val","calibration","test"]:
        check_evaluation_provenance(original,ckpt,split)
