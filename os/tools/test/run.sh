#!/usr/bin/env bash
#
# Dry-runs ride-test.sh against a pretend phone (bin/adb) and checks what it
# says, what it leaves behind and what it did to the phone. No phone needed.
#
#   bash os/tools/test/run.sh
#
# Written for the bash that ships with macOS (3.2), like the script it tests.

set -u

here=$(cd "$(dirname "$0")" && pwd)
script="$here/../ride-test.sh"
work=$(mktemp -d "${TMPDIR:-/tmp}/ride-test.XXXXXX") || exit 1
trap 'rm -rf "${work:?}"' EXIT

PKG=com.maxapacity.ride
passed=0
failed=0
name=""
OUT=""
CODE=0

# A fresh phone and a fresh home folder for each case.
start() {
  name="$1"
  rm -rf "${work:?}/state" "${work:?}/home"
  mkdir -p "$work/state" "$work/home/Desktop" "$work/home/Downloads"
}

have_apk() {
  echo first >"$work/home/Downloads/maxapacity-ride.apk"
  touch -t 202601010000 "$work/home/Downloads/maxapacity-ride.apk"
}

on_phone() { # version already installed
  echo "$1" >"$work/state/installed"
}

# run "<what is typed>" [VAR=value ...] [-- script arguments]
run() {
  typed="$1"
  shift
  vars=("HOME=$work/home" "FAKE_STATE_DIR=$work/state" "PATH=$here/bin:$PATH")
  while [ $# -gt 0 ] && [ "$1" != "--" ]; do
    vars+=("$1")
    shift
  done
  [ $# -gt 0 ] && shift
  OUT=$(printf '%s' "$typed" | env "${vars[@]}" bash "$script" "$@" 2>&1)
  CODE=$?
}

ok() {
  passed=$((passed + 1))
}

bad() {
  failed=$((failed + 1))
  printf 'FAIL  %s: %s\n' "$name" "$1"
  printf '%s\n' "$OUT" | sed 's/^/      | /'
}

exits() {
  if [ "$CODE" = "$1" ]; then ok; else bad "exit code $CODE, expected $1"; fi
}

says() {
  case "$OUT" in
    *"$1"*) ok ;;
    *) bad "did not say: $1" ;;
  esac
}

does_not_say() {
  case "$OUT" in
    *"$1"*) bad "said: $1" ;;
    *) ok ;;
  esac
}

did() { # the phone was asked this
  if grep -q -- "$1" "$work/state/calls.log" 2>/dev/null; then ok; else bad "never ran: $1"; fi
}

did_not() {
  if grep -q -- "$1" "$work/state/calls.log" 2>/dev/null; then bad "ran: $1"; else ok; fi
}

results() { # the one results folder
  for d in "$work/home/Desktop"/ride-test-*; do
    [ -d "$d" ] && printf '%s' "$d" && return
  done
}

has_file() {
  if [ -f "$(results)/$1" ]; then ok; else bad "no $1 in the results"; fi
}

has_no_file() {
  if [ -e "$(results)/$1" ]; then bad "unexpected $1 in the results"; else ok; fi
}

report_says() {
  if grep -q -- "$1" "$(results)/report.txt" 2>/dev/null; then ok; else bad "report lacks: $1"; fi
}

installed_is() {
  now=$(cat "$work/state/installed" 2>/dev/null)
  if [ "$now" = "$1" ]; then ok; else bad "phone has ${now:-nothing}, expected $1"; fi
}

# ---- A normal run --------------------------------------------------------------

start "installs, walks every screen, writes a report"
have_apk
on_phone 1.0.14
run ""
exits 0
says "Phone: moto g stylus - 2025, Android 15"
says "App: 1.0.15 (was 1.0.14)"
says "No crash. 12 pictures taken."
for shot in 01-dashboard 02-apps 03-apps-keyboard 04-rides 05-copilot 06-bike 06-bike-lower \
  07-tune 08-deck 09-settings 09-settings-lower 10-dashboard-again; do
  has_file "$shot.png"
done
has_no_file crash.txt
report_says "Android 15 (SDK 35)"
report_says "1220x2712 px at 400 dpi"
report_says "Crash:        none"
report_says "Janky frames: 9"
report_says "Home screen:  no"
did "adb install -r"
did_not "adb uninstall"
for screen in home apps rides copilot bike tune deck settings; do
  did "open $screen"
done
# Only the app is touched: no permission grants, no settings, no other package.
did_not "pm grant"
did_not "settings put"
did_not "pm uninstall"
did_not "pm clear"

start "picks the newest download"
have_apk
echo second >"$work/home/Downloads/maxapacity-ride (1).apk"
touch -t 202601020000 "$work/home/Downloads/maxapacity-ride (1).apk"
run ""
exits 0
says "Installing maxapacity-ride (1).apk"

start "takes a path"
echo apk >"$work/home/somewhere.apk"
run "" -- "$work/home/somewhere.apk"
exits 0
says "Installing somewhere.apk"

start "a path that is not there"
run "" -- "$work/home/missing.apk"
exits 1
says "there is no file at"
did_not "adb install"

