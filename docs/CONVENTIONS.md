# DRIFTLOCK — Frame and Unit Conventions

**Problem Statement SIH26168 — Intelligent Dead Reckoning for GNSS-denied navigation**

This document is binding for every component in the project. Any code that crosses a
boundary between two team members must follow it exactly.

If you think a convention here is wrong, raise it with the team and change this file.
Do not silently deviate in your own module — a local deviation becomes a silent
navigation error that nobody can trace.

**Owner:** Antarjot Singh (Navigation Lead)
**Version:** 1.1
**Last updated:** 8 September 2026

---

## 0. What changed in version 1.1, and why

Version 1.0 was written on 3 September, before the team roles were final and before
the navigation engine existed. Comparing it against the working code found eight
places where the two disagreed. All eight are now reconciled.

| Change | Reason |
|---|---|
| **Alignment ownership moved from Avi to Antarjot** (section 8) | The role division changed after version 1.0. The code already implements it this way. Left as it was, two people would have built the same component. |
| **The machine learning component sends a standard deviation, not a variance** (section 8.2) | Version 1.0 asked for variance. The filter expects standard deviation. Both are small positive numbers, so the mistake would never have raised an error — it would have made the filter trust the speed roughly twice as much as it should. |
| **The map matching interface is unchanged, and the filter changed to match it** (sections 8.4, 8.5) | Japneet's module is finished. When one side of an interface is built and the other is not, the built side wins. |
| **Heading is now defined from attitude and mount, not from velocity** (section 9) | The old definition is undefined when the vehicle is stopped and cannot tell forwards from reversing. |
| **Mount change inflates the mount uncertainty, not the attitude uncertainty** (section 8.6) | When somebody moves the phone, its orientation in the world is still perfectly well known — the gyroscope tracked every bit of that movement. What became unknown is how it now sits in the car. |
| **The rotation naming rule is now scoped** (section 4.4) | Mandatory at every boundary, as before. A narrow, documented exemption added for shorthand inside a single file. |
| **Gravity is 9.80665, not 9.81** (section 2) | Standard gravity. The difference is negligible; having two values in the project is not. |
| **New: the accelerometer contract and the clock choice** (section 7) | Version 1.0 did not say whether gravity was included, and did not say which of Android's two clocks to use. Both omissions cause silent failures. |

---

## 1. Quick reference

| Item | Convention |
|---|---|
| Navigation frame | East-North-Up |
| Vehicle body frame | x forward, y left, z up (right-handed) |
| Rotation storage (internal) | Quaternion, scalar-first `[w, x, y, z]` |
| Rotation naming | `R_<target>_from_<source>` at every boundary |
| Rotation direction | Body to navigation |
| Angles | Radians everywhere internally |
| Distance | Metres |
| Velocity | Metres per second |
| Acceleration | Metres per second squared |
| Angular rate | Radians per second |
| Time | Seconds, floating point, time since device boot |
| Uncertainty | **Standard deviation**, never variance |
| Position (internal) | Local East-North-Up metres from drive origin |
| Position (external) | Latitude and longitude, degrees |
| Gravity | `9.80665` metres per second squared |

---

## 2. Navigation frame — East-North-Up

The navigation frame is a local, fixed frame anchored to the ground for the duration
of one drive.

```
        z (Up)
        |
        |
        +---------- y (North)
       /
      /
    x (East)
```

| Axis | Direction |
|---|---|
| x | East |
| y | North |
| z | Up |

Gravity in this frame is:

```
g_nav = [0, 0, -9.80665]   metres per second squared
```

### Why East-North-Up and not North-East-Down

- Gravity is simply negative on the vertical axis, which matches intuition.
- Altitude increases upward, so plots read naturally without inverting an axis.
- OpenStreetMap and most mapping tooling think in East-North-Up terms, which means
  fewer sign conversions at the map matching interface.
- North-East-Down exists because aircraft descend. That reasoning does not apply here.

### Warning — textbooks use North-East-Down

