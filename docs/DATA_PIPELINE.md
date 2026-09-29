# Reviewed paired-data preparation, native contract v2

This path implements `driftlock-ml-2.0-causal-latest` from
[ARCHITECTURE.md](ARCHITECTURE.md). Historical v1.1 loaders, centre targets,
research labels and checkpoints retain their existing meanings.

The implementation has three boundaries: metadata-only proposal; verification
of a separately reviewed, hash-bound source mapping; then deterministic causal
canonicalization. It never creates source approval or training approval. Tests
use explicitly synthetic fixtures and establish software behavior only.

The implementation sequence is: test missing/false evidence and reserved-data
exclusion; implement the review gate; test timestamp and reference edge cases;
implement causal segmentation and latest-target windows; exercise the real
metadata-only proposal; run focused and full Python tests. Ownership is confined
to `driftlock_ml/paired_data.py`, its tests and this document.

## Current source gate

The [64-pair audit](../reports/DATA_AUDIT.md) supports indicated vehicle speed in
km/h. It does not yet establish raw phone XYZ settings, physical sensor timing,
signed or forward-only direction intervals, bounded cross-device synchronization
or independently reviewed original-trip identities. No real reviewed mapping is
provided, no canonical real-data training rows are produced, and no training
is launched by this work.

The fixed proposed groups are Vta/Vtb for training, Vfa for validation, Vw for
calibration, and reserved A/B/D groups for a later frozen final evaluation.
These are conservative source groups, not newly certified independent trips.
Final payloads are never read by either proposal or preparation.

## Commands and outputs

Run from the project directory. The completed real **metadata-only** command is:

```text
.venv/bin/python -m driftlock_ml.paired_data propose --inventory data/raw/iovnbd/metadata/all_e_pairs_20260920/acquisition_manifest.json --out logs/native_20260920_01/paired_preparation_20260920_01/proposal_v2
```

Its manifest contains 64 proposals, zero canonical drives and
`source_payloads_read=0`. Its contract/frame/unit fields describe the desired
output contract; they do not assert that the source already has those meanings.
Status is `REQUIRES_SOURCE_REVIEW`, and `training_approved=false`. Existing output
directories are never overwritten. Use a new directory for another invocation.

For a later **actual, separately reviewed** mapping, the exact command form is:

```sh
.venv/bin/python -m driftlock_ml.paired_data prepare \
  --inventory data/raw/iovnbd/metadata/all_e_pairs_20260920/acquisition_manifest.json \
  --review "$REVIEWED_PAIRED_SOURCE_MAPPING" \
  --out "$FRESH_PAIRED_OUTPUT"
```

The two variables must contain the real reviewed JSON path and an unused output
directory. No usable real review JSON currently exists. Missing or conflicting
review evidence raises an error before any raw CSV is opened. Preparation never
fills missing evidence, infers offsets, changes signs from gear codes, or marks
a review approved. It emits `manifest.json`, `manifest.sha256`, and one canonical
CSV for each surviving phone segment. Status remains
`PREPARED_REQUIRES_TRAINING_REVIEW` and `training_approved=false`; a separate
training approval must bind the resulting manifest hash. No runtime packets are
written from recording-relative timestamps.

## Required source review schema

The input mapping uses schema `driftlock-paired-source-review-v1` and contract
`driftlock-ml-2.0-causal-latest`. It must be authored after a real source review;
typing the required status does not establish physical facts. The software
checks structure, consistency and document hashes, not the truth of a person's
claims. The only currently passing example is the explicitly synthetic fixture
inside `tests/test_paired_data.py`; it must never be copied into a real approval.

| Field | Required meaning |
|---|---|
| `status`, `reviewer`, `reviewed_at` | `SOURCE_MAPPING_REVIEWED`, actual reviewer identity and review date |
| `evidence` | Dictionary of evidence IDs to project-relative document `path` and SHA-256; documents must resolve inside `docs/` or `reports/` |
| `independence_evidence` | Nonempty list of those evidence IDs for original-trip and split independence |
| `preprocessing` | `method=causal_hold_segment_start_v1`, `sample_hz=10`, `window_samples=20`, IMU gap/age limits both 0.15 s, and explicit reference gap/age limits |
| `drives` | Dictionary keyed by the selected Driver E IDs, such as `vta2`; final or unknown families are rejected |
| Each drive's `group_id`, `original_trip_id`, `original_group_evidence` | Reviewed original identity bound to the fixed conservative group; a trip/hash cannot cross splits |
| Each drive's `phone_source_sha256`, `reference_source_sha256` | Exact payload hashes from the verified acquisition inventory |
| `phone.encoding`, `phone.channels` | Explicit UTF-8 or Latin-1 and six exact original headers mapped to `ax,ay,az,gx,gy,gz`; no implicit column positions, permutations or extra features |
| `phone.frame`, units, gravity flag | `raw_phone_android_xyz`, `m/s2`, `rad/s`, and `acceleration_includes_gravity=true` |
| `phone.raw_axes_evidence`, `units_gravity_evidence`, `sensor_timing_evidence` | Evidence IDs establishing exporter settings, physical channel correspondence and sensor timestamp/age meaning |
| `reference.column`, `unit`, `unit_evidence` | Exact original field, explicit `km/h` or `m/s`, and unit evidence; only the reviewed km/h field is divided by 3.6 |
| `reference.quantity`, `label_semantics` | `signed_vehicle_forward_speed` with documented signed values, or `vehicle_indicated_speed_magnitude` with `verified_forward_only`; magnitude cannot be declared signed |
| `reference.direction_evidence`, `validity_evidence` | Evidence for direction and reference accuracy/freshness/outages |
| `reference.valid_rows`, `forward_rows` | Ordered, nonoverlapping zero-based `[start, stop)` source-row intervals; forward intervals are mandatory for forward-only labels |
| Each drive's `sections` | Ordered paired `phone_rows` and `reference_rows` intervals, each with a separately evidenced clock relationship |
| Each section's `phone_clock`, `reference_clock` | Exact `column`, `unit` (`ms` or `s`), named `source_origin`, numerical `offset_to_recording_s`, and clock `evidence` IDs |
| Each section's `sync` | Documented `method`, `residual_bound_s` and evidence IDs; residual must be nonnegative and smaller than the reference age limit |

