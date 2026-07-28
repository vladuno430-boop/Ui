# ARENA 3

An arena first-person shooter for Android, built from scratch in the mould of
the late-90s deathmatch games: no engine, no libraries, and no asset files. The
levels, textures, models and sounds are all generated in code at start-up, which
is why the whole game ships as a 180 KB APK.

```
tools/run-tests.sh          # simulation + audio checks, then build the APK
./gradlew assembleDebug     # just the APK
```

Install `app/build/outputs/apk/debug/app-debug.apk` on any device running
Android 7.0 or newer with OpenGL ES 3.0.

## What's in it

**Movement.** The physics is the point. Ground friction with a stop-speed floor,
separate ground and air acceleration, plane-clipping slide moves with crease
handling, and 18-unit step-ups. Getting those rules right is what makes the
emergent movement work: strafe jumping accelerates you past the 320 u/s run cap
(the test harness reaches 1500 u/s), circle jumps launch you off ramps, and a
rocket at your feet throws you across the map.

**Nine weapons** — gauntlet, machinegun, shotgun, grenade launcher, rocket
launcher, lightning gun, railgun, plasma gun and void cannon — with hitscan
cones, bouncing grenades, splash damage, armor absorption and knockback.

**Bots** that path with A*, pick goals by what they actually need, lead
projectiles by their target's velocity, strafe jump between pickups, back off
their own splash radius, and brake rather than slide off a ledge. Five skill
levels move reaction time, turn rate, aim jitter, projectile leading and caution
together.

**Three arenas.** The Forge is a sealed foundry with a central dais and a
balcony over the lava. Crucible is an open-air fortress with two towers, a
sunken trench and paired teleporters. The Void is platforms suspended over
nothing, where jump pads are the only reliable way across.

**Touch controls** with a floating movement stick, look-drag, and fire, jump,
crouch and weapon buttons — mirrorable for left-handed play, and scalable.

## How it is put together

```
app/src/main/java/com/arena3/
  core/      vectors, matrices, angles
  game/      the simulation — no Android dependency at all
  render/    geometry, models, particles, textures — also pure Java
  gl/        OpenGL ES 3.0 renderer, HUD, shaders
  audio/     PCM synthesis and a software mixer
  ui/        activities, menus, touch controls
tools/       tests and offline preview renderers
```

The split matters. `game/` and `render/` have no Android imports, so the entire
simulation and every byte of geometry the renderer uploads can be exercised on a
desktop JVM. That is what `tools/` is for.

### Levels are brushes, not files

Each arena is carved from convex brushes in Java, the way a `.map` file would
describe it. `MapBuilder` provides the vocabulary — rooms, ramps, staircases,
walkways, pillars, lava pools, jump pads, teleporters — and collision runs
directly against the brush planes.

At load, `WorldGeometry` turns each brush plane into a polygon by clipping a
large quad against the brush's other planes, drops faces buried inside other
solids, dices the survivors on a 128-unit grid, and bakes lighting into the
vertices: ambient, ambient occlusion from nine hemisphere rays, and every static
light with a shadow ray. A whole arena bakes in about 30 ms.

### Shadows and reflections

The sun is the one light that is *not* baked. It is evaluated per pixel so a
real-time shadow map can cut it. Before the scene draws, the level and every
living fighter go into a depth buffer rendered from the sun; `ShadowFit` sizes
one orthographic box around the whole arena, and the fragment shaders look the
result up with a four-tap PCF filter. The depth pass culls front faces, which
puts self-shadowing acne on the far side of the caster where nobody sees it, and
the bias is expressed in shadow-map texels rather than raw depth so the same
numbers hold across map sizes and both resolution settings.

Reflections come from an analytic environment — sky gradient, ground bounce, sun
disc — weighted by a Fresnel term and a per-material gloss, so a polished floor
mirrors at grazing angles and concrete does not. Both are one settings toggle
(*Shadows and reflections*, plus *High-detail shadows* for a 2048 map), since
the extra geometry pass is real cost on a slow phone.

### Bots navigate a graph nobody authored

There are no hand-placed waypoints. The arena is sampled on a grid, every spot
where a player-sized box can stand becomes a node, and two nodes are linked only
if that box can actually travel between them — traced, with the floor sampled
along the way to tell a walkable ramp from a ledge of the same height, and a
step from a gap that needs a jump. Jump pads and teleporters contribute one-way
links. The Forge yields ~385 nodes and 5400 links.

### Everything is synthesised

Sixteen world materials at 512x512 — panelled walls with vents and bolts, rusted
plate that bleeds downwards, diamond tread, grating, cracked concrete, lava,
worn hazard stripes — come out of tileable value and ridged noise. Each is built
in three tiers so it reads at every distance: panel structure large enough to
survive the mip chain, mid-scale bolts and vents, then a fine grain, with wear
and grime layered on top. They live in one array texture, so the whole level
still draws in a single call.

Fighters and weapons have their own eight-material set — plated armour, a woven
undersuit, machined gunmetal, ribbed grips, glowing visors — projected onto the
box geometry from each surface's dominant axis, so a fighter is a plated machine
rather than flat-shaded blocks. Generation is spread across cores and runs
behind a loading screen; the whole set is 18 MB of texture.

Thirty sound effects are synthesised as PCM from noise, oscillators and
envelopes, then mixed in software so they can be positioned and panned around
the player.

## Testing without a device

No emulator was available here, so the parts that could be verified were
verified directly, and the rest was made inspectable.

`tools/run-tests.sh` runs **102 checks**: movement constants measured against
their analytic values (a 45.6-unit standing jump, an exact 320 u/s run cap),
ramp and stair traversal, per-map geometry, reachability and spawn validity, a
ride on every jump pad, three 90-second bot matches, and the renderer's vertex
data. `SoundTest` checks all 30 effects for silence, clipping, DC offset and
end-of-buffer clicks.

Several real bugs came out of this: jump pads aimed at points *below* their
landing surfaces, so bots clipped the edge and fell; two pads that volleyed
players between each other forever; nav links that rejected any staircase
steeper than a single step; and bots landing from pads at 400 u/s and sliding
straight off the far side of a platform.

The shadows get graded against ground truth. `tools/SoftShadowMap.java` is a
software twin of the GL shadow pass — same projection, same culling, same PCF —
and the test suite asks it about a few thousand sunlit vertices, then asks a
collision ray the same question. They agree on 95–100% of samples, and *never*
in the direction of a false shadow, which is the failure mode that would be
visible. That comparison is what found the original bias, expressed in raw depth
units, throwing away shadows across an entire arena.

`tools/Preview.java` and `tools/ModelPreview.java` are software rasterisers that
render the exact vertex data the GL renderer uploads — through a shading path
that mirrors the shaders, shadow map included — so the level art, lighting and
models can be checked as images:

```
tools/run-tests.sh preview      # writes out/preview/*.png
```

`-Dshadowonly=true` paints the raw shadow term instead of the scene, which is
the quickest way to see where the sun actually reaches.

## Notes

This is an original implementation. The genre conventions are deliberate and the
movement rules follow the ones the classic arena shooters made standard, but no
id Software code, assets, names or trademarks are used — every texture, model,
sound and level here is generated by the code in this repository.

That rule is why no third-party texture pack is bundled. The widely-circulated
"HD" packs for the classic arena shooters are AI upscales of the original game's
own texture files, which leaves them derivative of artwork nobody here has the
right to redistribute. The materials in `ProcTex` are the answer to that instead.

An unrelated static landing page (`index.html`, `assets/`) from earlier work in
this repository is left untouched alongside the game.
