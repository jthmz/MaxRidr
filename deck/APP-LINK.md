# How a phone app talks to the Deck

This is the contract between the Deck service on the Mac (`server.mjs`, port 8791) and a phone
app such as MAXAPACITY Ride. Everything is plain HTTP with JSON, on the home network or over a
USB cable. The Deck's side of everything here is built and was tested on 23 Nov 2026 with plain
requests; no app has used it yet.

## 1. Finding the Deck

Try these in order. The first one that answers is the Deck.

| Route | Address | Sign-in | When it works |
|---|---|---|---|
| USB cable | `http://127.0.0.1:8791` | none | The phone is plugged into the Mac with USB debugging on. The Deck keeps an `adb reverse` link up by itself. |
| Network, found automatically | Bonjour service type `_maxdeck._tcp` (Android: `NsdManager`), then `http://<resolved host>:<resolved port>` | key, see below | Phone and Mac are on the same network and `remote` is on in the Deck's config. |
| Network, typed | `http://<Mac's address>:8791` | key or `phoneToken` | Fallback when discovery is blocked. |

"Am I home?" is simply: does `GET /api/state` answer within a couple of seconds. A `401` means
the Deck is there but this app is not paired (or was forgotten): pair again.

The Mac can be on two networks at once. Use whichever address discovery resolves; do not assume
the last one still works.

## 2. Pairing (once per phone, network only)

1. The person opens **Add a screen** on the Deck (the Car Thing, or `http://127.0.0.1:8791/#add`
   on the Mac). It shows a 6-digit code for five minutes.
2. The app sends it:

```
POST /api/pair
Content-Type: application/json

{"code": "123456", "app": true, "name": "Ride app"}
```

3. Answers, always HTTP 200:
   - `{"ok": true, "name": "Ride app", "key": "<43 characters>"}`: store `key` privately.
   - `{"ok": false, "why": "wrong"}`: wrong code. Five wrong codes withdraw the code.
   - `{"ok": false, "why": "closed"}`: no code is showing. Ask the person to open Add a screen.

From then on send the key with every request:

```
x-deck-token: <key>
```

The Deck keeps only a hash of the key. **Forget** on the Deck makes the key stop working (`401`).
The older way still works too: a `phoneToken` set by hand in the Deck's `config.json`, sent in the
same header.

## 3. Reading and controlling

- `GET /api/state`: the whole dashboard as JSON (channel, video board, Claude sessions, renders,
  alerts, Mac volume, `uploads` true or false, `offload` progress rows). Poll about once a second
  while the Deck view is on screen, not in the background.
- `POST /api/action` with a JSON body: `{"t":"app","key":"studio"}` opens a Launch tile,
  `{"t":"vol","d":4}` changes volume, `{"t":"mute"}`. Unknown actions are ignored.
- Music: `state.music` is `null`, or `{"app","playing","title","artist"}` for what Spotify or
  Music is playing on the Mac. `{"t":"music","do":"playpause"}` (or `"next"`, `"prev"`).
- Answers: each item of `state.board.blocked` has a key at the same place in `state.board.bk`,
  and each video in `state.board.owed` with a next step has one in `nk`. `state.board.answers`
  maps a key to `{"a": "ok" | "done" | "not yet", "ago"}` once answered.
  `{"t":"answer","k":"<key>","a":"ok"}` answers an ask; any other `a` takes the answer back. A
  key that is not on the board now is ignored.
- `{"t":"standbyAll","n":"<anything unique>"}` sends every screen showing the Deck to standby.

Do not send an `Origin` header that differs from the address being called; the Deck refuses those.

## 4. Sending rides and media home

Only when `state.uploads` is true.

**Ask what is wanted** (so nothing is sent twice):

```
POST /api/upload/check
{"device": "Joe's Moto", "files": [
  {"name": "ride_2026-11-23_1712.gpx", "size": 48213, "mtime": 1795478000, "kind": "ride"},
  {"name": "VID_20261123_171530.mp4", "size": 734003200, "mtime": 1795478130, "kind": "media"}]}
```

Answer: `{"ok": true, "need": [names], "have": [names], "refused": [{"name", "why"}]}`.

**Send each file in `need`**, one at a time, as the raw body:

```
PUT /api/upload?name=<file name>&mtime=<seconds>&device=<phone name>&kind=ride|media&i=<this one>&n=<how many>
Content-Length: <exact size>

<the file's bytes>
```

- `{"ok": true, "saved": true, "name": "..."}`: stored. Mark it sent.
- `{"ok": true, "saved": false, "why": "already here"}`: mark it sent.
- `{"ok": false, "why": "..."}` with `403` uploads off, `411` no length, `413` too big (default
  8 GB), `415` type not accepted, `507` drive missing or full, `400` arrived incomplete. Show
  `why` to the rider; retry later for `400` and `507`.

**Beside each ride's track, send its numbers** as `<same name>.json`, also `kind=ride`. The Deck
shows the newest ride from these (it does not read the track), and says "Ride home" when one
arrives:

```json
{"app": "MAXAPACITY Ride", "units": "mi", "file": "ride_2026-11-23_1712.gpx",
 "start": 1795478000000, "distanceM": 19955, "movingMs": 1490000, "elapsedMs": 1620000,
 "topMps": 18.33, "climbM": 96, "stops": 2, "leanLeft": 38, "leanRight": 41, "battery": 64}
```

`start` (milliseconds) and `distanceM` are needed; the rest is shown when present. `units` is
`mi` or `km`, whichever the rider reads. `battery` is the percent left as the ride ended; leave
it out when it was not read.

Rules the Deck applies:

- Accepted types: jpg, jpeg, png, heic, heif, dng, gif, webp, mp4, mov, m4v, 3gp, gpx, fit, tcx,
  kml, json, csv, txt.
- `kind` "ride" (or a ride file type) lands in `<offload folder>/<device>/Rides/`; everything else
  in `<offload folder>/<device>/<date of mtime>/`. `mtime` becomes the file's date on the Mac.
- Nothing is overwritten. The same name with a different size is kept beside the first as
  `name (2).ext`.
- A file counts only when every byte arrived. `i` and `n` are optional and only feed the progress
  line on the Deck ("2 of 5 files").
- A few seconds after the last file, the Deck shows one alert: "<device>: 5 files received".

## 5. What the app should do with this

- **Link up by itself:** on start and when the network changes, try USB, then discovery. If found
  but not paired, show "Deck found" and ask for the 6-digit code. No address typing.
- **Offload when home:** after a ride is saved, and whenever the Deck becomes reachable, run check
  then send, in a background service, on Wi-Fi or USB only, never on mobile data. Keep a "sent"
  mark per ride so a ride is offered once.
- **Say what happened:** "3 rides sent to your Mac" or the Deck's `why`.
- **Rides over the cable:** the Mac's own USB offload can only read
  `/sdcard/Android/data/com.maxapacity.ride/files/`. Writing each ride's GPX there (via
  `getExternalFilesDir`) lets it be collected even when the app is not running.
