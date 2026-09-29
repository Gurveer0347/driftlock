from __future__ import annotations
import json
import math
import platform
import random
import hashlib
from pathlib import Path
import numpy as np
import torch


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, allow_nan=False), encoding="utf-8")


def sha256(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def seed_all(seed):
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)
    # Exact numerical reproducibility across MPS/CUDA/CPU is not guaranteed.
    if hasattr(torch.backends, "cudnn"):
        torch.backends.cudnn.benchmark = False
        torch.backends.cudnn.deterministic = True


def device_for(request="auto"):
    if request == "auto":
        if torch.cuda.is_available():
            return torch.device("cuda")
        if torch.backends.mps.is_available():
            return torch.device("mps")
        return torch.device("cpu")
    if request == "mps" and not torch.backends.mps.is_available():
        raise RuntimeError("MPS unavailable. Use native arm64 Python on Mac or --device cpu.")
    if request == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("CUDA unavailable. Install the appropriate NVIDIA PyTorch build or use cpu.")
    return torch.device(request)


def environment():
    return {
        "python": platform.python_version(), "platform": platform.platform(),
        "architecture": platform.machine(), "torch": str(torch.__version__),
        "numpy": str(np.__version__), "mps_available": torch.backends.mps.is_available(),
        "cuda_available": torch.cuda.is_available(),
    }


def load_model(path, device="cpu"):
    from .model import VirtualOdometer
    # Only load trusted checkpoints. weights_only avoids arbitrary pickle objects.
    checkpoint = torch.load(path, map_location="cpu", weights_only=True)
    from .config import validate_config
    validate_config(checkpoint.get("config", {}))
    model = VirtualOdometer(checkpoint["config"])
    model.load_state_dict(checkpoint["model"])
    return model.to(device).eval(), checkpoint


def speed_metrics(y, mu, sigma=None):
    y, mu = np.asarray(y, float), np.asarray(mu, float)
    if y.shape != mu.shape or y.size == 0:
        raise ValueError("Metrics need matching non-empty arrays.")
    e = mu - y
    result = {
        "n": int(y.size), "rmse_mps": float(np.sqrt(np.mean(e**2))),
        "mae_mps": float(np.mean(np.abs(e))), "bias_mps": float(np.mean(e)),
        "p95_absolute_error_mps": float(np.quantile(np.abs(e), .95)),
    }
    if sigma is not None:
        s = np.maximum(np.asarray(sigma, float), 1e-5)
        result.update({
            "mean_sigma_mps": float(np.mean(s)),
            "nominal_95pct_interval_coverage": float(np.mean(np.abs(e) <= 1.96*s)),
            "gaussian_nll": float(np.mean(np.log(s) + .5*(e/s)**2 + .5*math.log(2*math.pi))),
        })
    return result
