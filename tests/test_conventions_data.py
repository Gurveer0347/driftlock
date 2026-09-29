"""Data boundary regressions for reviewed, signed, boot-clock centre targets."""
from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys

import numpy as np
import pandas as pd
import pytest

from driftlock_ml.data import Windows, check_manifest
from tools.make_demo_data import make_demo


CONTRACT = {
    "conventions_version": "1.1",
    "model_contract_version": "driftlock-ml-1.1-centre-forward",
    "prediction_timestamp": "window_centre",
    "speed_semantics": "vehicle_forward_signed",
    "time_base": "seconds_since_boot",
}
CFG = {
    **CONTRACT, "sample_hz": 10.0, "window_samples": 20,
    "conv_channels": 32, "hidden_size": 32, "speed_scale": 10.0,
    "sigma_floor_mps": 0.1, "frame": "phone", "acceleration_includes_gravity": True,
}
UNITS = {"time_unit": "s", "acceleration_unit": "m/s2", "gyro_unit": "rad/s", "speed_unit": "m/s"}
REPO = Path(__file__).resolve().parents[1]


def canonical_frame():
    t = 3600.0 + np.arange(40) / 10
    t[9] = 3600.905  # The reference at the centre must come directly from raw row 9.
    return pd.DataFrame({
        "timestamp_s": t, "ax": 0.0, "ay": 0.0, "az": 9.80665,
        "gx": 0.0, "gy": 0.0, "gz": 0.0,
        "speed_mps": np.arange(40, dtype=float) / 4 - 5,
        "label_valid": 1,
    })


def manifest_fixture(tmp_path, frame=None):
    frame = canonical_frame() if frame is None else frame
    entries = []
    for i, split in enumerate(["train", "val", "calibration", "test"]):
        drive = frame.copy()
        drive["timestamp_s"] += 100 * i
        path = tmp_path / f"drive_{i}.csv"
        drive.to_csv(path, index=False)
        entries.append({
            "path": path.name, "group_id": f"original_{i}", "split": split,
            "device_id": "SIMULATED_NOT_A_PHONE", "label_source": "synthetic_fixture",
            "label_semantics": "vehicle_forward_signed",
            "label_semantics_evidence": "Controlled signed synthetic vehicle-x equation.",
            "time_base_evidence": "Controlled synthetic boot origin of 3600 seconds.",
        })
    manifest = {
        **CONTRACT, **UNITS, "source_type": "synthetic", "frame": "phone",
        "acceleration_includes_gravity": True,
        "review_status": "SYNTHETIC_PIPELINE_TEST_ONLY", "drives": entries,
    }
    path = tmp_path / "manifest.json"
    path.write_text(json.dumps(manifest))
    return path


def loaded(tmp_path, frame=None):
    return Windows(check_manifest(manifest_fixture(tmp_path, frame)), CFG, "train")


def test_boot_offset_is_preserved_and_centre_label_is_signed(tmp_path):
    dataset = loaded(tmp_path)
    x, y, drive_id, end = dataset[0]
    drive = dataset.drives[drive_id]
    assert x.shape == (20, 6)
    assert end == 19
    assert drive["t"][0] == 3600.0
    assert drive["t"][end] == pytest.approx(3601.9)
    assert drive["target_t"][end] == pytest.approx(3600.95)
    assert float(y) == -2.75  # Raw row 9, neither grid row 9 nor latest row 19.


def test_future_reference_changes_cannot_change_centre_target(tmp_path):
    frame = canonical_frame()
    frame.loc[10:, "speed_mps"] = 40.0
    dataset = loaded(tmp_path, frame)
    assert float(dataset[0][1]) == -2.75


def test_invalid_reference_at_centre_rejects_window_even_if_latest_label_valid(tmp_path):
    frame = canonical_frame()
    frame["speed_mps"] = 10.0
    frame.loc[9, "label_valid"] = 0
    dataset = loaded(tmp_path, frame)
    assert (0, 19) not in [tuple(index) for index in dataset.index]


def test_future_imu_sample_is_not_used_on_earlier_grid_time(tmp_path):
    frame = canonical_frame()
    frame.loc[9, "ax"] = 10.0
    drive = loaded(tmp_path, frame).drives[0]
    assert drive["x"][9, 0] == 0.0
    assert drive["x"][10, 0] == 0.0


