# Native DRIFTLOCK status — 20 September 2026

## Clean surfaces and recorded-trip design preview — complete, 28 September 2026

Removed reflection stripes, sheen, strong white rims and button gradients while
preserving green tint, route artwork and motion. Both optimized APKs are installed
on the OnePlus with matching installed/build hashes. The separate **DRIFTLOCK
Preview** application (`in.driftlock.ui.preview`) displays the recorded-journey
layout requested for non-final inspection. A persistent DESIGN PREVIEW badge and
visible generated-sample disclosure identify the mockup; its map-header caption
stays above the fold. Main debug/release builds retain synthetic-source labels.
Source, controller, filter and model meanings are unchanged. No real trip was
fabricated or captured by this work. Historical v4/ML deliveries remain unchanged.

Final build: `.venv/bin/python tools/run_android.py --log
logs/clean_preview_20260928_01/disclosure_final_build.log -- :core:test
:app:testDebugUnitTest :app:assembleRelease :app:assemblePreview`, exit 0:
**87 core + 80 app tests**, zero failures/errors/skips. All 115 frozen source
inputs still match. The below-fold caption was reproduced, covered by a failing
map-header bounds assertion and corrected before this final suite.

Final regular APK: 19,927,701 bytes, SHA256
`7958d461ae0a86480fe7911c11fb84b95a286665941bdd9894985aad55fb2bd9`.
Final preview APK: 19,927,717 bytes, SHA256
`fb284450867cd9af4b8073a4c40700945f95273942347266f244a6b4b0e537f6`.
Both are non-debuggable, development-signed optimized prototypes. Installations
exited 0; ordinary OnePlus No risks found confirmations were used when shown.
Verification was not disabled.

Final phone rehearsal exited 0: both route choices → shortest → GNSS cut →
roadblock → ten-second pause → offline turn-back/detour → recovery → arrival →
zoom/drag/follow → separate Research with zero Android model calls. Screenshots
were inspected; preview identity and sample caption are visible. The phone is
left at Preview Home, replay revoked, real capture stopped, airplane mode off
and original rotation enabled. No new rendering benchmark or 120-fps claim was
made. Detailed results: `reports/CLEAN_UI_PREVIEW_REPORT.md`; receipts and captures:
`logs/clean_preview_20260928_01/`. No build or training is active.

Next: open **DRIFTLOCK Preview → Open saved Chandigarh region → Preview guided
journey → Shortest** for layout inspection, or DRIFTLOCK for the regular simulated
demo. Verified portable UI delivery: `handoffs/DRIFTLOCK_UI_Design_Preview_2026-09-28.zip`,
25,934,320 bytes, SHA256
`fe255a28101d6c14ac99dd4afa64d25116f4ff7592801860a9adbd7fbf73df6a`. Packaging exited 0; ZIP CRC and
all 153 payload hashes passed. All 114 portable frozen Android
inputs match the tested workspace; host SDK local.properties is excluded.
It contains both APKs, complete native source/tests, guide, final report and phone
verification. Receipt: `logs/clean_preview_20260928_01/package_verified.json`.
The immutable report snapshot predates this local packaging receipt.

## Clear glass and motion v4 — 28 September 2026

The optimized native prototype is built and installed on Gurveer's OnePlus.
The green Home slab now uses clear tint and crisp reflections and expands into
the map. Page, story-sheet, detail, selection, loading, zoom, follow and heading
transitions are coordinated. Static geography uses a bounded worker-built tile
cache; moving routes/markers remain vector overlays. No backdrop blur is used.
Detailed changes, evidence and limits: `reports/GLASS_MOTION_REPORT.md`.

Matched 14-second generated replay measurements: preserved v3 **4.41%** frame
deadline misses, median **22 ms**, 95th **32 ms**; final v4 **1.07%**, median
**11 ms**, 95th **17 ms**. These are individual rendering measurements, not
navigation accuracy or guarantees across devices. With explicit approval the
OnePlus is set to High and DRIFTLOCK to 120 Hz (Default). The app's corrected
surface/Compose-host request is 120 Hz, but observed delivery remains **60 Hz**
with recent frame intervals near 16.54 ms. Sustained 120 fps is not verified;
the precise remaining device/compositor policy is unresolved. Intermediate
measurements and excluded startup/aborted attempts remain in the logs.

