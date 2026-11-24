# MAXAPACITY Deck

A desk dashboard for video creators, running on a Spotify Car Thing. It shows your channel, your
video pipeline and what Claude is working on, and it controls your Mac: volume, mute, apps, and
one-tap "play the latest cut". Left alone, it becomes a standby screen with the time, a bar for
everything in progress, and your own pictures and footage behind it.

This folder is the Mac half of MaxRidr. The phone app is in `app/`, and `APP-LINK.md` here is the
contract between the two. `install.sh` copies this folder to where the Deck runs on the Mac;
`sync-from-mac.sh` copies the running Deck back here so changes can be committed.

Nothing is installed on the Car Thing. The Mac drives the device's own browser over USB, so
unplugging it and plugging it back in returns it to the stock screen.

## What it shows

| Screen | What is on it |
|---|---|
| **Home** | A ring gauge of subscribers toward the next goal, three counts (editing, ready to post, waiting on you), what Claude is doing now with one cell per task, latest uploads with views per hour |
| **Videos** | Every video on the board with a percent ring, status, next step and the newest cut file. Tap for details, then Watch cut / Folder / Studio / YouTube |
| **Claude** | Each recent Claude session with a live task bar, renders and encodes in progress, things that just happened, files ready for you, and what is waiting on you |
| **Launch** | App tiles with icons, mute, volume |
| **Settings** (tap the clock, or the fifth button) | Day / Night brightness, Standby, alert sound, ring gauges for disk space and Mac load, Add a screen, Reload |
| **Standby** | After four idle minutes, or at once from **Standby** in Settings: a large clock, subscribers, pipeline counts, and large rows with a bar for each Claude session, render and encode (the first one also lists its individual steps). Behind it: code rain, pictures from the channel and the website, and now and then a short silent clip from a finished video. Any touch or button wakes it |

Alerts pop up on the device when Claude finishes a task, a file becomes ready for you, a video
changes status, a render or long encode ends, or the subscriber count moves.

## Controls

- **Knob**: Mac volume. **Knob press**: mute.
- **Top buttons 1 to 4**: Home, Videos, Claude, Launch. **Fifth button**: Settings.
- **Back button**: close the open sheet, or go Home.
- **Standby whenever you like**: tap the clock (or press the fifth button), then **Standby**. It
  works the same on the Car Thing, the preview on the Mac, a paired screen and the Ride app's Full
  Deck, and sends that one screen to standby. A window on the Mac that is controlling the other
  screens takes them with it.

## Other screens: a phone, a tablet, another computer

The same Deck can show on any screen with a browser that is on the same network as the Mac. It is
off until `config.json` has `"remote": true`.

1. On the Deck (the Car Thing, or the preview on the Mac), tap the clock, then **Add a screen**.
   It shows a QR code, an address such as `http://192.168.1.20:8791` and a 6-digit code, good for
   five minutes.
   On the Mac, `open "http://127.0.0.1:8791/#add"` goes straight to that sheet.
2. On the new screen, scan the QR code, or open the address and type the code.
3. That screen now shows the Deck and stays paired. To remove it, open **Add a screen** again and
   tap **Forget** next to it. It drops back to the pairing page within a couple of seconds.

The 800x480 layout is scaled to fit whatever the screen is. A phone held upright gets the Deck
turned on its side, so it reads the right way up once the phone is turned, whether or not the
phone's rotation is locked. Settings on a paired screen has **Full screen** where the browser
offers it (Android, iPad, computers). On an iPhone, Share then Add to Home Screen should give an
icon that opens without the browser's bars; expect it to ask for a code once, because iOS keeps a
home-screen page's storage separate from Safari's.

Pairing over the network was checked on 23 Nov 2026 in a desktop browser set to phone and tablet
sizes, with the QR code read back by two decoders. Later that day a Moto G Stylus (2025) with Firefox paired over Wi-Fi and kept running after it
was unplugged. No iPhone or tablet has tried it, so the iPhone notes here are what that browser
is documented to do, not what was seen.

### An Android phone on a USB cable

With `"usbScreens": true`, an Android phone plugged into the Mac shows the Deck with no network:

1. On the phone, turn on USB debugging (Settings, About phone, tap Build number seven times, then
   Developer options, USB debugging) and allow this Mac when the phone asks.
2. Plug it in. The log says `usb: <phone> is linked`.
3. Open `http://localhost:8791` in the phone's browser. Or on the Deck: tap the clock, **Add a
   screen**, then **Open** next to the phone, which wakes it and opens that address.

