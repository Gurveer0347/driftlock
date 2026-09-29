"""Standalone .pt2 inference: requires only Python, NumPy and PyTorch.

This file is copied into each portable bundle. It deliberately imports no
driftlock_ml/training code. Supply actual seconds-since-boot times for your IMU
window; the bundled golden times are synthetic numerical-parity evidence only
when contract.json says so. A .pt2 model is not an Android LiteRT model.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import torch


def validate_timestamps(timestamps_s, window, max_boot_time_s):
    """Reject times that cannot describe a window on the agreed boot clock."""
    if timestamps_s is None:
        raise ValueError("Actual timestamps in seconds since boot are required.")
    times = np.asarray(timestamps_s, dtype=np.float64)
    if times.shape != (window,):
        raise ValueError(f"Expected timestamps with shape [{window}].")
    if not np.isfinite(times).all() or np.any(times < 0) or np.any(times >= max_boot_time_s):
        raise ValueError("Timestamps must be finite seconds since boot below ten million; check the clock.")
    if np.any(np.diff(times) <= 0):
        raise ValueError("Timestamps must be strictly increasing on one boot clock.")
    return times


class PortableOdometerPredictor:
    """A complete-window API producing exactly t/speed_mps/sigma_mps/valid."""

    def __init__(self, bundle):
        self.bundle = Path(bundle)
        self.contract = json.loads((self.bundle / "contract.json").read_text(encoding="utf-8"))
        expected = {
            "conventions_version": "1.1",
            "model_contract_version": "driftlock-ml-1.1-centre-forward",
            "prediction_timestamp": "window_centre",
            "speed_semantics": "vehicle_forward_signed",
            "time_base": "seconds_since_boot",
        }
        config = self.contract.get("config", {})
        if self.contract.get("version") != expected["model_contract_version"]:
            raise ValueError("Legacy or incompatible portable model contract.")
        for field, value in expected.items():
            if self.contract.get(field) != value or config.get(field) != value:
                raise ValueError(f"Portable contract/config mismatch: {field}.")
        self.window = int(config["window_samples"])
        self.sample_hz = float(config["sample_hz"])
        if self.window < 2 or self.sample_hz != 10.0:
            raise ValueError("This contract requires a complete window of at least two samples at 10 Hz.")
        if (self.contract.get("input_shape") != [1, self.window, 6]
                or self.contract.get("output_shape") != [1, 2]
                or self.contract.get("output_order") != ["speed_mps", "sigma_mps"]
                or self.contract.get("filter_packet_fields") != ["t", "speed_mps", "sigma_mps", "valid"]
                or config.get("frame") != "phone"
                or config.get("acceleration_includes_gravity") is not True):
            raise ValueError("Portable tensor or sensor contract mismatch.")
        self.limits = self.contract.get("validation", {})
        for key in ("max_boot_time_s", "max_speed_mps", "max_gyro_radps",
                    "min_accel_norm_mps2", "grid_tolerance_fraction", "invalid_sigma_mps",
                    "time_comparison_tol_s"):
            value = self.limits.get(key)
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not np.isfinite(value) or value <= 0:
                raise ValueError(f"Missing or invalid portable validation limit: {key}.")
        self.calibrated = self.contract.get("uncertainty_calibrated") is True
        path = self.bundle / "virtual_odometer.pt2"
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if digest != self.contract.get("portable_sha256"):
            raise ValueError("Portable model hash does not match contract.json.")
        self.model = torch.export.load(path).module()

    def predict_window(self, imu, timestamps_s):
        """Centre labels are available only after the newest input has arrived.

        Clock/shape mistakes raise; unusable sensor windows and uncalibrated or
        invalid outputs produce a finite invalid packet. Thresholds screen
        plausibility; they do not establish source units or calibration quality.
        """
        times = validate_timestamps(timestamps_s, self.window, self.limits["max_boot_time_s"])
        sample = np.asarray(imu, dtype=np.float32)
        if sample.shape != (self.window, 6):
            raise ValueError(f"Expected raw IMU shape [{self.window},6].")
        centre = float(times[0] + (times[-1] - times[0]) / 2)
        invalid = {"t": centre, "speed_mps": 0.0,
                   "sigma_mps": float(self.limits["invalid_sigma_mps"]), "valid": False}
        period = 1.0 / self.sample_hz
        tolerance = self.limits["grid_tolerance_fraction"] * period + self.limits["time_comparison_tol_s"]
        if (np.any(np.abs(np.diff(times) - period) > tolerance)
                or not np.isfinite(sample).all()
                or not self.calibrated):
            return invalid
        # Use float64 for norms so even outlandish float32 inputs cannot overflow.
        accel_norm = np.linalg.norm(sample[:, :3].astype(np.float64), axis=1)
        gyro_norm = np.linalg.norm(sample[:, 3:].astype(np.float64), axis=1)
        if (np.any(accel_norm < self.limits["min_accel_norm_mps2"])
                or np.any(gyro_norm > self.limits["max_gyro_radps"])):
            return invalid
        with torch.inference_mode():
            output = self.model(torch.from_numpy(sample).unsqueeze(0)).cpu().numpy()
        if output.shape != (1, 2) or not np.isfinite(output).all():
            return invalid
        speed, sigma = map(float, output[0])
        if abs(speed) >= self.limits["max_speed_mps"] or sigma <= 0:
            return invalid
        return {"t": centre, "speed_mps": speed, "sigma_mps": sigma, "valid": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", default=str(Path(__file__).resolve().parent))
    parser.add_argument("--input-npy", required=True, help="Raw float32 [20,6] or [1,20,6] IMU window.")
    parser.add_argument("--timestamps-npy", required=True, help="Actual float64 [20] seconds-since-boot timestamps.")
    args = parser.parse_args()
    predictor = PortableOdometerPredictor(args.bundle)
    sample = np.load(args.input_npy, allow_pickle=False)
    if sample.shape == (1, predictor.window, 6):
        sample = sample[0]
    times = np.load(args.timestamps_npy, allow_pickle=False)
    print(json.dumps(predictor.predict_window(sample, times), indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
