# MaxRidr

Two halves that find each other:

- **MAXAPACITY Ride** (`app/`): an Android home screen for the bike phone. GPS
  speedometer, automatic ride recording with history, Strava upload and
  Health Connect sharing (both a tap to turn on, see below), a Bluetooth link to the bike (ANT BMS battery, Fardriver controller, with
  tuning), an app drawer, Copilot, and over-the-air updates. Each ride records
  its furthest lean left and right, and with the bike linked, what the bike
  did: peak power, energy used and given back, the deepest voltage sag and
  the hottest temperatures. An optional crash alert texts your
  location to a contact if a hard hit is followed by the bike not moving. A
  new rider is offered a tour of the home screen, and after an update that
  brings something new the app says what (both stay in Settings, with a
  button to the MAXAPACITY website).
  A demo bike on the Bike screen shows every battery and controller screen with
  made-up numbers when no bike is nearby. On the Tune screen, Tunes loads a
  whole tune in one tap: three made to measure for the controller in hand
  (Calm, Balanced, Aggressive, as shares of what it came set to), or one
  from MAXAPACITY's own bikes: the tunes published for its builds, and the
  Arctic Leopard XE Pro R as it was delivered, to put a changed bike back. A
  tune loads as changes to look over and goes through the same trial as any
  other change. Currents and top speed never go above what the controller
  held when the app first met it, with one exception: the owner-tested DRAX
  Apex tune, which loads only on the Apex's own controller and only whole.
  Regen can be set to come on with the brake or when the throttle closes;
  the app warns before the second is tried. Tune ideas writes up the
  bike's settings and limits for the rider to send to an AI app or a tuner;
  it only reads, and nothing leaves the phone until the rider sends it. It
  carries the last ride's figures for the bike, so the advice is about what
  happened. The
  reply can be copied and pasted back: the app loads the changes it names,
  each held to what the bike allows, for the rider to look over and try.
- **MAXAPACITY Deck** (`deck/`): a service on the Mac that drives a desk
  dashboard on a Spotify Car Thing, and takes rides home from the phone. It
  has its own [README](deck/README.md).

The app is plain Android SDK, Java, no libraries. Android 12 or newer.

## Compare a sprocket swap on the home phone

In the Deck ride inbox, open a ride with installed gearing and choose **Pin benchmark loop**. Give the route a name. Ride it again on the same bike with the new sprockets installed in the calculator. Open the later ride on the Deck, confirm the GPS match, and tap how it felt. The Deck keeps a baseline per bike and remembers the answer for each ride. Use the same start, finish and direction for both recordings.

Choose **Share ride card** on either phone to preview a MAXAPACITY image, select the ride stats, add a photo, and send the finished card through an Android app of your choice. The card contains the displayed pixels, without a GPS track or the original photo’s metadata.

## On the website