@pytest.mark.parametrize("value", [-1.0, 10_000_000.0, 1_788_000_000.0])
def test_bad_boot_clock_magnitude_is_rejected(tmp_path, value):
    frame = canonical_frame()
    frame["timestamp_s"] += value - 3600.0
    with pytest.raises(ValueError, match="boot|clock|timestamp"):
        loaded(tmp_path, frame)


@pytest.mark.parametrize("value", [60.0, -60.0, 100.0])
def test_out_of_range_speed_fails_loudly(tmp_path, value):
    frame = canonical_frame()
    frame.loc[12, "speed_mps"] = value
    with pytest.raises(ValueError, match="speed|m/s"):
        loaded(tmp_path, frame)


def test_gyro_vector_norm_is_screened_not_just_each_axis(tmp_path):
    frame = canonical_frame()
    frame.loc[12, ["gx", "gy", "gz"]] = [3.0, 3.0, 3.0]
    with pytest.raises(ValueError, match="gyro|rad/s"):
        loaded(tmp_path, frame)


def test_stationary_gravity_removed_acceleration_is_rejected(tmp_path):
    frame = canonical_frame()
    frame["speed_mps"] = 0.0
    frame["az"] = 0.5
    with pytest.raises(ValueError, match="gravity|stationary"):
        loaded(tmp_path, frame)


def test_stationary_gravity_gate_is_mount_independent(tmp_path):
    frame = canonical_frame()
    frame["speed_mps"] = 0.0
    frame["az"] = 0.0
    frame["ax"] = 9.80665
    assert len(loaded(tmp_path, frame)) > 0


@pytest.mark.parametrize("key,value", [
    ("conventions_version", None), ("model_contract_version", "legacy"),
    ("prediction_timestamp", "latest_sample"), ("time_base", "wall_clock"),
    ("speed_semantics", "nonnegative_magnitude"), ("frame", "vehicle"),
    ("acceleration_includes_gravity", False), ("gyro_unit", "deg/s"),
])
def test_manifest_rejects_missing_or_mismatched_contract(tmp_path, key, value):
    path = manifest_fixture(tmp_path)
    manifest = json.loads(path.read_text())
    if value is None:
        del manifest[key]
    else:
        manifest[key] = value
    path.write_text(json.dumps(manifest))
    with pytest.raises(ValueError, match="contract|convention|frame|gravity|unit|time_base|speed_semantics"):
        check_manifest(path)


@pytest.mark.parametrize("field,value", [
    ("label_semantics", "speed_magnitude"), ("label_semantics_evidence", ""),
    ("time_base_evidence", "UNREVIEWED"),
])
def test_manifest_rejects_unproved_label_and_clock_semantics(tmp_path, field, value):
    path = manifest_fixture(tmp_path)
    manifest = json.loads(path.read_text())
    manifest["drives"][0][field] = value
    path.write_text(json.dumps(manifest))
    with pytest.raises(ValueError, match="semantics|evidence|review"):
        check_manifest(path)


def test_real_manifest_requires_approved_review_and_evidence(tmp_path):
    path = manifest_fixture(tmp_path)
    manifest = json.loads(path.read_text())
    manifest["source_type"] = "real"
    manifest["review_status"] = "REQUIRES_AVI_REVIEW_OF_GROUPS_ROUTES_LABELS_AXES"
    path.write_text(json.dumps(manifest))
    with pytest.raises(ValueError, match="review|approved|APPROVED"):
        check_manifest(path)
    manifest["review_status"] = "APPROVED_FOR_TRAINING"
    path.write_text(json.dumps(manifest))
    with pytest.raises(ValueError, match="evidence"):
        check_manifest(path)
    manifest["review_evidence"] = "Fixture demonstrating required external review evidence."
    for entry in manifest["drives"]:
        entry["label_source"] = "reviewed_reference"
        entry["label_semantics_evidence"] = "Test stand-in for an approved signed vehicle-x source review."
        entry["time_base_evidence"] = "Test stand-in for a documented synchronized hardware boot clock."
        entry["device_id"] = "UNCONFIRMED"
    path.write_text(json.dumps(manifest))
    assert check_manifest(path)["source_type"] == "real"


def test_real_manifest_cannot_claim_synthetic_evidence_is_real_review(tmp_path):
    path = manifest_fixture(tmp_path)
    manifest = json.loads(path.read_text())
    manifest.update(source_type="real", review_status="APPROVED_FOR_TRAINING",
                    review_evidence="SYNTHETIC_PIPELINE_TEST_ONLY")
    path.write_text(json.dumps(manifest))
    with pytest.raises(ValueError, match="evidence|synthetic|review"):
        check_manifest(path)