Most inertial navigation literature, including the standard reference works, uses
North-East-Down. **When copying equations from a book or paper, adapt the signs.**
Do not assume a published gravity term or rotation formula transfers unchanged.

---

## 3. Vehicle body frame — x forward, y left, z up

This frame is rigidly attached to the vehicle, not to the phone.

```
        z (Up)
        |
        |
        +---------- x (Forward, direction of travel)
       /
      /
    y (Left)
```

| Axis | Direction | Positive value means |
|---|---|---|
| x | Forward | Vehicle is accelerating |
| y | Left | Vehicle is sliding or turning left |
| z | Up | Vehicle is going over a bump |

### Why this choice

- It is right-handed. Every rotation formula assumes a right-handed frame.
- Vertical points up, matching the navigation frame. No axis flips between the two.
- Non-Holonomic Constraints become clean and readable:
  - lateral velocity (y) = 0 — a car cannot slide sideways
  - vertical velocity (z) = 0 — a car cannot fly

### This is not the phone frame

Android reports sensor data in **its own** frame, which is defined relative to the
device screen and differs from the vehicle frame above.

Converting from the phone frame to this vehicle frame is the entire job of the
alignment step, which lives **inside the navigation engine** and is owned by the
navigation lead. See section 8.

---

## 4. Rotations

### 4.1 Storage

| Context | Representation | Reason |
|---|---|---|
| Inside the filter | Quaternion | No gimbal lock, four numbers, numerically stable over long integration |
| Display and debugging | Roll, pitch, yaw in degrees | Human-readable |

Note the change from version 1.0. Now that alignment is internal to the navigation
engine, **no rotation crosses a module boundary at all.** The only orientation that
leaves the engine is a single scalar heading (section 9). The old rule about passing
3 by 3 matrices between people no longer has anything to apply to.

The estimated mount rotation is still available for inspection and debugging, and it
is logged, but nobody consumes it as an input.

### 4.2 Quaternion order — scalar-first

```
q = [w, x, y, z]
     ^
     scalar component first
```

### Warning — SciPy uses scalar-last

`scipy.spatial.transform.Rotation` uses `[x, y, z, w]` — **scalar last**.

If you use SciPy for any conversion, you must reorder on the way in and on the way
out. This is a real and easy-to-miss bug. Write a wrapper function rather than
reordering inline at each call site.

*(The navigation engine deliberately uses no SciPy rotation code for exactly this
reason. Every quaternion operation is written out in `rotations.py`.)*

### 4.3 Direction — body to navigation

All rotations are stored in the body-to-navigation direction:

```
vector_in_nav = R_nav_from_body @ vector_in_body
```

Sensors report in the body frame and results are needed in the navigation frame, so
this is the direction actually used in the code.

For the reverse direction, take the transpose explicitly at the point of use. Do not
store a second matrix — two representations of the same rotation will drift apart.

### 4.4 Naming — mandatory at boundaries, scoped inside a file

**At every boundary, and in every name that is shared, logged or published**, the
full form is mandatory:

```
R_<target>_from_<source>
```

```python
R_nav_from_body        # body frame to navigation frame
R_vehicle_from_phone   # phone frame to vehicle frame
R_nav_from_vehicle     # vehicle frame to navigation frame
```

**Never** a bare `R`, `rot`, `rotation`, or `orientation`.

Read the name left to right against the vector being multiplied and the source term
cancels visibly:

```python
v_nav = R_nav_from_body @ v_body      # "body" cancels — correct
v_nav = R_nav_from_body @ v_nav       # "nav" does not cancel — wrong, and visibly so
```

**The one exemption, added in version 1.1.** Inside a single file, where a rotation is
used dozens of times in dense mathematics, the short forms `R_nb` and `R_vb` are
permitted **on one condition**: the file's docstring must define them explicitly, at
the top, in the full form. `state.py`, `filter.py` and `alignment.py` do this.

