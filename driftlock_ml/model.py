"""Compact causal CNN + GRU. Inputs are ONLY six inertial channels.

A [batch, time, 6] input contains oldest to newest measurements. Prediction
refers to the window centre and is emitted after its newest sample arrives.
CNN padding is causal within the window; centre targets use delayed context. Hidden state
is RESET for each complete window; do not carry it across overlapping windows.
"""
from __future__ import annotations
import copy
import torch
from torch import nn
from torch.nn import functional as F
from .config import validate_config


class VirtualOdometer(nn.Module):
    def __init__(self, config: dict):
        super().__init__()
        validate_config(config)
        c = int(config.get("conv_channels", 32))
        h = int(config.get("hidden_size", 32))
        self.window_samples = int(config.get("window_samples", 20))
        self.sigma_floor = float(config.get("sigma_floor_mps", 0.1))
        self.register_buffer("input_mean", torch.zeros(1, 1, 6))
        self.register_buffer("input_std", torch.ones(1, 1, 6))
        self.register_buffer("speed_scale", torch.tensor(float(config.get("speed_scale", 10.0))))
        self.register_buffer("uncertainty_scale", torch.tensor(1.0))
        self.conv1 = nn.Conv1d(6, c, kernel_size=5)
        self.conv2 = nn.Conv1d(c, c, kernel_size=3)
        self.gru = nn.GRU(c, h, num_layers=1, batch_first=True)
        self.projection = nn.Linear(h, 32)
        self.mean_head = nn.Linear(32, 1)
        self.sigma_head = nn.Linear(32, 1)
        nn.init.constant_(self.sigma_head.bias, -1.5)

    def set_normalization(self, mean, std) -> None:
        self.input_mean.copy_(torch.as_tensor(mean, dtype=torch.float32).reshape(1, 1, 6))
        self.input_std.copy_(torch.as_tensor(std, dtype=torch.float32).reshape(1, 1, 6).clamp_min(1e-4))

    def features(self, raw: torch.Tensor) -> torch.Tensor:
        # Normalization is INSIDE the exported model. Android must not repeat it.
        x = ((raw - self.input_mean) / self.input_std).transpose(1, 2)
        x = F.relu(self.conv1(F.pad(x, (4, 0))))
        x = F.relu(self.conv2(F.pad(x, (2, 0))))
        return x.transpose(1, 2)

    def heads(self, hidden: torch.Tensor) -> torch.Tensor:
        z = F.relu(self.projection(hidden))
        # Vehicle-forward component: reverse motion is negative.
        mu = self.mean_head(z) * self.speed_scale
        sigma = (F.softplus(self.sigma_head(z)) * self.speed_scale + self.sigma_floor)
        sigma = sigma * self.uncertainty_scale
        return torch.cat((mu, sigma), dim=-1)

    def forward(self, raw: torch.Tensor) -> torch.Tensor:
        _, h = self.gru(self.features(raw))
        return self.heads(h[-1])


class ExportableOdometer(nn.Module):
    """The SAME one-layer PyTorch GRU, expressed with primitive operations.

    Avoids relying on a fused GRU converter operator. Static window length.
    Gate equations match torch.nn.GRU, including PyTorch's reset-gate placement.
    This is an export representation, not a different trained architecture.
    """
    def __init__(self, source: VirtualOdometer):
        super().__init__()
        self.base = copy.deepcopy(source).cpu().eval()
        self.window_samples = source.window_samples
        self.hidden_size = source.gru.hidden_size

    def forward(self, raw: torch.Tensor) -> torch.Tensor:
        features = self.base.features(raw)
        h = torch.zeros_like(features[:, 0, :1]).expand(-1, self.hidden_size)
        g = self.base.gru
        for t in range(self.window_samples):
            gi = F.linear(features[:, t, :], g.weight_ih_l0, g.bias_ih_l0)
            gh = F.linear(h, g.weight_hh_l0, g.bias_hh_l0)
            ir, iz, inn = gi.chunk(3, dim=-1)
            hr, hz, hn = gh.chunk(3, dim=-1)
            reset = torch.sigmoid(ir + hr)
            update = torch.sigmoid(iz + hz)
            new = torch.tanh(inn + reset * hn)
            h = (1.0 - update) * new + update * h
        return self.base.heads(h)
