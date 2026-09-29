"""Offline clock fixtures are synthetic; they establish no IO-VNBD eligibility."""
from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys

import numpy as np
import pandas as pd
import pytest
import torch

from driftlock_ml.config import CONTRACT_FIELDS, validate_config
from driftlock_ml.data import Windows, check_manifest
from driftlock_ml.evaluate import predict_dataset
from driftlock_ml.model import VirtualOdometer
from driftlock_ml.utils import sha256


REPO = Path(__file__).resolve().parents[1]
RELATIVE = "seconds_since_recording_start"
BOOT = "seconds_since_boot"
CFG = json.loads((REPO / "configs/baseline.json").read_text())
UNITS = {"time_unit": "s", "acceleration_unit": "m/s2", "gyro_unit": "rad/s", "speed_unit": "m/s"}


def fixture(tmp_path, *, relative=True, origin=12.5):
    tmp_path.mkdir(parents=True, exist_ok=True)
    entries = []
    for i, split in enumerate(("train", "val", "calibration", "test")):
        # Offset non-grid samples slightly into the past to give an unambiguous
        # causal test independent of floating-point equality at grid ticks.
        t = np.arange(40, dtype=float) / 10
        t[1:-1] -= .001
        t += origin
        frame = pd.DataFrame({"timestamp_s": t, "ax": np.arange(40) / 100 + i,
                              "ay": 0., "az": 9.80665, "gx": 0., "gy": 0., "gz": 0.,
                              "speed_mps": np.arange(40) / 10 + 1. + i / 10,
                              "label_valid": 1})
        file = tmp_path / f"drive_{i}.csv"
        frame.to_csv(file, index=False)
        entry = {"path": file.name, "group_id": f"independent_fixture_{i}", "split": split,
                 "label_source": "synthetic_fixture", "label_semantics": "vehicle_forward_signed",
                 "label_semantics_evidence": "Controlled synthetic signed-speed fixture.",
                 "time_base_evidence": "Controlled synthetic recording-relative clock." if relative
                 else "Controlled synthetic synchronized boot clock."}
        if relative:
            entry.update(data_time_base=RELATIVE, sensor_reference_clock="shared_recording_clock",
                         sensor_reference_clock_evidence="Controlled synthetic IMU and reference share the unchanged recording timeline.")
        entries.append(entry)
    manifest = {**CONTRACT_FIELDS, **UNITS, "source_type": "synthetic", "frame": "phone",
                "acceleration_includes_gravity": True, "review_status": "SYNTHETIC_PIPELINE_TEST_ONLY",
                "drives": entries}
    if relative:
        manifest["data_time_base"] = RELATIVE
    path = tmp_path / "manifest.json"
    path.write_text(json.dumps(manifest))
    return path


def test_reviewed_relative_clock_can_feed_training_without_relabeling_runtime(tmp_path):
    path = fixture(tmp_path)
    before = {p.name: sha256(p) for p in tmp_path.iterdir()}
    manifest = check_manifest(path)
    data = Windows(manifest, CFG, "train")
    drive = data.drives[0]
    assert drive["data_time_base"] == RELATIVE
    assert drive["t"][0] == 12.5
    assert drive["target_t"][19] == pytest.approx(13.45)
    assert drive["t"][19] == pytest.approx(14.4)
    assert manifest["time_base"] == CFG["time_base"] == BOOT
    model = VirtualOdometer(CFG)
    model.set_normalization(*data.normalization())
    x, y, _, _ = data[0]
    # One gradient, no optimizer step or fit: exercise the actual model boundary.
    (model(x[None])[0, 0] - y).square().backward()
    assert model.conv1.weight.grad is not None
    assert before == {p.name: sha256(p) for p in tmp_path.iterdir()}