The exemption exists because the alternative is a rename of roughly two thousand
seven hundred lines two days before a deadline, which is risk with no benefit — the
rule's purpose is that no reader is ever unsure which direction a rotation goes, and
a definition block at the top of the file achieves that inside that file. It does not
achieve it across a boundary, which is why the exemption stops there.

---

## 5. Units

| Quantity | Unit | Notes |
|---|---|---|
| Distance | metres | |
| Velocity | metres per second | |
| Acceleration | metres per second squared | |
| Angular rate | radians per second | |
| Angle | radians | |
| Time | seconds, floating point | |
| Uncertainty | **standard deviation**, in the unit of the quantity | See below |
| Latitude and longitude | degrees | Interfaces only, see section 6 |

### Never use kilometres per hour internally

Convert to kilometres per hour only at the moment of display. Mixing metres per second
and kilometres per hour mid-pipeline is a classic silent error — the numbers stay
plausible and the result is wrong by a factor of 3.6.

### Uncertainty is always a standard deviation, never a variance

**This is new in version 1.1 and it corrects an error in version 1.0.**

Every uncertainty published anywhere in this project is a **one sigma standard
deviation**, in the same unit as the quantity it describes. Never a variance, never a
ninety-five percent bound, never a percentage.

Why standard deviation rather than variance: it is in the same unit as the thing it
describes, so it can be checked by eye. "Half a metre per second of speed
uncertainty" means something to a human. A variance of 0.25 does not.

Why this matters more than it looks: variance is the square of standard deviation, so
confusing them is not a small error. A true uncertainty of 0.45 metres per second has
a variance of 0.2025. Send the variance where the standard deviation is expected and
the filter concludes the measurement is roughly **twice as accurate as it really is**,
over-trusts it, and produces a smooth trajectory that is wrong. Both numbers are small
and positive. Nothing crashes.

### Units belong in variable names

```python
heading_rad      # correct
heading_deg      # correct
speed_mps        # correct
sigma_mps        # correct — unit says it is a standard deviation in metres per second
speed_kmph       # correct, display only

heading          # wrong — ambiguous
speed            # wrong — ambiguous
variance         # wrong — and against section 5 in any case
```

---

## 6. Position representation

| Context | Representation |
|---|---|
| Inside the filter | Local East-North-Up, in metres, relative to a fixed drive origin |
| At input and output boundaries | Latitude and longitude, in degrees |
| Between the filter and the map matcher | Local East-North-Up metres, shared origin |

### Why filter in local metres

Filtering directly in latitude and longitude means:

- East-west scale changes with latitude, so a degree is not a fixed distance
- The covariance matrix mixes degrees with metres, which makes it meaningless
- Tuning becomes impossible because the units are inconsistent

### The drive origin

- The origin is the **first valid satellite fix of each drive**.
- **The navigation engine publishes the origin as soon as it has one**, as
  `origin_lat_deg`, `origin_lon_deg`, `origin_alt_m`. Every other component adopts
  that value; nobody chooses their own.
- Record the origin alongside every trajectory and every log file.
- The filter, the map matcher and the evaluation code must all use the **same**
  origin for a given drive, or positions will not line up — and they will look
  entirely reasonable on each side while not lining up, which is the hard kind of
  bug to find.

Conversion happens once at the start of a drive and once at output. Nowhere else.

---

## 7. Time and raw sensor data

### 7.1 Time

- Time is seconds, floating point, monotonically increasing within a drive.
- **Always use hardware sensor timestamps.** Never assume a fixed interval between
  samples.

**Which clock — new in version 1.1.** Android has two, and they are not
interchangeable:

| Source | Use |
|---|---|
| `SensorEvent.timestamp` | Nanoseconds since boot. **Use this.** Divide by 1e9. |
| `Location.getElapsedRealtimeNanos()` | Nanoseconds since boot. **Use this.** Divide by 1e9. |
| `Location.getTime()` | Wall clock, milliseconds since 1970. **Never use this.** |

A `Location` object carries both, which is exactly why this needs writing down.

