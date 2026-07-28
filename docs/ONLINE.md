# Online leaderboards

The game ships **without a server**. Out of the box every board is local: your
own runs are kept in `localStorage` and padded out with a set of deterministic
offline rivals so a fresh install has something to chase. The leaderboard screen
always says which of those you are looking at.

To compete beyond one device, point the game at an endpoint you control:

- **In game** — Leaderboards → Endpoint URL → Connect.
- **At build time** — set `window.DRIFT_ONLINE_ENDPOINT` before `js/main.js`
  loads.

The URL is stored under `markii-drift:endpoint`. Disconnecting returns the game
to offline boards; nothing is ever sent anywhere until you connect one.

## The API

Three routes, all JSON. The client sends no credentials — put whatever auth your
deployment needs in front of it, and treat every field as untrusted input.

### `POST /scores`

```json
{
  "track": "shibuya",
  "mode": "free",
  "name": "NO NAME",
  "score": 184320,
  "angle": 58,
  "car": "MARK II X71",
  "build": "1JZ-GTE swap / Single T67 conversion / Angle kit & knuckles / …"
}
```

`track` is one of `shibuya`, `docks`, `haruka`. `mode` is `free` or `career`.
`score` is an integer. Respond with anything JSON; the client only checks that
the request succeeded.

### `GET /scores?track=shibuya&mode=free&limit=20`

Return either a bare array or `{ "entries": [...] }`, highest score first:

```json
[
  { "name": "KEISUKE", "score": 241880, "angle": 61, "car": "MARK II X71" }
]
```

Extra fields are ignored. `name` is rendered escaped.

### `GET /ghosts/:id` (optional)

Returns a recorded run for the ghost car:

```json
{
  "hz": 20,
  "duration": 137.4,
  "frames": [[x, y, z, yaw, pitch, roll, rpm, smokeMask], …]
}
```

Positions are centimetres (integers), angles are milliradians, `smokeMask` is a
4-bit field of which wheels were lit up. This is exactly what `GhostRecorder`
produces, so a server can store and hand back what a client uploaded verbatim.

## Notes

- Requests time out after six seconds and fall back to the offline board; a
  failed fetch never blocks play.
- The service worker deliberately does not cache anything cross-origin, so
  leaderboard traffic is never served stale.
- There is no anti-cheat. A score arrives as a number from a client you do not
  control. If that matters to you, validate server-side against the ghost
  replay before accepting a run.
