#!/bin/bash
# Starts the Deck service. Used by the login item.
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
cd "$(dirname "$0")" || exit 1
NODE=$(command -v node || ls -d "$HOME"/.nvm/versions/node/*/bin/node 2>/dev/null | sort -V | tail -1)
[ -x "$NODE" ] || { echo "node not found"; sleep 60; exit 1; }
exec "$NODE" server.mjs