Final Android command: `.venv/bin/python tools/run_android.py --log
logs/glass_motion_20260928_01/final_build.log -- :core:test
:app:testDebugUnitTest :app:assembleRelease`, exit 0: **87 core + 77 app tests**,
zero failures/errors/skips. All 115 hashed source inputs still match. Python:
`.venv/bin/python -m pytest -q`, exit 0: **569 passed**, 15 exporter warnings,
27.54 s (`python_tests.log`). Independent read-only review found no important
remaining issue. Initial failures, fixes and reused Gradle results are documented.

Final optimized APK SHA256:
`f65b309c8e9ac33771ecd2de0ec6368b3dbef1032ac896a60b2f1af7acf0a820`;
19,927,701 bytes, non-debuggable, installed base hash matches. OnePlus's normal
No risks found / Continue installation prompt was confirmed; verification was
not disabled. The blocked first install attempt remains logged.

Final phone rehearsal exited 0: both route choices visible → shortest → GNSS
cut → roadblock → ten-second pause → offline detour/turn-back → GNSS recovery
→ arrival → zoom/drag/follow → separate Research with zero Android model calls.
Screenshots were inspected. Evidence: `logs/glass_motion_20260928_01/phone_final/`.
The app is left at Home with replay revoked and real capture stopped; idle bound
sensor service is expected. Airplane mode is off and original rotation remains
enabled. Exact request, source/installed hashes and state: `device_final_check.json`.

The v4 handoff contains the optimized APK, complete native source/tests, R8
rules, KSP2 settings, Room schema, presenter guide, phone captures, verification
and byte-identical historical trained ML evidence ZIP. Earlier v3 delivery and
raw originals remain preserved. The green car is scripted; the blue marker is
the separate estimator on generated replay. No production ML, real-road drift,
model parity or final-test result is implied. Contracts/source-meaning gates and
research separation remain intact. No training or build is active.

Next rehearsal: open DRIFTLOCK → Open saved Chandigarh region → Preview guided
journey → Shortest. Build if needed: the exact final command above.
This report snapshot precedes the immutable ZIP; its verification receipt is
appended locally after packaging.

**Verified v4 portable delivery:**
`handoffs/DRIFTLOCK_Judge_Demo_2026-09-28_v4.zip`, 29,556,525 bytes; SHA256
`3ab6de2ad22de829f3f5b1936b64c0a54579a8e7028c8dbdc4465d04de46efe4`. Packaging exited 0; ZIP CRC and all
166 payload hashes passed. Independent comparison verified all
114 portable hashed Android inputs against the tested workspace, plus
R8 rules/wrapper files; all 115 current frozen inputs still match.
The one host-specific SDK local.properties file is deliberately excluded,
along with signing keys/toolchains/caches/private recordings. The ML evidence
ZIP is byte-identical. Receipt: `logs/glass_motion_20260928_01/package_v4_verified.json`.
The immutable archive contains the report snapshot preceding this receipt.

## Guided journey v3 — 28 September 2026

The native judge-facing flow now starts when the shortest route card is tapped:
all available routes are saved locally, the green scene vehicle drives on mapped
geometry, GNSS input is withheld at 8 s, a mapped roadblock pauses the story at
17 s, the presenter selects an offline detour, GNSS returns at 38 s and the
scene arrives at 45 s. Both route cards fit the initial selection view. The UI
has a focused opening card, contextual map sheets, animated scene movement and
stage transitions, pinch/drag, zoom buttons and follow control. Real OSM streets,
road names and mapped footprints remain the geographic source; depth styling is
illustrative. Presenter steps: `docs/DEMO_GUIDE.md`.

The green scene vehicle is a scripted illustration; the blue marker is the
separate native estimator on generated IMU/GNSS samples. The replay-only virtual
source/receipt clock freezes during a presenter pause; live phone clocks and the
filter are unchanged. Research has its own source label and zero Android model
calls. No production model, measured drive accuracy, navigation drift, or mobile
model parity is implied. Source-meaning gates and the final-test reservation remain.

Frozen build: `.venv/bin/python tools/run_android.py --log
logs/guided_demo_20260928_01/frozen_android.log -- :core:test
:app:testDebugUnitTest :app:assembleDebug`, exit 0: **87 core + 65 app tests**,
zero failures/errors/skips. Python: `.venv/bin/python -m pytest -q`, exit 0:
**569 passed**, 15 exporter warnings, 27.31 s (`python_tests.log` in that log
folder). Android source hashes were unchanged during the build and still match.
A clean copied portable Android source independently passed the same 87 + 65
tests and APK assembly, exit 0 (`portable_source_build.log`). Independent review
approved the replay clock, current gesture camera, follow-to-pan continuity and
crossing-road visibility fixes after their targeted regression tests.