def test_verified_forward_only_data_must_not_contain_valid_reverse_labels(tmp_path):
    path = manifest_fixture(tmp_path)
    manifest = json.loads(path.read_text())
    for entry in manifest["drives"]:
        entry["label_semantics"] = "verified_forward_only"
    path.write_text(json.dumps(manifest))
    with pytest.raises(ValueError, match="forward|negative|reverse"):
        Windows(check_manifest(path), CFG, "train")


def test_windows_does_not_bypass_metadata_gate_for_in_memory_manifest(tmp_path):
    manifest = check_manifest(manifest_fixture(tmp_path))
    manifest["time_base"] = "wall_clock"
    with pytest.raises(ValueError, match="time_base|clock|contract"):
        Windows(manifest, CFG, "train")


def test_exploratory_assumed_valid_labels_cannot_be_promoted_by_manifest_review(tmp_path):
    path = manifest_fixture(tmp_path)
    manifest = json.loads(path.read_text())
    manifest["drives"][0]["label_validity_status"] = "UNREVIEWED_LABELS_EXPLORATORY_ONLY"
    path.write_text(json.dumps(manifest))
    with pytest.raises(ValueError, match="validity|exploratory|review"):
        check_manifest(path)


def test_complete_grid_does_not_drop_exact_last_tick_due_to_float_duration(tmp_path):
    frame = canonical_frame()
    frame["timestamp_s"] += 1000.0
    # 4603.9 - 4600.0 rounds just below 3.9 in binary floating point.
    dataset = loaded(tmp_path, frame)
    assert len(dataset.drives[0]["t"]) == 40
    assert dataset.drives[0]["t"][-1] == frame.timestamp_s.iloc[-1]


def test_raw_samples_without_a_full_grid_window_report_no_valid_windows(tmp_path):
    frame = canonical_frame().iloc[:20].copy()
    frame["timestamp_s"] = 3600.0 + np.arange(20) * 0.09
    with pytest.raises(ValueError, match="No valid train windows"):
        loaded(tmp_path, frame)


def test_real_manifest_cannot_use_synthetic_validity_marker(tmp_path):
    path = manifest_fixture(tmp_path)
    manifest = json.loads(path.read_text())
    manifest.update(source_type="real", review_status="APPROVED_FOR_TRAINING",
                    review_evidence="Test stand-in for documented source and split review.")
    for entry in manifest["drives"]:
        entry["label_source"] = "reviewed_reference"
        entry["label_semantics_evidence"] = "Test stand-in for approved signed vehicle-x source review."
        entry["time_base_evidence"] = "Test stand-in for synchronized hardware boot clock review."
        entry["label_validity_status"] = "synthetic_known_validity"
    path.write_text(json.dumps(manifest))
    with pytest.raises(ValueError, match="synthetic|validity"):
        check_manifest(path)


def test_missing_imu_rejects_intersecting_windows_without_discarding_drive(tmp_path):
    frame = canonical_frame()
    frame.loc[20, "ax"] = np.nan
    dataset = loaded(tmp_path, frame)
    assert len(dataset) > 0
    for _, _, _, end in dataset:
        assert not (20 <= end <= 39)


def test_reference_age_is_checked_at_centre_on_original_timeline(tmp_path):
    frame = canonical_frame()
    frame["timestamp_s"] = 3600.0 + np.arange(40) / 10
    frame = frame.drop(index=[8, 9]).reset_index(drop=True)
    dataset = loaded(tmp_path, frame)
    # Centre 3600.95 has only the original 3600.7 reference, which is stale.
    assert not dataset.drives[0]["label_valid"][19]


def test_non_csv_download_is_rejected_before_parsing(tmp_path):
    path = manifest_fixture(tmp_path)
    (tmp_path / "drive_0.csv").write_text("<!doctype html><html>download unavailable</html>")
    with pytest.raises(ValueError, match="HTML"):
        check_manifest(path)


def test_synthetic_generation_has_new_contract_boot_origin_and_reverse_labels(tmp_path):
    path = make_demo(tmp_path / "fresh", seconds=10)
    manifest = check_manifest(path)
    assert manifest["model_contract_version"] == "driftlock-ml-1.1-centre-forward"
    frame = pd.read_csv(path.parent / "SYNTHETIC_drive_1.csv")
    assert frame.timestamp_s.min() > 0
    assert (frame.speed_mps < 0).any()
    assert frame.label_valid.eq(1).all()
    assert len(Windows(manifest, CFG, "train")) > 0


