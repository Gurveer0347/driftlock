# Official SIH26168 requirements

Status: **OFFICIAL SOURCE VERIFIED — 20 September 2026**. This is a requirements
record, not a claim that DRIFTLOCK passes any requirement.

## Authoritative source and preserved wording

The [official SIH 2026 portal](https://sih.gov.in/sih2026PS), filtered to SIH26168,
opens the problem-details modal `ViewProblemStatement26168`. Its exact title is
“AI-ML based Intelligent Dead Reckoning system for seamless navigation”.
The issuer is ISRO / Department of Space; category Software; theme Smart Vehicles.
The displayed submission deadline was 30 September 2026. No separate publication
or revision date was displayed for this statement.

Access: 2026-09-20, starting **11:14:11 UTC / 16:44:11 IST**. Ordinary local HTTP
returned 200, and an independent browser visit, table search and modal opening
showed the same statement. The web retrieval service returned 403; this was a
retrieval-path limitation, not an inaccessible official source.

The complete source wording is preserved in the downloaded evidence rather than
silently rewritten:

- [Full official response](../logs/native_20260920_01/official/http_01.body).
- [Exact statement modal HTML](../logs/native_20260920_01/official/SIH26168_modal.html).
- [Extracted wording](../logs/native_20260920_01/official/SIH26168_modal_text.txt).
- [Retrieval manifest, commands, HTTP status and hashes](../logs/native_20260920_01/official/http_attempts.json).
- [Evidence and verification notes](../logs/native_20260920_01/official/README.md).

The original contains text-encoding artifacts. The raw files retain them; the
text extraction retains entities as written in the HTML. Use the raw response
for an exact byte-level comparison. The extract is supporting evidence for this
document and is not a rewritten problem statement.

## Requirement index

This compact index paraphrases the official statement. The source wording above
controls if a shortened entry is ambiguous. IDs are local traceability labels.

| ID | Official requirement / capability |
|---|---|
| PS-01 | Working phone application and deployable edge engine; external-IMU compatibility. |
| PS-02 | Standalone-phone navigation without vehicle-computer connections or external speedometer dependence. |
| PS-03 | AI/ML forward-speed and acceleration estimation from accelerometer/gyro; reject vibration, shocks and misalignment. |
| PS-04 | Automatic vehicle-relative pitch, roll and yaw calibration for dashboard/holder mounting. |
| PS-05 | Offline road constraints and map matching during outages. |
| PS-06 | AI-based GNSS/INS fusion improving position and velocity. |
| PS-07 | Millisecond-scale transitions into dead reckoning and back. |
| PS-08 | Smooth, continuous vehicle-position display; lane-level accuracy objective. |
| PS-09 | Desktop/cloud training; lightweight exported model executing locally with live accelerometer, gyro, compass and available GNSS. |
| PS-10 | Noise/bias removal, correction prediction and continuous positioning. |
| PS-11 | IO-VNBD training/testing; proposal includes preliminary models and subset position plots; additional screening datasets anticipated. |
| PS-12 | Blackout positional drift below 10% of travelled distance. |
| PS-13 | Smartphone position updates at 10 Hz; external FOG-IMU edge processing approximately 200 Hz. |
| PS-14 | Bring trained models to finale; downloaded maps permitted. |

Benchmark examples: under 5 m error over 50 m in under one minute, or under
100 m over 1 km at 60 km/h; GNSS-unavailable simulated environments are mentioned.
The exact threshold phrase is “less than 10% of the total distance travelled”.

## Interpretation and engineering decisions

These notes are DRIFTLOCK interpretations and verification recommendations. They
are not additional official quotations, and they do not establish compliance.

- **Two deployment paths:** keep the Android app smartphone-only. Treat an
  external-sensor adapter and separately tested edge execution path as an
  additional deliverable. Requiring external hardware to run the phone app would
  conflict with the standalone-phone goal. Conversely, forbidding all external
  IMU support in the entire codebase would omit PS-01. The user's phone-runtime
  input restrictions and the separate edge deliverable can both be respected.
- **No algorithm mandate from examples:** OpenStreetMap, NHC, UKF and HMM appear
  as examples in the source. They are not a requirement to replace the existing
  estimator with a particular filter family. DRIFTLOCK's chosen architecture
  still needs evidence for each capability, including its AI contribution.
- **Measure navigation, not only speed:** speed MAE alone cannot establish
  PS-12. A reproducible evaluator should report outage distance, position error,
  error/distance ratio, duration, reference quality and selected metric. The
  benchmark protocol must predeclare whether it assesses endpoint, maximum,
  cross-track or other position error; the source does not fully specify that
  aggregation. Do not silently use the most favourable one.
- **Retain the lane-level objective:** neither road snapping nor passing the
  percentage-distance criterion proves lane identification. Keep lateral/lane
  evidence separate, and report ambiguity when the road/reference data cannot
  distinguish lanes. Do not translate this wording into an invented metre
  threshold or claim it is already achieved.
- **Timing requires device evidence:** test navigation output frequency and
  transition delay on the actual phone. Model inference duration and UI refresh
  rate do not individually establish PS-07 or PS-13. Test the edge target on its
  declared hardware and sensor input separately; a 10 Hz smartphone replay is
  not evidence of approximately 200 Hz edge processing.
- **Data naming does not settle data semantics:** naming IO-VNBD in the official
  source does not establish this repository's units, frames, clock pairing,
  reference quality, group independence or label meaning. Preserve the data
  audit, no-truth-leakage rules, calibration separation and final-test reservation.
  Do not convert an exploratory checkpoint into a navigation-approved model.
- **The proposal artifact is positional:** an IO-VNBD position plot must disclose
  the data subset, preprocessing, actual model invocation and estimator inputs.
  A speed curve or synthetic navigation demo should not be relabelled as that
  required artifact.
- **Examples stay examples:** retain both distance scenarios in test planning
  without presenting them as the only allowed evaluation cases. A simulation
  must remain labelled; it cannot establish measured real-phone performance.

## User scope, not verified SIH eligibility constraints

The supplied implementation brief additionally requests native Kotlin/Compose,
ShadowDR, uncertainty gating, confidence states, trip packs, alternate routes,
blocked-road controls, offline A*/Dijkstra, airplane-mode testing, ONNX Runtime,
and budget/mid-range-phone targets. Track these as user requirements in addition
to the PS index. Do not describe the particular libraries, feature names, RAM,
model-size or inference-latency targets as official SIH rules without a separate
official citation.

This recheck did not verify general entrant eligibility, team composition,
selection odds, submission caps or administrative submission rules. The visible
table counter is transient portal metadata, not an acceptance probability. No
team or event rules were inferred from SIH2025 or any SIH250xx statement.

Only the official SIH source establishes the requirement index above. Search
results from repositories and third-party catalogues were discovery leads and
were not used as authority. An ISRO-site search did not produce a second copy
of this specific statement; that does not invalidate the successful official SIH
retrieval.
