"""Copies selected vanilla sound files from the Loom asset cache (for the trailer's sound effects)."""
import glob, json, os, re, shutil, sys
out = sys.argv[1]
os.makedirs(out, exist_ok=True)
home = os.path.expanduser('~/.gradle/caches')
indexes = sorted(glob.glob(home + '/**/assets/indexes/*.json', recursive=True), key=os.path.getmtime)
print('indexes', indexes)
if not indexes:
    sys.exit(0)
idx = json.load(open(indexes[-1]))['objects']
objects = os.path.join(os.path.dirname(os.path.dirname(indexes[-1])), 'objects')
pat = re.compile(r'minecraft/sounds/(entity/player/attack/.*|entity/generic/explode.*|random/explode.*|entity/lightning_bolt/.*|'
                 r'ambient/weather/thunder.*|random/bow.*|entity/arrow/.*|item/trident/.*|item/mace/.*|random/fuse.*|'
                 r'item/armor/.*|block/hay.*|dig/grass.*|dig/wood.*|step/wood.*|random/wood_click.*|random/click.*|random/pop.*|'
                 r'fireworks/.*|random/orb.*|random/levelup.*|item/shears/.*|mob/armorstand/.*|entity/armor_stand/.*|'
                 r'random/break.*|game/player/hurt/.*|damage/.*|fire/ignite.*|fire/fire.*|random/fizz.*|item/dye/.*|'
                 r'block/pumpkin/.*|entity/minecart/.*|minecart/.*|entity/wind_charge/.*|block/powder_snow/.*|'
                 r'mob/witch/.*|random/glass.*|dig/glass.*|item/bundle/.*|block/note_block/.*|note/.*|ui/.*|block/redstone_torch/.*|'
                 r'block/lever/.*|random/anvil_land.*|block/anvil/.*|entity/player/levelup.*|item/book/.*|block/crafter/.*|'
                 r'mob/wither/shoot.*|mob/wither/.*|block/trial_spawner/.*|block/vault/.*|block/copper_bulb/.*)\.ogg$')
n = 0
for name, meta in idx.items():
    if pat.match(name):
        h = meta['hash']
        src = os.path.join(objects, h[:2], h)
        dst = os.path.join(out, name.split('sounds/', 1)[1])
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if os.path.exists(src):
            shutil.copy(src, dst)
            n += 1
print('copied', n)
