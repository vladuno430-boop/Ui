# The car

Everything here lives in `game/js/physics/`. Units are SI, the body frame is
`+Z` forward, `+X` right, `+Y` up, and the origin sits on the ground under the
centre of gravity.

## Chassis

`Vehicle` is a planar rigid body — `x`, `z`, yaw — plus a vertical degree of
freedom for crests and jumps. Four wheels are placed from the wheelbase and
track, and each one gets its own tire, load, slip and wheel speed.

Per substep (two at 120 Hz, four when frames get long):

1. **Steering.** Input goes through a rate limit at the road wheel, then
   Ackermann splits it between the inside and outside wheels. The angle-kit part
   raises the lock to 56° and drops Ackermann to near zero, which is what real
   drift knuckles do.
2. **Vertical loads.** Static weight, then transfer split two ways:
   - *geometric*, through the roll centres — instant,
   - *elastic*, through springs and bars — lagged by a time constant derived
     from the front spring rate and the sprung mass.

   The elastic share is distributed front/rear by roll stiffness, so stiffening
   the rear bar moves more lateral transfer onto the rear axle and loosens the
   car. Aero adds load with the square of speed.
3. **Tires.** See below.
4. **Drivetrain.** Turbo spool → torque curve → clutch → gearbox → LSD → wheel
   torques.
5. **Integration.** Forces and moments summed in the body frame, including the
   Coriolis term from the rotating frame, plus the component of gravity along
   the road surface — which is why the touge downhill pulls.

## Tire model

Pacejka's Magic Formula, `y = D·sin(C·atan(Bx − E(Bx − atan Bx)))`, with:

- **Derived stiffness.** `B` is not a hand-picked number. `stiffnessFor(C, E,
  peak)` solves `(1−E)u + E·atan(u) = tan(π/2C)` by bisection so the curve peaks
  exactly at the slip angle or slip ratio the compound table asks for. The
  tables are written in terms you can reason about — peak grip, the slip it
  happens at, and how much survives past it.
- **Combined slip.** Longitudinal and lateral slip are normalised by their own
  peaks, so the friction limit is a circle in normalised space. Both axes are
  evaluated at the combined magnitude and the result is split along the slip
  direction.
- **Load sensitivity.** μ falls as vertical load rises. This is what makes
  weight transfer cost you grip rather than just move it around.
- **Relaxation length.** Force lags slip by a fixed *distance*, not a fixed
  time, so the lag shrinks with speed and the model stays stable at a standstill.
- **Temperature and wear.** Friction power heats the contact patch; grip peaks
  in a window around the optimum and falls off either side. Sliding wears the
  tire, and a worn tire loses up to a quarter of its peak.

`E` — how flat the curve is past its peak — is most of what separates a
semi-slick from a cheap hard drift tire:

| Compound | Peak μ | Peaks at | μ left at 55° |
|---|---|---|---|
| Street | 1.00 | 10.0° | 83% |
| Sport | 1.14 | 9.0° | 78% |
| Semi-slick | 1.30 | 8.0° | 71% |
| Drift spec | 0.92 | 13.0° | 95% |

A semi-slick grips harder and lets go faster. A drift tire never really lets go,
which is exactly why it is easy to hold sideways.

## Drivetrain

- **Engine.** A normalised torque curve for a turbo straight-six, scaled by the
  fitted engine's peak torque and by boost through a pressure-ratio term. An
  idle governor holds idle against internal friction; a soft limiter cuts above
  the redline.
- **Turbo.** Boost approaches a spool-dependent target with a first-order lag.
  Lifting off dumps it (and pops the exhaust). Anti-lag holds the target up off
  throttle.
- **Clutch.** Capacity-limited friction, and the pedal can only ever *reduce*
  engagement. Underneath sits an auto-clutch: below the launch revs it transmits
  no more than the engine can give while still climbing, which is how the car
  pulls away without either bogging or stalling. Clutch-kicking works because
  releasing and re-engaging dumps stored engine inertia into the driveline.
- **Differential.** Clutch-type LSD: preload plus a ramp that scales lock with
  drive torque, separately for power and coast, plus a viscous term. Welded is
  just a very large preload.
- **Handbrake.** Applies a large brake torque to the rear wheels *and*
  disconnects drive, so the axle can actually lock.

## Measured behaviour

Numbers from `Vehicle` on flat asphalt, stock build (1G-GTE, sport tires):

| | |
|---|---|
| 0–100 km/h | 8.8 s |
| 100–0 km/h | 3.8 s |
| Top speed | 220 km/h |
| Peak lateral, warm | 0.87 g |
| Peak lateral, cold | 0.78 g |
| Peak lateral, wet | 0.66 g |
| Peak lateral, semi-slicks + coilovers | 1.16 g |

The real GX71 did 0–100 in about 8.4 s and made 185 hp; the model's stock engine
reads 186 hp at 6000 rpm.

## Countersteer, and its sign

Body slip angle is `atan2(vx, |vz|)`. A car sliding with its nose to the *left*
of where it is travelling has positive slip, and is caught by steering *right* —
opposite lock carries the **same sign** as the slip angle. Getting that backwards
makes the car uncatchable, which is worth remembering if you touch the
steering-assist or a driver model.
