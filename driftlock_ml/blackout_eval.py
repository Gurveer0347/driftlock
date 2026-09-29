"""Speed-only delayed-measurement blackout diagnostic; never navigation drift.

Use measured centre and availability clocks. Pre-cut correction sees only outputs
available before the cut; IMU context may arrive after its centre target time.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import numpy as np
import pandas as pd
from .config import MAX_SAMPLE_AGE_PERIODS, VALIDATION_LIMITS, validate_timestamps
from .data import BOOT_TIME_BASE, DATA_TIME_BASES
from .utils import speed_metrics, write_json


def prediction_time_base(pred):
    """Read explicit offline clock provenance; legacy outputs used boot clocks."""
    if 'data_time_base' not in pred.columns:
        return BOOT_TIME_BASE
    values = pred.data_time_base.unique().tolist()
    if len(values) != 1 or values[0] not in DATA_TIME_BASES:
        raise ValueError('Prediction data_time_base must name one reviewed clock; mixed or unknown clocks are unsupported.')
    return values[0]


def score_predictions(pred, sample_hz=10., duration=30., warmup=30.):
    required = {'drive', 't', 'available_t', 'reference_speed_mps', 'speed_mps'}
    if not required.issubset(pred.columns):
        raise ValueError(f'Missing convention measurement fields: {sorted(required-set(pred.columns))}')
    if not np.isfinite([sample_hz,duration,warmup]).all() or min(sample_hz,duration,warmup)<=0:
        raise ValueError('Durations and sampling frequency must be positive and finite.')
    clock = prediction_time_base(pred)
    results = []
    max_gap_s = MAX_SAMPLE_AGE_PERIODS / sample_hz
    for name, drive in pred.groupby('drive', sort=False):
        # Both source clocks use the existing finite/nonnegative/ordered screen.
        # This checks numbers; the explicit declaration above determines meaning.
        times_s = validate_timestamps(drive.t.to_numpy())
        available_s = validate_timestamps(drive.available_t.to_numpy())
        if np.any(available_s < times_s):
            raise ValueError('A prediction cannot be available before its centre target.')
        values = drive[['reference_speed_mps','speed_mps']].to_numpy(float)
        if not np.isfinite(values).all():
            raise ValueError('Nonfinite prediction/reference; do not silently omit failures.')
        cuts_s = np.arange(times_s[0]+warmup, times_s[-1]-duration+1e-8, duration)
        for cut_s in cuts_s:
            before = drive[(drive.t>=cut_s-warmup) & (drive.t<cut_s) & (drive.available_t<cut_s)]
            reference_before = drive[(drive.t>=cut_s-warmup) & (drive.t<cut_s)]
            during = drive[(drive.t>=cut_s) & (drive.t<cut_s+duration)]
            if len(before)<5 or len(during)<int(.95*duration*sample_hz):
                continue
            if np.max(np.diff(during.t))>max_gap_s or np.max(np.diff(before.t))>max_gap_s:
                continue
            if cut_s-float(before.available_t.iloc[-1])>max_gap_s:
                continue
            if float(during.t.iloc[0])-cut_s>max_gap_s or cut_s+duration-float(during.t.iloc[-1])>max_gap_s:
                continue
            reference_mps = during.reference_speed_mps.to_numpy()
            speed_mps = during.speed_mps.to_numpy()
            held_mps = float(reference_before.reference_speed_mps.iloc[-1])
            offset_mps = float((before.reference_speed_mps-before.speed_mps).mean())
            models = {'last_preoutage_speed':np.full(len(reference_mps),held_mps),
                      'virtual_odometer':speed_mps,
                      'virtual_odometer_plus_frozen_preoutage_offset':speed_mps+offset_mps}
            # Piecewise-constant integration over actual recorded times. Final
            # hold ends at the declared cut boundary, at most max_gap_s later.
            dt_s = np.diff(np.r_[during.t.to_numpy(),cut_s+duration])
            for method, estimate_mps in models.items():
                row = {'drive':name,'data_time_base':clock,'cut_s':float(cut_s),'duration_s':duration,'method':method,
                       **speed_metrics(reference_mps,estimate_mps)}
                row['integrated_speed_difference_m'] = float(np.sum((estimate_mps-reference_mps)*dt_s))
                row['integrated_duration_s'] = float(dt_s.sum())
                row['last_pre_cut_available_t'] = float(before.available_t.iloc[-1])
                results.append(row)
    return pd.DataFrame(results)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--predictions',required=True)
    parser.add_argument('--out',required=True)
    parser.add_argument('--duration',type=float,default=30.)
    parser.add_argument('--warmup',type=float,default=30.)
    parser.add_argument('--sample-hz',type=float,default=10.)
    args=parser.parse_args()
    pred=pd.read_csv(args.predictions)
    scores=score_predictions(pred,args.sample_hz,args.duration,args.warmup)
    if scores.empty: raise ValueError('No complete valid blackout intervals; inspect clocks, coverage and labels.')
    out=Path(args.out)
    if out.exists() and any(out.iterdir()): raise FileExistsError('Use a fresh blackout evaluation directory.')
    out.mkdir(parents=True,exist_ok=True)
    scores.to_csv(out/'blackout_speed_metrics.csv',index=False)
    context={'sample_hz':args.sample_hz,'duration_s':args.duration,'warmup_s':args.warmup,'validation':VALIDATION_LIMITS}
    summary={method:{'mean_outage_rmse_mps':float(f.rmse_mps.mean()),'outages':len(f)} for method,f in scores.groupby('method')}
    write_json(out/'summary.json',{'summary':summary,'config':context,
        'data_time_base':prediction_time_base(pred),'runtime_packets_produced':False,
        'scope':'speed-only diagnostic, actual-dt held integration; not position drift or the complete ShadowDR engine',
        'availability_policy':'Only predictions available before the cut inform frozen pre-cut offset; centre estimates are delayed.',
        'warning':'Signed speed difference is NOT navigation position error. Reference labels may be imperfect.'})
    print('Configuration:',context); print(summary)


if __name__=='__main__': main()
