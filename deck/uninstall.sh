#!/bin/bash
# Removes the Deck login item. Your config and history stay in the install folder unless you pass --purge.
NAME="${DECK_NAME:-MAXAPACITY Deck}"
LABEL="${DECK_LABEL:-com.maxapacity.deck}"
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null
rm -f "$HOME/Library/LaunchAgents/$LABEL.plist"
[ "$1" = "--purge" ] && rm -rf "$HOME/Library/Application Support/$NAME"
echo "Removed. Unplug and replug the Car Thing to return it to its stock screen."
