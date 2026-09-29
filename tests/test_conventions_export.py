"""Portable handoff contract checks; all fixtures are numerical parity only."""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys

import numpy as np
import pytest
import torch

from driftlock_ml import export
from driftlock_ml.model import VirtualOdometer
from driftlock_ml.infer import OdometerPredictor


CFG = {
    "sample_hz": 10.0, "window_samples": 20, "conv_channels": 32,
    "hidden_size": 32, "speed_scale": 10.0, "sigma_floor_mps": 0.1,
    "frame": "phone", "acceleration_includes_gravity": True,
    "conventions_version": "1.1",
    "model_contract_version": "driftlock-ml-1.1-centre-forward",
    "prediction_timestamp": "window_centre",
    "speed_semantics": "vehicle_forward_signed", "time_base": "seconds_since_boot",
}


def save_checkpoint(path, calibrated=True, mean_bias=-0.3):
    model = VirtualOdometer(CFG).eval()
    with torch.no_grad():
        for parameter in model.parameters():
            parameter.zero_()
        model.mean_head.bias.fill_(mean_bias)
        model.sigma_head.bias.fill_(-1.5)
    model.set_normalization([0, 0, 9.80665, 0, 0, 0], [1] * 6)
    torch.save({"config": dict(CFG), "model": model.state_dict(),
                "uncertainty_calibrated": calibrated,
                "run": {"source_type": "synthetic", "purpose": "numerical_parity_only"}}, path)


def run_export(monkeypatch, checkpoint, out, *extra):
    monkeypatch.setattr(sys, "argv", ["export", "--checkpoint", str(checkpoint),
                                     "--out", str(out), *map(str, extra)])
    export.main()


@pytest.fixture(scope="module")
def bundle(tmp_path_factory):
    root = tmp_path_factory.mktemp("portable_contract")
    checkpoint = root / "numerical_parity.pt"
    save_checkpoint(checkpoint)
    with pytest.MonkeyPatch.context() as patch:
        run_export(patch, checkpoint, root / "bundle")
    return root / "bundle"


def portable_class(bundle):
    script = bundle / "portable_inference.py"
    assert script.is_file(), "Bundle must include standalone portable inference."
    spec = importlib.util.spec_from_file_location("standalone_odometer", script)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.PortableOdometerPredictor


def imu_window():
    imu = np.zeros((20, 6), dtype=np.float32)
    imu[:, 2] = 9.80665
    return imu


def test_export_golden_packet_has_exact_filter_fields_and_centre_time(bundle):
    packet = json.loads((bundle / "golden_output.json").read_text())
    assert set(packet) == {"t", "speed_mps", "sigma_mps", "valid"}
    times = np.load(bundle / "golden_timestamps_s.npy", allow_pickle=False)
    assert times.shape == (20,)
    assert packet["t"] == pytest.approx((times[0] + times[-1]) / 2)
    assert packet["speed_mps"] == pytest.approx(-3.0)
    assert packet["sigma_mps"] > 0
    assert packet["valid"] is True
    output = np.load(bundle / "golden_output.npy", allow_pickle=False)
    assert output.shape == (1, 2)
    np.testing.assert_allclose(output[0], [packet["speed_mps"], packet["sigma_mps"]])


def test_export_contract_identifies_signed_centre_delay_and_synthetic_purpose(bundle):
    contract = json.loads((bundle / "contract.json").read_text())
    assert contract["version"] == "driftlock-ml-1.1-centre-forward"
    assert contract["prediction_timestamp"] == "window_centre"
    assert contract["time_base"] == "seconds_since_boot"
    assert contract["speed_semantics"] == "vehicle_forward_signed"
    assert contract["output_shape"] == [1, 2]
    assert contract["filter_packet_fields"] == ["t", "speed_mps", "sigma_mps", "valid"]
    assert contract["nominal_centre_delay_s"] == pytest.approx(0.95)
    assert contract["measurement_available_at"] == "newest_input_sample"
    assert "synthetic" in contract["golden_vector"]["timestamps_provenance"]
    assert contract["golden_vector"]["purpose"] == "numerical_parity_only_not_research_evidence"
    assert contract["config"] == CFG


def test_export_supplied_imu_requires_actual_timestamps(tmp_path, monkeypatch):
    checkpoint = tmp_path / "model.pt"
    save_checkpoint(checkpoint)
    sample = tmp_path / "sample.npy"
    np.save(sample, imu_window())
    with pytest.raises(ValueError, match="timestamp"):
        run_export(monkeypatch, checkpoint, tmp_path / "handoff", "--sample-npy", sample)
    assert not (tmp_path / "handoff").exists()


def test_export_supplied_timestamps_preserve_boot_offset(tmp_path, monkeypatch):
    checkpoint = tmp_path / "model.pt"
    save_checkpoint(checkpoint)
    sample, times_file = tmp_path / "sample.npy", tmp_path / "times.npy"
    times = 713.2 + np.arange(20) / 10
    np.save(sample, imu_window())
    np.save(times_file, times)
    out = tmp_path / "handoff"
    run_export(monkeypatch, checkpoint, out, "--sample-npy", sample,
               "--sample-timestamps-npy", times_file)
    np.testing.assert_array_equal(np.load(out / "golden_timestamps_s.npy"), times)
    packet = json.loads((out / "golden_output.json").read_text())
    assert packet["t"] == pytest.approx(714.15)


