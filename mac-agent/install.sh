#!/usr/bin/env bash
# Installs the Seamless agent on macOS and starts it automatically at login (launchd).
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
VENV="$HOME/.seamless/venv"
PLIST="$HOME/Library/LaunchAgents/dev.seamless.agent.plist"

python3 -m venv "$VENV"
"$VENV/bin/pip" install --quiet --upgrade pip
"$VENV/bin/pip" install --quiet -r "$DIR/requirements.txt"
mkdir -p "$HOME/.seamless" "$HOME/Library/LaunchAgents"
cp "$DIR/seamless_agent.py" "$HOME/.seamless/seamless_agent.py"

cat > "$PLIST" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>dev.seamless.agent</string>
  <key>ProgramArguments</key>
  <array>
    <string>$VENV/bin/python</string>
    <string>$HOME/.seamless/seamless_agent.py</string>
    <string>run</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$HOME/.seamless/agent.log</string>
  <key>StandardErrorPath</key><string>$HOME/.seamless/agent.log</string>
</dict>
</plist>
PLIST

launchctl bootout "gui/$(id -u)" "$PLIST" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"

echo
echo "✅ Seamless agent installed and running (starts automatically at login)."
echo "   Logs: ~/.seamless/agent.log"
"$VENV/bin/python" "$HOME/.seamless/seamless_agent.py" pair
echo "macOS may ask to allow incoming connections for Python — click Allow."
