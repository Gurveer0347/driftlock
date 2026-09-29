"""Strict canonical data contract and memory-efficient causal windows."""
from __future__ import annotations
from pathlib import Path
import numpy as np
import pandas as pd
import torch
from torch.utils.data import Dataset
from .utils import read_json, sha256
from .config import (
    CONTRACT_FIELDS, MAX_BOOT_TIME_S, MAX_SPEED_MPS, MAX_GYRO_RADPS,
    STATIONARY_SPEED_MPS, STATIONARY_GYRO_RADPS, STATIONARY_GRAVITY_TOL_MPS2,
    MIN_ACCEL_NORM_MPS2, MAX_SAMPLE_AGE_PERIODS, validate_config,
)
from .rotations import GRAVITY_MPS2

CHANNELS = ["ax", "ay", "az", "gx", "gy", "gz"]
REQUIRED = ["timestamp_s", *CHANNELS, "speed_mps", "label_valid"]
SPLITS = {"train", "val", "calibration", "test"}
CANONICAL_UNITS = {"time_unit": "s", "acceleration_unit": "m/s2",
                   "gyro_unit": "rad/s", "speed_unit": "m/s"}
LABEL_SEMANTICS = {"vehicle_forward_signed", "verified_forward_only"}
BOOT_TIME_BASE = CONTRACT_FIELDS["time_base"]
RECORDING_TIME_BASE = "seconds_since_recording_start"
DATA_TIME_BASES = {BOOT_TIME_BASE, RECORDING_TIME_BASE}


def data_time_base(metadata):
    """Offline source clock, independent of the unchanged model/runtime clock.

    Legacy manifests omitted this field and required reviewed boot-clock evidence.
    New recording-relative data must declare it explicitly at manifest and drive.
    """
    base = metadata.get("data_time_base", BOOT_TIME_BASE)
    if base not in DATA_TIME_BASES:
        raise ValueError(f"Unsupported data_time_base {base!r}; wall clocks and unknown origins are unsupported.")
    return base


def validate_data_metadata(metadata, *, canonical_units=True):
    """Require declared conventions; plausible numbers cannot establish a clock/frame."""
    for key, expected in CONTRACT_FIELDS.items():
        if metadata.get(key) != expected:
            raise ValueError(f"Data contract mismatch in {key}: expected {expected!r}.")
    data_time_base(metadata)
    if "sensor_reference_clock" in metadata or "sensor_reference_clock_evidence" in metadata:
        validate_data_clock_metadata(metadata, source_type=metadata.get("source_type"))
    if metadata.get("frame") != "phone":
        raise ValueError("Data frame must be raw phone axes; alignment belongs to navigation.")
    if metadata.get("acceleration_includes_gravity") is not True:
        raise ValueError("Raw acceleration must include gravity (TYPE_ACCELEROMETER).")
    if canonical_units:
        for key, expected in CANONICAL_UNITS.items():
            if metadata.get(key) != expected:
                raise ValueError(f"Canonical unit mismatch in {key}: expected {expected!r}.")


def _require_evidence(value, field, source_type=None):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"Missing reviewed {field} evidence.")
    upper = value.upper()
    placeholders = ("UNREVIEWED", "UNCONFIRMED", "UNKNOWN", "REPLACE_WITH", "TODO")
    if any(marker in upper for marker in placeholders):
        raise ValueError(f"Unreviewed placeholder in {field} evidence.")
    if source_type == "real" and any(marker in upper for marker in ("SYNTHETIC", "SIMULATED")):
        raise ValueError(f"Real-data {field} evidence cannot be synthetic or simulated.")


def validate_data_clock_metadata(entry, *, expected_time_base=None, source_type=None):
    base = data_time_base(entry)
    if expected_time_base is not None and base != expected_time_base:
        raise ValueError("Drive data_time_base is missing or conflicts with the manifest data clock.")
    if "time_base" in entry and entry["time_base"] != BOOT_TIME_BASE:
        raise ValueError("Entry time_base conflicts with the model/runtime boot clock; declare data_time_base separately.")
    expected_shared = "shared_recording_clock" if base == RECORDING_TIME_BASE else "shared_boot_clock"
    if base == RECORDING_TIME_BASE or "sensor_reference_clock" in entry or "sensor_reference_clock_evidence" in entry:
        if entry.get("sensor_reference_clock") != expected_shared:
            raise ValueError(f"The data clock requires reviewed sensor_reference_clock={expected_shared!r}.")
        _require_evidence(entry.get("sensor_reference_clock_evidence"),
                          "sensor_reference_clock_evidence", source_type)
    return base