def test_export_refuses_nonempty_directory_without_touching_files(tmp_path, monkeypatch):
    checkpoint = tmp_path / "model.pt"
    save_checkpoint(checkpoint)
    out = tmp_path / "handoff"
    out.mkdir()
    sentinel = out / "golden_output.json"
    sentinel.write_text("prior handoff must survive")
    with pytest.raises((ValueError, FileExistsError), match="non.empty|overwrite"):
        run_export(monkeypatch, checkpoint, out)
    assert sentinel.read_text() == "prior handoff must survive"
    assert list(out.iterdir()) == [sentinel]


def test_export_refuses_legacy_checkpoint(tmp_path, monkeypatch):
    checkpoint = tmp_path / "legacy.pt"
    save_checkpoint(checkpoint)
    state = torch.load(checkpoint, weights_only=True)
    for field in ("conventions_version", "model_contract_version", "prediction_timestamp",
                  "speed_semantics", "time_base"):
        del state["config"][field]
    torch.save(state, checkpoint)
    with pytest.raises(ValueError, match="contract|legacy|conventions"):
        run_export(monkeypatch, checkpoint, tmp_path / "handoff")
    assert not (tmp_path / "handoff").exists()


def test_standalone_predicts_signed_speed_with_actual_centre_timestamp(bundle):
    predictor = portable_class(bundle)(bundle)
    packet = predictor.predict_window(imu_window(), 73.0 + np.arange(20) / 10)
    assert set(packet) == {"t", "speed_mps", "sigma_mps", "valid"}
    assert packet["t"] == pytest.approx(73.95)
    assert packet["speed_mps"] == pytest.approx(-3.0)
    assert packet["sigma_mps"] > 0
    assert packet["valid"] is True


@pytest.mark.parametrize("times", [None, np.arange(19) / 10,
    np.arange(20) / 10 + 1_790_000_000, np.arange(20) / 10 - 1,
    [0.0] * 20, [0.0] * 19 + [float("nan")]])
def test_standalone_rejects_missing_wrong_clock_or_invalid_timestamps(bundle, times):
    predictor = portable_class(bundle)(bundle)
    with pytest.raises(ValueError, match="time|clock"):
        predictor.predict_window(imu_window(), times)


@pytest.mark.parametrize("defect", ["nan", "gyro_degrees", "gravity_removed", "gap"])
def test_standalone_invalidates_unusable_full_window(bundle, defect):
    predictor = portable_class(bundle)(bundle)
    sample, times = imu_window(), 73.0 + np.arange(20) / 10
    if defect == "nan":
        sample[3, 2] = np.nan
    elif defect == "gyro_degrees":
        sample[3, 3] = 30
    elif defect == "gravity_removed":
        sample[:, :3] = 0
    else:
        times[10:] += 1
    packet = predictor.predict_window(sample, times)
    assert packet == {"t": pytest.approx((times[0] + times[-1]) / 2),
                      "speed_mps": 0.0, "sigma_mps": 60.0, "valid": False}


def test_standalone_runs_outside_repository_without_training_imports(bundle, tmp_path):
    script = bundle / "portable_inference.py"
    assert script.is_file(), "Bundle must include standalone portable inference."
    result = subprocess.run([sys.executable, "-I", str(script), "--bundle", str(bundle),
                             "--input-npy", str(bundle / "golden_input.npy"),
                             "--timestamps-npy", str(bundle / "golden_timestamps_s.npy")],
                            cwd=tmp_path, capture_output=True, text=True, timeout=60)
    assert result.returncode == 0, result.stderr
    actual = json.loads(result.stdout)
    expected = json.loads((bundle / "golden_output.json").read_text())
    assert actual == expected


def test_standalone_timing_tolerance_matches_main_api_near_boot_clock_limit(bundle):
    times = 9_000_000.0 + np.arange(20) * 0.11
    portable = portable_class(bundle)(bundle)
    main = OdometerPredictor(str(bundle / "checkpoint.pt"))
    expected = main.predict_window(imu_window(), times)
    assert expected["valid"] is True
    assert portable.predict_window(imu_window(), times) == expected


def test_standalone_rejects_modified_model_bytes(bundle, tmp_path):
    copied = tmp_path / "bundle"
    shutil.copytree(bundle, copied)
    with (copied / "virtual_odometer.pt2").open("ab") as handle:
        handle.write(b"unexpected changed model")
    with pytest.raises(ValueError, match="hash"):
        portable_class(copied)(copied)


def test_standalone_rejects_legacy_contract(bundle, tmp_path):
    copied = tmp_path / "bundle"
    shutil.copytree(bundle, copied)
    path = copied / "contract.json"
    contract = json.loads(path.read_text())
    contract["version"] = "driftlock-virtual-odometer-0.1"
    path.write_text(json.dumps(contract))
    with pytest.raises(ValueError, match="contract"):
        portable_class(copied)(copied)


@pytest.mark.parametrize("calibrated,mean_bias", [(False, -0.3), (True, 7.0)])
def test_export_and_standalone_mark_uncalibrated_or_implausible_output_invalid(
        tmp_path, monkeypatch, calibrated, mean_bias):
    checkpoint = tmp_path / "model.pt"
    save_checkpoint(checkpoint, calibrated=calibrated, mean_bias=mean_bias)
    out = tmp_path / "handoff"
    run_export(monkeypatch, checkpoint, out)
    packet = json.loads((out / "golden_output.json").read_text())
    assert packet["valid"] is False
    assert packet["speed_mps"] == 0.0
    assert packet["sigma_mps"] == 60.0
    predictor = portable_class(out)(out)
    assert predictor.predict_window(np.load(out / "golden_input.npy")[0],
                                    np.load(out / "golden_timestamps_s.npy")) == packet