Frozen APK SHA256:
`dfbaa0e48692cf9498d81c65a6704d2d164bd620922c171eb82d47a37a05abf0`.
Installation succeeded; the installed base APK hash matches. The physical OnePlus
rehearsal passed route selection → GNSS outage → closure → ten-second presenter
pause → accepted detour → GNSS recovery → arrival (`phone_rehearsal_frozen.log`,
exit 0). Screenshots were inspected; route options and closure choice are visible
without scrolling. Zoom, drag and follow were exercised physically as well.
Auto-rotation was restored to its original enabled setting; airplane mode is off.
The app was left at Home after revoking the synthetic session, with real sensor
recording stopped (`device_frozen_check.json`). Earlier failed automation attempts
are preserved; the successful script waits for enabled clickable controls.

The v3 handoff contains the APK, native source, presenter guide, phone captures
and the unchanged trained ML evidence ZIP. The previous v2 delivery remains
historical. No training or build is active. Next rehearsal: open DRIFTLOCK and
follow Home → saved Chandigarh region → Preview guided journey → Shortest.

**Verified portable delivery:**
`handoffs/DRIFTLOCK_Judge_Demo_2026-09-28_v3.zip`, 34,036,090 bytes, SHA256
`208ce71111bc3e0e55cd049338d903d0a50e2c2c0317e27e1d16ee1c8ef0e651`.
Packaging exited 0; ZIP CRC and all 129 payload hashes passed. All 114 packaged
Android source inputs exactly match the independently tested clean source copy
and the current workspace. The initial comparison included the Room schema
generated by that clean build; this generated output is excluded from source
input comparison. The original ML evidence ZIP is byte-identical. Evidence:
`logs/guided_demo_20260928_01/package_v3.log` and `package_v3_verified.json`.
The archive contains the status snapshot preceding this packaging receipt;
this local receipt records verification of the completed immutable archive.

## Later judge-demo build — 28 September 2026

The 20 September engineering evidence below remains historical. A later native
judge-demo APK has been built and installed on the connected OnePlus. It adds
the Option A-inspired map-first experience, local OSM footprint rendering,
an easier route/offline-pack flow, an explicit simulated GNSS outage/recovery
and conservative what-if closure preview, plus a separate research-model page
showing the actual validation plot and the failed hold-last comparison. The
model still makes **zero production Android navigation calls**. Source meanings
remain unresolved; no real-road accuracy or SIH drift objective has been proven.

Exact later build: `logs/two_day_demo_20260927_01/final_android.log`, exit 0,
87 core + 46 app tests, zero failures/errors; Python full suite 569 passed,
15 warnings, exit 0 in `logs/two_day_demo_20260927_01/python_tests.log`.
APK SHA256 `352aaa992bf9c800722b14432355bedf29fa55692683a38ac6995e6816c19429`;
installed base APK hash matched. OnePlus screenshots include actual local map,
route alternatives, offline-pack reload, simulated GNSS cut/recovery, closure
preview and research page. The closure in this observed run was **what-if from
a mapped point**, because an upcoming road could not be confidently established;
it was not a live-position reroute. Full current limits and exact presenter
steps are in `reports/STATUS.md` and `docs/DEMO_GUIDE.md`. The later delivery
does not change the historical measurements or remaining gates below. The
final portable delivery is `handoffs/DRIFTLOCK_Judge_Demo_2026-09-28_v2.zip`
(31,298,871 bytes, SHA256
`dbc6f9e07402d185714715b9df37743ff421be376823b5f548464e6f69a11d71`);
119 payload hashes and ZIP CRC passed. Source extracted from that archive
independently passed 87 core + 46 app tests and assembly, exit 0. The earlier
draft archive is superseded. Airplane mode was restored to disabled, recording
stopped and DRIFTLOCK left at Home.

An installable native engineering app runs on the connected OnePlus phone. **Full
MVP/SIH acceptance remains incomplete:** no approved production speed model or
real driving accuracy result exists. The blocked requirements are preserved.

