#!/usr/bin/env bash
# End-to-end test with the production jar: real dedicated server + real client (offline, under Xvfb).
# The selftest driver inside the test build runs the scenario; this script takes the screenshots,
# restarts the server for the save/load check and compares server and client logs.
# usage: e2e.sh <fabric|neoforge|forge>
set -u
L=$1
prop() { grep "^$1=" gradle.properties | cut -d= -f2 | tr -d '\r'; }
MC=$(prop minecraft_version)
JAVA="${GAME_JAVA:-$JAVA_HOME}/bin/java"
case $L in
  fabric) LV=$(prop fabric_loader_version) ;;
  neoforge) LV=$(prop neoforge_version) ;;
  forge) LV=$(prop forge_version) ;;
esac
E=$PWD/e2e
rm -rf "$E"; mkdir -p "$E/shots"
L1=".github/scripts/mclaunch.py"

echo "::group::build (selftest)"
./gradlew ":$L:build" -Pselftest --console=plain > "$E/build.log" 2>&1 || { tail -60 "$E/build.log"; exit 1; }
JAR=$(ls $L/build/libs/*.jar | grep -vE 'sources|-dev|slim' | head -1)
echo "jar: $JAR"
MODS=("$JAR")
if [ "$L" = fabric ]; then
  FAPI=$(prop fabric_version)
  curl -fsSL -o "$E/fabric-api-$FAPI.jar" "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$FAPI/fabric-api-$FAPI.jar"
  MODS+=("$E/fabric-api-$FAPI.jar")
fi
echo "::endgroup::"

echo "::group::install server and client"
SRV=$E/server
CLI=$E/client
python3 $L1 server --dir "$SRV" --mc "$MC" --loader "$L" --loader-version "$LV" --java "$JAVA" > "$E/srvcmd.txt" || exit 1
mkdir -p "$SRV/mods"; cp "${MODS[@]}" "$SRV/mods/"
echo "eula=true" > "$SRV/eula.txt"
printf 'online-mode=false\nwhite-list=false\nenforce-whitelist=false\nenforce-secure-profile=false\nspawn-protection=0\nlevel-type=minecraft\\:flat\nview-distance=5\nsimulation-distance=5\nspawn-monsters=false\nspawn-npcs=false\nspawn-animals=false\ngenerate-structures=false\nallow-flight=true\nmax-tick-time=-1\n' > "$SRV/server.properties"
echo "-Xmx2G -Ddamagetester.selftest=true" > "$SRV/jvm_args.txt"
start_server() { (cd "$SRV" && exec $(cat "$E/srvcmd.txt")) > "$E/server$1.log" 2>&1 & }
start_server 1
VID=$(python3 $L1 client --dir "$CLI" --mc "$MC" --loader "$L" --loader-version "$LV" --java "$JAVA") || exit 1
echo "client version: $VID"
mkdir -p "$CLI/mods"; cp "${MODS[@]}" "$CLI/mods/"
printf 'onboardAccessibility:false\nskipMultiplayerWarning:true\njoinedFirstServer:true\ntutorialStep:none\npauseOnLostFocus:false\nrenderDistance:5\nsimulationDistance:5\nsoundCategory_master:0.0\nnarrator:0\nguiScale:2\nrenderClouds:"false"\nfov:0.0\n' > "$CLI/options.txt"
echo "::endgroup::"

for i in $(seq 1 180); do grep -q 'Done (' "$E/server1.log" && break; sleep 5; done
grep -q 'Done (' "$E/server1.log" || { echo "::error::server did not start"; tail -80 "$E/server1.log"; exit 1; }

python3 $L1 launch --dir "$CLI" --id "$VID" --java "$JAVA" --jvm-arg=-Ddamagetester.selftest=true \
  --game-arg=--quickPlayMultiplayer --game-arg=localhost:25565 --game-arg=--width --game-arg=1280 --game-arg=--height --game-arg=720 \
  > "$E/client.log" 2>&1 &
CLIENT=$!

# screenshots at every SHOT marker
(
  tail -n +1 -F "$E/server1.log" 2>/dev/null | while read -r line; do
    case "$line" in
      *"[DT-SELFTEST] SHOT "*)
        n=$(echo "$line" | sed 's/.*SHOT \([^ ]*\).*/\1/')
        import -display "$DISPLAY" -window root "$E/shots/${n}.png" 2>/dev/null
        sleep 0.6
        import -display "$DISPLAY" -window root "$E/shots/${n}_b.png" 2>/dev/null ;;
      *"PHASE1 DONE"*) exit 0 ;;
    esac
  done
) &
WATCH=$!

STATUS=0
for i in $(seq 1 360); do
  grep -q "PHASE1 DONE" "$E/server1.log" && break
  if grep -qE "Minecraft has crashed|---- Minecraft Crash Report" "$E/client.log"; then echo "::error::client crashed"; STATUS=1; break; fi
  if [ $((i % 12)) -eq 0 ]; then
    echo "[$((i * 5))s] server: $(grep -E 'DT-SELFTEST\] (STEP|START)|joined the game|lost connection' "$E/server1.log" | tail -1 | cut -c1-150)"
    echo "        client: $(tail -1 "$E/client.log" | cut -c1-150)"
  fi
  sleep 5
done
grep -q "PHASE1 DONE" "$E/server1.log" || { echo "::error::phase 1 did not finish"; STATUS=1; }
import -display "$DISPLAY" -window root "$E/shots/zz_last.png" 2>/dev/null
kill $CLIENT 2>/dev/null; sleep 3; pkill -f "$CLI" 2>/dev/null
for i in $(seq 1 60); do pgrep -f "$SRV" >/dev/null || pgrep -f "fabric-server-launch|unix_args" >/dev/null || break; sleep 2; done
pkill -f "fabric-server-launch|unix_args" 2>/dev/null; sleep 2
kill $WATCH 2>/dev/null

if [ $STATUS -eq 0 ]; then
  start_server 2
  for i in $(seq 1 120); do grep -q "PHASE2 DONE" "$E/server2.log" && break; sleep 5; done
  pkill -f "fabric-server-launch|unix_args" 2>/dev/null
fi

python3 .github/scripts/e2e_check.py "$E" "$MC" "$L" || STATUS=1
exit $STATUS
