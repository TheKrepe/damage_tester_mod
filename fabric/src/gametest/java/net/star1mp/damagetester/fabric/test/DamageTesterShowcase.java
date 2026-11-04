package net.star1mp.damagetester.fabric.test;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.star1mp.damagetester.ModContent;
import net.star1mp.damagetester.block.DamageCounterBlock;
import net.star1mp.damagetester.entity.DamageTesterEntity;
import net.star1mp.damagetester.entity.TesterVariant;

/**
 * Not a test: builds a small training yard and takes nice screenshots + GIF frames for the mod pages.
 * Only runs when the {@code DT_SHOWCASE} environment variable is set (see .github/workflows/showcase.yml).
 */
public class DamageTesterShowcase implements FabricClientGameTest {
    private int bx;
    private int by;
    private int bz;

    public static boolean enabled() {
        return System.getenv("DT_SHOWCASE") != null;
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!enabled()) {
            return;
        }
        context.waitTicks(40);
        context.runOnClient(client -> {
            client.options.renderDistance().set(8);
            client.options.bobView().set(false);
        });
        try (TestSingleplayerContext sp = context.worldBuilder()
                .adjustSettings(s -> s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE))
                .create()) {
            command(context, sp, "time set 1000");
            command(context, sp, "weather clear");
            command(context, sp, "gamerule advance_time false");
            command(context, sp, "gamerule advance_weather false");
            sp.getConnection().waitForChunksRender(6000);

            List<Integer> row = sp.getServer().computeOnServer(server -> this.buildYard(sp.getConnection().getServerLevel(),
                    sp.getConnection().getServerPlayer()));
            // trees for a nicer background
            for (int[] t : new int[][]{{-12, 22}, {-5, 25}, {4, 24}, {12, 21}, {16, 12}, {-16, 12}}) {
                command(context, sp, "place feature minecraft:" + (t[0] % 2 == 0 ? "oak" : "birch") + " " + (bx + t[0]) + " " + by + " " + (bz + t[1]));
            }
            context.waitTicks(20);
            sp.getConnection().waitForChunksRender(6000);

            // ---------------------------------------------------------------- hero shot
            spectator(context, sp, bx + 0.5, by + 4.2, bz - 1.5, 0.0F, 22.0F);
            this.hitAll(context, sp, row);
            context.waitTicks(4);
            shot(context, "01_training_ground");

            // ---------------------------------------------------------------- close-up: colors and a crit
            int hero = row.get(2);
            Vec3 heroPos = position(sp, hero);
            spectator(context, sp, heroPos.x + 1.2, heroPos.y + 1.7, heroPos.z - 2.4, 25.0F, 8.0F);
            context.waitTicks(10);
            this.hit(sp, hero, (level, player) -> level.damageSources().playerAttack(player), 7.5F, false);
            context.waitTicks(2);
            this.hit(sp, hero, (level, player) -> level.damageSources().onFire(), 4.0F, false);
            context.waitTicks(2);
            this.hit(sp, hero, (level, player) -> level.damageSources().magic(), 3.0F, false);
            context.waitTicks(2);
            this.hit(sp, hero, (level, player) -> level.damageSources().playerAttack(player), 11.0F, true);
            context.waitTicks(4);
            shot(context, "02_damage_numbers");

            // ---------------------------------------------------------------- skins line-up
            List<Integer> skins = sp.getServer().computeOnServer(server -> this.buildSkinRow(sp.getConnection().getServerLevel()));
            context.waitTicks(10);
            spectator(context, sp, bx + 38.5, by + 2.6, bz + 1.0, 0.0F, 10.0F);
            context.waitTicks(30);
            shot(context, "03_skins");

            // ---------------------------------------------------------------- variants
            spectator(context, sp, bx + 7.0, by + 3.6, bz + 7.0, 35.0F, 20.0F);
            context.waitTicks(10);
            for (int id : row) {
                this.hit(sp, id, (level, player) -> level.damageSources().playerAttack(player), 5.0F, false);
            }
            context.waitTicks(4);
            shot(context, "04_variants");

            // ---------------------------------------------------------------- night: the jack o'lantern glows
            command(context, sp, "time set 18000");
            spectator(context, sp, bx + 38.5, by + 2.6, bz + 1.0, 0.0F, 10.0F);
            context.waitTicks(30);
            shot(context, "05_night");
            command(context, sp, "time set 1000");

            // ---------------------------------------------------------------- with the HUD: real attacks + GIF frames
            Vec3 target = position(sp, hero);
            command(context, sp, "gamemode creative @a");
            sp.getServer().runOnServer(server -> {
                ServerPlayer player = sp.getConnection().getServerPlayer();
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
                player.getAbilities().flying = false;
                player.onUpdateAbilities();
            });
            command(context, sp, String.format(java.util.Locale.ROOT, "tp @a %.2f %.2f %.2f 0 8", target.x, target.y, target.z - 2.6));
            context.waitTicks(30);
            this.cleanHud(context, true);
            context.getInput().lookAt(0.0F, 8.0F);
            context.waitTicks(15);
            int frame = 0;
            for (int attack = 0; attack < 4; attack++) {
                boolean jump = attack == 2;
                if (jump) {
                    context.getInput().pressKey(options -> options.keyJump);
                }
                for (int t = 0; t < 16; t++) {
                    if ((!jump && t == 2) || (jump && t == 7)) {
                        context.getInput().pressKey(options -> options.keyAttack);
                    }
                    context.waitTick();
                    this.cleanHud(context, false);
                    context.takeScreenshot(String.format("gif_%03d", frame++));
                }
            }
            this.cleanHud(context, true);
            context.getInput().pressKey(options -> options.keyAttack);
            context.waitTicks(3);
            shot(context, "06_in_game");
        }
    }

    // ==================================================================== scene building

    private List<Integer> buildYard(ServerLevel level, ServerPlayer player) {
        BlockPos base = player.blockPosition();
        this.bx = base.getX();
        this.bz = base.getZ();
        this.by = level.getHeight(Heightmap.Types.MOTION_BLOCKING, this.bx, this.bz + 8);
        int g = this.by - 1; // ground block layer
        java.util.Random rnd = new java.util.Random(7);

        // flowers and grass around
        for (int x = -18; x <= 18; x++) {
            for (int z = 2; z <= 30; z++) {
                BlockPos p = new BlockPos(this.bx + x, this.by, this.bz + z);
                double r = rnd.nextDouble();
                if (r < 0.10) {
                    set(level, p, Blocks.SHORT_GRASS);
                } else if (r < 0.12) {
                    set(level, p, Blocks.POPPY);
                } else if (r < 0.14) {
                    set(level, p, Blocks.DANDELION);
                } else if (r < 0.15) {
                    set(level, p, Blocks.CORNFLOWER);
                }
            }
        }
        // the yard: path floor, fence, lanterns
        for (int x = -9; x <= 9; x++) {
            for (int z = 4; z <= 17; z++) {
                set(level, new BlockPos(this.bx + x, this.by, this.bz + z), Blocks.AIR);
                if (z == 6 || z == 7 || x == 0 && z < 6) {
                    set(level, new BlockPos(this.bx + x, g, this.bz + z), Blocks.DIRT_PATH);
                }
            }
        }
        for (int x = -10; x <= 10; x++) {
            fence(level, this.bx + x, this.bz + 18, x % 5 == 0);
        }
        for (int z = 4; z <= 18; z++) {
            fence(level, this.bx - 10, this.bz + z, z % 5 == 3);
            fence(level, this.bx + 10, this.bz + z, z % 5 == 3);
        }
        // hay stacks in the corners
        for (int[] h : new int[][]{{-9, 17}, {-8, 17}, {-9, 16}, {9, 17}, {8, 17}, {9, 16}}) {
            set(level, new BlockPos(this.bx + h[0], this.by, this.bz + h[1]), Blocks.HAY_BLOCK);
        }
        set(level, new BlockPos(this.bx - 9, this.by + 1, this.bz + 17), Blocks.HAY_BLOCK);
        set(level, new BlockPos(this.bx + 9, this.by + 1, this.bz + 17), Blocks.HAY_BLOCK);
        set(level, new BlockPos(this.bx + 8, this.by, this.bz + 4), Blocks.BARREL);

        List<Integer> row = new ArrayList<>();
        // row of standing testers
        DamageTesterEntity a = stand(level, TesterVariant.STANDARD, this.bx - 6, this.bz + 10);
        DamageTesterEntity b = stand(level, TesterVariant.STANDARD, this.bx - 3, this.bz + 10);
        b.setSkin(DamageTesterEntity.SKIN_STRAW);
        DamageTesterEntity c = stand(level, TesterVariant.STANDARD, this.bx, this.bz + 10);
        DamageTesterEntity d = stand(level, TesterVariant.STANDARD, this.bx + 3, this.bz + 10);
        d.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        d.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        d.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
        d.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
        DamageTesterEntity e = stand(level, TesterVariant.STANDARD, this.bx + 6, this.bz + 10);
        e.setSkin(DamageTesterEntity.SKIN_PLANKS_PREFIX + "minecraft:oak_planks");
        row.add(a.getId());
        row.add(b.getId());
        row.add(c.getId());
        row.add(d.getId());
        row.add(e.getId());

        // tester on a target block powering a lamp
        BlockPos targetPos = new BlockPos(this.bx - 6, g, this.bz + 14);
        set(level, targetPos, Blocks.TARGET);
        set(level, targetPos.east(), Blocks.REDSTONE_LAMP);
        set(level, targetPos.west(), Blocks.REDSTONE_LAMP);
        DamageTesterEntity onTarget = stand(level, TesterVariant.STANDARD, this.bx - 6, this.bz + 14);
        onTarget.setSkin(DamageTesterEntity.SKIN_DYE_PREFIX + "lime");
        row.add(onTarget.getId());

        // small tester
        DamageTesterEntity small = stand(level, TesterVariant.SMALL, this.bx - 2, this.bz + 14);
        small.setSkin(DamageTesterEntity.SKIN_DYE_PREFIX + "light_blue");
        row.add(small.getId());

        // wooden gallows with two hanging testers
        for (int y = 0; y < 4; y++) {
            set(level, new BlockPos(this.bx + 1, this.by + y, this.bz + 15), Blocks.OAK_LOG);
            set(level, new BlockPos(this.bx + 5, this.by + y, this.bz + 15), Blocks.OAK_LOG);
        }
        for (int x = 1; x <= 5; x++) {
            set(level, new BlockPos(this.bx + x, this.by + 4, this.bz + 15), Blocks.SPRUCE_PLANKS);
        }
        DamageTesterEntity h1 = hang(level, this.bx + 2, this.bz + 15);
        DamageTesterEntity h2 = hang(level, this.bx + 4, this.bz + 15);
        h2.setSkin(DamageTesterEntity.SKIN_STRAW);
        row.add(h1.getId());
        row.add(h2.getId());

        // rail with a minecart tester
        for (int x = -9; x <= 9; x++) {
            level.setBlock(new BlockPos(this.bx + x, this.by, this.bz + 12),
                    Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST), 3);
        }
        DamageTesterEntity cart = DamageTesterEntity.spawnInMinecart(level, new BlockPos(this.bx + 7, this.by, this.bz + 12),
                ItemStack.EMPTY, null, 180.0F);
        if (cart != null) {
            cart.setSkin(DamageTesterEntity.SKIN_DYE_PREFIX + "orange");
            row.add(cart.getId());
        }

        // damage counter facing the camera
        level.setBlock(new BlockPos(this.bx + 1, this.by, this.bz + 8),
                ModContent.DAMAGE_COUNTER.get().defaultBlockState().setValue(DamageCounterBlock.FACING, Direction.NORTH), 3);
        return row;
    }

    private List<Integer> buildSkinRow(ServerLevel level) {
        int x0 = this.bx + 40;
        int z = this.bz + 9;
        for (int x = -12; x <= 12; x++) {
            for (int dz = -4; dz <= 4; dz++) {
                set(level, new BlockPos(x0 + x, this.by, z + dz), Blocks.AIR);
            }
            set(level, new BlockPos(x0 + x, this.by - 1, z), Blocks.DIRT_PATH);
            fence(level, x0 + x, z + 3, x % 4 == 0);
        }
        String[] skins = {"", DamageTesterEntity.SKIN_STRAW, "planks:minecraft:oak_planks", "planks:minecraft:cherry_planks",
                "planks:minecraft:dark_oak_planks", DamageTesterEntity.SKIN_JACK, "dye:red", "dye:light_blue", "jeb"};
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < skins.length; i++) {
            DamageTesterEntity t = stand(level, TesterVariant.STANDARD, x0 - 10 + i * 2 + (i >= 5 ? 1 : 0), z);
            if (skins[i].equals("jeb")) {
                t.setCustomName(Component.literal("jeb_"));
            } else {
                t.setSkin(skins[i]);
            }
            ids.add(t.getId());
        }
        for (int i = -10; i <= 10; i += 5) {
            set(level, new BlockPos(x0 + i, this.by, z + 2), Blocks.HAY_BLOCK);
        }
        return ids;
    }

    private static void fence(ServerLevel level, int x, int z, boolean lantern) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos p = new BlockPos(x, y, z);
        while (!level.getBlockState(p.below()).isSolid() && p.getY() > level.getMinY()) {
            p = p.below();
        }
        set(level, p, Blocks.OAK_FENCE);
        if (lantern) {
            set(level, p.above(), Blocks.LANTERN);
        }
    }

    private DamageTesterEntity stand(ServerLevel level, TesterVariant variant, int x, int z) {
        DamageTesterEntity t = DamageTesterEntity.spawnFromItem(variant, level, ItemStack.EMPTY, null,
                new Vec3(x + 0.5, this.by, z + 0.5), 180.0F);
        if (t == null) {
            throw new AssertionError("could not place " + variant);
        }
        return t;
    }

    private DamageTesterEntity hang(ServerLevel level, int x, int z) {
        DamageTesterEntity t = DamageTesterEntity.spawnFromItem(TesterVariant.HANGING, level, ItemStack.EMPTY, null,
                new Vec3(x + 0.5, this.by + 4 - TesterVariant.ROPE_LENGTH - TesterVariant.HANGING.height(), z + 0.5), 180.0F);
        if (t == null) {
            throw new AssertionError("could not hang a tester");
        }
        return t;
    }

    private static void set(ServerLevel level, BlockPos pos, Block block) {
        BlockState state = block.defaultBlockState();
        level.setBlock(pos, state, 3);
    }

    // ==================================================================== camera + helpers

    private void spectator(ClientGameTestContext context, TestSingleplayerContext sp, double x, double y, double z, float yaw, float pitch) {
        command(context, sp, "gamemode spectator @a");
        command(context, sp, String.format(java.util.Locale.ROOT, "tp @a %.2f %.2f %.2f %.1f %.1f", x, y, z, yaw, pitch));
        context.waitTicks(5);
        context.getInput().lookAt(yaw, pitch);
        sp.getConnection().waitForChunksRender(6000);
        this.cleanHud(context, true);
    }

    private static Vec3 position(TestSingleplayerContext sp, int id) {
        return sp.getServer().computeOnServer(server -> sp.getConnection().getServerLevel().getEntity(id).position());
    }

    private static void command(ClientGameTestContext context, TestSingleplayerContext sp, String command) {
        sp.getServer().runCommand(command);
        context.waitTick();
    }

    /** Clears chat and toasts; optionally hides the whole HUD (like pressing F1). */
    private void cleanHud(ClientGameTestContext context, boolean allowHide) {
        context.runOnClient(client -> {
            client.gui.hud.getChat().clearMessages(false);
            client.gui.toastManager().clear();
        });
    }

    private static void shot(ClientGameTestContext context, String name) {
        boolean wasHidden = context.computeOnClient(client -> client.gui.hud.isHidden());
        if (!wasHidden && !name.equals("06_in_game")) {
            context.runOnClient(client -> client.gui.hud.toggle());
            context.waitTick();
        }
        if (wasHidden && name.equals("06_in_game")) {
            context.runOnClient(client -> client.gui.hud.toggle());
            context.waitTick();
        }
        // Rendering is software-only on CI, so only switch to full HD for the screenshot itself.
        context.getInput().resizeWindow(1920, 1080);
        context.waitTicks(20);
        context.takeScreenshot("showcase_" + name);
        context.getInput().resizeWindow(960, 540);
        context.waitTicks(2);
        context.runOnClient(client -> {
            if (client.gui.hud.isHidden()) {
                client.gui.hud.toggle();
            }
        });
    }

    private interface SourceFactory {
        DamageSource create(ServerLevel level, ServerPlayer player);
    }

    private void hit(TestSingleplayerContext sp, int id, SourceFactory source, float amount, boolean crit) {
        sp.getServer().runOnServer(server -> {
            ServerLevel level = sp.getConnection().getServerLevel();
            Entity entity = level.getEntity(id);
            if (entity instanceof DamageTesterEntity tester) {
                tester.setInvulnerableTime(0);
                if (crit) {
                    tester.markCriticalHit();
                }
                tester.hurtServer(level, source.create(level, sp.getConnection().getServerPlayer()), amount);
            }
        });
    }

    private void hitAll(ClientGameTestContext context, TestSingleplayerContext sp, List<Integer> ids) {
        float[] amounts = {6.0F, 8.5F, 12.0F, 3.5F, 7.0F, 9.0F, 4.0F, 10.0F, 5.5F, 6.5F};
        for (int i = 0; i < ids.size(); i++) {
            int index = i;
            this.hit(sp, ids.get(i), (level, player) -> index % 3 == 1 ? level.damageSources().onFire()
                    : index % 3 == 2 ? level.damageSources().magic() : level.damageSources().playerAttack(player),
                    amounts[i % amounts.length], i == 2);
            if (i % 3 == 2) {
                context.waitTick();
            }
        }
    }
}
