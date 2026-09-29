# Native DRIFTLOCK architecture v2

This is the implementation design authorized by the 20 September user brief.
Legacy Python v1.1 and research artifacts remain separate. Nothing in this design
certifies data, performance or a production model.

## Runtime boundaries

`SensorService` owns Android hardware callbacks on a dedicated thread, buffered
logging, monotonic clocks and bounded queues. A pure Kotlin `core` module owns
navigation and graph algorithms. The Compose app observes immutable state on the
main thread; it never computes covariance or repairs sensor data.

`PhoneImu(t, accel[3], gyro[3])` is raw phone-frame SI data. `PhoneGnss` carries
the provider's monotonic fix time separately from receipt time, accuracy, speed,
bearing and optional accuracy fields. Callback pairing must never consume a later
measurement. Gaps invalidate model windows and degrade estimator uncertainty.
No public phone-runtime interface includes vehicle or evaluator reference data.

ENU metres, body-to-nav scalar-first quaternions, vehicle x-forward/y-left/z-up,
standard gravity 9.80665 and standard-deviation boundary units stay unchanged.
Native v2 publishes a categorical confidence horizon, not the legacy percentage.
The public model packet retains `t, speed_mps, sigma_mps, valid`; separate audit
metadata contains rejection reason, latency, model hash and invocation counts.

## Model

New production candidates use contract `driftlock-ml-2.0-causal-latest`: six raw
phone IMU channels, 10 Hz, initially 20 samples, latest available sample target.
No old centre-target checkpoint can be retimestamped. Any new window length,
normalization, sigma scale and source review are embedded in the model manifest.
CNN-GRU, compact causal CNN and TCN are bounded validation comparisons explicitly
authorized by the user. Normalization fits training only and is embedded once.

`ModelRuntime` receives only a complete oldest-first float32 IMU window and its
timestamps. `OnnxModelRuntime` verifies manifest/hash/contract/shape before local
CPU inference. Warmup, gaps, nonfinite values, excessive shock, input-distribution
limits, sigma and estimator innovation can reject predictions. Failure leaves
the deterministic estimator active. Research-unit models are never production
measurements. Until the source gate passes the production runtime is unavailable,
and the app must state that accurately.

Native resampling is causal with explicit age limits. Its processing and any
causal low-pass must match training preprocessing; a different phone sampling
rate is not permission to silently introduce a training/runtime transformation.

## Navigation

Port the corrected existing 19-state ESEKF, not an unrelated simplified position
integrator. Nominal position, velocity, phone attitude, accel/gyro biases, mount
and speed-scale uncertainty remain explicit. Propagation, NHC, stationary,
available GNSS, eligible speed and gated map updates use finite/time-checked
measurements and Joseph covariance updates. Native/Python fixtures establish
software parity; physical validation remains separate.

GNSS modes: HEALTHY, DEGRADING, DENIED, REACQUIRING, STABLE. Rejected innovations
cannot renew trusted-fix age or calibrate ShadowDR. Reacquisition uses consecutive
accepted fixes and bounded reconciliation, never rewrites past blackout states.
ShadowDR uses only current/past accepted fixes and exposes observability/quality.
Alignment states: UNINITIALIZED, PARTIAL, ALIGNED, DEGRADED, RECALIBRATING.
Stationary gravity gives tilt, not yaw. Forward motion/course and acceleration
events establish yaw/sign only when observable; remount disables unsafe vehicle
constraints until recovered. Magnetic magnitude, changes and independent heading
agreement determine whether magnetic measurements are usable.

## Roads and routes

Independent OSM graph schema carries WGS84 nodes, directed edges, original way and
physical segment IDs, geometry, names/classes, access/speed and turn restrictions.
Unsupported restrictions are exposed or excluded conservatively, never ignored
while claiming a legal route. Convert to local ENU only through a shared origin.

Routing uses directed shortest paths with previous-edge state for restrictions.
Return complete edge paths and instructions, not just a distance. Diverse
alternatives use edge-overlap and detour limits. A blocked physical segment
excludes both directed variants where appropriate for the current trip. Routing
from a matched fractional edge must obey its allowed direction; do not teleport
back to a passed junction. A legal reversal may require downstream connectors.

The causal HMM retains multiple connected hypotheses. Ambiguous or distant
positions cannot silently select a road. Matcher posterior is not calibrated
position confidence. Map feedback defaults disabled until with/without-map
ablations support a conservative gate. Display can show a hypothesis without
altering filter truth.

Trip packs persist the graph corridor, routes, instructions, destination,
coverage bounds, provenance, attribution, hashes and measured byte sizes. A
bounded geographic OSM download chosen by the user includes connector roads;
it is independent of benchmark truth. Local route planning, switching and
rendering need no network after load. Missing coverage returns an honest failure.

## Android and evidence

Working integration lives in `android/`, copied from the team UI foundation;
`team_work` and raw archives remain available for comparison. Kotlin JVM `core`
can be tested without Android hardware. App uses Compose, foreground location
service, coroutines/Flow, Room for pack/trip metadata and buffered session files.
Download/export controls require user actions; no automatic private uploads.

Evaluation feeds only allowed current/past events to a navigator process, while
the evaluator retains withheld GNSS/reference data and blackout masks. Every
ML benchmark records model hash/invocations/accepted/rejected counts; no real data
means failure, no synthetic substitution. Synthetic tests and demo mode are named
as such. Final-test groups stay inaccessible to selection and clock fitting.

An external-IMU edge adapter uses the same pure engine with explicit units/frame
and its own clock contract; its 200 Hz throughput/accuracy evidence is distinct
from the 10 Hz phone app. Physical phone, field, airplane-mode, memory, latency and
battery gates cannot be satisfied by a Mac build or JVM unit test.
