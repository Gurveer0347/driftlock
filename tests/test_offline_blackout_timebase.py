"""Offline speed diagnostics retain their declared clock without becoming packets."""
import json
from pathlib import Path
import subprocess
import sys

import numpy as np
import pandas as pd
import pytest

from driftlock_ml.blackout_eval import score_predictions

BOOT = 'seconds_since_boot'
RELATIVE = 'seconds_since_recording_start'


def predictions(clock=RELATIVE):
    t = 4.2 + np.arange(120) / 10
    return pd.DataFrame(dict(drive='synthetic_clock_check',t=t,available_t=t+.95,
                             reference_speed_mps=10.,speed_mps=8.,data_time_base=clock))


@pytest.mark.parametrize('clock',[BOOT,RELATIVE])
def test_speed_diagnostic_keeps_source_clock_without_changing_metrics(clock):
    result = score_predictions(predictions(clock),duration=2.,warmup=3.)
    assert not result.empty
    assert result.data_time_base.eq(clock).all()
    assert result[result.method=='virtual_odometer'].rmse_mps.eq(2.).all()


@pytest.mark.parametrize('bad',['wall_clock','UNREVIEWED',None])
def test_speed_diagnostic_rejects_unknown_source_clock(bad):
    with pytest.raises(ValueError,match='clock|time_base'):
        score_predictions(predictions(bad),duration=2.,warmup=3.)


def test_speed_diagnostic_rejects_mixed_source_clocks():
    frame = predictions()
    frame.loc[5,'data_time_base'] = BOOT
    with pytest.raises(ValueError,match='clock|time_base'):
        score_predictions(frame,duration=2.,warmup=3.)


def test_legacy_prediction_clock_remains_boot():
    result = score_predictions(predictions().drop(columns='data_time_base'),duration=2.,warmup=3.)
    assert result.data_time_base.eq(BOOT).all()


def test_offline_blackout_cli_records_relative_clock(tmp_path):
    source=tmp_path/'predictions.csv'; out=tmp_path/'diagnostic'
    predictions().to_csv(source,index=False)
    run=subprocess.run([sys.executable,'-m','driftlock_ml.blackout_eval','--predictions',str(source),
                        '--out',str(out),'--duration','2','--warmup','3'],
                       cwd=Path(__file__).resolve().parents[1],capture_output=True,text=True)
    assert run.returncode==0,run.stderr
    report=json.loads((out/'summary.json').read_text())
    assert report['data_time_base']==RELATIVE
    assert report['runtime_packets_produced'] is False
    assert pd.read_csv(out/'blackout_speed_metrics.csv').data_time_base.eq(RELATIVE).all()
    assert not list(out.glob('measurements*'))
