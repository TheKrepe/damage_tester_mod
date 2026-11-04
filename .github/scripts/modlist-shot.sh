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
n=0
for xy in "567 467" "615 467" "663 467" "711 467" "862 323"; do
  n=$((n+1)); xdotool mousemove $xy; sleep 1; shot "h$n"; xdotool click 1; sleep 4; shot "c$n"
  xdotool type --delay 120 "damage"; sleep 2; xdotool mousemove 330 200 click 1; sleep 3; shot "s$n"
  xdotool key Escape; sleep 3
done
pkill -f "java.*neoforge" || true
