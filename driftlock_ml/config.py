"""Shared ML interface version and configurable validation limits (SI units).

These screens catch obvious mistakes; none proves source units or label quality.
The navigation engine owns alignment, fusion and its separate configuration.
"""
from __future__ import annotations
import numpy as np

CONVENTIONS_VERSION = '1.1'
MODEL_CONTRACT_VERSION = 'driftlock-ml-1.1-centre-forward'
CONTRACT_FIELDS = {
    'conventions_version': CONVENTIONS_VERSION,
    'model_contract_version': MODEL_CONTRACT_VERSION,
    'prediction_timestamp': 'window_centre',
    'speed_semantics': 'vehicle_forward_signed',
    'time_base': 'seconds_since_boot',
}
MAX_BOOT_TIME_S = 10_000_000.0
MAX_SPEED_MPS = 60.0
MAX_GYRO_RADPS = 5.0
STATIONARY_SPEED_MPS = 0.1
STATIONARY_GYRO_RADPS = 0.1
STATIONARY_GRAVITY_TOL_MPS2 = 1.5
MIN_ACCEL_NORM_MPS2 = 0.1
GRID_TOLERANCE_FRACTION = 0.1
MAX_SAMPLE_AGE_PERIODS = 1.5
INVALID_SIGMA_MPS = 60.0
TIME_COMPARISON_TOL_S = 1e-8
VALIDATION_LIMITS = {
    'max_boot_time_s': MAX_BOOT_TIME_S,
    'max_speed_mps': MAX_SPEED_MPS,
    'max_gyro_radps': MAX_GYRO_RADPS,
    'min_accel_norm_mps2': MIN_ACCEL_NORM_MPS2,
    'grid_tolerance_fraction': GRID_TOLERANCE_FRACTION,
    'invalid_sigma_mps': INVALID_SIGMA_MPS,
    'time_comparison_tol_s': TIME_COMPARISON_TOL_S,
}

def validate_config(config):
    for key, expected in CONTRACT_FIELDS.items():
        if config.get(key) != expected:
            raise ValueError(f'Model contract mismatch ({key}); legacy checkpoints cannot be relabelled. Train/export with conventions {CONVENTIONS_VERSION}.')
    if config.get('frame') != 'phone' or config.get('acceleration_includes_gravity') is not True:
        raise ValueError('Conventions require raw phone frame and acceleration including gravity.')
    if int(config.get('window_samples', 0)) < 2 or float(config.get('sample_hz', 0)) != 10.0:
        raise ValueError('This version requires 10 Hz and a complete window of at least two samples.')
    if float(config.get('speed_scale', 0)) <= 0 or float(config.get('sigma_floor_mps', 0)) <= 0:
        raise ValueError('speed_scale and sigma_floor_mps must be positive.')
    return config

def validate_timestamps(timestamps_s, count=None):
    times_s = np.asarray(timestamps_s, dtype=np.float64)
    if times_s.ndim != 1 or not len(times_s) or (count is not None and len(times_s) != count):
        raise ValueError('Supply a full one-dimensional hardware timestamp vector in seconds since boot.')
    if not np.isfinite(times_s).all() or np.any(times_s < 0) or np.any(times_s >= MAX_BOOT_TIME_S):
        raise ValueError('Timestamps must be finite seconds since boot below ten million; wall-clock time is forbidden.')
    if np.any(np.diff(times_s) <= 0):
        raise ValueError('Timestamps must be strictly increasing; duplicate/backward clocks are forbidden.')
    return times_s

def usable_imu(imu):
    values = np.asarray(imu, dtype=np.float32)
    if values.ndim != 2 or values.shape[1] != 6:
        raise ValueError('Expected [samples,6] phone IMU values in channel order ax,ay,az,gx,gy,gz.')
    if not np.isfinite(values).all():
        return False
    accel_norm_mps2 = np.linalg.norm(values[:, :3].astype(np.float64), axis=1)
    gyro_norm_radps = np.linalg.norm(values[:, 3:].astype(np.float64), axis=1)
    return bool(np.all(accel_norm_mps2 >= MIN_ACCEL_NORM_MPS2) and np.all(gyro_norm_radps <= MAX_GYRO_RADPS))