def validate_label_metadata(entry, *, source_type=None):
    if entry.get("label_semantics") not in LABEL_SEMANTICS:
        raise ValueError("Label semantics require reviewed vehicle_forward_signed or verified_forward_only; "
                         "GPS speed magnitude does not establish reverse sign.")
    for field in ("label_semantics_evidence", "time_base_evidence"):
        _require_evidence(entry.get(field), field, source_type)
    validate_data_clock_metadata(entry, source_type=source_type)
    if "label_validity_status" in entry and entry["label_validity_status"] not in {
        "provided_validity_mask", "reviewed_validity_mask", "synthetic_known_validity",
    }:
        raise ValueError("Unreviewed/exploratory label validity cannot enter a reportable manifest.")
    if source_type == "real":
        _require_evidence(entry.get("label_source"), "label_source", source_type)
        if entry.get("label_validity_status") == "synthetic_known_validity":
            raise ValueError("Real data cannot use a synthetic label validity marker.")


def _validate_manifest_metadata(manifest):
    validate_data_metadata(manifest)
    base = data_time_base(manifest)
    source_type = manifest.get("source_type")
    if source_type not in {"real", "synthetic"}:
        raise ValueError("Manifest must explicitly say source_type=real or synthetic.")
    if source_type == "real":
        if manifest.get("review_status") != "APPROVED_FOR_TRAINING":
            raise ValueError("Real-data review_status must be APPROVED_FOR_TRAINING after source, "
                             "clock, labels and independent-group review.")
        _require_evidence(manifest.get("review_evidence"), "review_evidence", source_type)
    elif manifest.get("review_status") != "SYNTHETIC_PIPELINE_TEST_ONLY":
        raise ValueError("Synthetic manifest review_status must be SYNTHETIC_PIPELINE_TEST_ONLY.")
    for entry in manifest.get("drives", []):
        validate_label_metadata(entry, source_type=source_type)
        validate_data_clock_metadata(entry, expected_time_base=base, source_type=source_type)


def data_clock_metadata(manifest):
    """Preserve reviewed source-clock evidence in training and scoring artifacts."""
    _validate_manifest_metadata(manifest)
    return {"data_time_base": data_time_base(manifest), "runtime_time_base": BOOT_TIME_BASE,
            "timestamp_origin_changed": False,
            "drives": [{"path": entry["path"], "group_id": entry["group_id"],
                        "data_time_base": data_time_base(entry),
                        "time_base_evidence": entry["time_base_evidence"],
                        **{key: entry[key] for key in ("sensor_reference_clock", "sensor_reference_clock_evidence")
                           if key in entry}} for entry in manifest["drives"]]}


def reject_non_csv_payload(path):
    with Path(path).open("rb") as handle:
        prefix = handle.read(512).lstrip().lower()
    if prefix.startswith(b"version https://git-lfs.github.com"):
        raise ValueError("Git LFS pointer is not CSV data; obtain the actual source file.")
    if prefix.startswith((b"<!doctype html", b"<html", b"<head", b"<body")):
        raise ValueError("HTML response is not CSV data; inspect the source download.")


