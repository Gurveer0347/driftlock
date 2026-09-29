# DRIFTLOCK two-day judge demo — PRD

**Document date:** 27 September 2026  
**Purpose:** define a short, honest, judge-ready demonstration using the existing native Android app and the separate IO-VNBD research model.  
**Status:** implemented as a judge-facing engineering build on 28 September 2026;
the actual tested behavior, including the what-if road-closure limit, is in
[STATUS.md](../reports/STATUS.md) and the [presenter guide](DEMO_GUIDE.md).

## Product goal

Give judges a simple way to see what DRIFTLOCK is intended to do when satellite navigation becomes unreliable: prepare a journey from locally available roads, keep a navigation estimate running during a clearly marked simulated GNSS outage, and choose a legal alternate route after a road blockage. Then show Gurveer's trained research model in a separate screen and explain, in plain language and with its actual results, why it is not connected to live navigation yet.

This is a two-day prototype polish target. It is not a claim that the full SIH system, production odometer, real-road accuracy, or blackout drift requirement has passed.

## Audience and demo setting

The primary audience is a judge seeing DRIFTLOCK for the first time. The demo should work as a rehearsed, local Android-phone presentation with no account or cloud service. Use the bundled/cached Chandigarh road area for the dependable route flow. Label controlled GNSS loss and generated motion as **simulated**; label phone sensor capture as **live phone sensors** when that is what is actually running. Do not mix their data or labels.

## Main experience: route and recovery demonstration

1. **Choose the journey.** Show the available local map coverage. Let the presenter pick a start road and destination on the map. The current app supports map taps in its downloaded area; worldwide address search is outside this two-day scope.
2. **Compare routes.** Show up to three routes that the local road graph actually finds. Mark the shortest by measured graph distance. Follow real one-way and supported turn restrictions. Show free-flow ETA only when the source roads support it, and label it as an estimate rather than live traffic.
3. **Prepare offline.** Save the selected route and its available alternatives in the local trip pack. Show the real saved state and measured pack size. Do not call an already bundled map a new download. If online map fetch is unavailable, continue with the bundled area and say so plainly.
4. **Start the guided run.** Provide a clear choice between a controlled demo replay and live phone capture. The guided judge path uses the deterministic replay so the presentation is repeatable. Keep a visible **SIMULATED** badge throughout that replay.
5. **Demonstrate GNSS loss.** In the controlled replay, show the GNSS state changing to unavailable while the phone-motion estimator continues to update the estimated marker from its actual replayed inputs. Show the estimator's own uncertainty when available. Never substitute the synthetic reference/ground-truth path for the estimated marker. State that the replay demonstrates software behavior, not real-road accuracy.
6. **Report a road blockage.** Let the presenter block a named upcoming physical road segment. Recalculate from a supported current/last-known route point using the cached graph, excluding both directions of that physical segment. Show the alternate route and the additional distance when known. If the current position or graph is ambiguous, ask for a supported choice or show that no route is established; do not invent a U-turn, teleport, or path through restricted roads.
7. **Recover.** Restore the replayed GNSS stream and show its recovery state. The app must distinguish a returned GNSS fix from a verified navigation-accuracy result.
8. **Explain the outcome.** End with a short session summary containing only values measured from that run: route distance, saved-pack state, simulated outage interval, blockage and selected alternate. Do not report blackout drift or positioning accuracy unless it was measured against a qualified reference and clearly scoped.

## Separate experience: ML research model

Provide a distinct **Research model** screen or tab. It must not be reachable as a hidden source of live Driver speed and must not feed route selection, the ESEKF, map matching, or the navigation display.

Explain it simply: “This is a trained experiment that reads six recorded phone-motion channels and predicts the dataset's recorded GPS-speed number. The source does not let us prove that number is correctly timed, in metres per second, or signed for forward and reverse travel. We therefore keep the model out of live navigation.”

The screen may show the existing 40-sample CNN-GRU checkpoint, its six input channels, the dataset/source, validation plot, and recorded experiment results. Preserve their original labels and values:

- Validation RMSE: **4.88636 recorded reference units** on 78,931 windows. The reference unit is unresolved; this is not an m/s result.
- On the reviewed nominal 30/60-second blackout cut cases, average case RMSE was **4.47170** for the model and **2.30295** for holding the last recorded pre-cut value. Lower is better, so the model lost to this simple baseline on those cases.
- The model's uncertainty (`sigma`) is a standard deviation in the same unresolved recorded-reference units. It is not GPS-position uncertainty, navigation confidence, or metres per second.
- The research artifact reports `valid_for_navigation=false`; the production Android model invocation count is **zero** in the latest recorded status.

Put the plain-language explanation first. Put architecture, split, metric definitions, checkpoint identity, and caveats in a judge-details panel. Label all figures **research result — recorded units, not verified SIH performance**. Do not change units, round selectively, suppress the baseline, show reserved final-test results, or invent an accuracy percentage.

## Why the model is not in live navigation

Show these as concrete unresolved checks, not vague “AI limitations”:

1. The official files do not establish all phone-axis/frame transformations or enough timing evidence between phone motion and vehicle references.
2. Speed units, reference-fix freshness, and parts of the label meaning remain unresolved. A speed-magnitude label cannot establish reverse direction or signed vehicle-forward speed.
3. The existing exploratory model predicts an unchanged recorded number; it is not a verified signed-m/s model. Its sigma is also in recorded units.
4. On the tested blackout cuts, holding the last reference value performed better than the model.
5. No research-model Android/navigation parity or approved production model exists. The app correctly rejects research and synthetic artifacts for production use.

The message to judges is that DRIFTLOCK has a trained ML research prototype, and the team has deliberately kept it out of live navigation until labels, timing, baseline performance, calibration, and on-device behavior are supported by evidence.

## Visual and interaction direction

### Approved visual reference — Option A

![Approved Option A route-selection visual reference](assets/route_selection_option_a.png)

Use its warm cream and sage palette, forest-green primary action, generous route cards, and map-first hierarchy as the design reference. The generated illustration is only a style/layout reference: its roads, terrain, landmarks, routes, distances, time estimates, and offline-area size are not real DRIFTLOCK data and must not appear as the app's actual map.

### Map realism and 3D treatment

The map should look as realistic as the available, locally stored map data allows. Keep its geometry truthful:

- Draw road shapes and route lines from the actual downloaded OSM graph. The selected route must follow the exact legal graph edges computed by the router, and map labels must come from available source tags.
- The bundled Chandigarh OSM file contains real road geometry and some building, park, and waterway footprints. The current routing importer and native map renderer use the road graph and flat road strokes; they do not provide a complete 3D city, terrain surface, or measured building heights. See [OFFLINE_ROUTING.md](OFFLINE_ROUTING.md).
- For a 3D-style view, prefer a local 2.5D rendering of actual OSM footprints: a tilted ground plane, actual road geometry, and restrained building/park/water shapes where present in the downloaded source. Keep the renderer offline and preserve OpenStreetMap attribution.
- Use actual building height/level or terrain elevation only where those values are present and their meaning is clear. Where height is missing, leave footprints flat or use a modest, visibly illustrative extrusion; do not imply it is measured height. Do not add invented hills, landmarks, roads, building footprints, or blockage positions.
- The generated reference must never be used as a map texture or as navigation input. When local source data cannot support convincing 3D detail, preserve accurate roads and use restrained depth/shading instead of fabricating geographic detail.

Keep the existing DRIFTLOCK green/cream identity, clear typography, consistent spacing, purposeful cards, and restrained transitions. Use distinct labels for **Live phone**, **Simulated demo**, **GNSS unavailable**, and **Research model**. Avoid decorative gauges, fake confidence percentages, unexplained acronyms, fabricated download progress, or animated movement driven by truth data. Every screen should make the current data source obvious without requiring a presenter to explain hidden states.

## Scope and exclusions

**In scope:** improve the existing Compose screens and guided replay; make destination, route alternatives, local trip-pack save/reopen, controlled GNSS outage/recovery and roadblock rerouting easy to present; add or improve the separate research-model explanation; fix defects encountered in this flow; run existing tests and verify the result on the connected Android phone when available; update the demo guide and status with evidence.

**Out of scope:** changing the ESEKF or model architecture; resolving source semantics by assumption; making new training labels or changing the research target; using the model in Driver/navigation; opening or tuning on the reserved final test set; claiming real-trip drift, lane-level accuracy, Android ML parity, or the official <10% drift objective; worldwide geocoding, live traffic, backend accounts, automatic hazard feeds, or a replacement map service.

## Acceptance criteria

- A new presenter can complete destination → route comparison → local save → GNSS-loss replay → roadblock → legal alternate → recovery without developer tools or network access to the bundled area.
- The shortest route is selected by actual graph distance, and the app shows fewer than three choices when fewer legal paths exist.
- The replay continues to display the estimator output during GNSS loss; ground truth is never drawn or described as the estimate.
- A blocked physical segment is excluded in both directions. One-way restrictions and no-path/ambiguous-position cases remain honest and safe.
- The displayed streets and selected route match actual downloaded OSM geometry; 3D detail uses only available mapped features and does not present unknown heights or terrain as measured facts.
- Every simulated state stays visibly marked simulated; live sensor capture is identified separately.
- The research page shows the existing model and baseline results in recorded units, states why it is excluded from navigation, and makes no m/s or accuracy claim.
- Research and simulated models remain rejected by the production runtime; no increase in production model invocation count is implied.
- Relevant Python/Kotlin tests and the Android build pass. The updated APK is installed and the acceptance flow is checked on the phone when the device is available; otherwise the device check is recorded as pending.
- `reports/STATUS.md` and the demo guide identify exactly what was tested, what was simulated, and what remains unverified.

## Evidence baseline

The latest detailed native status currently in the project is dated 20 September 2026. It reports an installed OnePlus engineering app, route preparation/save, scoped physical offline-routing checks, and passing native/Python suites, while production ML and real-drive accuracy remain blocked. Before implementation, check for newer logs, builds, or user-provided changes and treat the newest evidence as authoritative. This PRD itself is a target, not proof that any acceptance criterion has been met.
