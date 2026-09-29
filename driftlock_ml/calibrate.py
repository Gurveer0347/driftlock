"""Fit one uncertainty multiplier on a separate calibration split, NEVER the test set."""
from __future__ import annotations
import argparse
from pathlib import Path
import numpy as np
import torch
from .data import Windows, check_manifest, check_evaluation_provenance, data_clock_metadata
from .evaluate import predict_dataset
from .utils import device_for, load_model, speed_metrics, write_json
from .config import CONTRACT_FIELDS, VALIDATION_LIMITS


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--checkpoint", required=True)
    p.add_argument("--manifest", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--device", default="auto", choices=["auto", "cpu", "mps", "cuda"])
    args = p.parse_args()
    torch.set_num_threads(4)
    dev = device_for(args.device)
    model, ckpt = load_model(args.checkpoint, dev)
    if ckpt.get("uncertainty_calibrated"):
        raise ValueError("Already calibrated. Start from the original best.pt.")
    manifest = check_manifest(args.manifest)
    check_evaluation_provenance(manifest, ckpt, "calibration")
    for key in ["frame", "acceleration_includes_gravity", *CONTRACT_FIELDS]:
        if manifest.get(key) != ckpt["config"][key]:
            raise ValueError(f"Contract mismatch: {key}")
    ds = Windows(manifest, ckpt["config"], "calibration", ckpt["config"]["eval_stride"])
    pred = predict_dataset(model, ds, dev)
    residual = pred.reference_speed_mps.to_numpy() - pred.speed_mps.to_numpy()
    sigma = pred.sigma_mps.to_numpy()
    factor = float(np.sqrt(np.mean((residual/np.maximum(sigma, 1e-5))**2)))
    factor = max(factor, .01)
    if not np.isfinite(factor) or factor > 100:
        raise ValueError("Uncertainty scaling is unstable. Inspect the model rather than publish its confidence.")
    model.uncertainty_scale.mul_(factor)
    ckpt["model"] = {k:v.detach().cpu() for k,v in model.state_dict().items()}
    ckpt["uncertainty_calibrated"] = True
    ckpt["calibration"] = {"split": "calibration", "sigma_multiplier": factor,
                           "note": "Global Gaussian scale fit, not a safety guarantee or out-of-distribution detector.",
                           "source_type": manifest["source_type"],
                           "data_clock": data_clock_metadata(manifest),
                           "config": ckpt["config"], "validation": VALIDATION_LIMITS,
                           "entries": [e for e in manifest["drives"] if e["split"] == "calibration"],
                           "metrics": speed_metrics(pred.reference_speed_mps, pred.speed_mps, sigma*factor)}
    out = Path(args.out); out.parent.mkdir(parents=True, exist_ok=True)
    if out.exists():
        raise FileExistsError(f"Refusing to overwrite {out}")
    torch.save(ckpt, out)
    write_json(out.with_suffix(".calibration.json"), ckpt["calibration"])
    print("Configuration:", ckpt["config"], "Validation:", VALIDATION_LIMITS)
    print(f"Sigma multiplier={factor:.4f}. Saved {out}. Now evaluate the untouched test set.")

if __name__ == "__main__":
    main()
