# Clean surfaces and recorded-trip design preview — 28 September 2026

The native Android UI now uses flat translucent tint, restrained edges and light
shadows. Decorative diagonal reflections, sheen overlays, bright white rims and
button gradients were removed. The green Home card, route artwork, text styling,
shared card-to-map transition, page animations and map controls remain.

Gurveer requested a non-final preview of a recorded-journey presentation using
generated samples. This is isolated in **DRIFTLOCK Preview**, application id
`in.driftlock.ui.preview`. Its persistent **DESIGN PREVIEW** badge identifies the
mockup; Home and the guided map visibly disclose generated sample data. The map
caption is placed in its header so the bottom navigation cannot obscure it.
“Recorded trip” is proposed layout copy, not a claim that this generated drive
was captured on the road. The main DRIFTLOCK app retains its simulated labels.

Debug and release have `RECORDED_TRIP_PREVIEW=false`; only the separate preview
build has it true. Its launcher label and FileProvider authority use the separate
application identity. Both optimized APKs retain the existing local development
signing configuration; this is not a Play Store release. Main-app data is retained.

## Verification

Final command, exit 0:

```text
.venv/bin/python tools/run_android.py --log logs/clean_preview_20260928_01/disclosure_final_build.log -- :core:test :app:testDebugUnitTest :app:assembleRelease :app:assemblePreview
```

**87 core + 80 app tests**, zero failures/errors/skips. The runner records all
115 source-input hashes; no input changed during the final build. Android XML
test results and exact commands are preserved beside the log. Existing core
results were reused by Gradle; app tests ran after the layout correction.

New Compose checks cover preview identity, ordinary-demo source labels and
pairing recorded-trip layout copy with generated-source disclosure. Physical
inspection found the first caption below the fold. A stricter map-header bounds
assertion failed before the correction (`disclosure_red.log`, exit 1) and passed
in the final suite. Initial compile failures and intermediate builds remain
preserved rather than being presented as final evidence.

| Build | Bytes | SHA256 |
|---|---:|---|
| Clean regular demo | 19,927,701 | `7958d461ae0a86480fe7911c11fb84b95a286665941bdd9894985aad55fb2bd9` |
| Design preview | 19,927,717 | `fb284450867cd9af4b8073a4c40700945f95273942347266f244a6b4b0e537f6` |

Both installations exited 0. OnePlus's ordinary **No risks found → Continue
installation** confirmation was used when shown. Installation verification was
not disabled. Logs: `release_final_install.*` and `preview_final_install.*`.

The initial physical preview walkthrough passed both route choices → shortest
→ GNSS cut → roadblock → ten-second presenter pause → offline turn-back/detour
→ signal recovery → arrival → zoom/drag/follow. The separate Research page showed
zero Android speed-model calls. The final walkthrough repeated the full sequence after the caption correction,
exit 0. Final screenshots and receipts are under
`logs/clean_preview_20260928_01/phone_final/`; its source caption was confirmed
inside the map header at pixel bounds [160,804,1053,868]. Both installed APK
hashes match the built files and neither is debuggable. The app is left at
Preview Home with replay revoked and real capture stopped. Airplane mode is off
and original auto-rotation remains enabled. Exact final receipt:
`logs/clean_preview_20260928_01/device_final_check.json`. The earlier walkthrough
remains separately preserved in `phone_preview/`.

An independent read-only review found no important issues in the build identities,
source labels or isolation. The native controller is byte-identical to v4. These
changes do not alter sensor generation, routing, estimator, training data, model,
uncertainty meaning or final-test reservation. No training or new data acquisition
was performed. Earlier v4 deliveries and research evidence remain unchanged.

## Limits

This change supplies interface and software verification. It does not supply a
real recorded drive, measured navigation drift, live ML speed, Android model
parity or SIH acceptance evidence. The green scene marker remains scripted and
the blue marker remains the separate estimator on generated inputs.

No new frame benchmark was run for this styling revision. The earlier v4 report
records actual delivery near 60 Hz despite the approved 120 Hz preference and
app request. A requested rate is not a sustained-120-fps result. Existing phone
refresh preferences were retained; the new preview app's per-app preference was
not separately changed.

Open **DRIFTLOCK Preview → Open saved Chandigarh region → Preview guided journey
→ Shortest** to inspect the layout. Open **DRIFTLOCK** for the regular simulated
demonstration. Both use the cleaner surfaces.
