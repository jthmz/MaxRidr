#!/bin/bash
#
# MAXAPACITY Ride: put the app on the phone plugged into this computer, walk
# through its screens and save a picture of each, with a short report.
#
#   bash ride-test.sh                   install maxapacity-ride.apk (looked for
#                                       next to this script, then in Downloads)
#   bash ride-test.sh path/to/app.apk   install that file
#   bash ride-test.sh --no-install      only walk through the app already there
#   bash ride-test.sh --yes             do not ask before replacing an older copy
#
# Needs adb (on a Mac: brew install android-platform-tools) and USB debugging
# switched on. Runs under the bash that ships with macOS (3.2).
#
# What it does to the phone: installs or updates the app, opens its screens
# and taps the search box once. It grants no permissions, changes no settings
# and touches no other app. If the copy on the phone is signed with a
# different key (builds before over-the-air updates were), Android will not
# update it; the script then asks, saves that copy's rides into the results
# folder, removes it and installs the new one.

set -u

PKG=com.maxapacity.ride
ACTIVITY="$PKG/.RideActivity"
MIN_SDK=31

install=1
assume_yes=0
apk=""

usage() {
  sed -n '3,13p' "$0" | sed 's/^# \{0,1\}//'
}

say() {
  printf '%s\n' "$*"
}

fail() {
  printf '\nStopped: %s\n' "$*" >&2
  exit 1
}

while [ $# -gt 0 ]; do
  case "$1" in
    --no-install) install=0 ;;
    --yes) assume_yes=1 ;;
    -h | --help)
      usage
      exit 0
      ;;
    -*) fail "unknown option $1 (try --help)" ;;
    *) apk="$1" ;;
  esac
  shift
done

# Output of a command on the phone, without the carriage returns adb adds.
phone() {
  adb shell "$@" 2>/dev/null | tr -d '\r'
}

# ---- The phone ---------------------------------------------------------------

command -v adb >/dev/null 2>&1 \
  || fail "adb is not installed. On a Mac: brew install android-platform-tools"

listing=$(adb devices 2>/dev/null | sed '1d' | grep -v '^[[:space:]]*$')
ready=$(printf '%s\n' "$listing" | awk '$2 == "device"' | wc -l | tr -d ' ')
if [ "$ready" = 0 ]; then
  case "$listing" in
    *unauthorized*)
      fail "the phone has not allowed this computer yet. Unlock it, accept the \"Allow USB debugging\" prompt, then run this again."
      ;;
    *)
      fail "no phone found. Plug it in, unlock it, and check USB debugging is on (Settings > System > Developer options)."
      ;;
  esac
fi
if [ "$ready" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
  say "More than one phone is connected:"
  printf '%s\n' "$listing" | awk '$2 == "device" { print "  " $1 }'
  fail "choose one, for example: ANDROID_SERIAL=<one of the above> bash $0"
fi

model=$(phone getprop ro.product.model)
release=$(phone getprop ro.build.version.release)
sdk=$(phone getprop ro.build.version.sdk)
case "$sdk" in
  '' | *[!0-9]*) fail "could not read the phone's Android version. Is it unlocked?" ;;
esac
say "Phone: $model, Android $release"
if [ "$sdk" -lt "$MIN_SDK" ]; then
  fail "the app needs Android 12 or newer; this phone runs Android $release."
fi

# ---- Where results go --------------------------------------------------------

base="$HOME/Desktop"
[ -d "$base" ] || base="$PWD"
out="$base/ride-test-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out" || fail "could not create $out"
report="$out/report.txt"

# ---- Install -----------------------------------------------------------------

installed_version() {
  phone dumpsys package "$PKG" | sed -n 's/^ *versionName=//p' | sed -n '1p'
}

before=$(installed_version)

