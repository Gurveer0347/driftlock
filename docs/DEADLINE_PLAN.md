# Work order toward 10 September 2026

This is a priority order, not a promise of accuracy or completion time. The earlier
8 September schedule has elapsed. Read reports/STATUS.md for current evidence.
The user-approved target is now signed vehicle-forward speed at the completed
window centre under `driftlock-ml-1.1-centre-forward`.

## First: verify the authorized contract change

Gurveer: preserve the same six raw-phone-IMU CNN+32-GRU, 10 Hz/20-sample window,
embedded normalization and per-window state reset. Verify centre labels, signed
output, since-boot timestamps, exact `t,speed_mps,sigma_mps,valid` packets and
invalid warmup/gap/uncalibrated handling. Old latest-sample magnitude checkpoints
are incompatible and remain historical artifacts.

Run current unit/regression checks, the actual MPS model doctor (CPU fallback if
needed), and a fresh complete CPU synthetic smoke with portable/standalone parity.
Record command exits, config, logs and artifact hashes. Synthetic results prove
software behaviour only, never real navigation usefulness.

## In parallel: resolve the real-data gate

Avi and Gurveer need source evidence for speed units, raw phone frame/settings,
speed-fix freshness/outages, clock identity and original-session independence.
The 10 September corrected plan permits reviewed recording-relative clocks for
offline work, with unchanged source intervals and explicit shared sensor/reference
timing evidence. A boot offset is needed for runtime packets, not offline training.
The existing IO-VNBD inspection does not resolve these. A `Kmh` header alone does
not authorize division by 3.6 while diagnostics conflict with that interpretation.
Do not guess axes/signs or enable --assume-valid-labels.

Signed-forward labels require documented direction; GPS speed magnitude cannot
supply reverse sign. A documented verified-forward-only subset supports only
forward-condition claims. Create reviewed canonical data and a proposed manifest
only when source evidence is sufficient. The generator cannot approve a split;
require actual review and four independent original groups before training.

Antarjot owns alignment and navigation integration. Agree how the centre target
and actual availability are applied to delayed states: 20 samples at 10 Hz make
the estimate available 0.95 s after its target. Never expose it earlier in replay.
Vishisht owns raw capture/runtime and hardware timestamps; Savneet owns display.

## Once reviewed data and real training are approved

Run one small real probe in a fresh directory. Check finite losses and actual
per-epoch timing, then propose one bounded baseline from scratch. Preserve every
experiment, effective config, hashes and logs. The trainer has no exact resume;
never invent a resume flag or launch duplicate training.

Select on validation only. Compare training-mean and last-preoutage-speed baselines
under the same availability/validity policy. Examine per-drive and supported motion/
road-type errors, including stops/braking/turns. Do not invent annotations or
claim that sigma detects unfamiliar roads without measured evidence. Further
window, feature, label, architecture or split changes need explicit approval.

## Freeze, evaluate and hand off

After validation selection, fit sigma only on calibration, then evaluate untouched
test. Preserve disappointing results and forward-only/reverse limitations. New
development prompted by test results contaminates that test for model selection;
require a fresh held-out evaluation.

Produce the exact contract, checkpoint/config/source hashes, predictions/metrics,
centre and availability times, golden sensor/timestamp vectors and standalone
portable inference example. Avi verifies independent results; Antarjot verifies
delayed integration. Speed error and integrated speed difference are not full
position drift or lane accuracy.

Optional actual .tflite conversion needs a separately approved supported runtime;
do not install Linux/containers/VMs or converter dependencies on this Mac. Vishisht
must verify Android golden parity, timing, valid handling and real capture semantics.
Portable parity alone is not mobile readiness.

## If source evidence or time remains insufficient

Finish verified non-data setup, interface tests and the honest blocker report.
A conventions-compliant interface is useful progress, but cannot replace a real
trained model or prove navigation effectiveness. Preserve all original data and
historical outputs. Do not manufacture independence, direction, units, labels or
measurements to meet the deadline.
