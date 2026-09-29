# DRIFTLOCK native application — 20 September 2026

Build a smartphone-only Android navigation application that maintains a defensible
position estimate during temporary GNSS loss and permits offline route changes
inside a downloaded road network. The user brief preserved in
`data/raw/engineering_reference_20260920/Pasted text.txt` is the complete product
specification. The official SIH statement is recorded separately in
`OFFICIAL_SIH26168_REQUIREMENTS.md`; user features are not invented SIH rules.

The app accepts phone acceleration, angular rate, gated magnetic field, available
GNSS and monotonic hardware timestamps. No required CAN, wheel-speed, external
odometer, evaluator truth, cloud inference or future sensor samples. A separate
edge adapter may accept external IMU for the additional official deployment path.

Driver flow: choose a destination and cached region; prepare up to three diverse
legal routes; review actual coverage/storage; save a trip pack; calibrate the
mount; navigate; report a road blocked or switch route; reroute locally; export
logs on explicit user action. Pack management supports create/load/delete. A
graph cannot route beyond its downloaded coverage. Restricted, disconnected or
ambiguous routes must produce an explanation, not fabricated geometry.

Driver mode shows map, position/uncertainty, next turn, GNSS/navigation state,
confidence state and a safe route-change action. Judge mode adds actual model
hash/invocations/acceptances/rejections/latency, sensor/update timing, alignment,
ShadowDR, covariance, road hypotheses, route and blockages. Controlled demo events
are visibly simulated and cannot overwrite a real session's provenance.

MVP and final acceptance are the checklists in the preserved brief. An APK build
alone is not MVP completion. Actual installation, sensor callbacks, on-device
production-model parity, GNSS blackout/recovery, offline rerouting and airplane
mode must be demonstrated on physical hardware. Real-drive drift, lane behavior,
device performance and energy use remain separate measured gates.
