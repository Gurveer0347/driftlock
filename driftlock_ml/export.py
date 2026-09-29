"""Portable handoff, plus an optional verified PyTorch -> LiteRT Linux conversion."""
from __future__ import annotations
import argparse
import platform
from pathlib import Path
import shutil
import sys
import numpy as np
import torch
from .config import MAX_BOOT_TIME_S, VALIDATION_LIMITS
from .model import ExportableOdometer
from .portable_inference import PortableOdometerPredictor, validate_timestamps
from .utils import load_model, write_json, sha256, environment


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--checkpoint", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--format", choices=["portable", "litert"], default="portable")
    p.add_argument("--sample-npy", help="Optional real raw [1,T,6] or [T,6] test window.")
    p.add_argument("--sample-timestamps-npy", help="Required with --sample-npy: actual [T] seconds-since-boot timestamps.")
    args = p.parse_args()
    out = Path(args.out)
    if out.exists() and (not out.is_dir() or any(out.iterdir())):
        raise FileExistsError("Refusing to overwrite a nonempty export target; choose a new directory.")
    if bool(args.sample_npy) != bool(args.sample_timestamps_npy):
        raise ValueError("A supplied sample requires --sample-timestamps-npy; timestamps require --sample-npy.")
    torch.set_num_threads(4)
    model, ckpt = load_model(args.checkpoint, "cpu")
    config = ckpt["config"]
    export_model = ExportableOdometer(model).eval()
    w = int(config["window_samples"])
    rng = np.random.default_rng(100)
    if args.sample_npy:
        sample = np.load(args.sample_npy, allow_pickle=False).astype(np.float32)
        if sample.shape == (w,6): sample = sample[None]
        if sample.shape != (1,w,6) or not np.isfinite(sample).all():
            raise ValueError(f"Sample must be finite [1,{w},6].")
        provenance = "user_supplied_window"
        times = validate_timestamps(np.load(args.sample_timestamps_npy, allow_pickle=False), w, MAX_BOOT_TIME_S)
        time_provenance = "user_supplied_actual_window_timestamps_seconds_since_boot"
    else:
        # Golden vectors are for numerical parity only, not research evidence.
        mean = model.input_mean.numpy(); std = model.input_std.numpy()
        sample = (mean + .1*std*rng.normal(size=(1,w,6))).astype(np.float32)
        provenance = "synthetic_conversion_test_vector_not_a_drive"
        times = np.arange(w, dtype=np.float64) / float(config["sample_hz"])
        time_provenance = "synthetic_boot_clock_for_numerical_parity_only"
    x = torch.from_numpy(sample)
    with torch.inference_mode():
        expected = model(x).numpy()
        primitive = export_model(x).numpy()
    np.testing.assert_allclose(primitive, expected, atol=2e-5, rtol=2e-5)
    # Test multiple independent vectors, not just the sample saved for handoff.
    for _ in range(8):
        v = torch.from_numpy((model.input_mean.numpy()+model.input_std.numpy()*rng.normal(size=(1,w,6))).astype(np.float32))
        with torch.inference_mode():
            np.testing.assert_allclose(model(v).numpy(), export_model(v).numpy(), atol=2e-5, rtol=2e-5)
    out.mkdir(parents=True, exist_ok=True)
    export_path = out/"virtual_odometer.pt2"
    program = torch.export.export(export_model, (x,))
    torch.export.save(program, export_path)
    loaded = torch.export.load(export_path).module()
    with torch.inference_mode():
        np.testing.assert_allclose(loaded(x).numpy(), expected, atol=2e-5, rtol=2e-5)
    np.save(out/"golden_input.npy", sample)
    np.save(out/"golden_output.npy", expected)
    np.save(out/"golden_timestamps_s.npy", times)
    sample.astype("<f4").tofile(out/"golden_input_f32.bin")
    shutil.copyfile(args.checkpoint, out/"checkpoint.pt")
    portable_script = out / "portable_inference.py"
    shutil.copyfile(Path(__file__).with_name("portable_inference.py"), portable_script)
    contract = {
        "version": config["model_contract_version"],
        "conventions_version": config["conventions_version"],
        "model_contract_version": config["model_contract_version"],
        "time_base": config["time_base"], "speed_semantics": config["speed_semantics"],
        "input_shape": [1,w,6], "input_dtype": "float32", "channel_order": ["ax","ay","az","gx","gy","gz"],
        "sample_hz": config["sample_hz"], "time_order": "oldest_to_newest",
        "prediction_timestamp": config["prediction_timestamp"],
        "timestamp_formula": "(timestamps_s[0] + timestamps_s[-1]) / 2",
        "timestamp_input_shape": [w], "timestamp_input_dtype": "float64",
        "measurement_available_at": "newest_input_sample",
        "nominal_centre_delay_s": (w - 1) / (2 * float(config["sample_hz"])),
        "fusion_timing": "Delayed centre estimate; fusion must handle measurement time separately from availability time.",
        "acceleration_units": "m/s^2",
        "gyro_units": "rad/s", "frame": config["frame"],
        "acceleration_includes_gravity": config["acceleration_includes_gravity"],
        "normalization": "embedded_in_model_do_not_normalize_twice",
        "input_mean": model.input_mean.flatten().tolist(), "input_std": model.input_std.flatten().tolist(),
        "recurrent_state": "reset_for_each_complete_window",
        "output_shape": [1,2], "output_order": ["speed_mps", "sigma_mps"],
        "filter_packet_fields": ["t", "speed_mps", "sigma_mps", "valid"],
        "speed_meaning": "Signed vehicle-forward speed along vehicle x in m/s; reversing is negative.",
        "uncertainty_meaning": "Gaussian predictive standard deviation of speed residuals in m/s; not variance or position confidence.",
        "uncertainty_calibrated": ckpt.get("uncertainty_calibrated",False),
        "validity": "Use portable_inference.py boundary checks. Invalid or uncalibrated full windows return centre t, speed 0, positive sentinel sigma and valid=false.",
        "validation": dict(VALIDATION_LIMITS),
        "golden_vector": {
            "purpose": "numerical_parity_only_not_research_evidence", "input_provenance": provenance,
            "timestamps_provenance": time_provenance,
            "raw_tensor_output": "golden_output.npy contains raw [1,2] output for numerical parity, including for invalid packets.",
            "filter_packet_output": "golden_output.json contains exactly the validated four-field filter packet.",
        },
        "correlation_warning": "Overlapping outputs and IMU-derived fusion inputs are correlated; do not assume independent measurements.",
        "source_type": ckpt["run"]["source_type"], "safe_for_driver_guidance": False,
        "config": config, "checkpoint_sha256": sha256(args.checkpoint),
        "portable_inference_sha256": sha256(portable_script),
        "portable_sha256": sha256(export_path), "litert_status": "not_converted",
    }
    write_json(out/"contract.json", contract)
    packet = PortableOdometerPredictor(out).predict_window(sample[0], times)
    write_json(out/"golden_output.json", packet)
    write_json(out/"export_environment.json", {**environment(), "config": config, "command": sys.argv,
                                               "checkpoint_sha256": sha256(args.checkpoint)})
    if args.format == "litert":
        if platform.system() != "Linux":
            raise RuntimeError("Portable handoff saved. LiteRT Torch documents Linux conversion. Use a separate Linux environment for --format litert.")
        try:
            import litert_torch
        except ImportError as e:
            raise RuntimeError("Install requirements-export-linux.txt in a separate Linux environment.") from e
        with torch.no_grad():
            converted = litert_torch.convert(export_model, (x,))
        # Require real interpreter parity BEFORE calling the conversion successful.
        converted_out = np.asarray(converted(x), dtype=np.float32)
        np.testing.assert_allclose(converted_out, expected, atol=1e-4, rtol=1e-4)
        for _ in range(4):
            vx = torch.from_numpy((model.input_mean.numpy()+.5*model.input_std.numpy()*rng.normal(size=(1,w,6))).astype(np.float32))
            with torch.inference_mode(): reference = model(vx).numpy()
            np.testing.assert_allclose(np.asarray(converted(vx)), reference, atol=1e-4, rtol=1e-4)
        path = out/"virtual_odometer.tflite"
        converted.export(str(path))
        contract["litert_status"] = "converted_and_desktop_parity_passed_android_test_pending"
        contract["litert_sha256"] = sha256(path)
        write_json(out/"contract.json", contract)
        print(f"LiteRT conversion + desktop parity passed: {path}. Android parity/latency are still required.")
    else:
        print("Portable export parity passed. .pt2 is NOT a .tflite file and cannot be relabelled.")
    print(f"Handoff files: {out}")

if __name__ == "__main__":
    main()
