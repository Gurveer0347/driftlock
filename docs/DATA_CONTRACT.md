# DRIFTLOCK ML data and inference contract

Active contract: `driftlock-ml-1.1-centre-forward`, adopting the user-approved
[team conventions v1.1](CONVENTIONS.md). Old latest-sample magnitude checkpoints
are incompatible and must be retrained; changing their timestamp is insufficient.

## Sensors, clocks and target

Input is float32 `[batch,20,6]` at 10 Hz, oldest first, with only
`[ax,ay,az,gx,gy,gz]`. Acceleration is raw phone-frame m/s² including gravity;
gyro is raw phone-frame rad/s. Standard gravity is 9.80665 m/s². Do not remove
gravity, rotate phone axes, apply screen-axis remapping, or add GNSS values to the
network. Antarjot owns alignment inside the navigation engine.

Runtime timestamps are finite, nonnegative, strictly increasing seconds since device boot,
with the configured screen requiring values below 10,000,000 s.
Android uses `SensorEvent.timestamp / 1e9` and
`Location.getElapsedRealtimeNanos() / 1e9`, never wall-clock `Location.getTime()`.
Keep the same boot clock across streams. Do not subtract the first timestamp and
publish a drive-relative value as time since boot. A source elapsed-time column
needs documented clock identity/offset before it can be published as a boot-clock
packet. It does not need that offset merely to support offline learning.

Gurveer approved this offline/runtime distinction on 10 September 2026.
Offline canonical data may declare `data_time_base=seconds_since_recording_start`
at both manifest and drive level. Each such drive also requires
`sensor_reference_clock=shared_recording_clock`, reviewed
`sensor_reference_clock_evidence`, and `time_base_evidence` describing the source
origin and intervals. Keep source elapsed seconds unchanged; do not subtract the
first time or invent a since-boot offset. All times must still be finite,
nonnegative, strictly increasing seconds below 10,000,000; resets and gaps remain
review conditions. A common exported row clock does not prove a held GPS fix is
fresh: reference validity needs its own evidence.

The shared model configuration retains `time_base=seconds_since_boot`; that field
describes the runtime interface, while `data_time_base` describes the offline CSV.
Legacy manifests that omit `data_time_base` retain their reviewed boot-clock
meaning. Explicit global/drive clock conflicts are rejected. Training, calibration
and evaluation retain the source-clock evidence in their artifacts. Relative
evaluation rows carry their declared clock and `valid=false` for filter use;
no `measurements_NN.csv` packet files are written for them. Their finite,
reference-valid predictions still contribute to offline metrics. New prediction
CSVs always retain `data_time_base`. The blackout diagnostic accepts older CSVs
without that column under their historical boot-clock assumption; removing the
column from a relative-time CSV loses provenance and is unsupported. That tool
produces diagnostic metrics only, never runtime packets.

Use actual hardware times to causally sample-and-hold onto the 10 Hz grid; a
sampling request is not proof of uniform arrival. Reject clock resets and gaps
that make a window unusable. Higher-rate input requires a separately agreed causal
anti-aliasing design shared by offline and runtime preprocessing.

For each completed window, the target time is
`(timestamps_s[0] + timestamps_s[-1]) / 2`. A 20-sample regular window spans 1.9 s,
so its centre is 0.95 s before its newest sample. No measured sample sits exactly
at that midpoint; do not choose either middle index as a substitute. The training
label uses the latest source row at or before the centre and requires that row's
reference to pass its validity mask and freshness review. Do not skip an invalid
row to recover an older label, use a future reference, or interpolate through an outage.

The full completed window includes samples after the target centre. This delayed
context is explicitly authorized. No sample later than the newest actually
available input may be used. Record both target and availability times in evaluation;
never make the estimate available at its earlier target time in replay. Delayed
fusion and the navigation engine's replay ordering require integration validation.

The CNN remains causal within its input sequence; one 32-state GRU resets per
window. Normalization is fitted on training only and embedded in the model.
Android must not repeat it or normalize using an entire held-out drive.

## Reference labels

One canonical CSV represents one original recording or a documented derived part.
Required header:

```csv
timestamp_s,ax,ay,az,gx,gy,gz,speed_mps,label_valid
```

| Column | Meaning |
|---|---|
| timestamp_s | Unchanged seconds on the reviewed `data_time_base`: boot or explicitly recording-relative |
| ax, ay, az | Raw phone acceleration including gravity, m/s² |
| gx, gy, gz | Angular rate about the same raw phone X/Y/Z axes, rad/s |
| speed_mps | Signed velocity along vehicle x: forward positive, reverse negative |
| label_valid | Reviewed binary 1/0 reference usability mask at that row |

Every drive must declare one of these label semantics, with evidence:

- `vehicle_forward_signed`: source direction and vehicle-forward sign are documented.
- `verified_forward_only`: documented forward-driving subset; unknown-direction
  and reverse intervals are excluded. Evaluation claims remain forward-only.

GPS speed magnitude alone cannot establish sign or prove forward travel. Never
negate labels based on a guessed axis, manufacture reverse examples from GNSS
magnitude, or interpret a model's negative output as validated reversing ability.
`label_valid` concerns reference quality; it is distinct from runtime `valid`.

