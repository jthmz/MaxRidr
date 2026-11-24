#!/bin/bash
# Installs the Deck as a login item on this Mac. Safe to run again to update.
#   ./install.sh            install or update
#   ./uninstall.sh          remove
set -e
NAME="${DECK_NAME:-MAXAPACITY Deck}"
LABEL="${DECK_LABEL:-com.maxapacity.deck}"
SRC="$(cd "$(dirname "$0")" && pwd)"
DST="$HOME/Library/Application Support/$NAME"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

command -v adb >/dev/null || { echo "adb is missing. Install it with: brew install android-platform-tools"; exit 1; }
NODE=$(command -v node || ls -d "$HOME"/.nvm/versions/node/*/bin/node 2>/dev/null | sort -V | tail -1)
[ -x "$NODE" ] || { echo "Node.js 22 or newer is missing. Install it with: brew install node"; exit 1; }
MAJOR=$("$NODE" -p 'process.versions.node.split(".")[0]')
[ "$MAJOR" -ge 22 ] || { echo "Node.js $MAJOR is too old; 22 or newer is needed."; exit 1; }

mkdir -p "$DST" "$HOME/Library/LaunchAgents" "$HOME/Library/Logs"
if [ "$SRC" != "$DST" ]; then cp "$SRC/server.mjs" "$SRC/qr.mjs" "$SRC/offload.mjs" "$SRC/ui.html" "$SRC/pair.html" "$SRC"/icon-*.png "$SRC/run.sh" "$SRC/config.example.json" "$SRC/README.md" "$SRC/APP-LINK.md" "$SRC/uninstall.sh" "$DST/"; fi
[ -f "$DST/config.json" ] || cp "$DST/config.example.json" "$DST/config.json"
chmod +x "$DST/run.sh" "$DST/uninstall.sh"

cat > "$PLIST" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array><string>/bin/bash</string><string>$DST/run.sh</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>10</integer>
  <key>StandardOutPath</key><string>$HOME/Library/Logs/maxapacity-deck.log</string>
  <key>StandardErrorPath</key><string>$HOME/Library/Logs/maxapacity-deck.log</string>
</dict></plist>
PL
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "Installed in: $DST"
echo "Edit $DST/config.json, then restart with: launchctl kickstart -k gui/$(id -u)/$LABEL"
echo "Preview in a browser: http://127.0.0.1:8791/"