| Phase | Status | Evidence / limit |
|---|---|---|
| Official requirements, complete competitor/source audits | DONE | Official source preserved/verified; full 1,652-paragraph competitor document read; 25-lineage matrix; source inventories in `logs/native_20260920_01/`. |
| Compliance audit | DONE; NOT A COMPLIANCE PASS | `SIH26168_COMPLIANCE.md` lists unmet model, field, drift, lane and edge-hardware requirements. |
| Official paired-data audit | DONE | 64 non-final Driver E pairs, 192 actual payloads, 343,618,223 bytes; hashes verified. Final A/B/D payloads unopened. |
| Guarded canonical preparation | SOFTWARE DONE; REAL DATA BLOCKED | 29 focused tests; 64-pair metadata proposal (42 train/2 validation/20 calibration). Raw-phone axes/frame, direction, paired timing/freshness still need evidence. |
| Mobile model pipeline | SOFTWARE DONE; REAL TRAINING BLOCKED | Tested CNN-GRU/CNN/TCN, mean/ridge baselines, calibration/export gates. No real selected/calibrated m/s production model. Tree baselines remain unbenchmarked. |
| Mac Android toolchain | DONE | Project-scoped SDK35/Gradle, no Linux/Docker/VM/system install. Actual existing CNN-GRU MPS forward/backward doctor passed. |
| Native capture/logger/runtime | IMPLEMENTED; PHONE BENCH PASSED | Actual accel/gyro/magnetometer/GNSS, monotonic clocks, causal pairing, loss markers, buffered logs and ONNX/hash gate; two physical recordings and completed ZIP export. |
| ESEKF/alignment/ShadowDR | SOFTWARE VERIFIED; FIELD OPEN | 40 focused native tests; four filter parity fixtures/24 steps/all states and covariance at 1e-8. Independent timing/alignment/shadow/remount defects reproduced and fixed. |
| Independent OSM/HMM/offline routing | SOFTWARE VERIFIED; FIELD BENEFIT OPEN | 1,443 drivable nodes/2,717 directed edges; legal alternatives, loops, blocked edges, wrong turns, conservative restrictions and persistent packs tested. |
| Driver/Judge/Demo/Logs UI | IMPLEMENTED | Actual phone map, route preparation/save, capture/export observed. Simulated replay is separately labelled and isolated. |
| Android installation | DONE | OnePlus CPH2573 Android16/API36 ARM64; engineering candidate02 installed. This 11,483,108 kB RAM phone is not a 4GB-budget-phone test. |
| Physical ONNX software check | DONE; NOT PRODUCTION PARITY | Untrained test-only asset: 109 actual CPU calls; max difference 4.172325e-7; median0.3440365ms/p950.434479ms. Production calls remain zero. |
| Physical offline routing test | DONE; SCOPED | Airplane on/Wi-Fi off: OK(1 test), zero downloads, Room reopen, three routes, legal detour and persisted blockage. Current road point explicitly synthetic; original radios restored. |
| Phone update rate / timing | PARTIALLY MEASURED | 415 publications/33.526s =12.34865Hz; engine-only median0.328437ms/p953.450391ms. Short stationary bench; initial9.874Hz shortfall preserved. |
| Full ML/navigation airplane test | BLOCKED | No approved production model. Partial routing/runtime tests cannot pass the complete requirement. |
| Real drive / budget phone / battery / drift | NOT STARTED | No physical vehicle drive, 4GB-device trial, lane accuracy or <10% drift established. USB charging precludes battery-drain inference. |
| Separate external-IMU adapter | SOFTWARE DONE; HARDWARE OPEN | Five adapter tests passed. Explicit source/body-frame/shared-clock/SI declarations; synthetic Mac200Hz-input throughput probe only. No vendor transport or FOG hardware validation. |
| Final held-out evaluation | NOT STARTED; RESERVED | No final-test selection, training, calibration or clock fitting. |

## Exact verification records

Paths below are under `logs/native_20260920_01/`.

- Full Python: `.venv/bin/python -m pytest -q`, exit0, **569 passed**, 15 exporter
  warnings,25.79s: `native_nav/python_full_review.log`.
- Integrated Android: `.venv/bin/python tools/run_android.py --log
  logs/native_20260920_01/review_matcher_green01.log -- :core:test
  :app:testDebugUnitTest :app:assembleDebug`, exit0, **82 core+38 app tests**, no
  failures/errors/skips; fresh XML and unchanged source hashes in its `.evidence/`.
