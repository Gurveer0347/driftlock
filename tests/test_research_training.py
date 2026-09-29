"""Research artifacts must work without masquerading as signed SI-speed models."""
import json
from pathlib import Path
import numpy as np
import pytest
import torch
from driftlock_ml.model import VirtualOdometer
from driftlock_ml.utils import load_model
from driftlock_ml.research import (
    RESEARCH_CONTRACT, recorded_metrics, fit_sigma_scale, load_research_model,
    check_manifest_binding, predict_recorded, export_bundle,
)

CFG = json.loads(Path('configs/baseline.json').read_text())


def checkpoint(tmp_path, calibrated=False):
    torch.manual_seed(42)
    model = VirtualOdometer(CFG)
    path = tmp_path / 'research.pt'
    torch.save({'research_contract': RESEARCH_CONTRACT, 'valid_for_navigation': False,
                'architecture_config': CFG, 'model': model.state_dict(),
                'uncertainty_calibrated': calibrated,
                'run': {'manifest_sha256': 'fixture', 'purpose': 'exploratory_recorded_reference'}}, path)
    return path, model


def test_production_loader_rejects_research_checkpoint(tmp_path):
    path, original = checkpoint(tmp_path)
    with pytest.raises(ValueError, match='contract'):
        load_model(path)
    loaded, _ = load_research_model(path)
    x = torch.randn(2, 20, 6)
    torch.testing.assert_close(loaded(x), original(x))


@pytest.mark.parametrize('field,value', [('research_contract','other'), ('valid_for_navigation',True)])
def test_research_loader_rejects_claim_changes(tmp_path, field, value):
    path, _ = checkpoint(tmp_path)
    data = torch.load(path, weights_only=True); data[field] = value; torch.save(data, path)
    with pytest.raises(ValueError): load_research_model(path)


def test_recorded_metrics_never_claim_mps_and_retain_negative_reference():
    metrics = recorded_metrics(np.array([-1.,3.]), np.array([1.,1.]), np.array([2.,2.]))
    assert metrics['rmse_reference_units'] == 2.
    assert metrics['bias_reference_units'] == 0.
    assert not any('mps' in key for key in metrics)
    assert metrics['coverage_95pct'] == 1.


@pytest.mark.parametrize('sigma', [np.array([0.,1.]),np.array([np.nan,1.]),np.array([-1.,1.])])
def test_metrics_do_not_hide_failed_sigma(sigma):
    with pytest.raises(ValueError): recorded_metrics(np.ones(2),np.ones(2),sigma)


def test_gaussian_sigma_scale_has_residual_standard_deviation_meaning():
    assert fit_sigma_scale(np.array([2.,-2.]),np.ones(2)) == 2.


def test_manifest_change_cannot_relabel_calibration_groups(tmp_path):
    manifest = tmp_path/'manifest.json'; manifest.write_text('{}')
    with pytest.raises(ValueError, match='manifest'):
        check_manifest_binding(manifest, {'run': {'manifest_sha256':'different'}})


class TinyRecorded(torch.utils.data.Dataset):
    def __init__(self):
        times = 12.+np.arange(20)*.1
        self.window = 20
        self.drives = [{'name':'fixture','entry':{'group_id':'fixture_train'},'t':times,
                        'target_t':np.full(20,12.95),'x':np.zeros((20,6),np.float32)}]
    def __len__(self): return 1
    def __getitem__(self,index): return torch.zeros(20,6),torch.tensor(-1.),0,19


def test_prediction_contains_only_recorded_quantity_and_delayed_clock():
    data = predict_recorded(VirtualOdometer(CFG),TinyRecorded(),'cpu')
    assert not bool(data.valid_for_navigation.any())
    assert data.reference_value.iloc[0] == -1.
    assert data.available_t_recording_s.iloc[0]-data.t_recording_s.iloc[0] == pytest.approx(.95)
    assert not {'speed_mps','sigma_mps','valid'}.intersection(data.columns)


def test_portable_research_export_matches_model_and_never_publishes_navigation_packet(tmp_path):
    from driftlock_ml.research_inference import ResearchPredictor
    path, original = checkpoint(tmp_path, calibrated=True)
    sample = np.zeros((1,20,6),np.float32); sample[:,:,2] = 9.8
    times = 12.+np.arange(20)*.1
    out=tmp_path/'bundle'
    export_bundle(path,out,sample,times,{'source':'synthetic_test_fixture'})
    predictor=ResearchPredictor(out)
    result=predictor.predict_window(sample[0],times)
    assert result['valid_for_navigation'] is False
    assert 'speed_mps' not in result and 'sigma_mps' not in result
    with torch.inference_mode(): expected=original(torch.from_numpy(sample)).numpy()[0]
    assert result['predicted_reference_value'] == pytest.approx(float(expected[0]),abs=2e-5)
    assert result['sigma_reference_units'] == pytest.approx(float(expected[1]),abs=2e-5)
    for bad in [sample[0,:19], np.zeros((20,7))]:
        with pytest.raises(ValueError): predictor.predict_window(bad,times)
    with pytest.raises(ValueError): predictor.predict_window(sample[0],np.zeros(20))
    with pytest.raises(FileExistsError): export_bundle(path,out,sample,times,{})


@pytest.mark.parametrize('operation', ['evaluate', 'calibrate'])
def test_research_cli_evaluation_and_calibration_finish_with_json(tmp_path, monkeypatch, capsys, operation):
    """Exercise complete command paths, retaining actual model and artifact work."""
    import sys
    import driftlock_ml.research as research
    from driftlock_ml.utils import sha256
    manifest=tmp_path/'manifest.json'; manifest.write_text('{}')
    path,_=checkpoint(tmp_path)
    saved=torch.load(path,weights_only=True)
    saved['run'].update(manifest_sha256=sha256(manifest),probe_only=False,train_mean_reference_value=1.)
    torch.save(saved,path)
    dataset=TinyRecorded(); dataset.drives[0]['entry']['source_file']='synthetic_fixture.csv'
    requested=[]
    def fixture_data(args,config,split,probe=False):
        requested.append(split)
        assert split==('val' if operation=='evaluate' else 'calibration')
        return dataset
    monkeypatch.setattr(research,'dataset_from_args',fixture_data)
    out=tmp_path/('evaluation' if operation=='evaluate' else 'calibrated.pt')
    argv=['research',operation,'--manifest',str(manifest),'--checkpoint',str(path),'--out',str(out),'--device','cpu']
    if operation=='calibrate':
        selection=tmp_path/'selection.json'
        selection.write_text(json.dumps({'checkpoint_sha256':sha256(path),'manifest_sha256':sha256(manifest),'frozen_before_calibration':True}))
        argv+=['--selection',str(selection)]
    monkeypatch.setattr(sys,'argv',argv)
    research.main()
    printed=json.loads(capsys.readouterr().out)
    assert requested==[('val' if operation=='evaluate' else 'calibration')]
    if operation=='evaluate':
        report=json.loads((out/'metrics.json').read_text())
        assert report['valid_for_navigation'] is False and printed['all_windows']['n']==1
    else:
        _,result=load_research_model(out)
        assert result['uncertainty_calibrated'] is True and printed['split']=='calibration'
        assert result['calibration']['source_checkpoint_sha256']==sha256(path)