Mixing the two fuses a satellite fix into a moment it does not belong to, and the
symptom is not "the clock is wrong" — the symptom is "the filter seems badly tuned".
That misdiagnosis costs a day.

**How to check it in one line:** a time-since-boot value on a phone that has been on
for an hour is about **3,600**. A wall-clock value today is about **1,788,000,000**.
They differ by a factor of half a million. If a timestamp is larger than about ten
million, it is the wrong clock.

### 7.2 Why fixed intervals are forbidden

Android's sampling rate request is a hint, not a guarantee. On cheap devices samples
arrive irregularly. Dead reckoning integrates over time, so an assumed interval where
the real one differs injects error directly into velocity and position.

Always integrate over **actual elapsed time** between consecutive samples.

### 7.3 Which sensors — new in version 1.1

| Use | Do not use |
|---|---|
| `TYPE_ACCELEROMETER` — raw specific force, **gravity included** | `TYPE_LINEAR_ACCELERATION` — gravity already removed |
| `TYPE_GYROSCOPE` | `TYPE_ROTATION_VECTOR`, `TYPE_GAME_ROTATION_VECTOR` |

**The filter removes gravity itself.** If the platform has already removed it, gravity
gets subtracted twice and the whole estimate collapses — silently, with numbers that
still look like plausible accelerations.

The forbidden types are all outputs of the phone manufacturer's own sensor fusion.
That fusion is tuned for a phone held in a hand, cannot be inspected, differs between
devices, and would silently compete with our filter for the same job.

**Do not remap the axes.** Android provides `remapCoordinateSystem` to correct for
screen rotation. Using it would fight the alignment step, which is already solving
that problem properly and from data.

---

## 8. Interface contracts

### 8.0 Who owns what — new in version 1.1

| Component | Owner | Produces |
|---|---|---|
| Android pipeline and sensor capture | Vishisht | Raw motion samples and satellite fixes |
| Machine learning virtual odometer | Gurveer Singh | Forward speed with its uncertainty |
| **Navigation engine, including alignment, fusion, ShadowDR and recovery** | **Antarjot Singh** | Position, velocity, heading, uncertainty, status |
| Map matching and road lock | Japneet Kaur | Snapped position with a road confidence |
| Data and validation | Avi Goyel | Loaded drives, blackout generation, metrics |
| User interface | Savneet Kaur | Display only |

**Alignment — the phone-to-vehicle rotation — is part of the navigation engine and is
not a separate component.** Version 1.0 listed it as Avi's; that is no longer correct
and no separate alignment module should be built.

### 8.1 Android pipeline (Vishisht) to filter

| Field | Type | Notes |
|---|---|---|
| `t` | float | Seconds since boot, per section 7.1 |
| `accel` | 3-vector | Metres per second squared, phone frame, **gravity included** |
| `gyro` | 3-vector | Radians per second, phone frame |

And, whenever the platform provides a location:

| Field | Type | Notes |
|---|---|---|
| `t` | float | Seconds since boot, from `getElapsedRealtimeNanos` |
| `lat_deg`, `lon_deg`, `alt_m` | float | Degrees, degrees, metres |
| `horizontal_accuracy_m` | float | One sigma, from `getAccuracy()` |
| `speed_mps`, `speed_accuracy_mps` | float, optional | Send whenever present |
| `course_deg`, `course_accuracy_deg` | float, optional | Compass bearing. **Send whenever present** |

**Send speed and bearing whenever the platform has them.** They are what make heading
observable quickly. With position alone, working out which way the vehicle points
takes far longer than it should.

### 8.2 Machine learning component (Gurveer) to filter

| Field | Type | Notes |
|---|---|---|
| `t` | float | Seconds since boot, timestamp of the window centre |
| `speed_mps` | float | Forward speed along the vehicle x axis. Metres per second, **not** kilometres per hour |
| `sigma_mps` | float | **Standard deviation**, metres per second. Required, not optional |
| `valid` | boolean | False when the model has no usable output for this window |