def test_synthetic_generation_refuses_to_overwrite_prior_outputs(tmp_path):
    path = make_demo(tmp_path, seconds=10)
    before = path.read_bytes()
    with pytest.raises(FileExistsError):
        make_demo(tmp_path, seconds=11)
    assert path.read_bytes() == before


def run_tool(tool, *args):
    return subprocess.run([sys.executable, str(REPO / "tools" / tool), *map(str, args)],
                          cwd=REPO, capture_output=True, text=True)


def conversion_fixture(tmp_path):
    source = tmp_path / "raw.csv"
    frame = canonical_frame()
    frame["timestamp_s"] *= 1e9
    frame["speed_mps"] *= 3.6
    frame.to_csv(source, index=False)
    mapping = {
        **CONTRACT, **UNITS, "time_unit": "ns", "speed_unit": "km/h",
        "frame": "phone", "acceleration_includes_gravity": True,
        "columns": {column: column for column in frame.columns},
        "label_semantics": "vehicle_forward_signed",
        "label_semantics_evidence": "Controlled signed synthetic conversion fixture.",
        "time_base_evidence": "Synthetic SensorEvent.timestamp-equivalent nanoseconds since boot.",
        "separator": ",", "skiprows": 0,
    }
    mapping_path = tmp_path / "map.json"
    mapping_path.write_text(json.dumps(mapping))
    return source, mapping_path, tmp_path / "canonical.csv"


def test_converter_preserves_boot_offset_signed_labels_and_audit(tmp_path):
    source, mapping, output = conversion_fixture(tmp_path)
    result = run_tool("convert_csv.py", "--input", source, "--mapping", mapping, "--output", output)
    assert result.returncode == 0, result.stderr
    converted = pd.read_csv(output)
    assert converted.timestamp_s.iloc[0] == 3600.0
    assert converted.speed_mps.iloc[0] == -5.0
    assert converted.label_valid.eq(1).all()
    audit = json.loads(output.with_suffix(".import.json").read_text())
    assert audit["model_contract_version"] == "driftlock-ml-1.1-centre-forward"
    assert audit["label_semantics"] == "vehicle_forward_signed"
    assert audit["source_sha256"] and audit["output_sha256"]


@pytest.mark.parametrize("key,value", [("time_base", "seconds_since_trip_start"),
                                        ("label_semantics", "speed_magnitude"),
                                        ("frame", "vehicle"),
                                        ("acceleration_includes_gravity", False)])
def test_converter_rejects_unsupported_semantics_before_writing(tmp_path, key, value):
    source, mapping, output = conversion_fixture(tmp_path)
    metadata = json.loads(mapping.read_text())
    metadata[key] = value
    mapping.write_text(json.dumps(metadata))
    result = run_tool("convert_csv.py", "--input", source, "--mapping", mapping, "--output", output)
    assert result.returncode != 0
    assert not output.exists()


@pytest.mark.parametrize("unit_field", ["time_unit", "acceleration_unit", "gyro_unit", "speed_unit"])
def test_converter_requires_explicit_supported_source_units(tmp_path, unit_field):
    source, mapping, output = conversion_fixture(tmp_path)
    metadata = json.loads(mapping.read_text())
    metadata[unit_field] = "UNREVIEWED"
    mapping.write_text(json.dumps(metadata))
    result = run_tool("convert_csv.py", "--input", source, "--mapping", mapping, "--output", output)
    assert result.returncode != 0
    assert "ValueError: Unsupported source unit" in result.stderr
    assert unit_field in result.stderr
    assert not output.exists()


def test_manifest_generator_never_claims_review_or_guesses_label_clock_evidence(tmp_path):
    manifest_fixture(tmp_path)
    output = tmp_path / "candidate.json"
    result = run_tool("build_manifest.py", "--data-dir", tmp_path, "--out", output,
                      "--source-type", "real", "--label-source", "unreviewed_reference")
    assert result.returncode == 0, result.stderr
    manifest = json.loads(output.read_text())
    assert manifest["model_contract_version"] == "driftlock-ml-1.1-centre-forward"
    assert manifest["review_status"] != "APPROVED_FOR_TRAINING"
    assert manifest["drives"][0]["label_semantics"] == "UNREVIEWED"
    assert manifest["drives"][0]["time_base_evidence"] == "UNREVIEWED"
    with pytest.raises(ValueError, match="review|APPROVED"):
        check_manifest(output)
