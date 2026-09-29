# DRIFTLOCK mobile model candidates — v2 causal latest

20 September 2026. **No navigation-approved trained model exists.** The implementation and numerical export checks are available; real training, model selection, calibration and driving accuracy remain blocked by source review. Existing research and v1.1 centre-target artifacts retain their original contracts and cannot be loaded as v2.

## Interface and intended use

`driftlock_ml/mobile.py` implements `driftlock-ml-2.0-causal-latest`. The input is raw phone acceleration **including gravity**, m/s², and phone angular velocity, rad/s, in exactly `ax, ay, az, gx, gy, gz` order. A complete oldest-first 10 Hz window has 20 samples and shape `[batch,20,6]`, float32. No GPS, vehicle reference, gear, speed, position, heading or clock is a model feature.

The two tensor outputs are signed vehicle-forward speed and predictive standard deviation, both in m/s. The runtime packet remains exactly `t, speed_mps, sigma_mps, valid`; tensor output alone is not a navigation measurement. `t` is the newest grid timestamp in seconds since boot. Availability and inference completion are tracked separately. Navigation performs its own age, timing, alignment and innovation checks. An eligible model output does not guarantee positional accuracy.

Reviewed offline examples retain `seconds_since_recording_start` and actual source identities. They are never assigned an invented boot origin or published as navigation packets. A centre-target v1.1 model must be retrained for this contract; changing its timestamp is insufficient. Research contract `driftlock-iovnbd-recorded-reference-v1` is explicitly incompatible.

## Candidate architectures

| Candidate | Structure | Registered parameters | Export |
|---|---|---:|---|
| CNN-GRU | Left-padded causal Conv1d 6→32, kernel 5; 32→32, kernel 3; GRU 32; projection and mean/sigma heads | 11,778 | GRU equations unrolled into primitive ONNX operations |
| CNN | Same first two convolutions; another 32→32 kernel 3 convolution; temporal mean; projection and heads | 8,546 | Static ONNX |
| TCN | Residual causal convolutions, 32 channels, kernel 3, dilation 1/2/4; newest feature; projection and heads | 8,162 | Static ONNX |

Each convolution sees only its present and earlier feature positions. The TCN newest feature has a 15-sample receptive field. CNN-GRU starts from a fresh zero recurrent state for every complete window, including overlapping windows. There is no hidden-state streaming. Parameter counts include registered projection/skip parameters; they are not an accuracy ranking. All candidates use signed mean output and positive `softplus` sigma with a positive floor.

## Preprocessing and statistical separation

The paired canonicalizer and native assembler use a 10 Hz grid anchored to segment start, latest-past hold, and 0.15 s raw IMU gap/age limits. A source time must be **strictly no later than the target**, even within floating-point epsilon. Gap tolerance is not permission to select a future source. The native epsilon regression is in `SensorPipelineTest`; offline/native whole-pipeline parity is not claimed. No anti-alias filter has been validated; higher-rate source preparation requires a reviewed design. The native service uses Android hardware timestamps and keeps receipt timestamps, with a bounded accelerometer/gyro pairing policy.

Normalization is fitted once on canonical training rows, not repeatedly on overlapping windows or on validation/calibration/test. Six means and positive standard deviations are embedded in the model. Android must not normalize again. Reference values are labels only; the latest target must have an eligible reference label. Paired preparation retains source rows, label masks/reasons, source and reference times, and availability.

`load_reviewed_splits` requires a **separate** hash-bound `APPROVED_FOR_TRAINING` review, named reviewer/date/source class, verified evidence documents under `docs/` or `reports/`, the prepared manifest hash, and the complete v2 contract. It validates every split, original trip, group, source hash, canonical path and direction/clock evidence before opening a canonical CSV. Reserved final groups are rejected even if removed from a supplied reservation list. Canonical file hashes are rechecked before use. This verifies identities and integrity; a metadata assertion cannot establish physical source meaning.

`train_candidate` reads train and validation only, uses CPU, bounded epochs/batches, records effective configuration, environment, source identity, epoch times and actual validation calls, and saves the best validation RMSE candidate. It compares training-mean and ridge baselines using the same six-channel windows. A candidate failing either baseline comparison is marked rejected; training never self-selects it. The default one epoch is a timing probe; report the measured time and planned run size before a longer real run. Checkpoints contain model state and provenance, not optimizer/RNG resume state. These runs do not support exact resume.

