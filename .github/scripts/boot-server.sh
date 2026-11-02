#!/usr/bin/env bash
# Boots a dedicated server through Gradle, waits for "Done", then stops it.
# Usage: boot-server.sh <gradle task> <run dir>
set -u
TASK="$1"; RUNDIR="$2"
mkdir -p "$RUNDIR"
echo "eula=true" > "$RUNDIR/eula.txt"
printf 'online-mode=false\nlevel-type=minecraft\\:flat\n' > "$RUNDIR/server.properties"
LOG=boot-$(echo "$TASK" | tr ':' '_').log
./gradlew "$TASK" --console=plain > "$LOG" 2>&1 &
PID=$!
STATUS=1
for i in $(seq 1 180); do
  if grep -q 'Done (' "$LOG"; then STATUS=0; break; fi
  if ! kill -0 $PID 2>/dev/null; then break; fi
  sleep 5
done
pkill -f "net.minecraft.server.Main|DedicatedServer|neoforge.*server|fabric.*server" 2>/dev/null || true
kill $PID 2>/dev/null || true
sleep 5
grep -nE "Mixin apply|FAILED during APPLY|was not applied|Exception|ERROR|damage_tester" "$LOG" | head -60
if [ $STATUS -ne 0 ]; then echo "::error::$TASK did not reach Done"; tail -120 "$LOG"; fi
exit $STATUS