**Changed from version 1.0:** this was `velocity_variance`. It is now a standard
deviation, per section 5. Gurveer has not started, so the change costs nothing now and
would have cost a silently wrong answer later.

**The uncertainty must vary per estimate.** A single fixed number chosen once defeats
the purpose. It should grow when the model is on a road type it has not seen much of,
and shrink where it is confident. That number is what decides how hard the filter
leans on the speed, and it is the only way the filter can know when not to.

**Also requested, not part of the contract:** an honest error breakdown by road type.
The model's failure modes become the navigation engine's failure modes, so they are
better known in advance than discovered during evaluation.

### 8.3 Filter to map matching component (Japneet) — unchanged

| Field | Type | Notes |
|---|---|---|
| `position_enu_m` | 3-vector | Local East-North-Up metres from drive origin |
| `heading_rad` | float | Radians, measured from East, counter-clockwise positive |
| `covariance` | matrix | Position covariance block. Sets the map matcher's search radius |
| `timestamp_s` | float | |

Unchanged from version 1.0 because her module is built against it. The navigation
engine publishes exactly these names through a dedicated boundary function rather
than renaming its own internal fields, because the same output also serves the
Android pipeline, the user interface and the evaluation harness.

### 8.4 Map matching component (Japneet) to filter — unchanged

| Field | Type | Notes |
|---|---|---|
| `matched_position_enu_m` | 3-vector | Local East-North-Up metres, same origin |
| `road_confidence` | float | 0 to 1. The probability of the committed road candidate |
| `timestamp_s` | float | |

Unchanged, for the same reason.

**Behaviour on low confidence.** The filter rejects any match below **0.80** outright
rather than fusing it weakly, and above that converts the confidence into a
measurement uncertainty in metres:

```
sigma_m = 3.0 + 40.0 × (1 − road_confidence)
```

So a perfect match is worth about three metres and a match at 0.85 is worth about
nine. A near-tied junction around one half is close to useless and is not used at all,
because committing to a road at fifty-five percent confidence is how a system ends up
confidently on the wrong street.

**Why the conversion happens on the filter's side.** A confidence is a probability;
the filter needs a distance. Deciding how many metres a given probability is worth is
a navigation decision, so it belongs with the navigation engine, and it is recorded in
the configuration file where it can be tuned and traced.

**Why the map measurement is deliberately weak.** The map matcher consumes the
filter's position to decide which road we are on. If the filter then treats her answer
as truth, the two form a loop — she trusts us, we trust her, and both can grow
confident about the same wrong road with no way to notice. Every drive is therefore
logged twice, with the map input on and off, so the two can always be compared.

### 8.5 Data and validation (Avi) to the replay harness

**This section replaces the alignment component section from version 1.0.**

Avi's loader produces three time-ordered streams in exactly the shapes of sections 8.1
and 8.2, plus the drive origin from section 6.

| Deliverable | Notes |
|---|---|
| Loaded drives | The three streams, on one clock, sorted by time |
| Blackout generation | Satellite fixes removed for 30, 60 and 120 second windows |
| Metrics | Produced identically for every variant, so improvement can be proved |

**Merge order is part of the contract.** A satellite fix and a learned speed are both
applied **before** the motion sample that follows them, never after. Applying them
afterwards fuses a measurement into a state that has already moved past the moment it
describes. That mistake makes offline results look slightly *better* while making the
phone subtly wrong, which is the worst combination available.

**The loader must validate its own output** — see section 11.5.

### 8.6 Filter to everyone — the published output

The navigation engine publishes one object on every update:

| Field | Notes |
|---|---|
| `lat_deg`, `lon_deg`, `alt_m` | Geographic position |
| `position_enu_m` | Local metres from the drive origin |
| `velocity_enu_mps`, `speed_mps` | |
| `yaw_enu_rad` | Vehicle heading, from East, anticlockwise. Section 9 |
| `course_deg` | The same heading as a compass bearing, for maps and display |
| `position_sigma_m`, `velocity_sigma_mps`, `heading_sigma_deg` | Standard deviations |
| `ellipse` | Ninety-five percent horizontal region for the map overlay |
| `status` | INITIALISING, ALIGNING, SATELLITE_GOOD, SATELLITE_DEGRADED, BLACKOUT, REACQUIRING, MOUNT_REALIGN |
| `calibration` | UNCALIBRATED, COARSE, GOOD |
| `confidence` | 0 to 100, for the display |
| `time_since_fix_s`, `blackout_duration_s`, `predicted_drift_m` | |
| `mount_change_detected` | |
| `covariance` | Nineteen by nineteen |

**The user interface displays the confidence. It never calculates it.**

**Behaviour on mount change — corrected in version 1.1.** When the phone is moved, the
filter widens the uncertainty on the **mount**, not on the attitude, and publishes
`MOUNT_REALIGN` with a reduced confidence.

Version 1.0 said to inflate the attitude uncertainty. That is the wrong state. The
phone's orientation in the world is still known perfectly well — the gyroscope
measured every part of the movement. What became unknown is how the phone now sits in
the car. Inflating attitude would throw away good information while leaving the bad
information untouched.

If the phone is moved **during a blackout**, there is nothing to re-align against. The
filter widens the mount uncertainty, keeps navigating on the constraints and the
learned speed, and drops its confidence sharply. It degrades honestly; it does not
stop.

---

## 9. Heading definition

Heading is the direction the **vehicle** points, in the navigation frame:

- Zero radians points East
- Positive rotation is counter-clockwise
- Range is negative pi to pi

**Changed in version 1.1.** Heading is computed by composing the phone's attitude with
the mount:

```
R_nav_from_vehicle = R_nav_from_body @ R_vehicle_from_body transposed
heading_rad        = atan2(R_nav_from_vehicle[1][0], R_nav_from_vehicle[0][0])
```

Version 1.0 defined heading as `atan2(velocity_north, velocity_east)` — the direction
of travel. That definition has two failure modes that matter to this project
specifically:

1. **It is undefined when the vehicle is stopped.** At a red light the velocity is
   zero and its direction is meaningless, so heading would jump around at exactly the
   moment the system should be calmest.
2. **It cannot tell forwards from reversing.**

The composed form stays valid at a standstill, through a slow crawl in a tunnel, and
while reversing.

### The bearing conversion — unchanged and still required

Compass bearing is measured clockwise from North. Satellite receivers and mapping
tools report bearing. **Convert explicitly at the boundary:**

```
heading_rad = radians(90 - bearing_deg)
```

Then wrap into the negative pi to pi range. Do not mix the two anywhere in the
pipeline. Both forms are published (`yaw_enu_rad` and `course_deg`) so that nobody
needs to convert one into the other themselves.

---

## 10. Code style rules that support these conventions

These exist so the eventual port to Kotlin is a translation rather than a rewrite.

1. **Explicit operations, no clever one-liners.** No chained broadcasting tricks, no
   dense vectorised expressions whose shapes are not obvious. **Amended in version
   1.1:** plain matrix multiplication and slicing are permitted and expected — a
   Kalman filter *is* matrix algebra, and writing it as loops would obscure it rather
   than clarify it. The rule's real purpose is portability, and portability is
   demonstrated directly: the Kotlin mirror in `kotlin/Esekf.kt` is written with plain
   arrays and no library, and matches the Python line for line.
2. **Keep the filter pure.** No plotting, no file input or output inside the filter
   module. State in, state out.
3. **No hidden global state.** Everything the filter needs is passed in.
4. **Constants in one place.** Tuning parameters live in `config.py` and nowhere else
   — there is not one magic number anywhere in the filter. Physical constants
   (gravity, the Earth model) live in `rotations.py`. Two files, split by whether a
   value is a choice or a fact. Never a literal at a call site.
5. **The configuration is written into every log file**, so that any result can be
   traced back to the exact settings that produced it.

---

