#!/usr/bin/env bash
# Boots a client under a virtual display until the title screen resources are loaded
# (this constructs every entity renderer), takes a screenshot of the display, then stops it.
# Usage: boot-client.sh <gradle task> <run dir> <screenshot.png>
set -u
TASK="$1"; RUNDIR="$2"; SHOT="$3"
mkdir -p "$RUNDIR"
LOG=client-boot.log
./gradlew "$TASK" --console=plain > "$LOG" 2>&1 &
PID=$!
STATUS=1
for i in $(seq 1 120); do
  if grep -qE "Minecraft has crashed|Crash Report|Exception in thread \"Render thread\"" "$LOG"; then break; fi
  if grep -qE "Sound engine started|Failed to open OpenAL device|Error starting SoundSystem" "$LOG" && [ $i -gt 6 ]; then
    sleep 40; STATUS=0; break
  fi
  if ! kill -0 $PID 2>/dev/null; then break; fi
  sleep 5
done
if grep -qE "Minecraft has crashed|Crash Report" "$LOG"; then STATUS=1; fi
import -display "$DISPLAY" -window root "$SHOT" 2>/dev/null || true
pkill -f "java.*(fabric|neoforge|minecraft)" 2>/dev/null || true
kill $PID 2>/dev/null || true
sleep 3
grep -nE "damage_tester|ERROR|Exception" "$LOG" | grep -viE "realms|narrator|flite|openal|alsa|SoundSystem|Anisotropic|GLX|backend OpenGL|authlib|401|sessionserver" | head -40
if [ $STATUS -ne 0 ]; then echo "::error::$TASK did not boot cleanly"; tail -150 "$LOG"; fi
exit $STATUS
