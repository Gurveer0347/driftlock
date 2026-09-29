# Android engineering build

Native Kotlin/Compose launcher lives in `android/`. It builds on the preserved
team design. `team_work/` and raw team archives remain unchanged.

- `SensorService`: foreground capture, hardware and receipt boot clocks,
  accelerometer/gyro/magnetometer/GPS callbacks, bounded copied-event queue,
  buffered raw/session logs, latest-target causal windows, model runtime.
- `NavigationEngine`: pure Kotlin 19-state ESEKF, alignment, GNSS health,
  ShadowDR and uncertainty. Its single owner is the service processing worker.
- `NativeController`: application-lifetime bridge; callbacks/commands serialize
  through the service. Immutable StateFlow drives Compose. Model callbacks arrive
  before the triggering raw IMU so the engine can process an intermediate causal
  grid timestamp correctly. Receipt/processing completion time remains separate.
- Road worker: separate serial executor and conflated latest observations. HMM,
  route searches, pack reads/downloads and Room queries do not run on the UI
  thread. Generation IDs prevent an old map result overwriting a newly loaded map.
- `NativeApp`: Home, preparation, download, calibration, Driver, confidence,
  Judge, Packs, explicit Demo, Logs and Settings. The no-position and no-model
  states are visible; a sample map never substitutes a fabricated phone fix.

Android 8/API26 minimum, compile/target35, ARM64 APK. JDK/Gradle/SDK caches are
project-scoped. Build with:

```sh
.venv/bin/python tools/run_android.py --log logs/android_check.log -- :core:test :app:testDebugUnitTest :app:assembleDebug
```

The runner serializes builds and preserves test artifacts/source hashes. Editing
while compilation runs is reported in evidence. APK:
`android/app/build/outputs/apk/debug/app-debug.apk`. It is a debug-signed engineering
artifact, not a store release. Keep the project debug key local.

## Runtime and privacy

CPU ONNX Runtime is available but no real model bundle is approved. Both model
and manifest byte hashes must match a separate reviewed allowlist; self-declared
approval is insufficient. The historical research checkpoint is incompatible.
The app therefore cannot satisfy the production-ML part of the user's MVP yet.

Permissions: precise/coarse Location, foreground Location service, notifications,
Internet for explicit map preparation. Android backup is disabled. Sessions stay
in app storage until an explicit export chooser is used; no automatic upload.
Stop recording before export to flush files. A recording does not automatically
resume after process death. Device notification controls stop capture.

The synthetic replay is an explicitly labeled test fixture under `assets/demo`,
with separate mock-permitting engine and no real model invocation. Live service
events are ignored during replay; leaving it clears the simulated engine state.
It cannot be silently entered because sensors or GPS are unavailable.

Actual physical device, sensors, cold/warm inference, memory, offline and battery
results belong in `reports/ANDROID_PERFORMANCE.md`; JVM tests are not substitutes.