@pytest.mark.parametrize("origin", [0., 1024., 3600.])
def test_origin_translation_keeps_windows_targets_and_centre_delay(tmp_path, origin):
    base = Windows(check_manifest(fixture(tmp_path / "a", origin=0.)), CFG, "train")
    shifted = Windows(check_manifest(fixture(tmp_path / "b", origin=origin)), CFG, "train")
    np.testing.assert_array_equal(base.index, shifted.index)
    for a, b in zip(base, shifted):
        torch.testing.assert_close(a[0], b[0], rtol=0., atol=0.)
        torch.testing.assert_close(a[1], b[1], rtol=0., atol=0.)
    np.testing.assert_allclose(shifted.drives[0]["target_t"] - origin,
                               base.drives[0]["target_t"], atol=1e-10, rtol=0., equal_nan=True)
    np.testing.assert_allclose(shifted.drives[0]["t"][19:] - shifted.drives[0]["target_t"][19:], .95)


def test_relative_timeline_never_uses_future_imu_or_reference(tmp_path):
    path = fixture(tmp_path)
    file = tmp_path / "drive_0.csv"
    frame = pd.read_csv(file)
    frame.loc[9, ["timestamp_s", "ax"]] = [13.405, 9.]
    frame.loc[10:, "speed_mps"] = 40.
    frame.to_csv(file, index=False)
    data = Windows(check_manifest(path), CFG, "train")
    assert data.drives[0]["x"][9, 0] == pytest.approx(.08)
    assert float(data[0][1]) == pytest.approx(1.9)


@pytest.mark.parametrize("field,value", [("data_time_base", None), ("data_time_base", BOOT),
    ("sensor_reference_clock", None), ("sensor_reference_clock", "separate_clocks"),
    ("sensor_reference_clock_evidence", None), ("sensor_reference_clock_evidence", "UNREVIEWED"),
    ("time_base_evidence", "UNKNOWN"), ("time_base", RELATIVE)])
def test_relative_metadata_requires_explicit_nonconflicting_clock_evidence(tmp_path, field, value):
    path = fixture(tmp_path)
    manifest = json.loads(path.read_text())
    if value is None:
        manifest["drives"][0].pop(field)
    else:
        manifest["drives"][0][field] = value
    path.write_text(json.dumps(manifest))
    with pytest.raises(ValueError, match="clock|time_base|evidence"):
        check_manifest(path)


@pytest.mark.parametrize("field,value", [("data_time_base", RELATIVE), ("time_base", "wall_clock"),
    ("sensor_reference_clock", "shared_recording_clock")])
def test_legacy_boot_manifest_cannot_hide_conflicting_entry_clock(tmp_path, field, value):
    path = fixture(tmp_path, relative=False)
    manifest = json.loads(path.read_text())
    manifest["drives"][0][field] = value
    path.write_text(json.dumps(manifest))
    with pytest.raises(ValueError, match="clock|time_base"):
        check_manifest(path)


@pytest.mark.parametrize("relative,declared", [(True, "shared_boot_clock"), (False, "shared_recording_clock")])
def test_optional_global_shared_clock_cannot_conflict_with_data_clock(tmp_path, relative, declared):
    path = fixture(tmp_path, relative=relative)
    manifest = json.loads(path.read_text())
    manifest["sensor_reference_clock"] = declared
    manifest["sensor_reference_clock_evidence"] = "Controlled synthetic shared timeline."
    path.write_text(json.dumps(manifest))
    with pytest.raises(ValueError, match="clock"):
        check_manifest(path)


@pytest.mark.parametrize("problem", ["negative", "wall_clock", "reset", "duplicate", "nonfinite", "units"])
def test_offline_approval_does_not_bypass_numeric_clock_or_unit_gates(tmp_path, problem):
    path = fixture(tmp_path)
    if problem == "units":
        manifest = json.loads(path.read_text())
        manifest["time_unit"] = "ms"
        path.write_text(json.dumps(manifest))
    else:
        file = tmp_path / "drive_0.csv"
        frame = pd.read_csv(file)
        if problem == "negative": frame["timestamp_s"] -= 20.
        if problem == "wall_clock": frame["timestamp_s"] += 1_788_000_000.
        if problem == "reset": frame.loc[25:, "timestamp_s"] -= 10.
        if problem == "duplicate": frame.loc[10, "timestamp_s"] = frame.loc[9, "timestamp_s"]
        if problem == "nonfinite": frame.loc[10, "timestamp_s"] = np.nan
        frame.to_csv(file, index=False)
    with pytest.raises(ValueError, match="clock|timestamp|unit|increasing"):
        Windows(check_manifest(path), CFG, "train")


