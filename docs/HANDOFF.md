# ML handoff under team conventions v1.1

Use [CONVENTIONS.md](CONVENTIONS.md), section 8.2, and the active
`driftlock-ml-1.1-centre-forward` contract in [DATA_CONTRACT.md](DATA_CONTRACT.md).
Gurveer authorized this target/interface change. Historical latest-sample,
nonnegative-magnitude checkpoints cannot be relabeled or reused as compliant.
This document is a delivery checklist; no real model, mobile result or navigation
accuracy is established by its examples.

## To Antarjot: exact measurement boundary

```python
from driftlock_ml.infer import OdometerPredictor

predictor = OdometerPredictor("handoff/portable/checkpoint.pt", device="cpu")
# imu_window: actual raw phone (20, 6) float32 samples, oldest first.
# timestamps_s: their actual hardware-derived (20,) times in seconds since boot.
measurement = predictor.predict_window(imu_window, timestamps_s)
# Exactly: t, speed_mps, sigma_mps, valid.
```

Only use packets whose `valid` is true. `speed_mps` is signed along vehicle x:
positive forward, negative reverse. `sigma_mps` is positive predictive standard
deviation in m/s. Do not expect variance or extra diagnostics at this boundary.
A model trained on verified-forward-only data supports only forward-condition
claims; negative predictions do not establish reverse accuracy.

`t` is `(timestamps_s[0] + timestamps_s[-1]) / 2`, on the shared boot clock.
At 10 Hz with 20 samples, the packet becomes available 0.95 s after that centre.
The model is trained on centre labels, using only its complete available window.
Later-than-centre samples in the window are intentional delayed context. Never
apply the packet early in replay or pretend it describes the newest input time.

Antarjot must validate delayed-state update/replay handling together with Avi's
availability schedule and the conventions merge order. The ML API does not
implement rewind, state buffering or a navigation filter. A centre timestamp
alone does not make delayed fusion correct.

`push_resampled(t, channels)` is an alternative for a stream already resampled
from hardware times. It returns `valid=false` during warmup, after gaps, and for
unusable windows; invalid clock values and malformed input shapes raise. Warmup packets use the current event
time until a full window exists. Invalid complete windows retain their centre
when the clock is valid. Invalid speed/sigma fields are placeholders and must be
discarded. Uncalibrated checkpoints also produce invalid filter packets.

Phone-to-vehicle alignment, gravity removal in navigation, heading, position,
ShadowDR and recovery belong to Antarjot. Raw phone axes/gravity remain unchanged
at the model input. Shared IMU data and overlapping windows create correlated
errors; delayed updates, rate/gating and covariance policy need end-to-end testing.
The learned sigma is not positional confidence or proof of novel-road detection.

## To Avi: reproducible independent evaluation

Deliver the selected compatible checkpoint/config, source and manifest hashes,
review evidence, training history, calibration provenance and untouched-test
predictions/metrics. Keep source units, signed/verified-forward-only label evidence,
source-relative or boot-clock provenance, excluded intervals and original-group
independence clear. A verified boot-clock offset is not required for offline
learning on explicitly reviewed recording-relative data.
Do not infer device hold-out from unknown physical-device identities.

For reviewed boot-clock evaluation, each per-drive `measurements_NN.csv` contains exactly
`t,speed_mps,sigma_mps,valid`. Its `predictions.csv` companion includes centre `t`,
newest-input `available_t`, `speed_mps`, reference labels and diagnostic context.
Preserve the availability companion when scheduling learned measurements; sorting
only by target time would introduce information before it was available.

For approved recording-relative offline evaluation, `predictions.csv` retains
that `data_time_base`, its unchanged centre/availability times and raw model
outputs. Its `valid` field is false for boot-clock filter use, and no measurement
CSV is produced. Metrics, plots, calibration evidence and speed-only blackout
diagnostics retain the offline clock. Do not feed these relative timestamps to
the navigation packet API or invent a boot offset. This does not alter the
checkpoint's runtime interface when actual boot-timestamped phone inputs are used.

