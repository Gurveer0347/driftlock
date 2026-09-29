# Clear glass, motion and phone rendering — 28 September 2026

The optimized v4 native prototype is built, installed and rehearsed on Gurveer's
OnePlus CPH2573. Green styling is preserved with translucent tint, crisp rims,
static reflections and readable text. No backdrop blur or captured backdrop is
used. The Home region slab expands into the map. Pages, status badges, story
panels, details, buttons, selections, loading, zoom, follow and vehicle heading
have coordinated transitions.

## Rendering changes

The previous map rebuilt projected geography, paths and label placement during
animated frames. Static OSM geography now renders on a dedicated worker into
256-pixel tiles with a bounded 96-tile cache (about 24 MiB of pixel storage).
Routes and moving markers remain vector overlays. Worker generations revoke
stale requests; graph changes clear displayed tiles. Crossing geometry and
consistent label suppression avoid missing crossings and tile-edge fragments.
Follow animates from the visible camera after dragging; heading turns take the
shortest angle across ±180 degrees. Raster road/label widths may still step at
zoom-level changes. OSM footprint depth remains illustrative, not measured 3D.

The prototype release uses R8/resource shrinking and the existing local
development signing identity, preserving saved app data on update. It is not a
store release. KSP2 is pinned for the existing Room compiler/schema path. No
Linux, Docker, VM, dependency upgrade, system install or upload was involved.

## Measured phone results

The same script waits until the mapped journey is ready, resets Android's frame
statistics, then observes a 14-second generated replay without UI polling or
screenshots in the measured interval. Each row is one run, not an aggregate or
performance guarantee. The modern Janky frames deadline count is used; the
separate legacy heuristic is retained in raw logs and is not interchangeable.

| Build / phone preference | Frames | Missed deadlines | Median | 95th percentile | Observed rate |
| --- | ---: | ---: | ---: | ---: | ---: |
| Preserved v3 / Auto-select | 794 | 35 (4.41%) | 22 ms | 32 ms | 60 Hz |
| Glass optimized / Auto-select | 883 | 8 (0.91%) | 11 ms | 16 ms | 60 Hz |
| Glass optimized / High, app 120 Hz | 848 | 5 (0.59%) | 11 ms | 16 ms | 60 Hz |
| **Final v4 / High, app 120 Hz** | **844** | **9 (1.07%)** | **11 ms** | **17 ms** | **60 Hz** |

The final run has fewer missed deadlines and half the median reported frame
time compared with v3. Load, temperature and scenario affect results; these
measurements do not establish smoothness on every page or device. Initial
startup measurements used inconsistent readiness points and are excluded.
An early final measurement was terminated because installation had not completed;
its log is retained and excluded. The successful measurement began after
installation success and an independent installed-APK hash check.

With Gurveer's explicit approval, OnePlus's global mode changed from Auto-select
to High, enabling the per-app menu. DRIFTLOCK's 120 Hz (Default) choice was
confirmed. Other apps' per-app preferences, resolution, radios and rotation were
not changed. Direct access to a restricted OnePlus Settings activity was denied;
normal Settings UI was used without bypassing the restriction.

The app requests a supported rate up to 120 Hz. Android 14+ uses a surface-rate
hint without a competing display-mode preference; Android 15+ also receives a
vote from the actual Compose drawing view. Older systems retain the supported
same-resolution mode request. The final phone Window reports
preferredRefreshRate=120.00001 with no competing display mode. Nevertheless the
final display reports 60 Hz; all 120 recent frame records report intervals near
16.54 ms. **Sustained 120 fps is not verified.** The exact remaining device or
compositor policy has not been established; do not claim a proven specific
OnePlus restriction. Evidence: logs/glass_motion_20260928_01/frame_comparison.json,
each *_ready_gfxinfo.txt, final_120_ready_display.txt and phone_refresh_setting.json.

## Verification

Final command:

```sh
.venv/bin/python tools/run_android.py --log logs/glass_motion_20260928_01/final_build.log -- :core:test :app:testDebugUnitTest :app:assembleRelease
```

Exit 0. Preserved XML: **87 core + 77 app tests**, zero failures, errors or skips.
Some unchanged Gradle tasks were up to date; reused results are not claimed as
new execution. All 115 hashed source inputs still match. Regression coverage
includes tile projection/crossings/reuse/cache bounds, label suppression,
page/container transitions, touch camera consistency, continuous follow, short
heading turns and modern/legacy refresh requests. Initial failing regressions
remain logged. The Compose observer/thread failure was fixed with a dedicated
serial tile executor and main-thread publication; the KSP1 schema failure was
fixed by pinning KSP2. Independent read-only review found no important residual
issue. See final_build_verified.json and review_final.txt in the same log folder.

Python: .venv/bin/python -m pytest -q, exit 0: **569 passed**, 15 existing exporter
warnings, 27.54 s (python_tests.log). Python/model/data source did not change after
that run; no broad retest was needed for the final Android-only refresh fix.

The final APK is 19,927,701 bytes, non-debuggable; SHA256:
`f65b309c8e9ac33771ecd2de0ec6368b3dbef1032ac896a60b2f1af7acf0a820`.
Installed base APK hash matches. Installation succeeded after the normal OnePlus
scan said “No risks found” and Continue installation was confirmed. Verification
was not disabled. The first blocked attempt remains logged.

The final physical rehearsal exited 0: both route choices visible → shortest
starts drive → GNSS cut → closure → ten-second presenter pause → offline detour
and turn-back → GNSS recovery → arrival → zoom/drag/follow → separate Research
with zero Android model calls. Actual screenshots were inspected. Evidence:
phone_final/phone_rehearsal.json, phone_map_controls.json and captured screens.

The app is left at Home with replay revoked. Calibration independently shows
Start phone sensors, and the bound service has startForegroundCount=0. An idle
bound service is expected and does not mean recording is active. The first
state check incorrectly expected no service binding; the corrected verification
checks capture state. Airplane mode is off; original enabled rotation remains.
Exact final state, request and hashes: device_final_check.json.

## Handoff and meaning

The v4 handoff includes APK, native source/tests, R8 rules, KSP2 configuration,
Room schema, guides, actual-phone captures, verification and the unchanged ML
evidence ZIP. Earlier v3 packages and raw originals remain preserved. The ZIP
receipt is recorded in STATUS after packaging; packaged reports are the snapshot
immediately preceding that receipt.

Generated replay, separate estimator, research model, contracts, source-meaning
gates and reserved final test retain their meanings. Rendering checks are not
real-trip drift, lane accuracy or SIH performance. No training was performed.

## Official references

- [Compose shared elements](https://developer.android.com/develop/ui/compose/animation/shared-elements)
- [Compose performance](https://developer.android.com/develop/ui/compose/performance)
- [Window refresh-rate precedence](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#preferredRefreshRate)
- [View and Compose frame-rate requests](https://developer.android.com/develop/ui/views/animations/adaptive-refresh-rate)
- [Android frame-rate policy](https://developer.android.com/media/optimize/performance/frame-rate)