`calibrate_candidate` requires a separate hash-bound validation selection review and reviewed runtime thresholds. A baseline rejection cannot be overridden. It reads only calibration payloads and fits a positive global sigma multiplier using Gaussian NLL residual scaling, with independent group checks and actual invocation counts. The scale is embedded. Calibration reports before/after NLL and nominal 68%/95% coverage; fitting this scale alone does not prove reliability in unseen conditions. No real calibration has been performed.

## Export and Android admission

`export_selected` obtains actual reviewed validation windows and provenance. Reportable `export_onnx` requires a reviewed real-data checkpoint, frozen selected validation result with positive calls, independent calibration with positive calls, matching embedded sigma scale and reviewed runtime gates. It rejects legacy/research/untrained/synthetic checkpoints before creating an output directory. At least four validation goldens are required. The source data gate remains upstream and unchanged.

Export uses static `[1,20,6] → [1,2]` ONNX opset 17, embedded normalization, reset-per-window recurrent semantics and CPU ONNX Runtime. A bundle includes model and manifest SHA-256 identities, golden raw arrays/timestamps/outputs, a little-endian float32 input, actual invocation counts and parity/latency records. `hashes.json` covers all preceding bundle files. Offline golden timestamps explicitly retain their recording-relative meaning.

A real candidate receives `ANDROID_PARITY_PENDING`; the exporter **never** emits `APPROVED_FOR_NAVIGATION` and never modifies an app allowlist. Untrained checks receive `SOFTWARE_CHECK_ONLY`, `uncertainty_calibrated=false`, and `safe_for_driver_guidance=false`. The production Android loader requires independent allowlisting of both exact model and exact manifest hashes, complete contract checks and calibrated uncertainty. The default allowlist is empty. Missing or rejected models remain unavailable, with no fallback weights.

Android test assets under `android/app/src/androidTest/assets/synthetic_mobile_parity/` contain only an explicitly untrained CNN-GRU and goldens. The instrumentation calls ONNX Runtime directly to test numerical execution; it never loads these weights through the production runtime, changes approval or publishes speed packets. Physical test evidence and precise limits are in [MODEL_BENCHMARK](../reports/MODEL_BENCHMARK.md).

## Current source and evidence limits

The [data audit](../reports/DATA_AUDIT.md) verified 64 non-final Driver E pairs and 192 preserved originals. Vehicle-indicated speed has supported km/h units. This does **not** establish signed vehicle-forward velocity, forward-only intervals, reference accuracy/freshness, phone raw-axis/export settings or defensible synchronization for all sections. Gear codes 6/14 are not decoded by assumption. Phone GPS-speed units are not imported from competitor preprocessing, and wheel radius is not invented.

The current proposal has zero canonical drives and `REQUIRES_SOURCE_REVIEW`; it is not trainable. Reserved A/B/D final groups remain unopened by this work. There are no new real speed metrics, no selected winner, no validated maneuver/device/route generalization, and no final-test results. Verified-forward-only training, if later approved, must retain that narrower label scope and cannot support a reversing claim.

Software checks prove tensor shapes, reset state, causal features, guarded partition/evidence handling, a tiny synthetic training step, sigma-scaling arithmetic, numerical conversion and actual runtime invocation. They do not prove real-world speed accuracy, end-to-end preprocessing parity, sensor-to-output latency, calibration under mounting changes or full navigation drift. Mobile availability requires a separately reviewed trained artifact and physical application-level acceptance.

## Reproducible entry points

```text
.venv/bin/python -m pytest tests/test_mobile.py -q
.venv/bin/python -m driftlock_ml.mobile --software-check --architecture cnn_gru --out runs/NEW_UNTRAINED_SOFTWARE_RUN
```

The command-line entry point is deliberately limited to explicit software checks. Reviewed future training uses `train_candidate(manifest_path, approval, fresh_out, MobileConfig(...), root=project_root, epochs=1)`; calibration and `export_selected` require their separate reviews. No automatic training is launched when review is missing. Software run artifacts and dependency lock snapshot are listed in the benchmark report.

The installed CPU runtime is ONNX Runtime 1.22.0, matching the Android dependency; exporter/checker is ONNX 1.20.1 with PyTorch 2.14.0. The legacy TorchScript export path is explicit and its deprecation/constant-folding warnings are preserved. See the primary [PyTorch ONNX documentation](https://docs.pytorch.org/docs/2.14/onnx.html) and [ONNX Runtime Python guide](https://onnxruntime.ai/docs/get-started/with-python.html).