start "says so when it is the home screen"
have_apk
run "" "FAKE_HOME=$PKG/.RideActivity"
exits 0
report_says "Home screen:  yes"

start "taps the middle of the search box"
have_apk
run ""
did "input tap 595 310"

start "taps by position when the screen cannot be read"
have_apk
run "" FAKE_NO_UIAUTOMATOR=1
exits 0
did "input tap 610 325"
has_file 03-apps-keyboard.png

start "walks through without installing"
on_phone 1.0.15
run "" -- --no-install
exits 0
did_not "adb install"
says "No crash. 12 pictures taken."

# ---- The phone is not ready ----------------------------------------------------

start "no phone"
have_apk
run "" FAKE_DEVICES=none
exits 1
says "no phone found"
did_not "adb install"

start "phone has not allowed this computer"
have_apk
run "" FAKE_DEVICES=unauthorized
exits 1
says "Allow USB debugging"

start "two phones, none chosen"
have_apk
run "" FAKE_DEVICES=two
exits 1
says "More than one phone is connected"
did_not "adb install"

start "two phones, one chosen"
have_apk
run "" FAKE_DEVICES=two ANDROID_SERIAL=ZY22FAKE01
exits 0

start "Android too old"
have_apk
run "" FAKE_SDK=30 FAKE_RELEASE=11
exits 1
says "needs Android 12 or newer; this phone runs Android 11"
did_not "adb install"

start "no APK anywhere"
run ""
exits 1
says "no maxapacity-ride.apk"

start "nothing installed and told not to install"
run "" -- --no-install
exits 1
says "the app is not on the phone"

start "locked at first, then unlocked"
have_apk
run "
" FAKE_LOCKED_FOR=1
exits 0
says "Unlock the phone, then press Return."
says "No crash. 12 pictures taken."

start "locked and left locked"
have_apk
run "" FAKE_LOCKED_FOR=99
exits 1
says "would not come to the front"

# ---- An older copy signed with another key --------------------------------------

start "older copy: answering no changes nothing"
have_apk
on_phone 1.0-debug
run "n
" FAKE_INSTALL=incompatible
exits 1
says "signed with a different key"
says "nothing was changed on the phone"
did_not "adb uninstall"
installed_is 1.0-debug

start "older copy: no answer changes nothing"
have_apk
on_phone 1.0-debug
run "" FAKE_INSTALL=incompatible
exits 1
did_not "adb uninstall"
installed_is 1.0-debug

start "older copy: yes keeps its rides, then replaces it"
have_apk
on_phone 1.0-debug
run "y
" FAKE_INSTALL=incompatible
exits 0
says "Saved the old copy's rides (1)"
has_file rides-from-old-app/ride_2026-11-20_1810.gpx
did "adb uninstall $PKG"
installed_is 1.0.15
says "App: 1.0.15 (was 1.0-debug)"

start "older copy: --yes does not ask"
have_apk
on_phone 1.0-debug
run "" FAKE_INSTALL=incompatible FAKE_NO_RIDES=1 -- --yes
exits 0
does_not_say "[y/N]"
says "The old copy has no saved rides."
installed_is 1.0.15

start "older copy: rides that cannot be copied are never removed"
have_apk
on_phone 1.0-debug
run "" FAKE_INSTALL=incompatible FAKE_PULL_FAILS=1 -- --yes
exits 1
says "could not be copied off the phone, so nothing was removed"
did_not "adb uninstall"
installed_is 1.0-debug

# ---- The install is refused -----------------------------------------------------

start "the phone refuses the install"
have_apk
run "" FAKE_INSTALL=refused
exits 1
says "the phone refused the install"

start "a newer build is already there"
have_apk
on_phone 1.0.20
run "" FAKE_INSTALL=downgrade
exits 1
says "already has a newer build (1.0.20)"

start "any other failure shows what adb said"
have_apk
run "" FAKE_INSTALL=weird
exits 1
says "INSTALL_FAILED_INSUFFICIENT_STORAGE"

# ---- The app crashes -------------------------------------------------------------

start "a crash part-way is caught and the walk stops there"
have_apk
run "" FAKE_CRASH_AT=bike
exits 2
says "The app CRASHED at \"bike\"."
has_file crash.txt
has_file 05-copilot.png
has_no_file 06-bike.png
report_says "Crash:        YES, at \"bike\""
did_not "open tune"
did_not "open settings"

start "a crash on start-up"
have_apk
run "" FAKE_CRASH_AT=home
exits 2
says "The app CRASHED"
has_file crash.txt
has_no_file 01-dashboard.png

start "a crash when the keyboard opens"
have_apk
run "" FAKE_CRASH_ON_TAP=1
exits 2
says "The app CRASHED at \"apps, keyboard open\"."
has_file 02-apps.png
has_no_file 03-apps-keyboard.png

# ---- Help -----------------------------------------------------------------------

start "help"
run "" -- --help
exits 0
says "bash ride-test.sh --no-install"
says "ships with macOS (3.2)"

start "unknown option"
run "" -- --frobnicate
exits 1
says "unknown option --frobnicate"

printf '\n%d checks passed, %d failed\n' "$passed" "$failed"
[ "$failed" = 0 ]