Over the cable there is no pairing code (the phone counts as the Mac itself), the page can keep
the phone's screen on, and the phone charges. If the cable is unplugged or jostled the link is
lost; the Deck puts it back within a few seconds and the page carries on. Any app on that phone
can reach the Deck the same way, so use a phone you trust.

Seen working on 23 Nov 2026 on a Moto G Stylus (2025) with Firefox 160: the layout, the screen
staying on past the phone's one-minute timeout, and the link returning 2 seconds after it was
dropped. Other phones and browsers have not been tried.

### Offload from a phone on USB

With `"usbOffload": true` (and `usbScreens` on), plugging an Android phone in copies its new photos,
videos and ride files to the Mac, with a progress bar on the Claude tab and the standby screen and
an alert when it is done.

- Where they land: `offloadTo`, or `_PHONE OFFLOAD` on the footage drive when that is empty, in
  `<phone name>/<date the file was made>/`. If that drive is not plugged in, it waits.
- What is taken: files under the phone folders in `offloadFolders` (default `DCIM`, the camera).
  Add `Android/data/com.maxapacity.ride/files` for rides saved by the MAXAPACITY Ride app. The
  first time a phone is seen it reaches back `offloadSinceDays` days, then takes everything new.
- Copy only. Nothing on the phone is changed or deleted, nothing on the Mac is overwritten, a
  file counts as done only when the copy is the same size as the original, and 20 GB is always
  left free. Unplugging mid-way is safe: the rest follows next time.
- What has been copied is remembered per phone in `data/offload/`. Delete that file to start over.

Run for real on 23 Nov 2026 with a Moto G Stylus (2025): one photo came across at its full size
with its original date, and the next pass copied nothing twice. A large video and an unplug
mid-copy have not been tried. The Ride app's rides did not come across: its shared folder was
empty and its private storage cannot be read over USB, so the app has to save or send them.

### The MAXAPACITY Ride phone app

The full contract for a phone app is in `APP-LINK.md`: how it finds the Deck (USB, or Bonjour
service `_maxdeck._tcp` on the network), pairs with the 6-digit code, reads state, and sends
rides and media home. In short:

- **Finding it:** with `remote` on, the Deck announces itself on the network, so an app needs no
  address typed in.
- **Pairing:** the same 6-digit code as a browser screen; the app is handed a key and sends it
  in an `x-deck-token` header. It then appears under Paired screens and can be forgotten there.
- **Uploads:** with `"uploads": true`, an app that is let in can send rides and media over the
  network. They land where the USB offload puts them (rides in `<phone>/Rides`), only known
  media and ride file types are taken, nothing is overwritten, and the Deck shows progress and
  one alert per batch. `uploadMaxGB` is the largest single file (default 8).

Tested on 23 Nov 2026 with plain requests from this Mac and over its network address: a ride
file and a 3 MB clip arrived byte for byte, a repeat was skipped, a script file and a request
with no key were refused. No app has used any of it yet.

The older sign-in still works:

The Ride app's Deck mode reads the same `/api/state` and `/api/action` as a browser screen. Over
USB it uses `127.0.0.1` and needs nothing. Over Wi-Fi it sends the `phoneToken` from
`config.json` (8 characters or more; empty turns it off) and is then treated as a paired screen
called "Ride app". Enter the Mac's Wi-Fi address and that token in the app's Deck connection.

### Controlling the other screens from the Mac

A Deck window on the Mac can drive every other screen showing the Deck, the Car Thing included:
they show the same tab, the same open sheet, the same scroll position and go to standby with it.

- Switch it on: `open "http://127.0.0.1:8791/#drive"`, or in a Deck window on the Mac tap the
  clock, **Add a screen**, **Control screens** (that browser then remembers it).
- Mouse: click and scroll as usual. Keyboard: `1` to `4` or the left and right arrows change tab,
  up and down arrows scroll, `M` opens Settings, `Esc` closes a sheet or goes Home, `S` goes to
  standby, any key wakes, `Enter` mutes the Mac.
- A controlled screen stays awake while the controlling window is awake, and goes back to
  running itself a minute after that window is closed. Touching it still works; the next thing
  done on the Mac puts it back in step. The same goes for the Car Thing's knob, buttons and touch.
- Only this Mac (or a phone on USB) can control; a screen paired over the network cannot.