Both halves have a page on [maxapacitybikes.com/apps](https://maxapacitybikes.com/apps):
what each does, how to set it up, and how they reach each other and the
Studio (the tool the channel's videos are cut with, kept in a private
repository).

- [MAXAPACITY Ride](https://maxapacitybikes.com/apps/ride) shows the current
  version, its size and its checksum, and offers the download. It reads them
  in the visitor's browser from the `ride-ota` release, the same listing an
  installed phone updates from. **That listing now has two readers.** A change
  to its shape (the manifest's fields, the file's name, the tag) has to be
  made in three places together: `Updater.java` here, and `src/lib/ride-release.ts`
  and its test fixture in the website's repository.
- [MAXAPACITY Deck](https://maxapacitybikes.com/apps/deck) offers this
  repository as a ZIP and gives the setup steps from `deck/README.md`.
- In the app, Settings ends with "About and help", which opens the app's page.
  The row above it, "MAXAPACITY website", opens the site's home page.
- [maxapacitybikes.com/apps.json](https://maxapacitybikes.com/apps.json) is the
  same directory for a program: where each app lives, how to get it, how to
  reach it (the Deck's port, its Bonjour name, its key header) and every link
  between the apps. It is schema 1 and never drops a field.

The words on those pages live in the website's repository (`src/data/apps.ts`
and `src/data/apps-directory.json` in `jthmz/maxapacity`). They were written
from this README and `deck/README.md`, so when a change here alters what a
rider or a Deck owner sees, change the page with it.

## How the phone and the Deck link

To use a spare Android phone as the home receiver, open **Deck → Make this
phone my Deck** on that phone. Tap **Pair a riding phone**, then open **Deck**
on the riding phone and enter the six digits. Both phones use the same home
Wi-Fi. The receiver can stay in the background and receives the GPX and JSON
files for saved rides when the rider comes home. The files are under
`Android/data/com.maxapacity.ride/files/Received rides/` on the home phone.
Open **Deck** on the riding phone to see queued rides, transfer progress and the last sync.
On the home phone, the **Ride inbox** shows completed rides newest first. Tap a ride
for its route and recorded numbers. If Wi-Fi drops, the next sync checks which files
arrived and retries the missing ones. Each completed ride appears once.

The inbox also offers optional PayPal support, with a QR code for a second phone.
Riders can also donate from Settings. PayPal handles the amount and checkout.

Open **Apps → Part Picker** to choose a bike and explore its build, or **Apps →
Sprocket Calculator** to compare gearing. The calculator works from first launch without internet, including wheel torque
change, speed and chain-length estimates. Both online and offline, the in-app calculator
restores the selected bike’s last valid setup. Choose a bike or add a named one for a
second build. Enter your installed tooth counts, then choose a gearing goal to try a
two-tooth rear change. Mark the current or proposed pair installed once it is fitted.
The recorder captures that bike’s installed setup at ride start. A ride with an earlier
different pair on the same bike shows both rides’ distance, moving time and top speed.
The home Deck can compare rides it has received, too. The website link carries the current numbers. The Part Picker uses the live catalog
and opens a recognized model from linked hardware or the rider’s “About my bike”
note. Unknown or conflicting models open the bike chooser.

### Using the Mac Deck

Nobody types an address. The app looks for the Deck by itself: down a USB
cable first, then by the Deck's announcement on the home Wi-Fi, then at the
last address that worked. Plenty of home networks never pass that
announcement on, so when nothing answers the app asks every address on its
own Wi-Fi instead, which also finds a Mac whose address has changed.

- **Over USB** nothing is asked: the Deck trusts a phone cabled to its Mac.
- **Over Wi-Fi** the app shows "Deck found". On the Deck, tap the clock, then
  Add a screen, and type the 6-digit code it shows into the app. Once.

After that the app's Deck screen shows the desk, and every ride is sent to the
Mac after it is saved, or as soon as the phone is next in reach of the Deck. It
only ever uses Wi-Fi or the cable, never mobile data, and a ride is sent once.

The Deck screen is a glance in the app's own style. "Full Deck" on it opens
the whole Deck, the same page the Car Thing shows with every tab and control,
let in with the key the phone already holds. A phone left there on a charger
is a desk display.

When the link will not come up, the Deck connection sheet offers every other
way in (search again, type or paste the Deck's address or its whole link, a
USB cable), a short list of what to check, and a link report: what the phone
tried and what answered, ready to share with whoever is helping. The report
never holds the phone's key.

The Deck has to allow it: `"remote": true` in its `config.json` to be found on
the network, and `"uploads": true` to take rides. The contract between the two
is [deck/APP-LINK.md](deck/APP-LINK.md).

## Strava and Health Connect

Neither asks a rider for a key or an account detail.

**Strava.** Settings, Strava, Connect, then Authorize on Strava's own screen,
which opens in the Strava app when it is installed. Send to Strava on a ride
does the same and uploads that ride once connected. Rides saved before
connecting are offered, not sent unasked; rides after it upload on their own.

Strava only signs riders in through a registered API application, so that
one tap needs this app's own application in the build. Setting it up is a
one-time job for whoever publishes builds:

1. At [strava.com/settings/api](https://www.strava.com/settings/api) create
   an application with Authorization Callback Domain set to `localhost`.
2. Add its Client ID and Client Secret to this repository as Actions secrets
   named `STRAVA_CLIENT_ID` and `STRAVA_CLIENT_SECRET`.

The next build carries them, and the build's summary says so. A build made
without them still works: it asks each rider for an application of their
own, as every build once did, and a phone connected that way keeps using its
own.

Two things to know. Strava limits how many riders a new application may sign
in, starting with only its owner; the limit is raised on the application's
page. And the secret travels inside the app, which anyone can download: with
it someone could pose as this app to Strava and use up its allowance, but
could not reach a rider's account, whose sign-in never leaves their phone.
If that happens, make a new secret on Strava's page and update the
repository secret; phones get it with the next update.

**Health Connect** (Android 14 and later). Settings, Health Connect, Turn on,
then Allow on the phone's own screen. Every saved ride, old and new, is
written to Health Connect as a cycling workout with its route and distance,
and apps the rider has let read workouts there pick it up: Oura, Google
Health, Samsung Health and others. In Oura that is Settings, Data Sharing,
Health Connect. The app only writes; it reads nothing from Health Connect.

## Layout

| Path | What |
|---|---|
| `app/` | The app: `src/`, `res/`, `assets/`, `AndroidManifest.xml`, and `tests/` |
| `deck/` | The Deck: the Mac service, its screens, and `APP-LINK.md` |
| `build.gradle`, `settings.gradle` | The Gradle build |
| `tools/ride-test.sh` | Installs the app on a plugged-in phone and photographs every screen |
| `tools/test/` | Checks for that script, against a pretend phone |
| `.github/workflows/` | `build.yml` tests, builds and publishes the app; `test-script.yml` checks the script |

## Install on a phone

1. On the [releases page](https://github.com/jthmz/MaxRidr/releases), open the
   newest "MAXAPACITY Ride" draft and download `maxapacity-ride.apk` and
   `ride-test.sh`. Drafts are visible only when signed in with write access.
2. Plug the phone in, unlock it, and run:

```bash
bash ~/Downloads/ride-test.sh
```

It installs the app, opens each screen, and saves pictures and a `report.txt`
to a folder on the Desktop. It needs `adb` (`brew install android-platform-tools`).

After that the app updates itself from this repository's `ride-ota` release.

## Build

```bash
gradle testReleaseUnitTest assembleRelease
```

Needs JDK 17, Gradle 8.10 or newer and the Android SDK (platform 35). The APK
lands in `build/outputs/apk/release/`. Without a signing key in the
environment it is signed with the debug key; see the top of
`.github/workflows/build.yml` for how CI signs and publishes.

## Updates

Every push that touches the app builds a draft release. A push to `main`, or
a run of the "Build app" workflow started by hand, also publishes that build
to the `ride-ota` release, which installed phones check every six hours.

Phones update quietly, so the app tells the rider what an update brought: a
"What's new" sheet the first time the home screen opens on it. The notes are
written by hand, at the top of `NOTES` in
[WhatsNew.java](app/src/com/maxapacity/ride/WhatsNew.java); the comment there
says how. A build with no new note says nothing, and Settings keeps the
earlier notes. The same Settings group has the tour and the two ways to the
website.

Builds are signed with a key the workflow creates on its first run and keeps
on a draft release. Android only installs an update signed with the same key
as the installed app, so a phone carrying a build from before this repository
existed (1.0.19 or older, from `jthmz/maxapacity`) needs this app installed
once over USB; `ride-test.sh` offers to save its rides first.

## History

The app was developed in `jthmz/maxapacity` under `os/` and moved here with
its commits. The first commits in this repository keep those old paths.
