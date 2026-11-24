# MaxRidr

MAXAPACITY Ride: an Android home screen for the bike phone. GPS speedometer,
automatic ride recording with history and Strava upload, a Bluetooth link to
the bike (ANT BMS battery, Fardriver controller, with tuning), an app drawer,
Copilot, a Deck mode, and over-the-air updates. Each ride records its furthest
lean left and right, and an optional crash alert texts your location to a
contact if a hard hit is followed by the bike not moving.

Plain Android SDK, Java, no libraries. Android 12 or newer.

## Layout

| Path | What |
|---|---|
| `app/` | The app: `src/`, `res/`, `assets/`, `AndroidManifest.xml`, and `tests/` |
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