- Doctor: `model_doctor.log`, exit0, arm64 Python3.12.14/torch2.14.0, actual
  11,554-parameter CNN-GRU forward/backward on MPS. Cold1.405s is not epoch timing.
- Candidate02 install: `device/candidate02_install_retry.log`, Success; one prior
  ordinary-install verification failure retained. No security setting bypassed.
  APK SHA256 `2e435132f5159fe0f30d4ad06bd53ee86fd527f233993124426852641676586c`.
- Phone capture/export: `device/sensor_bench02_summary.json`; raw ZIP/acquisition
  hash under `data/raw/phone_sessions_20260920/`. All317 model events invalid with
  zero invocations. Export chooser observed and cancelled without sending.
- Runtime/offline: `device/onnx_instrumentation01.log`,
  `device/synthetic_mobile_parity.json`, `device/offline_instrumentation01.log`,
  `device/offline_device_report.json`, `device/offline_radio_steps01.json`.

Gradle builds are serialized with immutable source/XML evidence. Later source
changes require a new build record; older counts remain historical. No training
is active. Private recordings/coordinates are excluded from portable handoffs.

## Remaining gates

1. Obtain source-backed axes/frame, direction and shared-clock/freshness evidence,
   or collect documented forward-driving phone sessions with independent splits.
   The public IO-VNBD files were obtained; guessing metadata cannot resolve this.
2. Prepare eligible real data, compare models/baselines on validation, calibrate
   only on calibration, freeze an approved model/manifest, run its Android parity.
   Keep final test closed until freeze. No safe command can bypass missing evidence.
3. Follow `docs/FIELD_TEST_PROTOCOL.md` for safely mounted real drives and isolated
   outage evaluation. The installed logger is ready; software cannot drive the car.
4. Finish full airplane-mode, sustained/budget-phone and drift/recovery measurements;
   preserve failures before judging MVP/SIH acceptance.

Next local verification: `.venv/bin/python tools/run_android.py --log
logs/native_20260920_01/next_verification.log -- :core:test
:app:testDebugUnitTest :app:assembleDebug`. Final delivery additions follow below.

## Final frozen build

`edge_probe/final_combined.log`: exit0, **87 core + 38 app tests**, zero failures,
errors or skips. All XML fresh and source hashes unchanged; root independently
checked every recorded hash in `final_source_check.json`. This includes the
edge adapter and clearer stopped-session Judge labels. Full Python remains569
passed; the reviewed Python source hashes still match.

Final APK: `handoffs/DRIFTLOCK_Native_Engineering_2026-09-20/driftlock-engineering.apk`,
36,513,244 bytes, SHA256
`60d526a35a3cad822214a88eb5603fd1116136937fbdff5c1b8afa7d5b9e07a7`.
Build: `.venv/bin/python tools/run_android.py --log
logs/native_20260920_01/edge_probe/final_combined.log -- :core:test
:app:testDebugUnitTest :app:assembleDebug`.

The separate synthetic Mac edge probe delivered5,000 timed samples with virtual
200Hz timestamps; p95 adapter+engine processing0.021792ms. It is neither a
real-time hardware scheduler nor an external FOG device/accuracy result.
Phone sensor performance remains bound to candidate02 as documented; the final
APK adds the unused separate edge boundary and stopped-screen copy only.

Final APK installation succeeded (`device/final_install.log`); reading the installed
package's own base APK SHA256 independently matches the delivered APK
(`device/installed_apk_hash.json`). OEM ordinary installation confirmation was
used; no security/verification setting was disabled. Sensor recording is stopped.

## Portable engineering delivery

Package: `handoffs/DRIFTLOCK_Native_Engineering_2026-09-20.zip` (28,538,929 bytes;410 payload files).
SHA256 `0ae0cadc0333305ecb6c25b54e55fe16bec1250893daed867ee091ee180b5131`. ZIP CRC and every payload SHA256 passed,
exit0; evidence `logs/native_20260920_01/package_verification.json`.
Includes final APK, current source, build/run instructions and scoped evidence;
excludes private recordings, raw IO-VNBD corpus, toolchains and signing key.
Final phone launch Status:ok (`device/final_launch.log`); left at Home with
recording stopped. Original radio settings restored. No training/build remains
active. Local engineering delivery is complete; production ML and physical field
acceptance remain BLOCKED/NOT STARTED as listed above.