def test_runtime_config_still_refuses_recording_relative_contract():
    with pytest.raises(ValueError, match="time_base|contract"):
        validate_config({**CFG, "time_base": RELATIVE})


@pytest.mark.parametrize("relative", [False, True])
def test_prediction_rows_distinguish_source_clock_and_boot_packet_validity(tmp_path, relative):
    data = Windows(check_manifest(fixture(tmp_path, relative=relative)), CFG, "test")
    model = VirtualOdometer(CFG)
    pred = predict_dataset(model, data, "cpu", calibrated=True)
    assert pred.data_time_base.eq(RELATIVE if relative else BOOT).all()
    assert pred.valid.eq(not relative).all()


@pytest.mark.parametrize("relative", [False, True])
@pytest.mark.parametrize("scope", ["drive", "entry", "both"])
def test_custom_prediction_resolves_explicit_drive_or_entry_clock(tmp_path, relative, scope):
    data = Windows(check_manifest(fixture(tmp_path, relative=relative)), CFG, "test")
    drive = data.drives[0]
    clock = RELATIVE if relative else BOOT
    drive.pop("data_time_base", None)
    drive["entry"].pop("data_time_base", None)
    if scope in {"drive", "both"}:
        drive["data_time_base"] = clock
    if scope in {"entry", "both"}:
        drive["entry"]["data_time_base"] = clock
    pred = predict_dataset(VirtualOdometer(CFG), data, "cpu", calibrated=True)
    assert pred.data_time_base.eq(clock).all()
    assert pred.valid.eq(not relative).all()


@pytest.mark.parametrize("calibrated", [False, True])
def test_custom_prediction_missing_clock_never_defaults_to_boot(tmp_path, calibrated):
    data = Windows(check_manifest(fixture(tmp_path, relative=False)), CFG, "test")
    data.drives[0].pop("data_time_base")
    data.drives[0]["entry"].pop("data_time_base", None)
    with pytest.raises(ValueError, match="explicit|clock|time_base"):
        predict_dataset(VirtualOdometer(CFG), data, "cpu", calibrated=calibrated)


@pytest.mark.parametrize("direct,entry", [(BOOT, RELATIVE), (RELATIVE, BOOT)])
def test_custom_prediction_rejects_conflicting_clock_declarations(tmp_path, direct, entry):
    data = Windows(check_manifest(fixture(tmp_path)), CFG, "test")
    data.drives[0]["data_time_base"] = direct
    data.drives[0]["entry"]["data_time_base"] = entry
    with pytest.raises(ValueError, match="conflict|clock|time_base"):
        predict_dataset(VirtualOdometer(CFG), data, "cpu", calibrated=True)


@pytest.mark.parametrize("scope", ["drive", "entry"])
@pytest.mark.parametrize("clock", [None, "wall_clock"])
def test_custom_prediction_rejects_unsupported_explicit_clock(tmp_path, scope, clock):
    data = Windows(check_manifest(fixture(tmp_path, relative=False)), CFG, "test")
    drive = data.drives[0]
    drive.pop("data_time_base", None)
    drive["entry"].pop("data_time_base", None)
    (drive if scope == "drive" else drive["entry"])["data_time_base"] = clock
    with pytest.raises(ValueError, match="clock|time_base"):
        predict_dataset(VirtualOdometer(CFG), data, "cpu", calibrated=True)


def checkpoint_fixture(tmp_path, *, relative=True, calibrated=True):
    path = fixture(tmp_path / "data", relative=relative)
    manifest = check_manifest(path)
    model = VirtualOdometer(CFG)
    checkpoint = {"config": CFG, "model": model.state_dict(), "uncertainty_calibrated": calibrated,
                  "run": {"manifest_snapshot": manifest, "source_type": "synthetic", "train_mean_speed_mps": 2.}}
    ckpt_path = tmp_path / "fixture.pt"
    torch.save(checkpoint, ckpt_path)
    return path, ckpt_path


