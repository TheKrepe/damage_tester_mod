# First tick a player is seen: face south and put a tester 3 blocks in front of them.
execute as @a[tag=!dt_placed] at @s run tp @s ~ ~ ~ 0 12
execute as @a[tag=!dt_placed] at @s run summon damage_tester:damage_tester ~ ~ ~3 {Rotation:[180f,0f]}
execute as @a[tag=!dt_placed] at @s run summon damage_tester:damage_tester ~2 ~ ~3 {Rotation:[180f,0f],equipment:{head:{id:"minecraft:iron_helmet",count:1},chest:{id:"minecraft:iron_chestplate",count:1}}}
tag @a[tag=!dt_placed] add dt_placed
# Then hit every tester every 15 ticks with alternating damage types.
scoreboard players add #t dt_timer 1
execute if score #t dt_timer matches 15 as @e[type=damage_tester:damage_tester] run damage @s 6 minecraft:mob_attack
execute if score #t dt_timer matches 30 as @e[type=damage_tester:damage_tester] run damage @s 3.5 minecraft:magic
execute if score #t dt_timer matches 45 as @e[type=damage_tester:damage_tester] run damage @s 9 minecraft:on_fire
execute if score #t dt_timer matches 45.. run scoreboard players set #t dt_timer 0
