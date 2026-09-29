"""Standalone recorded-reference inference. Never emits a navigation packet."""
from pathlib import Path
import argparse
import hashlib
import json
import numpy as np
import torch

RESEARCH_CONTRACT = 'driftlock-iovnbd-recorded-reference-v1'


class ResearchPredictor:
    def __init__(self, bundle):
        self.bundle = Path(bundle)
        self.contract = json.loads((self.bundle/'research_contract.json').read_text())
        if self.contract.get('research_contract') != RESEARCH_CONTRACT or self.contract.get('valid_for_navigation') is not False:
            raise ValueError('Expected the explicitly research-only recorded-reference contract.')
        self.window = int(self.contract['input_shape'][1])
        model_path = self.bundle/'recorded_reference.pt2'
        digest = hashlib.sha256(model_path.read_bytes()).hexdigest()
        if digest != self.contract['portable_sha256']:
            raise ValueError('Portable model hash differs from the research contract.')
        self.model = torch.export.load(model_path).module()

    def predict_window(self, imu, timestamps_s):
        values = np.asarray(imu,dtype=np.float32)
        times = np.asarray(timestamps_s,dtype=np.float64)
        if values.shape != (self.window,6) or not np.isfinite(values).all():
            raise ValueError(f'Expected finite [{self.window},6] source-export IMU values.')
        if times.shape != (self.window,) or not np.isfinite(times).all() or np.any(times<0):
            raise ValueError('Supply the full recording-relative timestamp vector in seconds.')
        if not np.allclose(np.diff(times),.1,atol=.01,rtol=0):
            raise ValueError('Expected oldest-first 10 Hz window; resets, duplicates and gaps are unsupported.')
        with torch.inference_mode():
            output = self.model(torch.from_numpy(values[None])).numpy()[0]
        if not np.isfinite(output).all() or output[1] <= 0:
            raise ValueError('Model produced nonfinite value or nonpositive sigma.')
        return {'t_recording_s':float((times[0]+times[-1])/2),
                'available_t_recording_s':float(times[-1]),
                'predicted_reference_value':float(output[0]),
                'sigma_reference_units':float(output[1]),'valid_for_navigation':False}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle',required=True)
    parser.add_argument('--input-npy',required=True)
    parser.add_argument('--timestamps-npy',required=True)
    args=parser.parse_args()
    sample=np.load(args.input_npy,allow_pickle=False)
    if sample.ndim==3 and sample.shape[0]==1: sample=sample[0]
    result=ResearchPredictor(args.bundle).predict_window(sample,np.load(args.timestamps_npy,allow_pickle=False))
    print(json.dumps(result,indent=2,allow_nan=False))


if __name__=='__main__': main()
