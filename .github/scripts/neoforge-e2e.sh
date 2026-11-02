#!/usr/bin/env bash
# End-to-end NeoForge check: dedicated server + real client joining it.
# A datapack places two testers in front of the player and hits them every 15 ticks,
# so damage packets travel over a real network connection to the client.
set -u
SRV=neoforge/runs/server
mkdir -p "$SRV/world/datapacks"
cp -r .github/test-datapack "$SRV/world/datapacks/dt_test"
echo "eula=true" > "$SRV/eula.txt"
printf 'online-mode=false\nspawn-protection=0\nwhite-list=false\nenforce-whitelist=false\n' > "$SRV/server.properties"

./gradlew :neoforge:runServer --console=plain > server.log 2>&1 &
for i in $(seq 1 120); do grep -q 'Done (' server.log && break; sleep 5; done
grep -q 'Done (' server.log || { echo "::error::server did not start"; tail -80 server.log; exit 1; }
# op everyone so the datapack can run as them is not needed; functions run as server.

mkdir -p neoforge/runs/client
printf 'onboardAccessibility:false\nskipMultiplayerWarning:true\njoinedFirstServer:true\ntutorialStep:none\n' > neoforge/runs/client/options.txt
./gradlew :neoforge:runJoinTest --console=plain > client.log 2>&1 &
JOINED=0
for i in $(seq 1 90); do
  if grep -q 'joined the game' server.log; then JOINED=1; break; fi
  if grep -qE "Minecraft has crashed|Crash Report" client.log; then break; fi
  sleep 5
done
sleep 45
import -display "$DISPLAY" -window root neoforge-ingame.png 2>/dev/null || true
sleep 3
import -display "$DISPLAY" -window root neoforge-ingame-2.png 2>/dev/null || true

STATUS=0
[ $JOINED -eq 1 ] || { echo "::error::client never joined"; STATUS=1; }
if grep -q "Failed to load function" server.log; then echo "::error::test datapack broken"; STATUS=1; fi
if grep -qE "lost connection|Disconnecting|disconnected" server.log; then echo "::error::client got disconnected"; STATUS=1; fi
if grep -qE "Minecraft has crashed|Crash Report" client.log server.log; then echo "::error::crash"; STATUS=1; fi
echo "---- server log (filtered)"; grep -nE "joined|left|lost connection|damage_tester|ERROR|Exception|dt_test" server.log | head -40
echo "---- client log (filtered)"; grep -nE "Connecting|disconnect|damage_tester|ERROR|Exception" client.log | grep -viE "realms|narrator|flite|openal|alsa|SoundSystem|Anisotropic|GLX|backend OpenGL|authlib|401|sessionserver" | head -40
pkill -f "java.*(fabric|neoforge|minecraft)" 2>/dev/null || true
exit $STATUS
