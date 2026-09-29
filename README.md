# DRIFTLOCK — Design Preview

Native Android preview for **SIH26168**: saved road maps, a guided journey through GNSS loss, manual offline rerouting, and signal recovery. Built with Kotlin and Jetpack Compose. The ML research code is included separately.

## Install the preview

Download **[driftlock-design-preview.apk](https://github.com/Gurveer0347/driftlock/raw/refs/heads/main/downloads/driftlock-design-preview.apk)** from this repository, install it on an ARM64 Android phone running Android 8.0 or later, and open **DRIFTLOCK Preview**. The APK is an optimized, development-signed prototype.

**Home → Open saved Chandigarh region → Preview guided journey → Shortest.**

The car starts driving, GNSS becomes unavailable, and a roadblock pauses the journey. Tap the offline alternative to see the turn-back and detour over locally saved roads. GNSS returns near the destination. Zoom, pan and follow controls are available throughout. The driver identifies the blockage manually; an offline app cannot obtain live roadblock information from a server.

This preview uses **generated samples**, with a persistent DESIGN PREVIEW badge. Recorded-trip wording previews a proposed interface; it does not describe a real captured trip. The scene marker and the estimator marker have distinct roles. The research model is separate from navigation.

## What is included

| Path | Contents |
| --- | --- |
| `android/` | Complete Kotlin/Compose app, native navigation and matching, offline routing, phone capture, tests, Gradle wrapper and bundled public map |
| `downloads/` | Verified design-preview APK from 28 September 2026 |
| `driftlock_ml/` | Six-channel CNN + GRU, training, evaluation, calibration and portable inference source |
| `tests/`, `tools/`, `configs/` | ML regressions, dataset utilities and baseline/configuration templates |
| `docs/` | Architecture, conventions, data contract, model limitations, demo and field-test guides |
| `reports/`, `verification/` | Dated verification reports, portable build receipt and file hashes |

Private phone recordings, raw training datasets, trained model bundles, SDK/toolchains, caches and signing keys are excluded. Synthetic ONNX instrumentation fixtures and the small research replay already bundled in the tested app retain their original evidence labels. Some historical documents reference assets retained in the original engineering workspace; they are not promises that those assets are included here.

## Build the Android preview

Open `android/` in Android Studio. Use **JDK 17+**, **Android SDK 35** and **Build Tools 35.0.0**; set your own SDK location in `android/local.properties`. Gradle 8.11.1 is pinned by the included wrapper. See [Android build instructions](android/README.md) for creating your own local development signing key.

From `android/`:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assemblePreview --no-daemon -Pkotlin.incremental=false
```

Output: `android/app/build/outputs/apk/preview/app-preview.apk`. A locally rebuilt APK uses your signing key, so it cannot update an installed copy signed by a different key. Preserve existing app data when planning that migration. The regular debug/release variants retain the original simulation labels.

## ML research

The network uses **ax, ay, az, gx, gy, gz only**. GPS/reference speed can supervise offline learning; GPS, wheel speed and CAN are not runtime model features. Current IO-VNBD reference-unit, axis/frame, direction and synchronization ambiguities remain documented. Historical exploratory outputs are **not valid navigation speed**. There is no approved production model in this repository.

The intended contract is described in [DATA_CONTRACT](docs/DATA_CONTRACT.md) and [MODEL_CARD](docs/MODEL_CARD.md). Keep independent training, validation, calibration and final-test groups. A speed prediction does not establish trajectory drift or lane accuracy.

For isolated Python software checks, create a local virtual environment, install `requirements.txt`, then run `python -m pytest -q`. `run_smoke_test.sh` creates and trains on synthetic fixtures for software verification only. It does not establish real-world performance. `requirements-export-linux.txt` is for the separate documented conversion environment; do not install it on macOS.

## Verification and data attribution

The published APK matches the verified 28 September artifact: **19,927,717 bytes**, SHA-256 `fb284450867cd9af4b8073a4c40700945f95273942347266f244a6b4b0e537f6`. Its original Android verification passed **87 core + 80 app tests**. This publication does not represent a new Android build or a new road test. See [preview verification](reports/CLEAN_UI_PREVIEW_REPORT.md).

The app requests supported refresh rates up to 120 Hz. Sustained 120 fps has not been established. No real-trip navigation drift or Android trained-model parity is claimed.

Bundled map data **© OpenStreetMap contributors**, licensed under **ODbL**. See [OpenStreetMap copyright](https://www.openstreetmap.org/copyright) and [map provenance](android/app/src/main/assets/maps/manifest.json). Third-party assets retain their licenses. No additional blanket license grant is made for team source code.
