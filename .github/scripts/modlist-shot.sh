#!/usr/bin/env bash
# Opens the NeoForge mod list on the title screen, selects Damage Tester and screenshots it.
set -u
mkdir -p neoforge/runs/client shots
printf 'onboardAccessibility:false\ntutorialStep:none\nguiScale:2\n' > neoforge/runs/client/options.txt
./gradlew :neoforge:runClient --console=plain > modlist.log 2>&1 &
for i in $(seq 1 120); do
  grep -qE "Sound engine started|Failed to open OpenAL device|Error starting SoundSystem" modlist.log && break
  sleep 5
done
sleep 30
shot() { import -display "$DISPLAY" -window root "shots/$1.png"; }
shot 1_title
WID=$(xdotool search --name "Minecraft" | head -1); echo "window $WID"
xdotool windowactivate --sync "$WID" 2>/dev/null; xdotool windowmove "$WID" 0 0; xdotool windowsize "$WID" 1280 720; sleep 3
shot 2_title_sized
# gui 640x360: Mods button is the left half of the row under Multiplayer (y = 90+48+48 = 186..206)
xdotool mousemove 268 392 click 1; sleep 4
shot 3_after_click
xdotool type --delay 120 "damage"; sleep 3
shot 4_search
# first entry of the list
xdotool mousemove 120 150 click 1; sleep 3
shot 5_selected
pkill -f "java.*neoforge" || true
