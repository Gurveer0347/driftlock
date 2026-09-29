# DRIFTLOCK native engineering app

Current launcher: Kotlin/Compose `org.driftlock.app.NativeApp`, entered through
`in.driftlock.ui.MainActivity`. The app now has actual phone capture and local
logging, native ESEKF/alignment/ShadowDR, independent OSM roads, HMM matching,
offline routes/packs/blockages, Driver/Judge screens and explicit synthetic replay.
The preserved original UI scaffold under `in.driftlock.ui` is not the active
navigation implementation. Older files under `android/docs` describe that scaffold.

**No production ML bundle is approved.** The historical research model has
unresolved reference semantics and cannot be used as a metres-per-second odometer.
Synthetic test assets prove numerical execution only. Full MVP, real-trip drift
and lane-level accuracy are not established. See `../reports/FINAL_STATUS.md`.

## Run the supplied APK

Install the engineering APK on an ARM64 Android phone (API26+). Open DRIFTLOCK.
Use Home for the included independent OSM region, More → Calibration/Logs for
real phone capture, and More → Demo for explicitly simulated estimator replay.
Stop recording before exporting or starting simulation. No share/upload is automatic.
See `../docs/DEMO_GUIDE.md` and `../docs/FIELD_TEST_PROTOCOL.md`.

## Build on another developer's machine

Open this directory in Android Studio. Use an existing JDK17+ (verified here with
JDK21), Android SDK Platform35 and Build Tools35.0.0. Configure SDK location through
Android Studio or a local, uncommitted `local.properties`. The pinned wrapper is
Gradle8.11.1. No Linux, Docker or virtual machine is needed.

This project keeps debug signing inside the project. Create your own local debug
key once from the repository root; do not copy Gurveer's signing key:

```sh
mkdir -p .tooling/android-user
keytool -genkeypair -keystore .tooling/android-user/debug.keystore -storepass android -keypass android -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000
```

If the key already exists, retain it. A different signing key cannot update an
already installed APK; keep the existing installed app/data until the owner
chooses an appropriate migration. No uninstall or data deletion is automatic.

From this directory:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug --no-daemon -Pkotlin.incremental=false
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. In Gurveer's configured workspace,
use `../tools/run_android.py` from the repository root to serialize builds and
preserve source/test evidence. That runner expects the existing `.tooling` SDK.
Do not run simultaneous builds against shared output directories.

For the clear-glass judge demo, use the optimized prototype build:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease --no-daemon -Pkotlin.incremental=false
```

APK: `app/build/outputs/apk/release/app-release.apk`. It uses R8/resource
optimization and the local development signing configuration, so it can update
this prototype without deleting its saved data. It is not a store release.
KSP2 is explicitly enabled for Room compiler compatibility; legacy KSP failed
while reading its existing schema during the first release build.

The active UI uses flat translucent tint, subtle edges and shared container/page
transitions. Map geography is rasterized on a worker into a bounded tile cache;
routes and markers remain vector overlays. The activity requests a supported
refresh rate up to 120 Hz at the current display resolution. Device, battery,
thermal and manufacturer policy can limit the actual rate. See
`../reports/GLASS_MOTION_REPORT.md` for measured phone frame results.
On Android 14+ it uses the surface-rate hint without a competing display-mode
preference; Android 15+ also receives a vote from the actual Compose drawing
view. Older Android versions retain the supported same-resolution mode request.

The current surfaces use flat translucent tint and subtle boundaries, with no
reflection stripes, sheen, button gradients or bright white rims.

## Recorded-trip layout preview

Build `:app:assemblePreview` for the separate **DRIFTLOCK Preview** app
(`in.driftlock.ui.preview`). It uses generated samples to preview how a recorded
journey would be presented. A persistent DESIGN PREVIEW badge and visible sample
source distinguish this mockup. It is not a captured real trip or field evidence.
The controller, sensor source, estimator and research gates are unchanged.
Debug/release builds keep RECORDED_TRIP_PREVIEW=false and synthetic labels.
APK: `app/build/outputs/apk/preview/app-preview.apk`. Its separate app identity
keeps the main app's saved data intact. No recorded-trip preview is silently
enabled in the regular demo.

## Evidence and limitations

Physical OnePlus CPH2573 checks cover sensors, capture/export, independent map
rendering, offline routing/storage with radios disabled, and test-only ONNX CPU
numerical parity. They do not establish an approved trained model, full end-to-end
airplane-mode navigation, safe driving, real drift or budget-device performance.

Current design: `../docs/ANDROID_ARCHITECTURE.md`, `../docs/NAVIGATION_ENGINE.md`,
`../docs/OFFLINE_ROUTING.md`, `../docs/MODEL_CARD.md`.
Public map source/provenance: `app/src/main/assets/maps/manifest.json`.
Map data © OpenStreetMap contributors, ODbL; https://www.openstreetmap.org/copyright.
