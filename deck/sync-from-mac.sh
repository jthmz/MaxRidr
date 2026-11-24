#!/bin/bash
# Copies the Deck that is running on this Mac into this folder, so the changes can be committed.
# The running copy lives in ~/Library/Application Support/<name>/ (see install.sh, which goes the other way).
# Your config.json, the data folder and backup files are never copied.
set -e
NAME="${DECK_NAME:-MAXAPACITY Deck}"
SRC="$HOME/Library/Application Support/$NAME"
DST="$(cd "$(dirname "$0")" && pwd)"
[ -f "$SRC/server.mjs" ] || { echo "No Deck found in: $SRC"; exit 1; }
rsync -a --exclude config.json --exclude 'data/' --exclude '*.bak-*' --exclude '.git/' --exclude '.DS_Store' --exclude sync-from-mac.sh "$SRC/" "$DST/"
echo "Copied from: $SRC"
echo "Now review and commit the changes in: $DST"