What to know before turning it on:

- A paired screen can do what the Car Thing can: see the board, the Claude task names and the
  channel figures, change the Mac's volume, and open the apps, folders and files the Deck lists.
- Only the Deck itself (the Car Thing and this Mac) can show a code or forget a screen. Five wrong
  codes withdraw the code. Up to eight screens stay paired.
- Only private network addresses are answered (home and office ranges), and the Mac has to be
  addressed by its IP address, a `.local` name or `remoteName`.
- The connection is plain HTTP, not encrypted. Use it on a network you trust, not on shared Wi-Fi.
- Browsers only let a page keep the screen awake on a secure (HTTPS or localhost) address, so over
  the network that is the device's own setting: Auto-Lock on iOS, "Stay awake while charging" in
  Android's developer options.
- If a screen cannot open the address at all, check that it is really on the Mac's network. Two
  routers in one home often both hand out `192.168.1.x` addresses, so matching numbers prove
  nothing: compare the router (gateway) address on the Mac and on the screen. A Mac on a cable to
  one router and a phone on another router's Wi-Fi cannot see each other.
- A Mac on two networks at once (a cable to one router, Wi-Fi from another) hands out its Wi-Fi
  address, because phones and tablets are on the Wi-Fi; the wired address is shown under it as
  "on a cable". A new address is picked up within about 10 seconds.
- If the Mac's address changes, pair again from the new address. Setting `remoteName` to the Mac's
  `.local` name (System Settings, General, Sharing shows it) keeps one address that follows the Mac.
- macOS's firewall has to allow incoming connections to `node`. The official Node.js build (from
  nodejs.org or nvm) is signed, and the firewall's default settings allow signed software without
  asking. With another build, macOS may ask once.

## Requirements

- A Car Thing whose firmware has `adb` enabled (the common community images do). Without one, the
  preview and paired screens still work: start it with `node server.mjs --no-device`.
- macOS with Node.js 22 or newer and `adb` (`brew install node android-platform-tools`).
- For standby pictures and clips: `ffmpeg` (`brew install ffmpeg`). Without it the standby screen
  still works, with code rain only.

## Install

```bash
./install.sh
```

This copies the files to `~/Library/Application Support/MAXAPACITY Deck/`, adds a login item,
and starts it. Run it again to update. `./uninstall.sh` removes the login item.

Preview it in any browser at <http://127.0.0.1:8791/>.

## Configure

Everything personal is in `config.json` (created from `config.example.json`):

- `channel`, `subGoal`: the YouTube channel and the subscriber goal for the Home gauge.
- `boardFile`: a JSON file with `owed`, `inflight` and `blocked_on_joe` lists (the Dash board).
- `roots`, `footage`: how paths on the board map to this Mac, and where each video's `EDIT` folder lives.
- `claudeTitleFilter`: only show Claude sessions whose title matches this pattern.
- `apps`: the Launch tiles. Each has a `url`, an `app` name, or a `path`, and may name an `icon`:
  `film`, `chart`, `play`, `wheels`, `cut`, `drive`, `done`, `folder`, `globe`, `spark`, `app`.
