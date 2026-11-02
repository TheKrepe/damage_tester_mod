package net.star1mp.damagetester.fabric.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.star1mp.damagetester.ModContent;
import net.star1mp.damagetester.entity.DamageTesterEntity;
import org.spongepowered.asm.mixin.MixinEnvironment;

/**
 * Development-only client game test: boots a real client, places two testers, hits them and
 * takes screenshots. Never shipped in the mod jar. Run with {@code ./gradlew :fabric:runClientGameTest}.
 */
public class DamageTesterClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        context.waitTicks(40);
        try (TestSingleplayerContext sp = context.worldBuilder()
                .adjustSettings(s -> s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE))
                .create()) {
            sp.getServer().runCommand("time set 6000");
            sp.getServer().runCommand("weather clear");
            sp.getConnection().waitForChunksRender();

            // --- place one with a real right click, like a player would, and measure how long it takes to show up
            sp.getServer().runCommand("item replace entity @a hotbar.0 with damage_tester:damage_tester 4");
            context.runOnClient(client -> client.player.getInventory().setSelectedSlot(0));
            BlockPos target = sp.getServer().computeOnServer(server -> {
                ServerPlayer player = sp.getConnection().getServerPlayer();
                BlockPos feet = player.blockPosition();
                int z = feet.getZ() - 3;
                int y = sp.getConnection().getServerLevel().getHeight(Heightmap.Types.MOTION_BLOCKING, feet.getX(), z) - 1;
                return new BlockPos(feet.getX(), y, z);
            });
            context.waitTicks(5);
            context.getInput().lookAt(target);
            context.waitTicks(2);
            java.nio.file.Path before = context.takeScreenshot("place_before");
            context.getInput().pressKey(options -> options.keyUse);
            int placedAfter = -1;
            for (int t = 1; t <= 40; t++) {
                context.waitTick();
                boolean present = context.computeOnClient(client -> {
                    for (Entity e : client.level.entitiesForRendering()) {
                        if (e instanceof DamageTesterEntity) {
                            return true;
                        }
                    }
                    return false;
                });
                if (present) {
                    System.out.println("[DamageTesterTest] visual change vs before: "
                            + diff(before, context.takeScreenshot("place_visible")));
                    placedAfter = t;
                    break;
                }
            }
            System.out.println("[DamageTesterTest] tester visible on the client " + placedAfter + " tick(s) after right click");
            if (placedAfter < 0 || placedAfter > 2) {
                throw new AssertionError("Placed tester took too long to appear: " + placedAfter + " ticks");
            }
            context.takeScreenshot("place_done");
            sp.getServer().runCommand("kill @e[type=damage_tester:damage_tester]");
            context.waitTicks(5);
            context.getInput().lookAt(0.0F, 14.0F);

            // --- place two testers 3 blocks in front of the player (player looks south, +Z)
            int[] ids = sp.getServer().computeOnServer(server -> {
                ServerPlayer player = sp.getConnection().getServerPlayer();
                ServerLevel level = sp.getConnection().getServerLevel();
                BlockPos base = player.blockPosition();

                DamageTesterEntity a = place(level, base.getX() - 1, base.getZ() + 4);
                DamageTesterEntity b = place(level, base.getX() + 2, base.getZ() + 4);
                b.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
                b.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                b.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
                b.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
                return new int[]{a.getId(), b.getId()};
            });
            sp.getConnection().waitForClientboundEntityUpdates(ModContent.DAMAGE_TESTER.get());
            context.getInput().lookAt(0.0F, 14.0F);
            context.waitTicks(20);
            context.takeScreenshot("damage_tester_idle");

            // --- several hits of different damage types. Vanilla ignores weaker hits during the
            // half-second hurt cooldown, so space them out like real attacks would be.
            hit(sp, ids[1], (level, player) -> level.damageSources().playerAttack(player), 9.0F);
            hit(sp, ids[0], (level, player) -> level.damageSources().playerAttack(player), 7.0F);
            context.waitTicks(11);
            hit(sp, ids[0], (level, player) -> level.damageSources().magic(), 4.0F);
            context.waitTicks(11);
            hit(sp, ids[0], (level, player) -> level.damageSources().onFire(), 2.5F);
            context.waitTicks(3);
            context.takeScreenshot("damage_tester_hit");

            // --- a real melee critical hit from the player (exercises the Player mixin)
            sp.getServer().runOnServer(server -> sp.getConnection().getServerPlayer()
                    .setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD)));
            context.waitTicks(40); // let the attack cooldown fill up after switching items
            sp.getServer().runOnServer(server -> {
                ServerPlayer player = sp.getConnection().getServerPlayer();
                Entity tester = sp.getConnection().getServerLevel().getEntity(ids[0]);
                tester.setInvulnerableTime(0);
                player.fallDistance = 1.5;
                player.setOnGround(false);
                player.setSprinting(false);
                player.attack(tester);
            });
            context.waitTicks(4);
            context.takeScreenshot("damage_tester_crit");

            boolean sawCrit = context.computeOnClient(client -> {
                Entity entity = client.level.getEntity(ids[0]);
                if (!(entity instanceof DamageTesterEntity tester)) {
                    throw new AssertionError("Damage tester missing on the client");
                }
                if (tester.getFloatingNumbers().isEmpty()) {
                    throw new AssertionError("No floating damage numbers arrived on the client");
                }
                return tester.getFloatingNumbers().stream().anyMatch(DamageTesterEntity.FloatingNumber::critical);
            });
            if (!sawCrit) {
                throw new AssertionError("The melee critical hit was not reported");
            }

            sp.getServer().runOnServer(server -> {
                for (int id : ids) {
                    Entity entity = sp.getConnection().getServerLevel().getEntity(id);
                    if (!(entity instanceof DamageTesterEntity tester) || tester.isRemoved()) {
                        throw new AssertionError("Damage tester " + id + " died");
                    }
                    if (tester.getHealth() < tester.getMaxHealth()) {
                        throw new AssertionError("Damage tester lost health: " + tester.getHealth());
                    }
                }
            });

            MixinEnvironment.getCurrentEnvironment().audit();

            // --- the item icon in the hotbar
            sp.getServer().runCommand("give @a damage_tester:damage_tester 3");
            context.waitTicks(30);
            context.takeScreenshot("damage_tester_hotbar");

            // --- sneak-punch with an empty hand dismantles it
            sp.getServer().runOnServer(server -> {
                ServerPlayer player = sp.getConnection().getServerPlayer();
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                player.setShiftKeyDown(true);
                Entity tester = sp.getConnection().getServerLevel().getEntity(ids[1]);
                tester.hurtServer(sp.getConnection().getServerLevel(),
                        sp.getConnection().getServerLevel().damageSources().playerAttack(player), 1.0F);
                if (!tester.isRemoved()) {
                    throw new AssertionError("Sneak-punch did not dismantle the tester");
                }
                player.setShiftKeyDown(false);
            });
            context.waitTicks(10);
            context.takeScreenshot("damage_tester_dismantled");

            // ================= skins
            sp.getServer().runCommand("kill @e[type=#damage_tester:testers]");
            context.waitTicks(5);
            int[] skinIds = sp.getServer().computeOnServer(server -> {
                ServerPlayer player = sp.getConnection().getServerPlayer();
                ServerLevel level = sp.getConnection().getServerLevel();
                BlockPos base = player.blockPosition();
                String[] skins = {"", "straw", "planks:minecraft:oak_planks", "planks:minecraft:cherry_planks", "dye:red", "jeb"};
                int[] out = new int[skins.length];
                for (int i = 0; i < skins.length; i++) {
                    DamageTesterEntity t = place(level, base.getX() + 5 - i * 2, base.getZ() + 6);
                    if (skins[i].equals("jeb")) {
                        t.setCustomName(net.minecraft.network.chat.Component.literal("jeb_"));
                    } else {
                        t.setSkin(skins[i]);
                    }
                    out[i] = t.getId();
                }
                // the real interaction path: right click with a hay block
                DamageTesterEntity first = (DamageTesterEntity) level.getEntity(out[0]);
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.BLUE_DYE));
                first.interact(player, net.minecraft.world.InteractionHand.MAIN_HAND, new Vec3(0, 1, 0));
                if (!first.getSkin().equals("dye:blue")) {
                    throw new AssertionError("Dye did not change the skin: " + first.getSkin());
                }
                return out;
            });
            context.waitTicks(20);
            context.takeScreenshot("skins");

            // ================= variants, damage counter and redstone
            sp.getServer().runCommand("kill @e[type=#damage_tester:testers]");
            context.waitTicks(5);
            Object[] scene = sp.getServer().computeOnServer(server -> {
                ServerPlayer player = sp.getConnection().getServerPlayer();
                ServerLevel level = sp.getConnection().getServerLevel();
                BlockPos base = player.blockPosition();
                // small
                int gx = base.getX() + 3, gz = base.getZ() + 5;
                int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING, gx, gz);
                DamageTesterEntity small = DamageTesterEntity.spawnFromItem(net.star1mp.damagetester.entity.TesterVariant.SMALL, level,
                        ItemStack.EMPTY, null, new Vec3(gx + 0.5, gy, gz + 0.5), 180.0F);
                // hanging under a block 4 high
                int hx = base.getX(), hz = base.getZ() + 5;
                int hy = level.getHeight(Heightmap.Types.MOTION_BLOCKING, hx, hz) + 3;
                level.setBlock(new BlockPos(hx, hy, hz), net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState(), 3);
                DamageTesterEntity hanging = DamageTesterEntity.spawnFromItem(net.star1mp.damagetester.entity.TesterVariant.HANGING, level,
                        ItemStack.EMPTY, null, new Vec3(hx + 0.5, hy - 1.5, hz + 0.5), 180.0F);
                // standing tester on a target block (redstone)
                int tx = base.getX() - 3, tz = base.getZ() + 5;
                int ty = level.getHeight(Heightmap.Types.MOTION_BLOCKING, tx, tz) - 1;
                BlockPos targetPos = new BlockPos(tx, ty, tz);
                level.setBlock(targetPos, net.minecraft.world.level.block.Blocks.TARGET.defaultBlockState(), 3);
                DamageTesterEntity onTarget = DamageTesterEntity.spawnFromItem(level, ItemStack.EMPTY, null, new Vec3(tx + 0.5, ty + 1, tz + 0.5), 180.0F);
                // moving tester further away
                int mx = base.getX() + 1, mz = base.getZ() + 10;
                int my = level.getHeight(Heightmap.Types.MOTION_BLOCKING, mx, mz);
                DamageTesterEntity moving = DamageTesterEntity.spawnFromItem(net.star1mp.damagetester.entity.TesterVariant.MOVING, level,
                        ItemStack.EMPTY, null, new Vec3(mx + 0.5, my, mz + 0.5), 180.0F);
                // damage counter next to the small tester, facing the player
                BlockPos counterPos = new BlockPos(base.getX() + 2, gy, base.getZ() + 3);
                level.setBlock(counterPos, ModContent.DAMAGE_COUNTER.get().defaultBlockState()
                        .setValue(net.star1mp.damagetester.block.DamageCounterBlock.FACING, net.minecraft.core.Direction.NORTH), 3);
                return new Object[]{small.getId(), hanging.getId(), onTarget.getId(), moving.getId(), targetPos, counterPos, moving.position()};
            });
            int smallId = (int) scene[0], hangingId = (int) scene[1], targetTesterId = (int) scene[2], movingId = (int) scene[3];
            BlockPos targetPos = (BlockPos) scene[4];
            BlockPos counterPos = (BlockPos) scene[5];
            Vec3 movingStart = (Vec3) scene[6];
            context.waitTicks(10);
            hit(sp, smallId, (level, player) -> level.damageSources().playerAttack(player), 6.0F);
            hit(sp, hangingId, (level, player) -> level.damageSources().playerAttack(player), 8.0F);
            hit(sp, targetTesterId, (level, player) -> level.damageSources().playerAttack(player), 9.0F);
            context.waitTicks(3);
            int power = sp.getServer().computeOnServer(server -> sp.getConnection().getServerLevel().getBlockState(targetPos)
                    .getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER));
            System.out.println("[DamageTesterTest] target block power after a 9 damage hit: " + power);
            if (power != 9) {
                throw new AssertionError("Target block power should be 9 but was " + power);
            }
            context.waitTicks(25);
            context.takeScreenshot("variants");
            sp.getServer().runOnServer(server -> {
                ServerLevel level = sp.getConnection().getServerLevel();
                Entity moving = level.getEntity(movingId);
                double moved = moving.position().distanceTo(movingStart);
                System.out.println("[DamageTesterTest] moving tester moved " + moved + " blocks");
                if (moved < 1.0) {
                    throw new AssertionError("Moving tester did not move");
                }
                if (level.getEntity(hangingId) == null || level.getEntity(hangingId).isRemoved()) {
                    throw new AssertionError("Hanging tester fell off");
                }
                if (!(level.getBlockEntity(counterPos) instanceof net.star1mp.damagetester.block.DamageCounterBlockEntity counter)
                        || !counter.hasTester() || !counter.getStats().hasData()) {
                    throw new AssertionError("Damage counter did not pick up the small tester's stats");
                }
                if (level.getBlockState(targetPos).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER) != 0) {
                    throw new AssertionError("Target block did not reset");
                }
            });
            context.getInput().lookAt(0.0F, 30.0F);
            context.waitTicks(5);
            context.takeScreenshot("variants_counter");
        }
    }

    /** Share of changed pixels in the middle third of the screen. */
    private static String diff(java.nio.file.Path a, java.nio.file.Path b) {
        try (var ia = com.mojang.blaze3d.platform.NativeImage.read(java.nio.file.Files.newInputStream(a));
             var ib = com.mojang.blaze3d.platform.NativeImage.read(java.nio.file.Files.newInputStream(b))) {
            int w = ia.getWidth(), h = ia.getHeight(), changed = 0, total = 0;
            for (int y = h / 3; y < 2 * h / 3; y++) {
                for (int x = w / 3; x < 2 * w / 3; x++) {
                    total++;
                    if (ia.getPixel(x, y) != ib.getPixel(x, y)) {
                        changed++;
                    }
                }
            }
            return String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * changed / total);
        } catch (java.io.IOException e) {
            return "n/a";
        }
    }

    private static DamageTesterEntity place(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        DamageTesterEntity tester = DamageTesterEntity.spawnFromItem(level,
                new ItemStack(ModContent.DAMAGE_TESTER_ITEM.get()), null, new Vec3(x + 0.5, y, z + 0.5), 180.0F);
        if (tester == null) {
            throw new AssertionError("Could not spawn a damage tester");
        }
        return tester;
    }

    private interface SourceFactory {
        net.minecraft.world.damagesource.DamageSource create(ServerLevel level, ServerPlayer player);
    }

    private static void hit(TestSingleplayerContext sp, int id, SourceFactory source, float amount) {
        sp.getServer().runOnServer(server -> {
            ServerLevel level = sp.getConnection().getServerLevel();
            Entity entity = level.getEntity(id);
            entity.setInvulnerableTime(0);
            entity.hurtServer(level, source.create(level, sp.getConnection().getServerPlayer()), amount);
        });
    }
}