def validate_canonical_frame(frame, entry, *, name="CSV"):
    """Return numeric arrays, screening units without guessing source semantics."""
    missing = set(REQUIRED) - set(frame.columns)
    if missing:
        raise ValueError(f"{name}: missing {sorted(missing)}. Convert raw data first.")
    if len(frame) < 2:
        raise ValueError(f"{name}: at least two timestamped rows are required.")
    t = pd.to_numeric(frame.timestamp_s, errors="raise").to_numpy(dtype=np.float64)
    if not np.isfinite(t).all() or np.any(np.diff(t) <= 0):
        raise ValueError(f"{name}: timestamps must be finite and strictly increasing.")
    if np.any(t < 0) or np.any(t >= MAX_BOOT_TIME_S):
        raise ValueError(f"{name}: timestamp_s must be nonnegative {data_time_base(entry)} below {MAX_BOOT_TIME_S}; "
                         "wall clocks and unknown origins are unsupported.")
    x = frame[CHANNELS].apply(pd.to_numeric, errors="raise").to_numpy(dtype=np.float32)
    y = pd.to_numeric(frame.speed_mps, errors="raise").to_numpy(dtype=np.float32)
    lv = pd.to_numeric(frame.label_valid, errors="raise").to_numpy()
    if not np.isin(lv, [0, 1]).all():
        raise ValueError("label_valid must contain 0 or 1, not arbitrary scores.")
    if np.any(np.isfinite(y) & (np.abs(y) >= MAX_SPEED_MPS)):
        raise ValueError(f"{name}: abs(speed_mps) must be below {MAX_SPEED_MPS} m/s; review speed units.")
    if entry.get("label_semantics") == "verified_forward_only" and np.any((lv == 1) & (y < 0)):
        raise ValueError(f"{name}: verified_forward_only labels contain valid negative reverse speed.")
    acceleration_norm = np.linalg.norm(x[:, :3].astype(np.float64), axis=1)
    gyro_norm = np.linalg.norm(x[:, 3:].astype(np.float64), axis=1)
    if np.any(np.isfinite(gyro_norm) & (gyro_norm > MAX_GYRO_RADPS)):
        raise ValueError(f"{name}: gyroscope norm exceeds {MAX_GYRO_RADPS} rad/s; review gyro units/axes.")
    stationary = ((lv == 1) & np.isfinite(y) & (np.abs(y) < STATIONARY_SPEED_MPS)
                  & np.isfinite(gyro_norm) & (gyro_norm <= STATIONARY_GYRO_RADPS))
    gravity_error = np.abs(acceleration_norm - GRAVITY_MPS2)
    if np.any(stationary & np.isfinite(gravity_error) & (gravity_error > STATIONARY_GRAVITY_TOL_MPS2)):
        raise ValueError(f"{name}: stationary acceleration must include gravity near {GRAVITY_MPS2} m/s2.")
    if np.any(np.isfinite(acceleration_norm) & (acceleration_norm < MIN_ACCEL_NORM_MPS2)):
        raise ValueError(f"{name}: acceleration norm is near zero; inspect gravity removal and source units.")
    return t, x, y, lv


def check_manifest(path):
    manifest = read_json(path)
    _validate_manifest_metadata(manifest)
    entries = manifest.get("drives", [])
    if not entries:
        raise ValueError("Manifest has no drives. Read docs/DATA_CONTRACT.md.")
    root = Path(path).resolve().parent
    seen_groups, seen_paths, seen_hashes = {}, set(), {}
    for entry in entries:
        for key in ["path", "group_id", "split", "label_source"]:
            if not entry.get(key):
                raise ValueError(f"Drive is missing {key}: {entry}")
        if entry["split"] not in SPLITS:
            raise ValueError(f"Unknown split: {entry['split']}")
        group, split = entry["group_id"], entry["split"]
        if group in seen_groups and seen_groups[group] != split:
            raise ValueError(f"Data leakage: original group {group} appears in multiple splits.")
        seen_groups[group] = split
        file = (root / entry["path"]).resolve()
        if file in seen_paths:
            raise ValueError(f"Same file listed twice: {file}")
        seen_paths.add(file)
        if not file.is_file():
            raise FileNotFoundError(f"Missing canonical CSV: {file}")
        reject_non_csv_payload(file)
        digest = sha256(file)
        if digest in seen_hashes and seen_hashes[digest] != split:
            raise ValueError("Identical data appears in different splits under different filenames.")
        seen_hashes[digest] = split
        entry["resolved_path"] = str(file)
        entry["sha256"] = digest
    present = {x["split"] for x in entries}
    if not SPLITS.issubset(present):
        raise ValueError(f"Use separate drive groups for all four splits. Missing: {SPLITS-present}")
    return manifest


def load_drive(entry, config):
    validate_config(config)
    validate_label_metadata(entry)
    file = Path(entry["resolved_path"])
    reject_non_csv_payload(file)
    frame = pd.read_csv(file)
    if len(frame) < int(config["window_samples"]):
        raise ValueError(f"{file.name}: too short for one model window.")
    t, x, y, lv = validate_canonical_frame(frame, entry, name=file.name)
    valid_label = (lv == 1) & np.isfinite(y) & (np.abs(y) < MAX_SPEED_MPS)
    valid_imu = np.isfinite(x).all(axis=1)
    hz = float(config["sample_hz"])
    median_dt = float(np.median(np.diff(t)))
    if median_dt > MAX_SAMPLE_AGE_PERIODS / hz:
        raise ValueError(f"{file.name}: samples are too slow ({median_dt:.4f}s) for {hz} Hz.")
    if median_dt < .75 / hz:
        raise ValueError(f"{file.name}: higher-rate data needs agreed causal anti-alias resampling first.")
    # Include the potential final tick before comparing actual times: subtracting
    # a large boot offset can round an exact duration just below its last tick.
    sample_count = int(np.ceil((t[-1] - t[0]) * hz)) + 1
    grid = t[0] + np.arange(sample_count, dtype=np.float64) / hz
    grid = grid[grid <= t[-1]]
    # Latest past sample only, never a future IMU sample.
    idx = np.searchsorted(t, grid, side="right") - 1
    age = grid - t[idx]
    usable = valid_imu[idx] & (age <= MAX_SAMPLE_AGE_PERIODS/hz) & (age >= 0)
    # Padded NaNs are never passed to the model: any intersecting window is rejected.
    xg = x[idx]
    window = int(config["window_samples"])
    target_t = np.full(len(grid), np.nan, dtype=np.float64)
    complete_count = max(0, len(grid) - window + 1)
    target_t[window-1:] = (grid[:complete_count] + grid[window-1:]) / 2
    # Query the original reference stream at centre time, not the held IMU grid:
    # a reference after a grid tick but before the centre must not be held twice.
    centre_idx = np.searchsorted(t, target_t[window-1:], side="right") - 1
    reference_age = target_t[window-1:] - t[centre_idx]
    yg = np.full(len(grid), np.nan, dtype=np.float32)
    yg[window-1:] = y[centre_idx]
    labels = np.zeros(len(grid), dtype=bool)
    labels[window-1:] = (valid_label[centre_idx] & (reference_age >= 0)
                         & (reference_age <= MAX_SAMPLE_AGE_PERIODS/hz))
    return {"x": xg, "y": yg, "t": grid, "imu_valid": usable,
            "target_t": target_t, "label_valid": labels, "name": file.stem, "entry": entry,
            "data_time_base": data_time_base(entry)}