- Standby: `standbyPictures`, `standbyClips` (on or off), `standbyPictureSec` (seconds per picture),
  `standbyClipEveryMin` (minutes between clips), `website` (pictures on that page are used, that
  site's own only), `pictureFolders` and `clipFolders` (extra folders of your own).
- Offload: `usbOffload`, `offloadTo`, `offloadFolders`, `offloadSinceDays`. Uploads from an app:
  `uploads`, `uploadMaxGB`. Ride app's older sign-in: `phoneToken`.
- Other screens: `remote` (true lets paired screens on this network show the Deck) and `remoteName`
  (an address to show instead of the Mac's IP address, such as `my-mac.local`). `usbScreens`
  (true keeps a link open to Android phones on USB, so they can open `http://localhost:8791`).
- `fullBrightness`, `nightBrightness`, `ambientAfterSec`, `disks`, `soundFile`.

After editing, restart:

```bash
launchctl kickstart -k gui/$(id -u)/com.maxapacity.deck
```

## How it works

- `server.mjs` (Mac): collects data, serves the preview, and holds one DevTools connection to the
  Car Thing's kiosk browser through `adb forward`. It writes `ui.html` into that browser, pushes
  state to it, and receives taps back as console messages.
- `ui.html` (device): one self-contained page written for the Car Thing's Chromium 69. Served over
  HTTP it is the same page with a phone-friendly head put in by the server; it then scales itself
  to the window and polls the Mac for state instead of being pushed it.
- `offload.mjs`: choosing which phone files are new and where they go. `pair.html`: what an unpaired screen gets. `qr.mjs`: the QR code maker (no dependencies).
  `icon-*.png`: the home-screen icon.
- `data/`: view history (for views per hour), the settings chosen on the device, `screens.json`
  (paired screens: a name and a hash of each screen's key, never the key), and `data/media/` with
  the standby pictures (800x480 JPEGs) and clips (folders of 640x384 JPEG frames).
- Who the server answers: this Mac (the preview) always; with `remote` on, a paired screen on a
  private network address. A request from this Mac has to name it as `127.0.0.1` or `localhost`,
  and anything that changes something has to come from the Deck's own page, so another website
  open in a browser on the Mac cannot read or drive the Deck.
- A page served over HTTP carries the build of `ui.html` it came from and reports it each time it
  asks for state. When `ui.html` changes, every open page reloads itself within a couple of
  seconds, so a screen left running picks up a new version unattended. Each page also tells the
  Mac its size and whether it could keep its screen on; that goes in the log as a `screen` line.
- To try pairing on one computer: `DECK_TEST_REMOTE=1 DECK_PORT=8793 node server.mjs --no-device`
  treats `http://localhost:8793` as a screen elsewhere and `http://127.0.0.1:8793` as the Deck.
- Standby pictures come from the channel's thumbnails, the pictures on `website`, and
  `pictureFolders`. Clips are cut from the newest export in each `<footage>/<project>/EDIT`
  folder and from `<footage>/_FINISHED VIDEOS`: three short shots per clip, no sound. Eight clips
  are kept; one is replaced after it has played three times. Clips are only made while the Mac is
  not busy encoding. The footage drive is needed to make clips, not to play them.

## What it reads

- YouTube: the public channel page, upload feed and thumbnails only. The subscriber count is the
  rounded public figure. Exact counts, watch time and revenue would need a channel sign-in.
- The website named in `website`: its home page and the pictures on it.
- Claude: session titles and task lists from the Claude desktop app's local files, plus file
  modified times. Code-tab sessions keep their task list only inside the session's transcript
  file, so that file is scanned for the plan entries; nothing else in it is kept, shown or sent
  anywhere.
- The board file, the Studio server's render list, running `ffmpeg` processes, and disk space.

## Rules learned the hard way

- **Never run `adb reverse`** against the Car Thing. Its adb service crashes and USB stays down
  until the device is replugged. `usbScreens` does use `adb reverse`, for Android phones only: a
  device is skipped if it is the Car Thing by name, if it is the device the Deck is driving, or
  if it does not answer `getprop` the way Android does. When typing adb commands by hand with
  more than one device attached, always name the device with `-s`.
- **The Car Thing's browser cannot play video.** It has no decoder for any format, so clips are
  JPEG frames played in sequence at 12 a second.
- **Its memory is small and its graphics side keeps every image it is handed.** Drawing 160
  full-size frames the ordinary way ran the browser out of memory in four seconds. So frames are
  drawn on canvases smaller than 257x256 pixels (those are drawn in software), every decoded frame
  is closed right after it is drawn, and after each clip the Mac asks the browser to empty its
  caches. If free memory on the device drops under 40 MB, pictures and clips pause for half an hour.
- **Continuous animation is fine** when it is a whole element moved with transform or opacity. It
  raises the kernel's `fb: unsupport fmt` count, but that shows no visible flicker on the device.
- **Leave `fullBrightness` off.** Forcing brightness above the stock ceiling is the suspected cause
  of the display going dark while the device keeps running.
- **Do not touch the backlight service during boot.** Brightness is only set after 75 seconds of
  device uptime.
- The page must stay Chromium 69-safe: no optional chaining, no flex `gap`, comma-style colors.
- Type: the page asks for the Car Thing's built-in Circular typeface and falls back to the system
  sans elsewhere. That typeface belongs to Spotify, so screenshots of the device are not suitable
  as marketing for a product sold under another name. Titles are 23px or larger and running text 20px or larger: ISO 15008 puts the minimum letter height at 12 arcminutes, which on this 4-inch screen is about a 23px font at 50 cm and 32px at 70 cm.

Log: `~/Library/Logs/maxapacity-deck.log`.
