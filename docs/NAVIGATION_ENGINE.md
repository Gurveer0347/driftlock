# Native navigation engine

The pure Kotlin engine is in `android/core/.../nav`. It runs the 19-state ESEKF
with phone IMU, accepted Android GNSS, optional gated phone magnetic field, and
optional signed speed packets. It has no Android dependency, network access,
model weights, wheel/OBD input or truth channel. The Android service supplies
serialized events; the app owns routes, maps, persistence and model eligibility.

This is a prototype estimator with software verification. Its uncertainty and
ShadowDR discrepancy are not independent ground truth or a measured road-test
accuracy. Device sensor capture, field drift and ONNX device parity require their
own evidence. No production model is made valid merely by passing a speed packet.

## Source and numerical contract

`Esekf.kt` and `NavConfig.kt` retain original source SHA-256 comments for the
preserved team Kotlin files. The current matrices follow the repaired Python
`team_work/navigation/driftlock_nav/{filter,state,config}.py`. No raw archive is
edited. Fixtures include source hashes and their own hashes in
`android/core/src/test/resources/nav/provenance.json`.

The error state is `[position(3), velocity(3), phone attitude(3), accelerometer
bias(3), gyro bias(3), phone-to-vehicle mount(3), speed scale(1)]`. All internal
position and velocity are East–North–Up. Quaternions are scalar-first and map phone
to ENU (`qNb`) or phone to vehicle (`qVb`). Vehicle x is forward, y left, z up.
Vehicle heading is the yaw of `R_nb R_vb^T`, zero east, positive toward north;
`courseDeg` converts it to navigation bearing. The heading sigma includes both
attitude and mount covariance and their cross terms.

Acceleration retains gravity, in m/s², and gyro is rad/s. Only the navigation
filter removes world gravity after an observable orientation is established.
The filter uses second-order state transition and continuous noise-density
variance times `dt`, with Joseph-form measurement covariance updates and NIS
gates. Finite values and positive uncertainty are required. A signed forward
speed may be negative. No hidden floor rewrites a valid model sigma.

Native tests compare every nominal-state value and all 361 covariance entries
against repaired Python outputs to 1e-8 after propagation, GNSS position/velocity,
NHC, signed speed, map, ZUPT and ZARU updates. Three stationary mount fixtures and
a mixed nonidentity case are explicit synthetic software inputs. The 45-second
maneuver fixture contains only IMU and GNSS, generated with documented seed/config;
it is not real-trip performance evidence or end-to-end engine parity.

## Public interface and threading

Use one serialized caller for all methods:

```kotlin
val engine = NavigationEngine() // mock fixes rejected, map feedback disabled
engine.onImu(PhoneImu(...))
engine.onGnss(PhoneGnss(...))
engine.onMag(PhoneMag(...))
engine.onSpeed(SpeedMeasurement(t, speedMps, sigmaMps, valid))
engine.onDiscontinuity(reason, receiptT)
engine.confirmForwardMotion()
val snapshot = engine.snapshot()
```

The shared immutable event types are in `core.sensors.PhoneEvents.kt`.
`onSpeed` returns true for an accepted current measurement or a queued future
current-target measurement; `acceptedSpeeds` increments only on actual fusion.
Snapshot values copy state and covariance. The audit ring retains the latest 128
reasons; persistent logging belongs to the caller. `NavigationEvent.t` is the
state/effective epoch, with original `measurementT` and `receiptT` when available.
The speed packet contract remains exactly four fields; model runtime telemetry
separately records actual inference completion and availability.

Position/origin remain null until a trusted fix. `LocalFrame` uses WGS84 ECEF/ENU
with a fixed origin for a session. An unavailable altitude stays null externally;
a zero-height reference plane is used only for horizontal conversion. Current
GNSS fusion is horizontal and does not pretend that missing vertical accuracy is
known. Map and route coordinates must use this same origin before comparison.
A fresh engine is required to start a new drive and origin.

## Causal timing and delayed GNSS

All runtime times are seconds since device boot. Invalid or backward raw IMU
samples, changed sensor segment, queue discontinuities and gaps over 0.5 seconds
are explicit rejection/reset events. The caller must pair only already available
acceleration with gyro and must never provide future source samples to the model.

For latest-target model windows, invoke `onSpeed` before `onImu` for the raw event
that completed the window. If its grid target falls between raw IMU epochs, the
engine queues the packet, propagates with the previous available IMU to the exact
target, fuses there, then completes the remaining raw interval. Targets already
behind the filter are rejected without rewinding or retimestamping. Rates are
limited to 10 Hz. Floating clock comparisons use a 1e-8-second numerical tolerance;
that tolerance does not authorize choosing a future model input sample.

