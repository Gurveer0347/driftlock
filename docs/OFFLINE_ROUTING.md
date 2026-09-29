# Offline roads, matching and route continuity

Native implementation: `android/core/.../routing` and `matching`; Android trip
index: `android/app/.../trips`. These modules do not consume evaluator truth.

## Independent map source

The bundled Chandigarh XML is a preserved OpenStreetMap API response, downloaded
20 September 2026. Exact URL, date, bytes and SHA256 are in
`data/raw/osm_20260920/manifest.json`. It imports to 1,443 drivable nodes and 2,717
directed edges. The app includes the same original bytes and attribution. Loading
the sample region does not create a phone position there.

Users can request a small rectangular region by latitude/longitude. Only that
rectangle is sent to OSM. XML is bounded to 20 MB, rejects external entities and
HTML/errors, and requires explicit bounds. The bundled region's 2,329,732 bytes
are known before saving. A new region's size is unknown until the request
completes, and is displayed honestly as unknown with a cap, not a fabricated
estimate. There is no worldwide map, live traffic feed or automatic hazard feed.

The importer obeys directed roads, motor-vehicle access, barriers, and supported
via-node no/only turn restrictions. Conditional/via-way restrictions and missing
members conservatively exclude affected ways. Unsupported details are warnings.
Map tags can be wrong/outdated; an algorithmically legal path is not a guarantee
that a real road is currently safe/open. Coordinate projection is a local
equirectangular map projection, not an exact ECEF/ENU navigation transform.

## Routes and road matching

Dijkstra retains incoming-edge state for restrictions. Paths include every edge;
a start inside an edge keeps its remaining fraction and direction. A passed
junction is never used as an invented immediate reverse connection. Alternatives
use physical-edge overlap <=75% and length <=2.5 times shortest, up to three. A
graph with fewer alternatives shows fewer. ETA exists only when all used edge
speed limits exist and is a free-flow arithmetic estimate, not live traffic.

The causal HMM retains up to 12 hypotheses, with absolute position and heading
gates before normalization and directed-route transitions. A dominant posterior
>=0.85 and margin >=0.2 permits a committed road; otherwise it abstains. This is
a hypothesis ranking, not calibrated position confidence. Matching runs on a
separate worker at up to 2 Hz; the estimator continues independently. Map feedback
is disabled pending ablation evidence.

## Trip lifecycle and rerouting

Save stores original XML, SHA256, routes, destination and blocked physical
segments in an atomic local directory. Reload verifies bytes and complete path
connectivity/restrictions. Room stores a low-rate, rebuildable trip index; raw IMU
never enters Room. Pack sizes are measured from files. Delete removes only the
explicitly selected app trip pack. Invalid hashes prevent use.

The Route Blocked control explicitly names the upcoming matched segment. Both
directions of that physical segment are excluded for that trip. It first tries a
connected precomputed alternative, then searches cached roads. If the *current*
segment itself is blocked before its endpoint, the router cannot assume a legal
mid-road U-turn; it returns no established connection. It can route after a
subsequent legal reversal is actually observed. Missing roads or disconnected
paths show NO DOWNLOADED ROUTE AVAILABLE.

Three committed off-route observations spanning >=1.5 s trigger local rerouting.
Ambiguous matches clear current-road authority; no rerouting is based on a stale
old road. Manual Change Route excludes an upcoming segment temporarily to seek
a different path; it does not falsely mark a closure. If none exists, the current
route remains available with an explicit message.

The current UI selects roads by map taps. It does not offer worldwide address
search. Coverage is the downloaded rectangle, including connector roads within
it. Automatic corridor expansion and map tile packs are not implemented; the app
renders independent vector roads locally.
