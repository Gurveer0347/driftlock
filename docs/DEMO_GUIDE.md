# DRIFTLOCK — judge demo presenter guide

This is a **guided, time-compressed scenario** on real, locally saved OpenStreetMap
roads. The green scene vehicle follows a scripted mapped route. The blue marker
is the separate native sensor estimator consuming generated phone IMU/GNSS
samples. The trained CNN–GRU research model has its own screen and does not
drive navigation.

The current build uses flat, translucent glass: green tint, subtle edges
and readable text without reflection stripes, bright rims or backdrop blur. The Home region slab expands into the
map; pages, story sheets, selections, details, zoom, follow and vehicle heading
transition smoothly. Use pinch/drag to explore, **+ / −** to zoom and the target
button to follow the scene car again. The short two-route choice and the
roadblock action remain visible without scrolling.

## Separate recorded-trip design preview

**DRIFTLOCK Preview** is installed alongside DRIFTLOCK. Open it to inspect the
recorded-journey layout using generated samples. Its persistent **DESIGN PREVIEW**
badge and visible sample-data caption identify the mockup. The recorded-trip
wording is proposed interface copy; this is not a captured real journey or field
evidence. The main DRIFTLOCK app retains its simulated-source labels.

Use the same Home → saved Chandigarh region → Preview guided journey → Shortest
sequence in either app. Sensor generation, routing, estimator, research model
separation and the roadblock sequence are unchanged. Both apps use the cleaner
surfaces. The separate application identity preserves the main app's saved data.

It is an optimized native prototype build. On Gurveer's OnePlus the approved
preference is **High → DRIFTLOCK: 120 Hz**, but measured delivery remained about
60 fps. Do not promise sustained 120 fps. Frame results and exact device evidence
are in `reports/GLASS_MOTION_REPORT.md`.

## The 90-second presentation path

1. On **Home**, tap **Open saved Chandigarh region**. This opens the region
   bundled with the app; it does not place the physical phone in Chandigarh.
2. On **Routes**, tap **Preview guided journey**. The app shows two routes
   computed from the locally stored road graph. Tap **SHORTEST · START DRIVE**.
   The app saves the routes locally and starts the scenario immediately.
3. Let the map play. The green scene vehicle moves along mapped streets. At
   about **8 seconds**, **GNSS OFFLINE** appears: GNSS samples are withheld from
   the native estimator while the scripted journey and offline map continue.
4. At about **17 seconds**, the road ahead closes and the run pauses. Tap
   **Take offline alternative**. The app uses a connected detour that excludes
   the blocked road; the scene vehicle turns back through mapped streets and
   continues on the orange route while GNSS remains withheld. Generated sensor
   acquisition and receipt time use a virtual replay clock: your pause freezes
   that clock, so it does not create a sensor gap or stale synthetic GNSS fix.
5. Near arrival, GNSS samples return automatically. The view shows **GNSS
   ONLINE**, then **Destination reached**. Use **+**, **−**, pinch, drag and the
   follow button to inspect the map. **Sensor details** explains the two
   markers and displays native-estimator status.
6. If asked about ML, open **Research**. Show the trained CNN–GRU validation
   plot and explain that GPS speed is only its recorded training reference,
   never an input. The experiment remains separate from navigation because the
   dataset's label units, timing and direction are unresolved.

The mapped closure is a presenter-controlled scenario, not live automatic
roadblock detection. The saved road graph provides the route and alternative
without a network request during the run. The map's footprint depth is
illustrative; mapped streets and available building outlines are from OSM.

## One short script

“We prepare multiple routes from roads stored on the phone. Here we choose the
shortest and start a controlled drive. When GNSS drops, the native estimator
continues consuming phone-motion samples and the saved map stays available.
Now a road is blocked: we choose a connected alternate locally, turn back on
mapped streets and continue. GNSS returns near the destination. The green car
is the guided scenario; the blue dot shows the separate estimator. Our ML
research screen shows a genuinely trained speed model, with its limitations
kept separate from this navigation demonstration.”

## If judges ask for proof or limits

- **Real drive?** This guided run uses generated sensor samples. It demonstrates
  software behavior, not measured road accuracy or the SIH drift target.
- **Are the roads real?** The road geometry, names, alternatives and mapped
  footprints are from the bundled OSM extract. The drawn perspective is an
  illustration; no building height or terrain measurements are claimed.
- **Does the green car show measured position?** It is the scripted scene path.
  The blue marker is the estimator output. Open **Sensor details** to show this
  distinction and position uncertainty.
- **Is ML already providing live speed?** No. The research model is trained on
  official IO-VNBD smartphone recordings, but its recorded-reference speed
  label cannot yet be certified as a timed, signed m/s vehicle-speed target.
  On the documented held-cut validation cases, holding the last recorded speed
  beat the model; do not quote its RMSE as navigation accuracy.
- **Offline proof?** Routes are saved to a local trip pack. **More → Packs** can
  reload the pack with airplane mode enabled; this physical check was performed
  on the OnePlus. Restore the radio setting after showing it.

## Reset

Tap **Replay** to run the same guided sequence again. The roadblock waits for
your choice, so you can pause the narration there. If starting from a fresh
install, follow Home → Routes → Preview guided journey → Shortest. Keep the
phone charged and present while parked or with a passenger operating it.

Detailed source, test and limitation evidence: `reports/STATUS.md` and
`reports/FINAL_STATUS.md`.