if [ "$install" = 1 ]; then
  if [ -z "$apk" ]; then
    here=$(cd "$(dirname "$0")" && pwd)
    for dir in "$here" "$HOME/Downloads"; do
      # Newest wins: a browser calls a second download "maxapacity-ride (1).apk".
      for f in "$dir"/maxapacity-ride*.apk; do
        [ -f "$f" ] || continue
        if [ -z "$apk" ] || [ "$f" -nt "$apk" ]; then apk="$f"; fi
      done
      [ -n "$apk" ] && break
    done
  fi
  [ -n "$apk" ] || fail "no maxapacity-ride.apk next to this script or in Downloads. Download it from the same release as this script, or give its path."
  [ -f "$apk" ] || fail "there is no file at $apk"

  say "Installing $(basename "$apk") ..."
  result=$(adb install -r "$apk" 2>&1)
  case "$result" in
    *Success*) ;;
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE* | *"signatures do not match"*)
      say ""
      say "The copy on the phone (${before:-older build}) is signed with a different key, so"
      say "Android will not update it in place. It has to be removed first, which"
      say "erases its settings. Its saved rides are copied into the results folder."
      if [ "$assume_yes" != 1 ]; then
        printf 'Remove it and install the new one? [y/N] '
        answer=""
        read -r answer || answer=""
        case "$answer" in
          y | Y | yes | Yes) ;;
          *) fail "nothing was changed on the phone." ;;
        esac
      fi
      rides=$(phone ls "/sdcard/Android/data/$PKG/files/rides" | grep -c '\.gpx$')
      if [ "$rides" -gt 0 ]; then
        # Never remove rides that could not be copied off first.
        if adb pull "/sdcard/Android/data/$PKG/files/rides" "$out/rides-from-old-app" >/dev/null 2>&1; then
          say "Saved the old copy's rides ($rides) to $out/rides-from-old-app"
        else
          fail "the old copy has saved rides ($rides) and they could not be copied off the phone, so nothing was removed. To remove it anyway: adb uninstall $PKG"
        fi
      else
        say "The old copy has no saved rides."
      fi
      adb uninstall "$PKG" >/dev/null 2>&1
      result=$(adb install "$apk" 2>&1)
      case "$result" in
        *Success*) ;;
        *) fail "the old copy was removed but the new one did not install: $result" ;;
      esac
      ;;
    *INSTALL_FAILED_VERSION_DOWNGRADE*)
      fail "the phone already has a newer build (${before:-unknown}) than this file. Download the latest APK and run this again."
      ;;
    *INSTALL_FAILED_OLDER_SDK*)
      fail "this phone's Android is too old for the app."
      ;;
    *INSTALL_FAILED_VERIFICATION_FAILURE* | *INSTALL_FAILED_USER_RESTRICTED* | *INSTALL_FAILED_ABORTED* | *"canceled by user"*)
      fail "the phone refused the install. Look at its screen: if it is asking (Play Protect, or \"Install via USB\"), allow it, then run this again."
      ;;
    *)
      fail "adb could not install the app: $result"
      ;;
  esac
fi

version=$(installed_version)
[ -n "$version" ] || fail "the app is not on the phone. Run this without --no-install."
if [ -n "$before" ] && [ "$before" != "$version" ]; then
  say "App: $version (was $before)"
else
  say "App: $version"
fi

# ---- Walk through the screens ------------------------------------------------

size=$(phone wm size | sed -n 's/.*: *\([0-9][0-9]*\)x\([0-9][0-9]*\).*/\1 \2/p' | tail -n 1)
width=${size% *}
height=${size#* }
density=$(phone wm density | sed -n 's/.*: *\([0-9][0-9]*\).*/\1/p' | tail -n 1)
case "$width$height" in
  '' | *[!0-9]*)
    width=1080
    height=2340
    ;;
esac

count=0
crashed_at=""

app_in_front() {
  phone dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | grep -q "$PKG"
}

# Shows a screen by name ("home" is the dashboard) and lets it settle.
show() {
  adb shell am start -n "$ACTIVITY" --es open "$1" >/dev/null 2>&1
  sleep 1.5
}

shot() {
  adb exec-out screencap -p >"$out/$1.png" 2>/dev/null
  # A real picture starts with the PNG signature; anything else is an error message.
  if [ "$(head -c 4 "$out/$1.png" 2>/dev/null | tail -c 3)" = PNG ]; then
    count=$((count + 1))
    say "  $1"
  else
    rm -f "$out/$1.png"
    say "  $1: the phone gave no picture"
  fi
}

# True once the app has died. Keeps what Android logged about it.
crashed() {
  if adb logcat -d -b crash 2>/dev/null | grep -q "$PKG"; then
    adb logcat -d -b crash >"$out/crash.txt" 2>/dev/null
    return 0
  fi
  return 1
}