class Windows(Dataset):
    def __init__(self, manifest, config, split, stride=1):
        validate_config(config)
        _validate_manifest_metadata(manifest)
        if split not in SPLITS:
            raise ValueError(f"Unknown split: {split}")
        if int(stride) < 1:
            raise ValueError("Window stride must be positive.")
        self.config, self.split = config, split
        self.window = int(config["window_samples"])
        self.drives = []
        self.index = []
        for entry in manifest["drives"]:
            if entry["split"] != split:
                continue
            drive = load_drive(entry, config)
            d = len(self.drives)
            self.drives.append(drive)
            bad = np.concatenate(([0], np.cumsum(~drive["imu_valid"])))
            for end in range(self.window-1, len(drive["x"]), int(stride)):
                start = end - self.window + 1
                if bad[end+1] == bad[start] and drive["label_valid"][end]:
                    self.index.append((d, end))
        if not self.index:
            raise ValueError(f"No valid {split} windows. Check labels, units, sample rate and gaps.")
        self.index = np.asarray(self.index, dtype=np.int64)

    def __len__(self):
        return len(self.index)

    def __getitem__(self, index):
        d, end = self.index[index]
        drive = self.drives[d]
        x = drive["x"][end-self.window+1:end+1].copy()
        return (torch.from_numpy(x), torch.tensor(drive["y"][end], dtype=torch.float32),
                int(d), int(end))

    def normalization(self):
        # TRAINING only. Each raw sample counted once, not once per overlapping window.
        if self.split != "train":
            raise ValueError("Normalization may only be fitted to the training split.")
        total = np.zeros(6, dtype=np.float64)
        squared = total.copy()
        count = 0
        for drive in self.drives:
            z = drive["x"][drive["imu_valid"]].astype(np.float64)
            total += z.sum(0)
            squared += (z*z).sum(0)
            count += len(z)
        mean = total / count
        std = np.sqrt(np.maximum(squared/count - mean**2, 1e-8))
        return mean.astype(np.float32), std.astype(np.float32)


def check_evaluation_provenance(manifest, checkpoint, split):
    """Protect evaluation even when somebody supplies a different manifest later.

    New genuinely independent test drives are allowed. Old training/development
    or calibration groups may not be relabelled as a clean test set.
    """
    original = checkpoint.get("run", {}).get("manifest_snapshot")
    if not original:
        raise ValueError("Checkpoint lacks training provenance; cannot claim clean evaluation.")
    if original["source_type"] != manifest["source_type"]:
        raise ValueError("Source-type mismatch: do not score a synthetic smoke model as a real trained model.")
    forbidden = {"val": {"train"}, "calibration": {"train", "val"},
                 "test": {"train", "val", "calibration"}}[split]
    used = [e for e in original["drives"] if e["split"] in forbidden]
    if split == "test":
        used += checkpoint.get("calibration", {}).get("entries", [])
    groups = {e["group_id"] for e in used}
    hashes = {e["sha256"] for e in used if e.get("sha256")}
    for entry in manifest["drives"]:
        if entry["split"] == split and (entry["group_id"] in groups or entry["sha256"] in hashes):
            raise ValueError(f"Data leakage against checkpoint history: {entry['group_id']} is not a clean {split} group.")
