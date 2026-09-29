# Implementation prompt — DRIFTLOCK two-day judge demo

Use this prompt with the project open at `/Users/gurveersingh/DRIFTLOCK_ML_Codex`.

---

You are implementing the two-day DRIFTLOCK judge demo described in `docs/TWO_DAY_DEMO_PRD.md`. Work in the existing native Android app and preserve the separate IO-VNBD research-model experience. The goal is a reliable, polished presentation of what is implemented, not a claim of production navigation accuracy.

## Before editing

Read the current `AGENTS.md`, `PROJECT_CONTEXT.md`, `CODEX_START_HERE.md`, `README.md`, `docs/PRD.md`, `docs/CONVENTIONS.md`, `docs/DATA_CONTRACT.md`, `docs/ANDROID_ARCHITECTURE.md`, `docs/OFFLINE_ROUTING.md`, `docs/DEMO_GUIDE.md`, `docs/MODEL_CARD.md`, `reports/STATUS.md`, and `reports/FINAL_STATUS.md`. Inspect the actual app screens, demo controller, route/trip code, model gate, tests, current phone connection, and newest build/evidence logs. Do not assume the status snapshot is still current.

Keep all work project-local and use the existing Mac/Android toolchain. Do not install Linux, Docker, a VM, system packages, or unnecessary product dependencies. Preserve raw files, model artifacts, reserved test groups, and original handoffs. Do not upload data, send messages, spend money, or push code.

## Build this experience

1. Make the existing map-first route flow easy to present: select a start and destination within actual downloaded coverage, show up to three legal routes, identify the shortest by actual graph length, and save/reopen the local trip pack with real coverage and size information.
2. Make the guided GNSS outage replay repeatable and clearly simulated. The moving estimated marker must come from the navigation estimator's outputs, never the replay's hidden/reference truth. Show the actual GNSS state and available estimator uncertainty; do not imply the replay proves field drift.
3. Make the roadblock flow name the upcoming physical segment, exclude both directions, and select a legal connected alternative from a supported route point. Respect one-way roads and turn restrictions. When the current point or graph cannot support a safe route, show the real limitation instead of inventing a reversal or path.
4. Show GNSS recovery as a state transition, not as proof of positional accuracy. End with a small factual run summary.
5. Keep research ML in a clearly separate screen. Explain the trained CNN-GRU, six phone-motion inputs, unresolved recorded target units/timing/frame/direction, the held-cut comparison where the model lost to hold-last, recorded-unit sigma, `valid_for_navigation=false`, and zero approved production use. Display exact existing metrics only with their original units and provenance. Never feed this model into navigation, routes, or live Driver speed.
6. Follow the approved Option A reference at `docs/assets/route_selection_option_a.png`: warm cream/sage surfaces, forest-green action, map-first hierarchy, generous route cards, and calm premium typography. Treat it as visual direction only; do not use its generated map art as geographic data.
7. Make the map as geographically faithful as available local data allows. Render route lines from the exact OSM graph edges selected by the router. If adding a 3D/2.5D view, use the existing Compose renderer and actual downloaded OSM roads plus available building/park/water footprints; preserve offline behavior and OSM attribution. The current renderer is flat and current map data do not provide comprehensive elevations/heights, so do not invent hills, landmarks, streets, building footprints, or blockage locations. Use real height/elevation only when present and understood; otherwise keep buildings flat or use clearly illustrative, conservative massing. Do not add a large mapping SDK or network dependency for this two-day polish.
8. Keep the UI readable and restrained with consistent spacing, purposeful cards, subtle animation, and always-visible source/state labels. Do not rebuild the app or introduce decorative/fabricated metrics.

## Scientific and product gates

- Preserve the active model/runtime contract and fail-closed production model gate. Do not change architecture, channels, targets, label semantics, split policy, or model allowlists.
- Do not reinterpret GPS units, fix freshness, axes, clock alignment, direction, or source-frame transformations. A new source ambiguity is a blocker to source-dependent claims; continue independent UI work and document the precise issue.
- Keep the final-test reservation intact. Do not retrain, tune, or select using final-test groups.
- Never call the research checkpoint a production odometer, signed m/s output, navigation confidence, positioning accuracy, or an explanation for the app's estimated marker.
- Mark separately which behavior was verified on a physical phone, which ran from the deterministic simulated replay, and which is not implemented or unverified. Distinguish real mapped geometry from illustrative 3D height styling in the UI or presenter guide.
- Do not claim the official <10% drift target, safe real-world use, lane-level accuracy, full Android parity, or model effectiveness without the required measured evidence.

## Work and verification

First reproduce any defect before changing it. Add a regression test for a reproduced bug, make the smallest focused fix, and rerun the relevant test suite. Preserve full logs and exit statuses. Serialize Android builds; never start simultaneous Gradle builds. If a long operation is proposed, measure a bounded probe and record its size/time before starting it.

Verify at minimum the affected Kotlin/core tests, app unit tests, full existing Python tests if shared behavior changes, and a fresh debug APK build using the repository's documented runner. On the phone, rehearse the full route → save → simulated GNSS loss → block road → alternate → recovery sequence when a device is available. Verify airplane-mode route-pack reopening where the existing protocol allows it. Inspect the final screen flow and confirm every source badge stays accurate. If hardware is unavailable, leave the physical check pending; do not substitute a synthetic pass.

Update `docs/DEMO_GUIDE.md` with a short presenter script and update `reports/STATUS.md` and `reports/FINAL_STATUS.md` only with newly verified evidence. Capture the command, exit code, build/APK identity, tests, phone/manual steps, and the exact limitations. Keep existing disappointing model comparisons visible. Package a fresh APK or handoff only if the build and checks actually pass; preserve earlier deliveries.

Finish with a concise report that says what judges can see, what was tested on the phone, what remains simulated, what the ML model's real limits are, and how to launch the demo. Do not say “complete” if a listed acceptance gate remains pending.

---

**Source of truth:** `docs/TWO_DAY_DEMO_PRD.md` defines the demo scope. Project authority and data integrity rules in `AGENTS.md` take precedence if any conflict appears.