# One screen: show it, picture it, and for long screens picture the lower half.
visit() { # name, file, "scroll" or ""
  [ -n "$crashed_at" ] && return
  show "$1"
  if crashed; then
    crashed_at="$1"
    return
  fi
  shot "$2"
  if [ "${3:-}" = scroll ]; then
    adb shell input swipe $((width / 2)) $((height * 3 / 4)) $((width / 2)) $((height / 4)) 350 >/dev/null 2>&1
    sleep 1
    shot "$2-lower"
  fi
}

say ""
say "Unlock the phone and leave it be for half a minute."
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
adb logcat -b crash -c >/dev/null 2>&1
adb shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1

show home
tries=0
while ! app_in_front; do
  tries=$((tries + 1))
  if [ "$tries" -gt 3 ]; then
    if crashed; then
      crashed_at="start-up"
      break
    fi
    fail "the app would not come to the front. Is the phone unlocked?"
  fi
  printf 'The app is not in front. Unlock the phone, then press Return. '
  read -r _ || true
  show home
done

say "Screens:"
visit home 01-dashboard
visit apps 02-apps

# The keyboard: this used to squeeze the dashboard until the app fell over,
# and it is the one thing a phone lays out differently from a test bench.
if [ -z "$crashed_at" ]; then
  box=$(adb exec-out uiautomator dump /dev/tty 2>/dev/null | tr '>' '\n' \
    | grep 'class="android.widget.EditText"' | sed -n '1p' \
    | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')
  if [ -n "$box" ]; then
    # shellcheck disable=SC2086  # four numbers, split on purpose
    set -- $box
    adb shell input tap $((($1 + $3) / 2)) $((($2 + $4) / 2)) >/dev/null 2>&1
  else
    adb shell input tap $((width / 2)) $((130 * ${density:-420} / 160)) >/dev/null 2>&1
  fi
  sleep 2
  if crashed; then
    crashed_at="apps, keyboard open"
  else
    shot 03-apps-keyboard
    adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1
    sleep 1
  fi
fi

visit rides 04-rides
visit copilot 05-copilot
visit bike 06-bike scroll
visit tune 07-tune
visit deck 08-deck
visit settings 09-settings scroll
visit home 10-dashboard-again

# ---- Report ------------------------------------------------------------------

home_app=$(phone cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -n 1)
case "$home_app" in
  "$PKG"/*) is_home="yes" ;;
  *) is_home="no (it is ${home_app:-unknown})" ;;
esac

{
  say "MAXAPACITY Ride on-phone test, $(date '+%Y-%m-%d %H:%M')"
  say ""
  say "Phone:        $model, Android $release (SDK $sdk)"
  say "Screen:       ${width}x${height} px at ${density:-?} dpi"
  say "App:          $version${before:+ (before this run: $before)}"
  say "Home screen:  $is_home"
  say "Pictures:     $count"
  if [ -n "$crashed_at" ]; then
    say "Crash:        YES, at \"$crashed_at\". Details in crash.txt"
  else
    say "Crash:        none"
  fi
  say ""
  say "How smoothly it drew (from the phone):"
  phone dumpsys gfxinfo "$PKG" | grep -E 'Total frames rendered|Janky frames|percentile' | sed 's/^ */  /'
  say ""
  say "Warnings and errors the app logged:"
  pid=$(phone pidof "$PKG" | awk '{ print $1 }')
  if [ -n "$pid" ]; then
    adb logcat -d --pid="$pid" '*:W' 2>/dev/null | tr -d '\r' | tail -n 60 | sed 's/^/  /'
  else
    say "  (the app is not running)"
  fi
} >"$report" 2>&1

say ""
if [ -n "$crashed_at" ]; then
  say "The app CRASHED at \"$crashed_at\"."
else
  say "No crash. $count pictures taken."
fi
say "Results: $out"
say "Send back report.txt and the pictures (and crash.txt if there is one)."
if [ "$(uname)" = Darwin ] && command -v open >/dev/null 2>&1; then
  open "$out" >/dev/null 2>&1
fi

[ -z "$crashed_at" ] || exit 2
exit 0
