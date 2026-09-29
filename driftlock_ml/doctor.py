"""Confirm your actual machine can execute the CNN+GRU forward and backward pass."""
from __future__ import annotations
import argparse
import json
import time
import torch
from .model import VirtualOdometer
from .utils import device_for, environment, read_json


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--device", default="auto", choices=["auto", "cpu", "mps", "cuda"])
    p.add_argument("--config", default="configs/baseline.json")
    a = p.parse_args()
    torch.set_num_threads(4)
    print(json.dumps(environment(), indent=2))
    cfg = read_json(a.config)
    print("Configuration:", json.dumps(cfg, sort_keys=True))
    dev = device_for(a.device)
    m = VirtualOdometer(cfg).to(dev)
    x = torch.randn(8, cfg["window_samples"], 6, device=dev)
    start = time.perf_counter()
    y = m(x)
    y.sum().backward()
    if dev.type == "mps": torch.mps.synchronize()
    if dev.type == "cuda": torch.cuda.synchronize()
    print(f"PASS: CNN+GRU forward/backward on {dev}; output {list(y.shape)}")
    print(f"Parameters: {sum(p.numel() for p in m.parameters()):,}")
    print(f"Single cold probe: {time.perf_counter()-start:.3f}s (not a training-time benchmark)")

if __name__ == "__main__":
    main()
