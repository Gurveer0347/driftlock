"""Independent speed evaluation. No fabricated positioning/trajectory accuracy."""
from __future__ import annotations
import argparse
from pathlib import Path
import numpy as np
import pandas as pd
import torch
from torch.utils.data import DataLoader
from .data import (BOOT_TIME_BASE, Windows, check_manifest, check_evaluation_provenance,
                   data_clock_metadata, data_time_base)
from .utils import device_for, load_model, speed_metrics, write_json
from .config import CONTRACT_FIELDS, MAX_SPEED_MPS, VALIDATION_LIMITS


def predict_dataset(model, dataset, device, batch_size=512, calibrated=False):
    # The public helper also accepts custom datasets. Legacy manifest defaults
    # belong in the validated loader, never at this prediction boundary.
    clocks = []
    for drive in dataset.drives:
        declarations = [data_time_base(metadata) for metadata in (drive, drive.get("entry", {}))
                        if "data_time_base" in metadata]
        if not declarations:
            raise ValueError("Prediction drives require explicit data_time_base in drive or entry metadata.")
        if any(clock != declarations[0] for clock in declarations[1:]):
            raise ValueError("Conflicting drive/entry data_time_base at the prediction boundary.")
        clocks.append(declarations[0])
    rows = []
    model.eval()
    with torch.inference_mode():
        for x,y,d,e in DataLoader(dataset, batch_size=batch_size, shuffle=False, num_workers=0):
            pred = model(x.to(device)).cpu().numpy()
            if not np.isfinite(pred).all() or np.any(pred[:,1] <= 0):
                raise ValueError("Nonfinite speed or nonpositive sigma; preserve failure rather than omit bad predictions.")
            for k in range(len(y)):
                drive = dataset.drives[int(d[k])]
                clock = clocks[int(d[k])]
                rows.append({"drive": drive["name"], "group_id": drive["entry"]["group_id"],
                             "data_time_base": clock,
                             "t": float(drive["target_t"][int(e[k])]),
                             "available_t": float(drive["t"][int(e[k])]),
                             "valid": bool(clock == BOOT_TIME_BASE and calibrated and abs(pred[k,0]) < MAX_SPEED_MPS),
                             "reference_speed_mps": float(y[k]), "speed_mps": float(pred[k,0]),
                             "sigma_mps": float(pred[k,1])})
    return pd.DataFrame.from_records(rows)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--checkpoint", required=True)
    p.add_argument("--manifest", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--split", choices=["val", "test"], default="test")
    p.add_argument("--device", default="auto", choices=["auto", "cpu", "mps", "cuda"])
    args = p.parse_args()
    torch.set_num_threads(4)
    dev = device_for(args.device)
    model, ckpt = load_model(args.checkpoint, dev)
    manifest = check_manifest(args.manifest)
    check_evaluation_provenance(manifest, ckpt, args.split)
    for key in ["frame", "acceleration_includes_gravity", *CONTRACT_FIELDS]:
        if manifest.get(key) != ckpt["config"][key]:
            raise ValueError(f"Frame contract mismatch: {key}")
    dataset = Windows(manifest, ckpt["config"], args.split, ckpt["config"]["eval_stride"])
    pred = predict_dataset(model, dataset, dev, calibrated=ckpt.get("uncertainty_calibrated", False))
    out = Path(args.out)
    if out.exists() and any(out.iterdir()):
        raise FileExistsError("Use a fresh evaluation directory; preserve previous results.")
    out.mkdir(parents=True, exist_ok=True)
    pred.to_csv(out/"predictions.csv", index=False)
    def metric(frame):
        return speed_metrics(frame.reference_speed_mps, frame.speed_mps, frame.sigma_mps)
    baseline = ckpt["run"]["train_mean_speed_mps"]
    by_drive = {str(name):metric(frame) for name,frame in pred.groupby("drive")}
    boot_clock = data_time_base(manifest) == BOOT_TIME_BASE
    time_axis = "Window centre (seconds since boot)" if boot_clock else "Window centre (seconds since recording start)"
    report = {
        "source_type": manifest["source_type"], "split": args.split,
        "config": ckpt["config"], "validation": VALIDATION_LIMITS,
        "data_clock": data_clock_metadata(manifest), "plot_time_axis": time_axis,
        "timestamp_meaning": "t=window centre; available_t=newest input, both on data_clock. Honour delayed availability; no runtime clock mapping is inferred.",
        "filter_packet_export": {"written": boot_clock, "reason": "reviewed boot-clock packet timestamps" if boot_clock
                                 else "recording-relative timestamps cannot be exported as boot-clock filter packets"},
        "fusion_valid_windows": int(pred.valid.sum()),
        "metrics_policy": "All finite reference-valid predictions retained, including fusion-invalid offline clocks, out-of-range or uncalibrated outputs.",
        "road_type_breakdown": {"status":"not_available", "reason":"No reviewed time-aligned road-type annotations supplied."},
        "uncertainty_calibrated": ckpt.get("uncertainty_calibrated", False),
        "all_windows": metric(pred), "by_drive": by_drive,
        "macro_drive_rmse_mps": float(np.mean([x["rmse_mps"] for x in by_drive.values()])),
        "constant_training_mean_baseline": speed_metrics(pred.reference_speed_mps, np.full(len(pred), baseline)),
        "reference_note": "Errors are versus supplied labels. Phone GNSS is not precision inertial ground truth.",
        "warning": "Overlapping windows and held 1 Hz labels are correlated. Not independent trials. Not position drift.",
    }
    write_json(out/"metrics.json", report)
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    for i,(name,frame) in enumerate(pred.groupby("drive")):
        # Retain all data in CSV/metrics; decimate only the illustration for readability.
        plot = frame.iloc[::max(1, len(frame)//2000)]
        fig, ax = plt.subplots(figsize=(11, 4.5))
        ax.plot(plot.t, plot.reference_speed_mps*3.6, label="Reference vehicle-forward speed")
        ax.plot(plot.t, plot.speed_mps*3.6, label="Virtual Odometer")
        ax.fill_between(plot.t, (plot.speed_mps-plot.sigma_mps)*3.6,
                        (plot.speed_mps+plot.sigma_mps)*3.6, alpha=.15, label="One-sigma model interval")
        title = f"{name} | {args.split} drive | speed, not position"
        if manifest["source_type"] == "synthetic":
            title = "SYNTHETIC SOFTWARE TEST ONLY | " + title
        ax.set(title=title, xlabel=time_axis, ylabel="Speed (km/h)")
        ax.legend(loc="best"); ax.grid(alpha=.25); fig.tight_layout()
        fig.savefig(out/f"speed_{i+1:02d}.png", dpi=150); plt.close(fig)
        # Dedicated filter packet excludes evaluation-only fields and never publishes variance.
        if boot_clock:
            packet = frame[["t", "speed_mps", "sigma_mps", "valid"]].copy()
            packet.loc[~packet.valid, "speed_mps"] = 0.0
            packet.loc[~packet.valid, "sigma_mps"] = VALIDATION_LIMITS["invalid_sigma_mps"]
            packet.to_csv(out/f"measurements_{i+1:02d}.csv", index=False)
    print("Configuration:", ckpt["config"], "Validation:", VALIDATION_LIMITS)
    print(f"{args.split} RMSE={report['all_windows']['rmse_mps']:.4f} m/s, MAE={report['all_windows']['mae_mps']:.4f} m/s")
    print(f"Reports and speed plots: {out}")

if __name__ == "__main__":
    main()
