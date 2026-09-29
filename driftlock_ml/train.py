"""Train with drive-group splits. No GNSS value enters the model input."""
from __future__ import annotations
import argparse
import time
from pathlib import Path
import numpy as np
import torch
from torch import nn
from torch.utils.data import DataLoader
from .data import Windows, check_manifest, data_clock_metadata
from .model import VirtualOdometer
from .utils import device_for, environment, read_json, seed_all, sha256, write_json
from .config import CONTRACT_FIELDS, VALIDATION_LIMITS, validate_config


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--manifest", required=True)
    p.add_argument("--config", default="configs/baseline.json")
    p.add_argument("--out", required=True)
    p.add_argument("--device", default="auto", choices=["auto", "cpu", "mps", "cuda"])
    p.add_argument("--epochs", type=int)
    p.add_argument("--batch-size", type=int)
    p.add_argument("--threads", type=int, default=4)
    args = p.parse_args()
    torch.set_num_threads(args.threads)
    cfg = read_json(args.config)
    if args.epochs is not None:
        cfg["epochs"] = args.epochs
    if args.batch_size is not None:
        cfg["batch_size"] = args.batch_size
    if cfg["epochs"] < 1 or cfg["batch_size"] < 1:
        raise ValueError("epochs and batch_size must be positive.")
    validate_config(cfg)
    print("Configuration:", cfg, "Validation:", VALIDATION_LIMITS, flush=True)
    seed_all(cfg["seed"])
    dev = device_for(args.device)
    manifest = check_manifest(args.manifest)
    for key in ["frame", "acceleration_includes_gravity", *CONTRACT_FIELDS]:
        if manifest.get(key) != cfg[key]:
            raise ValueError(f"Manifest/config mismatch in {key}. Do not silently change sensor frames.")
    out = Path(args.out)
    if out.exists() and any(out.iterdir()):
        raise FileExistsError("Run directory is not empty. Use a new --out to preserve experiments.")
    out.mkdir(parents=True, exist_ok=True)
    train = Windows(manifest, cfg, "train", cfg["train_stride"])
    val = Windows(manifest, cfg, "val", cfg["eval_stride"])
    generator = torch.Generator().manual_seed(cfg["seed"])
    train_loader = DataLoader(train, batch_size=cfg["batch_size"], shuffle=True,
                              num_workers=0, generator=generator)
    val_loader = DataLoader(val, batch_size=cfg["batch_size"], num_workers=0)
    model = VirtualOdometer(cfg)
    model.set_normalization(*train.normalization())
    model = model.to(dev)
    # Probe the actual Conv + GRU forward/backward path before committing a long run.
    try:
        probe = next(iter(train_loader))[0][:2].to(dev)
        model(probe).sum().backward()
        model.zero_grad(set_to_none=True)
    except (RuntimeError, NotImplementedError) as exc:
        raise RuntimeError(f"{dev} failed the model probe. Rerun with --device cpu. Original: {exc}") from exc
    optimizer = torch.optim.AdamW(model.parameters(), lr=cfg["learning_rate"], weight_decay=cfg["weight_decay"])
    nll = nn.GaussianNLLLoss(eps=1e-6)
    warmup = min(cfg["warmup_epochs"], max(0, cfg["epochs"]-1))
    train_y = np.asarray([train.drives[d]["y"][e] for d,e in train.index])
    baseline = float(train_y.mean())
    run_meta = {"config": cfg, "validation": VALIDATION_LIMITS, "environment": environment(), "device": str(dev),
                "data_clock": data_clock_metadata(manifest),
                "source_type": manifest["source_type"], "manifest_sha256": sha256(args.manifest),
                "manifest_snapshot": manifest, "train_mean_speed_mps": baseline,
                "train_windows": len(train), "validation_windows": len(val),
                "parameter_count": sum(p.numel() for p in model.parameters()),
                "status": "research_baseline_not_field_validated"}
    write_json(out/"run.json", run_meta)
    write_json(out/"config.json", cfg)
    print(f"Device={dev} | Parameters={run_meta['parameter_count']:,} | train={len(train):,}, val={len(val):,}", flush=True)
    if manifest["source_type"] == "synthetic":
        print("SYNTHETIC SOFTWARE TEST. These results are NOT SIH performance evidence.", flush=True)
    best, stale, history = float("inf"), 0, []
    for epoch in range(1, cfg["epochs"]+1):
        start = time.perf_counter()
        model.train()
        loss_sum = count = 0
        for x, y, _, _ in train_loader:
            x,y = x.to(dev), y.to(dev)
            optimizer.zero_grad(set_to_none=True)
            mu,sigma = model(x).unbind(-1)
            scale = model.speed_scale
            mean_loss = ((mu-y)/scale).square().mean()
            loss = mean_loss if epoch <= warmup else nll(mu/scale, y/scale, (sigma/scale).square()) + .1*mean_loss
            if not torch.isfinite(loss):
                raise FloatingPointError("Non-finite loss. Check data and learning rate.")
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), cfg["gradient_clip"])
            optimizer.step()
            loss_sum += float(loss.detach().cpu()) * len(y)
            count += len(y)
        model.eval()
        sq = n = 0
        with torch.inference_mode():
            for x,y,_,_ in val_loader:
                prediction = model(x.to(dev))[:,0].cpu()
                sq += float((prediction-y).square().sum())
                n += len(y)
        rmse = (sq/n)**.5
        record = {"config": cfg, "epoch": epoch, "training_loss": loss_sum/count, "validation_rmse_mps": rmse,
                  "seconds": time.perf_counter()-start, "stage": "mean_warmup" if epoch<=warmup else "gaussian_nll"}
        history.append(record)
        print(f"Epoch {epoch:02d}/{cfg['epochs']} | loss={record['training_loss']:.5f} | val RMSE={rmse:.4f} m/s | {record['seconds']:.1f}s", flush=True)
        if epoch > warmup and rmse < best:
            best, stale = rmse, 0
            state = {k:v.detach().cpu() for k,v in model.state_dict().items()}
            checkpoint = {"config": cfg, "model": state, "epoch": epoch,
                          "validation_rmse_mps": rmse, "run": run_meta,
                          "uncertainty_calibrated": False}
            tmp = out/"best.tmp"
            torch.save(checkpoint, tmp)
            tmp.replace(out/"best.pt")
        elif epoch > warmup:
            stale += 1
        write_json(out/"history.json", history)
        if stale >= cfg["patience"]:
            print("Early stopping: validation did not improve.")
            break
    print(f"Saved {out/'best.pt'}. Next: calibrate, then evaluate on untouched test drives.")

if __name__ == "__main__":
    main()