def test_relative_evaluation_keeps_source_clock_and_suppresses_filter_packets(tmp_path, monkeypatch):
    from driftlock_ml.evaluate import main
    path, checkpoint = checkpoint_fixture(tmp_path)
    out = tmp_path / "evaluation"
    monkeypatch.setattr(sys, "argv", ["evaluate", "--checkpoint", str(checkpoint), "--manifest", str(path),
                                     "--out", str(out), "--device", "cpu"])
    main()
    assert not list(out.glob("measurements_*.csv"))
    assert list(out.glob("speed_*.png"))
    pred = pd.read_csv(out / "predictions.csv")
    assert pred.data_time_base.eq(RELATIVE).all() and not pred.valid.any()
    assert pred.t.iloc[0] == pytest.approx(13.45)
    report = json.loads((out / "metrics.json").read_text())
    assert report["data_clock"]["data_time_base"] == RELATIVE
    assert report["filter_packet_export"]["written"] is False
    assert "recording" in report["filter_packet_export"]["reason"]
    assert report["plot_time_axis"] == "Window centre (seconds since recording start)"
    assert report["config"]["time_base"] == BOOT
    assert report["all_windows"]["n"] == len(pred)


def test_relative_sigma_calibration_preserves_offline_provenance_and_runtime_contract(tmp_path, monkeypatch):
    from driftlock_ml.calibrate import main
    path, checkpoint = checkpoint_fixture(tmp_path, calibrated=False)
    out = tmp_path / "calibrated.pt"
    monkeypatch.setattr(sys, "argv", ["calibrate", "--checkpoint", str(checkpoint), "--manifest", str(path),
                                     "--out", str(out), "--device", "cpu"])
    main()
    fitted = torch.load(out, weights_only=True)
    assert fitted["uncertainty_calibrated"] is True
    assert fitted["config"]["time_base"] == BOOT
    assert fitted["calibration"]["data_clock"]["data_time_base"] == RELATIVE


def test_converter_retains_relative_clock_audit_and_manifest_candidate(tmp_path):
    source_manifest = fixture(tmp_path / "source")
    manifest = json.loads(source_manifest.read_text())
    mapping = {**CONTRACT_FIELDS, **UNITS, **manifest["drives"][0],
               "frame": "phone", "acceleration_includes_gravity": True,
               "columns": {k: k for k in pd.read_csv(tmp_path / "source/drive_0.csv").columns},
               "separator": ","}
    mapping_path = tmp_path / "mapping.json"
    mapping_path.write_text(json.dumps(mapping))
    outputs = tmp_path / "canonical"
    for i in range(4):
        source = tmp_path / f"source/drive_{i}.csv"
        before = source.read_bytes()
        result = subprocess.run([sys.executable, "tools/convert_csv.py", "--input", str(source),
                                 "--mapping", str(mapping_path), "--output", str(outputs / source.name)],
                                cwd=REPO, capture_output=True, text=True)
        assert result.returncode == 0, result.stderr
        assert source.read_bytes() == before
        audit = json.loads((outputs / source.name).with_suffix(".import.json").read_text())
        assert audit["data_time_base"] == RELATIVE
        assert audit["sensor_reference_clock"] == "shared_recording_clock"
        np.testing.assert_array_equal(pd.read_csv(outputs / source.name).timestamp_s, pd.read_csv(source).timestamp_s)
    candidate = tmp_path / "candidate.json"
    result = subprocess.run([sys.executable, "tools/build_manifest.py", "--data-dir", str(outputs),
                             "--out", str(candidate), "--source-type", "real", "--label-source", "unreviewed",
                             "--data-time-base", RELATIVE], cwd=REPO, capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    generated = json.loads(candidate.read_text())
    assert generated["data_time_base"] == RELATIVE
    assert all(e["data_time_base"] == RELATIVE for e in generated["drives"])
    assert generated["review_status"] != "APPROVED_FOR_TRAINING"
