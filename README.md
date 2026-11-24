# MaxRidr

Two halves that find each other:

- **MAXAPACITY Ride** (`app/`): an Android home screen for the bike phone. GPS
  speedometer, automatic ride recording with history and Strava upload, a
  Bluetooth link to the bike (ANT BMS battery, Fardriver controller, with
  tuning), an app drawer, Copilot, and over-the-air updates. Each ride records
  its furthest lean left and right, and an optional crash alert texts your
  location to a contact if a hard hit is followed by the bike not moving. A
  demo bike on the Bike screen shows every battery and controller screen with
  made-up numbers when no bike is nearby. Tune ideas, on the Tune screen,
  writes up the bike's settings and limits for the rider to send to an AI app
  or a tuner; it only reads, and nothing leaves the phone until the rider
  sends it.
- **MAXAPACITY Deck** (`deck/`): a service on the Mac that drives a desk
  dashboard on a Spotify Car Thing, and takes rides home from the phone. It
  has its own [README](deck/README.md).

The app is plain Android SDK, Java, no libraries. Android 12 or newer.

## How the phone and the Deck link

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

When the link will not come up, the Deck connection sheet offers every other
way in (search again, type or paste the Deck's address or its whole link, a
USB cable), a short list of what to check, and a link report: what the phone
tried and what answered, ready to share with whoever is helping. The report
never holds the phone's key.

The Deck has to allow it: `"remote": true` in its `config.json` to be found on
the network, and `"uploads": true` to take rides. The contract between the two
is [deck/APP-LINK.md](deck/APP-LINK.md).

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

Builds are signed with a key the workflow creates on its first run and keeps
on a draft release. Android only installs an update signed with the same key
as the installed app, so a phone carrying a build from before this repository
existed (1.0.19 or older, from `jthmz/maxapacity`) needs this app installed
once over USB; `ride-test.sh` offers to save its rides first.

## History

The app was developed in `jthmz/maxapacity` under `os/` and moved here with
its commits. The first commits in this repository keep those old paths.