Evaluate speed RMSE, MAE, signed bias, p95 absolute error, nominal interval coverage,
per-drive errors and baselines. Compare the constant training-mean and held
last-preoutage-speed baselines with the same delays and valid intervals. Actual-dt
integrated speed difference is still a speed diagnostic, not position error.

Where reviewed road-type and motion annotations exist, report their residuals,
coverage, sample/group counts and limitations. Include stops, acceleration/braking,
turns and reverse only where the references support them. Unknown road types must
remain unknown. Measure whether sigma grows with error on unfamiliar roads;
the learned head and global calibration alone do not establish this property.

These behaviour diagnostics need real data and are pending while source semantics
are blocked. Navigation stationary/alignment, straight-drive/NHC and map matcher
convergence tests in the team document belong to integration with the navigation
engine; their historical synthetic claims are not verified by this repository.

## To Vishisht: runtime and parity artifacts

Receive the compatible model, contract.json, effective config, golden_input.npy,
golden_input_f32.bin, golden_timestamps_s.npy, golden_output.json, hashes and
portable_inference.py. A verified mobile handoff additionally needs the actual
virtual_odometer.tflite. Savneet owns display/UI, not capture or uncertainty logic.

The portable example requires no training imports:

```bash
.venv/bin/python handoff/portable/portable_inference.py \
  --bundle handoff/portable \
  --input-npy handoff/portable/golden_input.npy \
  --timestamps-npy handoff/portable/golden_timestamps_s.npy
```

The project wrapper must agree with it:

```bash
.venv/bin/python -m driftlock_ml.infer \
  --checkpoint handoff/portable/checkpoint.pt \
  --input-npy handoff/portable/golden_input.npy \
  --timestamps-npy handoff/portable/golden_timestamps_s.npy
```

Before Android acceptance:

1. Run the same golden sensor and timestamp vectors; compare the two tensor
   outputs and exact four-field packet semantics within the saved tolerance.
2. Use TYPE_ACCELEROMETER including gravity and TYPE_GYROSCOPE, raw phone axes.
   Use SensorEvent.timestamp / 1e9 and Location.getElapsedRealtimeNanos() / 1e9
   on one boot clock; wall-clock time is incompatible.
3. Verify causal resampling from actual event times, oldest-first 20x6 float32
   windows, embedded normalization and reset GRU state per complete window.
4. Verify centre timestamps, actual packet availability, positive sigma and
   invalid warmup/gap/malformed/uncalibrated handling. Do not substitute variance.
5. Measure warmed-up batch-one latency, memory and battery on the actual phone,
   separately from the model's inherent 0.95 s window-centre delay.
6. Validate integration after mount changes and with the navigation engine's
   delayed update/gating policy. A golden vector does not test real sensor semantics.

## Conversion and packaging

Train and perform portable export on the Mac. A checkpoint .pt and torch.export
.pt2 are not .tflite models. Renaming a file is not conversion. Do not install the
Linux export requirements in the Mac .venv. Optional approved remote conversion
is described in [COLAB_EXPORT_GUIDANCE.md](COLAB_EXPORT_GUIDANCE.md); verify current
official converter requirements when executing that separate task.

Use fresh export directories. Supplied real parity windows require both
`--sample-npy` and matching `--sample-timestamps-npy`; do not invent a boot-time
vector for private/source data. Synthetic golden vectors are labelled numerical
checks and are never driving-performance evidence.

Package only matching source/config/checkpoint, contract, parity artifacts,
measured reports and reproduction commands. Exclude .venv, secrets and original
private trips. Remote upload requires specific approval. Historical runs and source
reports remain unchanged and must not be mixed with the new contract's results.

The 10 September aim is a reproducible real speed model with honest held-out
results and a clear interface. Source semantics remain a prerequisite. Real-road
usefulness, reverse generalization, delayed-filter behaviour, actual conversion
and Android parity are separate acceptance gates; do not mark pending work done.
