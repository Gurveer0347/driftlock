"""Conventions 1.1 speed measurement boundary: t, speed_mps, sigma_mps, valid.

Time is the centre of a completed oldest-first window, in seconds since boot.
The estimate arrives after the newest sample; it is a delayed measurement. Inputs
are six raw phone IMU channels plus timing metadata, never GNSS or direction hints.
"""
from __future__ import annotations
from collections import deque
import argparse
import json
import numpy as np
import torch
from .config import (GRID_TOLERANCE_FRACTION, INVALID_SIGMA_MPS, MAX_SPEED_MPS,
                     TIME_COMPARISON_TOL_S, usable_imu, validate_timestamps)
from .utils import load_model, device_for


def invalid_measurement(t_s):
    # These are sentinels, not a prediction: consumers must ignore valid=false.
    return {'t': float(t_s), 'speed_mps': 0.0, 'sigma_mps': INVALID_SIGMA_MPS, 'valid': False}


class OdometerPredictor:
    def __init__(self, checkpoint: str, device: str = 'cpu'):
        self.device = device_for(device)
        self.model, self.checkpoint = load_model(checkpoint, self.device)
        self.window = int(self.checkpoint['config']['window_samples'])
        self.sample_hz = float(self.checkpoint['config']['sample_hz'])
        self.buffer = deque(maxlen=self.window)
        self.last_t = None

    def reset(self):
        """Discard the window after a mount change, clock failure or new drive."""
        self.buffer.clear()
        self.last_t = None

    def predict_window(self, imu, timestamps_s) -> dict:
        times_s = validate_timestamps(timestamps_s, self.window)
        centre_t_s = float(times_s[0] + (times_s[-1] - times_s[0]) / 2)
        values = np.asarray(imu, dtype=np.float32)
        if values.shape != (self.window, 6):
            raise ValueError(f'Expected [{self.window},6] raw phone IMU window.')
        expected_dt_s = 1 / self.sample_hz
        timing_ok = np.all(np.abs(np.diff(times_s) - expected_dt_s) <=
                           GRID_TOLERANCE_FRACTION * expected_dt_s + TIME_COMPARISON_TOL_S)
        if not timing_ok or not usable_imu(values):
            return invalid_measurement(centre_t_s)
        if self.checkpoint.get('uncertainty_calibrated') is not True:
            return invalid_measurement(centre_t_s)
        with torch.inference_mode():
            speed_mps, sigma_mps = self.model(torch.from_numpy(values).unsqueeze(0).to(self.device))[0].cpu().tolist()
        if not np.isfinite([speed_mps, sigma_mps]).all() or abs(speed_mps) >= MAX_SPEED_MPS or sigma_mps <= 0:
            return invalid_measurement(centre_t_s)
        return {'t': centre_t_s, 'speed_mps': float(speed_mps), 'sigma_mps': float(sigma_mps), 'valid': True}

    def push_resampled(self, t: float, six_channels) -> dict:
        """Already-resampled data with real timestamps; always emit a validity flag.

        Before a full window exists, an invalid notification uses this event time.
        After completion, t is the measured midpoint. Raw Android events require
        causal resampling using their hardware timestamps before this API.
        """
        try:
            t_s = float(validate_timestamps([t])[0])
            if self.last_t is not None and t_s <= self.last_t:
                raise ValueError('Non-monotonic time; buffer cleared.')
        except (TypeError, ValueError):
            self.reset()
            raise
        values = np.asarray(six_channels, dtype=np.float32)
        if values.shape != (6,):
            self.reset()
            raise ValueError('Expected six raw phone IMU channels.')
        if self.last_t is not None:
            dt_s = t_s - self.last_t
            if abs(dt_s - 1 / self.sample_hz) > GRID_TOLERANCE_FRACTION / self.sample_hz + TIME_COMPARISON_TOL_S:
                self.buffer.clear()
        self.last_t = t_s
        if not usable_imu(values[None]):
            attempted_times_s = [time_s for time_s, _ in self.buffer][-self.window+1:] + [t_s]
            invalid_t_s = t_s
            if len(attempted_times_s) == self.window:
                invalid_t_s = attempted_times_s[0] + (t_s-attempted_times_s[0])/2
            self.buffer.clear()
            return invalid_measurement(invalid_t_s)
        self.buffer.append((t_s, values.copy()))
        if len(self.buffer) < self.window:
            return invalid_measurement(t_s)
        return self.predict_window(np.stack([v for _, v in self.buffer]),
                                   np.array([time_s for time_s, _ in self.buffer]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--checkpoint', required=True)
    parser.add_argument('--input-npy', required=True)
    parser.add_argument('--timestamps-npy', required=True, help='Measured/resampled seconds-since-boot vector, oldest first.')
    parser.add_argument('--device', default='cpu')
    args = parser.parse_args()
    predictor = OdometerPredictor(args.checkpoint, args.device)
    values = np.load(args.input_npy, allow_pickle=False)
    if values.ndim == 3 and values.shape[0] == 1:
        values = values[0]
    times_s = np.load(args.timestamps_npy, allow_pickle=False)
    print(json.dumps(predictor.predict_window(values, times_s), indent=2, allow_nan=False))


if __name__ == '__main__':
    main()
