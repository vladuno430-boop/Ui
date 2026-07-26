# Castielshop — landing page

A concept landing page for a Russian-language classifieds marketplace. Static
site, no build step, no dependencies — open `index.html` or serve the folder.

```
npx http-server -p 8080 .
```

## Structure

```
index.html          markup + inline SVG icon sprite
assets/css/         design tokens and component styles
assets/js/app.js    listings data and interaction modules
```

## Design system

Everything visual is driven by custom properties at the top of `styles.css`.
Brand constants (gold accent, indigo, type families) are theme-independent;
surfaces, text and borders are redefined per theme under `[data-theme]`.

Light and dark are both first-class. The initial theme comes from
`prefers-color-scheme`, an explicit choice is remembered in `localStorage`, and
a blocking inline script in `<head>` applies it before first paint so the page
never flashes the wrong theme.

Type uses a fluid `clamp()` scale (`--step--1` … `--step-4`). Fraunces carries
display headings, Manrope the interface, IBM Plex Mono prices and labels.

## Interaction

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

## Accessibility

Skip link, visible focus rings, semantic landmarks and headings, `aria-pressed`
on favourite toggles with labels that track state, `aria-expanded` on the city
menu, and a live region on the feed. The sticky-header offset for anchor
targets is measured at runtime rather than hard-coded.

The listings are rendered client-side; a `<noscript>` message covers that case.

## Notes

This is a design exercise, not a real service — the data in `app.js` is
fictional and the form submissions are simulated.