The clock equation is exactly `recording_seconds = source_value * unit_scale +
offset_to_recording_s`. The offsets are supplied by review, never optimized or
inferred from the data. Whole-file equality of row counts, matching medians, or
the phrase “manually synchronized” without source-specific residual evidence
does not satisfy these fields. Unreviewed metadata fails closed.

## Causality, segmentation and reference masks

Source rows stay in their recorded order. Within each reviewed section, clocks
must be finite, nonnegative and strictly increasing. Duplicate/backward clocks
reject that section and preparation; they are never sorted. A reviewer can
instead identify separate source-row sections and document each origin/offset.
The code does not invent a reset origin or assign a later clock epoch.

A phone gap above 0.15 s or a nonfinite six-channel row ends a segment and clears
window history. The next valid row anchors a new grid at its unchanged reviewed
time; no missing interval is filled. Higher-rate phone sources (median interval
below 0.075 s) are rejected because this path contains no anti-alias filter.
Within a segment, the 10 Hz grid holds only the latest source row whose time is
at or before the tick, with a maximum age of 0.15 s. There is no interpolation,
gravity removal, rotation, normalization or hidden GRU-state processing here.

At a target tick, reference selection uses the latest row whose **latest possible
time**, `reference_time + residual_bound`, is at or before the tick. Its oldest
possible age, `tick - (reference_time - residual_bound)`, must pass both the
reviewed reference-age and reference-gap limits. Invalid, nonfinite or
unknown-direction latest rows remain invalid; the code does not fall back to an
older valid row. Known source reference gaps are logged, and the age cap prevents
holding a value through them. Reference gaps do not erase otherwise valid phone
IMU history; they make unsupported targets unusable.

Canonical CSVs contain the six raw features plus `timestamp_s`, `speed_mps`,
`label_valid`, `label_reason`, source row IDs/times, `available_t`, and the reviewed
section ID. Invalid targets have a reason and a blank/NaN speed. Reference fields
and clocks are never appended to the six-feature model tensor. Network windows
are oldest first, float32 `[20,6]`, and target the **last** window timestamp.
`prediction_timestamp` is exactly `latest_sample`, matching the native gate.

`available_t` records the source callback tick that made the resampled row
available, which can follow its target. It is a recording-clock processing
schedule, not measured Android wall-clock latency or a fabricated boot clock.
The no-future source inequality is strict. The native assembler's floating-point
tolerance must be reconciled against the same golden preprocessing fixtures
before claiming offline/Android parity; this implementation makes no such claim.

## APIs and provenance

- `propose_dataset(inventory_path, out)` reads only the acquisition JSON.
- `prepare_dataset(inventory_path, review_path, out, root=ROOT)` validates every
  selected mapping before reading payloads, verifies bytes/hashes, and writes a
  fresh staging dataset only after all selected pairs succeed.
- `canonicalize_pair(phone_path, reference_path, review, drive_id, root=ROOT)`
  returns segment DataFrames and an audit. For this direct API, the review must
  contain only that drive plus its verified acquisition `inventory_pair` record.
  It applies the same source review and reserved-path guards.
- `audit_pair(...)` returns that same audit without writing canonical files.
- `causal_windows(frame, window_samples=20)` yields only usable latest-target
  windows from one canonical segment, keeping source IDs and labels outside
  `imu`. It does not approve the frame or fit statistics.

Canonical paths resolve relative to the generated manifest directory. Evidence
document paths explicitly resolve relative to the project root. The manifest
embeds source mappings, source/review hashes, preprocessing configuration,
per-segment hashes, original trips, fixed splits and per-pair rejection counts.
Its `reserved_final_test_groups` lists `review_A_S1_S2`, `review_A_S3abc`,
`review_A_S4`, `review_B_M`, and `review_D_Y1`; there are no final-test paths in
`drives`. Canonicalization itself cannot certify independence or authorization.

No normalization, model fitting, validation selection, sigma calibration or
final evaluation occurs in this module. Historical loaders reject its versioned
contract, and the new mobile trainer must separately verify preparation hashes
and explicit training approval before using it.

## Verification evidence

`.venv/bin/python -m pytest tests/test_paired_data.py -q` completed with **29
passed, exit 0**. Regressions cover absent evidence, duplicate/backward clocks,
reviewed reset sections, stale/invalid/future references, residual bounds,
forward masks, unchanged signed values, missing/corrupt original bytes, LFS
pointers, GPS feature rejection, group/trip leakage, reserved paths, nonfinite
IMU, gap segmentation and invariance of past windows under future-value changes.
Every source fixture is synthetic and explicitly labelled as a software check.

The full `.venv/bin/python -m pytest -q` run completed with **555 passed, 15
warnings, exit 0**, in 28.13 s; the warnings are captured in its log. Logs and the
real metadata-only proposal are under
`logs/native_20260920_01/paired_preparation_20260920_01/`. The proposal command
completed with exit 0, 64 proposed pairs, zero canonical segments and zero raw
or final-test payload reads. Its earlier proposal directory is preserved.

These checks establish software behavior and artifact integrity. They do not
establish source eligibility, physical navigation accuracy, model effectiveness,
Android preprocessing parity or device performance.
