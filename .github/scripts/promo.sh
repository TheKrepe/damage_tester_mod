#!/usr/bin/env bash
# Records the promo choreography (selftest/Promo.java) with the production jar on a real client.
# usage: promo.sh <fabric|neoforge|forge>   (run under xvfb-run with a 540x960 screen)
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
E=$PWD/promo
rm -rf "$E"; mkdir -p "$E/out"
L1=".github/scripts/mclaunch.py"
./gradlew ":$L:build" -Pselftest --console=plain > "$E/build.log" 2>&1 || { tail -60 "$E/build.log"; exit 1; }
JAR=$(ls $L/build/libs/*.jar | grep -vE 'sources|-dev|slim' | head -1)
MODS=("$JAR")
if [ "$L" = fabric ]; then
  FAPI=$(prop fabric_version)
  curl -fsSL -o "$E/fabric-api-$FAPI.jar" "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$FAPI/fabric-api-$FAPI.jar"
  MODS+=("$E/fabric-api-$FAPI.jar")
fi
SRV=$E/server
CLI=$E/client
python3 $L1 server --dir "$SRV" --mc "$MC" --loader "$L" --loader-version "$LV" --java "$JAVA" > "$E/srvcmd.txt" || exit 1
mkdir -p "$SRV/mods"; cp "${MODS[@]}" "$SRV/mods/"
echo "eula=true" > "$SRV/eula.txt"
printf 'online-mode=false\nwhite-list=false\nenforce-whitelist=false\nenforce-secure-profile=false\nspawn-protection=0\nlevel-type=minecraft\\:flat\nview-distance=6\nsimulation-distance=6\nspawn-monsters=false\nspawn-npcs=false\nspawn-animals=false\ngenerate-structures=false\nmax-tick-time=-1\n' > "$SRV/server.properties"
echo "-Xmx2G -Ddamagetester.selftest=true -Ddamagetester.promo=true" > "$SRV/jvm_args.txt"
(cd "$SRV" && exec $(cat "$E/srvcmd.txt")) > "$E/server.log" 2>&1 &
VID=$(python3 $L1 client --dir "$CLI" --mc "$MC" --loader "$L" --loader-version "$LV" --java "$JAVA") || exit 1
mkdir -p "$CLI/mods"; cp "${MODS[@]}" "$CLI/mods/"
printf 'onboardAccessibility:false\nskipMultiplayerWarning:true\njoinedFirstServer:true\ntutorialStep:none\npauseOnLostFocus:false\nrenderDistance:6\nsimulationDistance:6\nsoundCategory_master:0.0\nnarrator:0\nguiScale:2\nmaxFps:60\nenableVsync:false\nfov:0.0\nrenderClouds:"true"\n' > "$CLI/options.txt"
for i in $(seq 1 180); do grep -q 'Done (' "$E/server.log" && break; sleep 5; done
python3 $L1 launch --dir "$CLI" --id "$VID" --java "$JAVA" --jvm-arg=-Ddamagetester.selftest=true \
  --game-arg=--quickPlayMultiplayer --game-arg=localhost:25565 --game-arg=--width --game-arg=540 --game-arg=--height --game-arg=960 \
  > "$E/client.log" 2>&1 &
REC=""
for i in $(seq 1 600); do
  if [ -z "$REC" ] && grep -q "MARK ready" "$E/server.log"; then
    date +%s%3N > "$E/out/rec_start.txt"
    ffmpeg -y -loglevel error -f x11grab -framerate 30 -video_size 540x960 -draw_mouse 0 -i "$DISPLAY" \
      -c:v libx264 -preset veryfast -crf 12 -pix_fmt yuv420p "$E/out/rec.mp4" < /dev/null &
    REC=$!
  fi
  grep -q "\[DT-PROMO\] DONE" "$E/server.log" && break
  sleep 1
done
sleep 1
[ -n "$REC" ] && kill -INT $REC && wait $REC
pkill -f "$CLI" 2>/dev/null; pkill -f "fabric-server-launch|unix_args" 2>/dev/null
grep "\[DT-PROMO\]" "$E/server.log" | sed 's/.*\[DT-PROMO\] //' > "$E/out/marks.txt"
cat "$E/out/marks.txt"
gzip -c "$E/client.log" > "$E/out/client.log.gz"; gzip -c "$E/server.log" > "$E/out/server.log.gz"
[ -s "$E/out/rec.mp4" ]
