# Ui

Two independent things live here.

- **[`game/`](game/)** — *X71 Night Run*, a night drifting game for Android
  built around a Toyota Mark II X71.
- **`index.html`, `assets/`** — Castielshop, a concept landing page for a
  classifieds marketplace.

Neither has a build step or a dependency to install.

---

# X71 Night Run

Night runs in a 1988 Toyota Mark II X71 (GX71) — city blocks, a dockyard and a
mountain pass, in whatever weather and at whatever hour you pick.

```sh
cd game && npx http-server -p 8080 .     # then open it, ideally on a phone
```

For the Android app, see **[docs/ANDROID.md](docs/ANDROID.md)**. The
`android/` project is a WebView shell that packages `game/` into an APK.

## What is in it

**The car.** One car, modelled in detail: the flat bonnet, the quad sealed-beam
headlamps under a chrome brow, the upright glasshouse with its thin C-pillar,
the full-width tail lamp panel. Built procedurally from cross sections, so body
kits, over-fenders, wheels, vinyls and paint are all parameters rather than
assets.

**The physics.** A four-wheel model with a Pacejka tire, combined slip, load
sensitivity, relaxation length, and tire temperature and wear. Weight transfer
is split into an instant geometric part through the roll centres and a lagged
elastic part through the springs, so the setup sheet genuinely changes how the
car rotates. Clutch, gearbox and a clutch-type LSD sit between the turbo six and
the rear wheels. Full details and measured numbers in
**[docs/PHYSICS.md](docs/PHYSICS.md)**.

Which means the techniques work the way they should: handbrake and clutch-kick
entries, feint, power-over, and holding the angle on the throttle with opposite
lock. There is no invisible hand keeping you pointed the right way — the
countersteer assist is a slider, and it starts at 25%.

**The garage.** Nine part categories from intake to a built 2JZ, twenty setup
sliders (camber, toe, lock, springs, dampers, bars, diff ramps, bias, final
drive, boost, pressures), and styling: five body kits, five wheel designs, paint
colour and finish, vinyls, tint, cage, caliper colour.

**Three tracks.** Shibuya Loop, Yokohama Docks and Mt. Haruka Pass — each with
its own scenery generator, and the pass with real elevation, so gravity is part
of the corner.

**Modes.** A nine-event career across three chapters, free drift with full
control of the conditions, and leaderboards. Ghosts of your best runs are
recorded and can be raced. Online is opt-in and needs an endpoint you provide —
see [docs/ONLINE.md](docs/ONLINE.md); without one, boards are local and say so.

**Everything is generated.** No textures, no meshes, no audio files. The engine
note is an additive harmonic stack tied to firing frequency with turbo whistle,
blow-off and overrun pops; the soundtrack is a phonk sequencer — 808 cowbell
melodies over a distorted sub, triplet hats, Memphis-minor pads. Six tracks,
none of them a file.

## Controls

| | Touch | Keyboard | Gamepad |
|---|---|---|---|
| Steer | drag the left pad | ← → / A D | left stick |
| Throttle | GAS (slide sideways to modulate) | ↑ / W | right trigger |
| Brake | BRAKE | ↓ / S | left trigger |
| Handbrake | E-BRAKE | Space | A |
| Clutch | CL | Shift | B |
| Shift | ▲ ▼ | E / Q | bumpers |
| Camera | CAM | C | Y |
| Reset | RESET | R | X |

Tilt steering is in Settings, with a calibration button. Headlights toggle on
`H`. The gearbox is automatic by default; turn it off in the garage.

## Structure

```
game/
  index.html            markup for every screen
  css/game.css          the whole interface
  sw.js                 precaches the shell so it runs offline
  js/core/              math, WebGL wrapper, procedural mesh builder
  js/physics/           tire model, vehicle dynamics
  js/render/            shaders, forward renderer, car model, weather, effects
  js/world/             track spline + sampling, themed scenery
  js/game/              camera, input, scoring, tuning, career, saves, ghosts
  js/audio/             engine synthesis and the phonk sequencer
  js/ui/                screens and HUD
android/                WebView shell + Gradle build
docs/                   physics, Android build, online API
```

## Requirements

WebGL 2 — Chrome 58+, any current Android WebView, Safari 15+. The game tells
you plainly if the device cannot provide it. Audio starts on the first tap
because browsers require a gesture.

---

# Castielshop — landing page

A concept landing page for a Russian-language classifieds marketplace. Static
site, no build step, no dependencies — open `index.html` or serve the folder.

```
npx http-server -p 8080 .
```

### Structure

```
index.html          markup + inline SVG icon sprite
assets/css/         design tokens and component styles
assets/js/app.js    listings data and interaction modules
```

### Design system

Everything visual is driven by custom properties at the top of `styles.css`.
Brand constants (gold accent, indigo, type families) are theme-independent;
surfaces, text and borders are redefined per theme under `[data-theme]`.

Light and dark are both first-class. The initial theme comes from
`prefers-color-scheme`, an explicit choice is remembered in `localStorage`, and
a blocking inline script in `<head>` applies it before first paint so the page
never flashes the wrong theme.

Type uses a fluid `clamp()` scale (`--step--1` … `--step-4`). Fraunces carries
display headings, Manrope the interface, IBM Plex Mono prices and labels.

### Interaction

`app.js` is a set of independent IIFE modules — each one returns early if its
markup is absent, so removing a section from the HTML cannot break the rest.

- **Command palette** — `Ctrl`/`⌘ K` or `/`, with arrow-key navigation, focus
  trapping and restore, and live filtering over the listings.
- **Feed** — category, attribute filter, sort and paging are held in one state
  object and re-rendered from a single `render()`.
- **Favourites** — persisted in `localStorage`, reflected in the header badge
  and available as a feed filter.
- **Card artwork** — generated as inline SVG per listing from a category
  palette and glyph set, so the page ships no image assets.
- Scroll reveal and stat counters run off `IntersectionObserver`.

`prefers-reduced-motion` is honoured throughout: transitions collapse in CSS,
and the counters, ticker and smooth scrolling check the query in JS.

### Accessibility

Skip link, visible focus rings, semantic landmarks and headings, `aria-pressed`
on favourite toggles with labels that track state, `aria-expanded` on the city
menu, and a live region on the feed. The sticky-header offset for anchor
targets is measured at runtime rather than hard-coded.

The listings are rendered client-side; a `<noscript>` message covers that case.

### Notes

This is a design exercise, not a real service — the data in `app.js` is
fictional and the form submissions are simulated.