Review source units, timestamps, speed availability/accuracy, per-fix age, known
outages and jumps before making a validity mask on a derived staging copy. A finite
speed and satellite count do not establish fresh reference data. Repeated labels
are correlated, and an IMU row timestamp does not refresh a held GNSS fix.
Do not use `--assume-valid-labels` for a reportable experiment.

Numeric gates screen for clock mistakes, absolute speed below 60 m/s, gyro norm
at most 5 rad/s, and plausible acceleration/gravity where reviewed stationary
labels permit. They do not prove source units, frame, sign, or speed accuracy.
Preserve original rows; investigate rejected data rather than silently sorting,
rescaling, changing labels or dropping rows to improve scores.

## IO-VNBD source review remains blocked

The inspected synchronized files have XYZ headers and approximately 10 Hz IMU,
but actual speed units conflict with `GPS SPEED (Kmh)` in several files. Do not
assume `/3.6` from that header. The logger's phone/world-frame setting and author
transformations, speed-fix freshness/outage mask, source-clock intervals/alignment, and
original-session independence require source evidence. Unsynchronized yaw/pitch/roll
headers also do not establish XYZ mapping. Confirm exact headers and encoding per
source; do not guess column indices or signed axis permutations.

See [the acquisition report](../reports/DATA_REPORT.md) and its linked evidence.
That report records the earlier contract at inspection time; the current interface
is defined here. Raw files remain unchanged. Authoritative LFS size/SHA-256 checks
establish download integrity, not eligibility for training.

## Manifest and split approval

The manifest's shared contract metadata must agree with the model config:

```json
{
  "conventions_version": "1.1",
  "model_contract_version": "driftlock-ml-1.1-centre-forward",
  "prediction_timestamp": "window_centre",
  "speed_semantics": "vehicle_forward_signed",
  "time_base": "seconds_since_boot",
  "data_time_base": "seconds_since_boot",
  "frame": "phone",
  "acceleration_includes_gravity": true,
  "time_unit": "s",
  "acceleration_unit": "m/s2",
  "gyro_unit": "rad/s",
  "speed_unit": "m/s"
}
```

This is a field reference, not a usable or approved manifest. Add `source_type`,
`review_status`, `review_evidence` and `drives` from actual evidence. For real
training, `review_status` must be `APPROVED_FOR_TRAINING` and `review_evidence`
must identify the documented review. Every drive needs `path`, `group_id`, `split`,
`label_source`, `label_semantics`, `label_semantics_evidence`, and
`time_base_evidence`. Record physical `device_id` only when supported; an unknown
unit or shared phone model does not support device hold-out.

For reviewed recording-relative data, replace only `data_time_base` with
`seconds_since_recording_start` and declare it on every drive. Supply the shared
recording-clock fields described above. Keep model/runtime `time_base` unchanged.
The converter preserves these fields in its import audit; the manifest generator
requires explicit `--data-time-base seconds_since_recording_start` and still
produces a candidate requiring review. No metadata example approves actual data.

The generator proposes files/groups/splits only. It does not approve real data,
infer signed labels, verify clocks, or discover hidden duplicate trips. Filling
an approval string without the required review is not approval.

Assign original groups before windowing. Require separate original groups for
train, val, calibration and test. Related parts, synchronized/unsynchronized
representations and paired sensor exports stay in one group/split. Review routes,
overlap and near-duplicate recordings; file hashes detect only exact duplicates.
Four groups is the software minimum, not strong evidence of generalization.

Fit normalization on train, choose the model on val, then fit the sigma multiplier
on calibration. Use untouched test only after selection is frozen. Preserve
source/config/manifest hashes, the exact command and review status for every run.
Gurveer approved the corrected preparation/small-baseline plan on 10 September;
unresolved source semantics still cannot enter an approved training manifest.

## Filter boundary and uncertainty

A usable packet has exactly these fields:

| Field | Meaning |
|---|---|
| t | Centre of the complete window, seconds since boot |
| speed_mps | Signed vehicle-forward speed, m/s |
| sigma_mps | Positive predictive standard deviation, m/s |
| valid | Boolean indicating a usable estimate for that window |

`predict_window(imu, timestamps_s)` requires a `(20,6)` raw window and a `(20,)`
timestamp vector. `push_resampled(t, channels)` accepts an already uniformly
resampled event with its actual timestamp. Warmup, timing gaps, nonfinite/range-invalid
IMU samples and uncalibrated/unusable estimates produce `valid=false`. Invalid
clock vectors and malformed input shapes raise an error; no fabricated timestamp
is emitted. Invalid packets use a placeholder speed and positive sigma sentinel;
consumers must discard them. Warmup uses the current event time until a complete
window exists; invalid full windows use their centre time when the clock is valid.

Publish no variance field or optional/null timestamp. The network's second head
is input-dependent sigma; a global calibration multiplier does not turn it into
a fixed uncertainty. Neither `valid=true` nor calibration is an accuracy guarantee.
Measure coverage and residuals by drive and road type where reviewed annotations
exist. Unknown road types must remain unknown. Higher sigma on unfamiliar roads,
reverse performance and navigation effectiveness remain empirical tests.

Overlapping estimates and the fusion engine share IMU data. Antarjot owns delayed
update handling, correlation-aware gating and navigation covariance. No speed-only
metric is position drift, map-lock accuracy, or an Android readiness claim.