## 11. Validation tests every component must pass

### 11.1 The stationary gate — alignment and filter together

```
Input:   stationary vehicle, phone at any mounting angle
Process: rotate accelerometer reading into navigation frame, subtract gravity
Expect:  residual approximately zero on all three axes, within sensor noise
```

**Nothing proceeds until this passes.** A one degree frame error leaks
9.81 x sin(1 degree) = 0.17 metres per second squared into the signal — larger than
the sensor bias that produces 32 metres of drift in a minute. And it looks completely
normal in every other metric.

*Status: passing in the unit test suite on synthetic data. To be re-run on the first
real recorded drive.*

### 11.2 The straight-drive test

```
Input:   straight drive at steady speed, satellites available
Expect:  lateral velocity (body y) approximately zero
         vertical velocity (body z) approximately zero
         forward velocity (body x) matches satellite-derived speed
```

Any persistent lateral velocity means the yaw alignment is wrong.

*Status: the filter records the normalised innovation of every constraint update, so
this is measurable directly from any run's logs. On synthetic drives the mean sits
near the expected value, which is what a correct alignment looks like.*

### 11.3 The Non-Holonomic Constraints diagnostic

```
Adding Non-Holonomic Constraints improves accuracy  -> frames are correct
Adding Non-Holonomic Constraints makes it worse     -> frames are wrong
```

This is the fastest frame-error detector available and it costs about ten lines of
code. Use it as a check, not only as a feature.

*Status: **run on 8 September 2026 across four synthetic drives.** Constraints on gave
a median blackout error of 3.08 metres and 0.55 percent of distance travelled;
constraints off gave 4.00 metres and 0.76 percent. Improvement by a factor of 1.30.
**Verdict: frames are correct.** Re-run this on the first real drive.*

### 11.4 The convergence test — filter and map matcher together

```
Input:   straight road, deliberately incorrect initial position
Expect:  position converges toward the road
Failure: position oscillates back and forth between cycles
```

Oscillation means the filter and the map matcher are over-weighting each other. This
is a tuning problem, and it is far easier to find in this controlled test than in a
full pipeline run.

*Status: to be run jointly with Japneet once real drives are loaded. The filter's map
input is off by default until this test passes.*

### 11.5 Loader sanity checks — new in version 1.1

The data loader validates every stream before the filter sees it, and fails loudly
with a message naming the problem. These exist because the recorded benchmark drives
were produced by researchers who have never read this document, and their columns
follow their conventions, not ours.

| Check | Fails when | What it catches |
|---|---|---|
| Timestamp magnitude below ten million | Value is about 1.79 billion | Wall clock instead of time since boot |
| Timestamps strictly increasing | Any step backwards | Two streams merged on different clocks |
| Speed below 60 metres per second | Highway speed reads about 100 | Kilometres per hour instead of metres per second |
| Standard deviation greater than zero | Value is 0, or suspiciously near a square | A variance sent where a standard deviation was expected |
| Accelerometer magnitude near 9.8 at rest | Value near zero at rest | Gravity already removed by the platform |
| Gyroscope magnitude below about 5 | Ordinary turns read about 30 | Degrees per second instead of radians per second |

Each of these turns a silently wrong answer into a one-line error message. The
accelerometer one in particular is worth the whole section: if gravity has already
been removed, the filter removes it a second time and every result is wrong while
every number still looks plausible.

---

## 12. Change log

| Date | Change | By |
|---|---|---|
| 3 September 2026 | Initial version, agreed by team | Antarjot Singh |
| 8 September 2026 | Version 1.1. Alignment ownership moved to the navigation lead. Machine learning interface now sends a standard deviation rather than a variance. Map matching interface unchanged and the filter changed to match it. Heading redefined from attitude and mount. Mount-change behaviour corrected. Rotation naming rule scoped. Gravity set to 9.80665. Added the clock choice, the raw sensor contract, the roles table, and the loader sanity checks. Section 11.3 run and recorded. | Antarjot Singh |
