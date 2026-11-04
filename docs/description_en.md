# Damage Tester

**A training dummy that shows you exactly how much damage you deal.**
Place it, hit it, and watch it wobble while the number pops up above its head.

## Features

- **Real damage.** Damage is measured after armor, enchantments and effects, exactly as a normal mob would take it. The dummy never dies, so even a 500-damage hit shows up in full.
- **Floating damage numbers**, colored by damage type: fire, magic, explosions, freezing, lightning, wither, thorns, trident and more.
- **Critical hits** stand out: bigger, golden and marked with ✦.
- **Action bar stats:** last hit, DPS, total damage and number of hits in the current series.
- **Wobble animation.** The harder you hit, the further it leans back before springing upright again.
- **Armor.** Right-click to put armor, a pumpkin or a head on the dummy and test how much damage it absorbs. Right-click with an empty hand to take it off.
- **Redstone.** A dummy standing on a target block powers it when hit, with a signal strength equal to the damage (up to 15).
- **Pick it up** with Sneak + punch with an empty hand. In survival it drops as an item.
- Dispensers can place dummies.

## Skins

Right-click the dummy with an item (shears restore the default look):

| Item | Skin |
|---|---|
| Hay bale | Straw dummy |
| Any planks, including modded ones | Wooden dummy made of those planks |
| Any dye | Burlap in that color |
| Jack o'Lantern | Pumpkin head with a glowing face |
| Name tag `jeb_` | Cycles through all colors, just like the sheep |

## Variants

| Item | What it does |
|---|---|
| **Damage Tester** | The classic dummy on a stand |
| **Small Damage Tester** | Child-sized, for testing damage against small mobs |
| **Hanging Damage Tester** | Hangs under a block (right-click the bottom face) and swings like a punching bag |
| **Moving Damage Tester** | Rides on a little cart, great for archery practice. Sneak + right-click with an empty hand to switch modes: circle → back and forth → stopped |
| **Damage Counter** (block) | Its screen shows the stats of the nearest dummy within 8 blocks: last hit, DPS, total, hits and best hit |

## Recipes

- **Damage Tester:** stick, hay bale, stick in the top row, armor stand below the hay bale.
- **Small Damage Tester:** hay bale, stick and wooden slab in a column.
- **Hanging Damage Tester:** Damage Tester + lead.
- **Moving Damage Tester:** Damage Tester + minecart.
- **Damage Counter:** planks on the sides, a glass pane on top, a target block in the middle, redstone at the bottom.

All items are in their own "Damage Tester" creative tab.

## Config

File `config/damage_tester.json`:

- damage numbers on/off;
- damage shown in hearts;
- wobble strength;
- action bar stats;
- seconds before a hit series resets;
- straw particles.

## Requirements

- Minecraft **26.3**
- **Fabric** (+ Fabric API) or **NeoForge**
- Required on both the server and the client.