Real Android GNSS often arrives after a raw IMU has advanced past its measurement
clock. A fix may be propagated to the current epoch only if both receipt age and
state age are at most 0.5 seconds and the provider supplies speed 0–60 m/s,
speed sigma in (0,2] m/s, bearing, and bearing sigma in (0,20] degrees. After initialization, the velocity must also pass a read-only innovation probe
against the current state before either the projected position or trusted health
can change. A rejected projection velocity rejects the whole delayed fix. The fix
is advanced by its own horizontal velocity. Position sigma is conservatively enlarged
by `age*(speed_sigma + speed*bearing_sigma_radians) + 0.5*3*age²` metres. Missing
velocity uncertainties or older fixes are rejected explicitly. There is no delayed
state replay. Original measurement time, not callback time, refreshes GNSS health.
The audit records the propagated effective epoch. This bounded constant-velocity
approximation can be inadequate in abrupt maneuvers; the limit is not a promise
that every provider callback will pass.

Only accepted position innovations renew the trusted-fix timer and recovery
count. Motion hints and course/acceleration anchors additionally require accepted
provider velocity, or a qualified near-zero speed consistent with the current
state. Missing speed accuracy and velocity innovations rejected by the filter
cannot trigger stationary ZUPT/ZARU. Rejected future clock bounds do not advance the
input monotonicity marker and cannot lock out subsequent legitimate fixes. Poor accuracy (>35 m), mock fixes, bad clocks and rejected
outliers cannot create an origin or restore healthy status. Recovery requires
three accepted fixes with staged covariance inflation; loss of a trusted fix for
2.5 seconds gives `DENIED`. No fix yet is `UNKNOWN`.

## Alignment and initialization

Before observable mount/yaw, the output is `PARTIAL`: GNSS-aided constant-velocity
initialization with explicitly growing uncertainty, not full inertial navigation.
It does not integrate tilted raw gravity as vehicle acceleration. The unknown
acceleration covariance uses density 3 m/s²/√Hz with `Q_pp=q dt³/3`,
`Q_pv=q dt²/2`, `Q_vv=q dt`. Heading remains unavailable and vehicle NHC/model speed
fusion is disabled.

Gravity can establish tilt but never vehicle yaw or forward sign at rest.
Stationary detection needs a fresh trusted near-zero speed, low acceleration/gyro
variation, and mean gyro norm below 0.05 rad/s; constant rotation is not rest.
Mount estimation then needs gravity, sustained longitudinal excitation with a
fresh accepted GNSS speed/course, and an explicit driver declaration that the
calibration maneuver is forward. The app must describe that declaration accurately.
Unknown reverse motion cannot be inferred from GNSS speed magnitude.

Directional events are collected only after the explicit forward declaration;
confirmation clears previous directional events while retaining stationary gravity.
The solver requires at least 40 longitudinal samples across eight seconds, angular
RMS spread at most 15°, and a bounded RMS/√N mean-direction residual. Standard
deviation of unsigned angles is deliberately not used: equal ±60° errors must
not look like zero uncertainty. Handling triggers include large gyro/acceleration, a stationary gravity change,
more than 20° cumulative rotation while recent independent GNSS motion evidence
confirms a stop, and persistent NHC innovation (120 eligible updates with mean
NIS above 9). The stop rotation rule does not apply during ordinary driving turns.
The NHC/stop monitor has the reference 15-second detection cooldown. These triggers
invalidate the mount, clear the declaration, discard
speed hints, magnetic anchor and ShadowDR history, and show `RECALIBRATING`.
Stream discontinuities show `DEGRADED`; both require fresh calibration evidence.
A declaration alone never marks the mount aligned.

After alignment, NHC runs at up to 10 Hz only above 1.5 m/s and below 0.6 rad/s
vehicle yaw rate. Stationary ZUPT/ZARU use raw gyro. Model speed requires current
target, valid uncertainty, alignment and innovation gate. Calibration grade uses
healthy-fix time, turns, longitudinal excitation and convergence of mount, both
sensor biases and scale, following the repaired Python thresholds.

## Magnetic aid, ShadowDR and horizon

Magnetic field is optional. Unknown/low sensor accuracy, implausible 25–65 µT
magnitude, more than 15% magnitude change, stale timestamps and large independent
heading disagreement reject it. A direction anchor needs ten eligible samples
while accepted GNSS course agrees with the aligned vehicle heading. It is a local
field direction aid, not a claim of true-north geomagnetic calibration. Short
receipt/state delays up to 0.1 second are rotated using available gyro. Remount
clears the anchor; no absolute magnetic heading is invented before alignment.

