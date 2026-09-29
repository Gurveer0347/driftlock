# Physical phone and field validation

Never operate engineering controls while driving. Use a passenger or configure
while parked. The current APK is an engineering prototype with no approved
production ML bundle and no validated navigation accuracy guarantee.

## Bench check

1. Install the recorded APK SHA on an authorized ARM64 phone. Record model/API,
   actual available sensors, build hash, charging/thermal state and app version.
2. Start phone sensors with precise Location permission. Hold still briefly and
   inspect hardware-clock monotonicity, accelerometer gravity magnitude, gyro,
   magnetometer availability and dropped-event counters. Absence stays explicit.
3. Obtain outdoor/open-sky GNSS when possible. Record provider fix age and accuracy
   separately from callback receipt. Indoor absence is a blocked fix test, not a
   successful GPS result. Do not invent a starting location.
4. Save a real independent OSM trip pack with legal alternatives. Restart app and
   reload; verify persisted bounds/routes/blocked segments and actual bytes.
5. With user control of device radio settings, enable airplane mode, keep phone
   Location enabled, reload the saved map, compute a legal local detour, and
   verify raw sensor operation. Restore prior radio settings afterward. GNSS may
   continue in airplane mode; airplane mode is a network test, not guaranteed
   GNSS blackout.
6. Record model status. An untrained synthetic parity fixture proves runtime
   execution only, never a trained production model or vehicle-speed accuracy.
7. Stop recording and export the local archive. Validate archive/schema/hash and
   ensure no high-rate Room writes. Do not transmit it without user authorization.

## Vehicle sessions

Use only phones and mounts physically available. Collect healthy-GNSS sessions
with synchronized raw sensor clocks. Assign original trips to learning,
validation, calibration and blind evaluation *before* selection; reserve a full
blind session where feasible. Do not claim device holdout with one phone.

While parked, secure the phone, start recording and confirm intended forward
calibration. Stationary gravity establishes tilt; straight acceleration/braking
and accepted GNSS course are needed for yaw. Include forward stops, acceleration,
braking, gentle turns and safe intersections. Log intentional remount only while
parked. Unknown reverse sign cannot become an approved signed target.

Evaluator owns withheld GNSS and blackout masks; navigator receives only current
phone sensors and permitted GNSS. Test 5/10/30/60/120/300 s outages only where
recording length and truth support them. Report endpoint/horizontal error, drift
normalized by true distance, recovery overshoot/time, per-session failures,
uncertainty coverage and runtime. Very short/zero distance invalidates normalized
drift. Do not infer trajectory performance from speed RMSE.

Compare INS, last speed, constant velocity, classical filter, ML, uncertainty
gate, ShadowDR and map constraints with identical conditions. A claimed ML run
requires a real approved model hash and positive actual invocation count. Keep
synthetic tests separate and leave disappointing results intact.