Every 20 seconds of eligible operation ShadowDR launches a calibrated and control
copy; the latter has biases zeroed and scale set to one while retaining geometry.
Both run for 30 seconds with GNSS measurements withheld. They receive eligible
NHC and current-target model packets. Shadow ZUPT/ZARU requires a fresh model speed
source; a post-launch GNSS zero-speed hint may aid the live filter but cannot aid
its shadows. The calibrated magnetic direction and field magnitude are frozen at
launch. A newly learned post-launch anchor is unavailable to that run. Raw magnetic
field is rotated with each shadow's own gyro bias and checked against its own
heading/magnitude/innovation gates; it does not reuse a GNSS-corrected live bias.
The current model packet eligibility still follows live acceptance, so the copies
share event selection and are not completely independent estimators. Comparisons use the GNSS-aided
live estimator; shared errors and correlation remain possible. A real reference
interruption invalidates the run, and it expires on time without waiting for a
later fix. Healthy discrepancy histories are sampled at up to 10 Hz and the last
12 paired entries feed the Python-style `d=a*t+b*t²` least-squares fit with
nonnegative coefficients. No completed eligible reference means null predicted
drift, not zero measured error. The optional `shadowComparison` exposes calibrated
and control discrepancies; it never asserts calibration must improve a run.

`HIGH`, `MODERATE`, `LOW` and `UNRELIABLE` are rule-based evidence categories,
never probabilities or percentages. Missing alignment/heading or extreme position
uncertainty gives `UNRELIABLE`; denial without a learned discrepancy model,
reacquisition and large uncertainty reduce the category. A high category while
satellite fixes are stable does not validate prolonged blackout performance.

Map feedback is disabled by default to avoid recycling correlated navigation
information. The optional method additionally requires alignment, exact state
clock, identical origin, finite position, valid posterior and an innovation gate.
A road lock displayed by the independent matcher is not automatically an EKF
measurement.

## Evidence and limitations

Build/test commands and exit status are preserved under
`logs/native_20260920_01/native_nav/`; Python fixes and their complete test evidence
are under `logs/native_20260920_01/nav_repairs/`. Use the shared project runner:

```text
.venv/bin/python tools/run_android.py --log logs/native_20260920_01/native_nav/verification.log -- :core:test
```

The original Kotlin math, repaired Python, native orchestration and app adapters
are different evidence layers. Passing host JVM numerical tests does not establish
Android sensor accuracy, model usefulness, real-trip drift, end-to-end timing on a
phone, lane accuracy or robust arbitrary mounting during all maneuvers. The full
application's device acceptance report must state which hardware checks actually
ran. All thresholds are prototype engineering defaults awaiting field validation.

## Separate external-IMU edge interface

`core.edge.ExternalImuAdapter` forwards declared sensor-body samples into the same
`NavigationEngine`. It is not used by the Android phone app and adds no external
hardware requirement to phone operation. The portable boundary has no serial,
USB, BLE, network or vendor packet parser.

`ExternalImuDeclaration` requires source identity and evidence; `m/s^2`
acceleration including gravity; `rad/s` angular rate; fixed right-handed sensor
XYZ body axes with a frame identity; and a declared shared monotonic boot-clock
identity. `accept(sample, availableT)` takes the source timestamp and the actual
transport-observed availability timestamp separately. Source time must not exceed
availability, both identities must match, and accepted source times must strictly
increase. Metadata declarations do not themselves verify physical units or clock
synchronization. A real device requires documented axes, calibration and clock
mapping before this interface can be used honestly; none is guessed here.

No axes or units are silently converted. Unsupported/undeclared conventions,
nonfinite or out-of-envelope inputs, wrong identities, future samples and duplicate
clocks are rejected. Source-clock resets and gaps over 0.5 seconds invalidate the
session and notify the navigator; a new session/engine is required to restart.
The adapter reports delivered samples separately from measurement acceptance:
delivery is not an alignment or positioning-quality claim. Any optional GNSS or
speed aid must use the same explicitly established clock/frame contracts.

Five adapter tests cover declaration/causality/reset gates and exact snapshot
parity against direct engine input over the existing synthetic maneuver. A bounded
Mac/JVM processing probe and its limitations are recorded in
`reports/NAVIGATION_BENCHMARK.md`. Vendor transport integration and physical
external-IMU/FOG verification remain unavailable without actual device specifications
and hardware. The adapter never activates a phone-trained model on external IMU data.
