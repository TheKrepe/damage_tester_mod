package net.star1mp.damagetester.selftest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.star1mp.damagetester.ModContent;
import net.star1mp.damagetester.entity.DamageTesterEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Footage for videos (CI only, {@code -Ddamagetester.promo=true}): builds a small training
 * ground and plays the same choreography on every Minecraft version, so the clips can be cut
 * together. The CI script records the screen; {@code PROMO MARK} lines carry wall-clock times.
 */
public final class Promo {
    private static final Logger LOG = LoggerFactory.getLogger("DT-PROMO");

    private record Step(int waitAfter, Runnable action) {
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static int index;
    private static long nextTick = -1;
    private static boolean done;
    private static MinecraftServer server;
    private static ServerLevel level;
    private static ServerPlayer player;
    private static BlockPos origin;
    private static DamageTesterEntity tester;
    private static DamageTesterEntity side;

    private Promo() {
    }

    static void tick(MinecraftServer srv) {
        if (done) {
            return;
        }
        server = srv;
        level = srv.overworld();
        long now = level.getGameTime();
        if (STEPS.isEmpty()) {
            build();
            nextTick = now + 20;
        }
        if (now < nextTick) {
            return;
        }
        if (player == null) {
            if (server.getPlayerList().getPlayers().isEmpty()) {
                return;
            }
            player = server.getPlayerList().getPlayers().get(0);
            nextTick = now + 60; // let the client finish loading
            return;
        }
        if (index >= STEPS.size()) {
            done = true;
            LOG.info("[DT-PROMO] DONE {}", System.currentTimeMillis());
            server.halt(false);
            return;
        }
        Step step = STEPS.get(index++);
        try {
            step.action().run();
        } catch (Throwable t) {
            LOG.error("[DT-PROMO] step crashed", t);
        }
        nextTick = level.getGameTime() + step.waitAfter();
    }

    private static void step(int waitAfter, Runnable action) {
        STEPS.add(new Step(waitAfter, action));
    }

    private static void mark(String name) {
        LOG.info("[DT-PROMO] MARK {} {}", name, System.currentTimeMillis());
    }

    private static void command(String cmd) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
    }

    private static String fill(int x0, int y0, int z0, int x1, int y1, int z1, String block) {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", origin.getX() + x0, origin.getY() + y0, origin.getZ() + z0,
                origin.getX() + x1, origin.getY() + y1, origin.getZ() + z1, block);
    }

    private static String set(int x, int y, int z, String block) {
        return String.format(Locale.ROOT, "setblock %d %d %d %s", origin.getX() + x, origin.getY() + y, origin.getZ() + z, block);
    }

    private static void hold(ItemStack stack) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
    }

    private static void swing() {
        Compat.swing(player);
    }

    private static void hit(boolean critical) {
        if (critical) {
            Compat.fall(player, 1.0F);
            player.setOnGround(false);
        }
        player.setSprinting(false);
        player.attack(tester);
        swing();
        Compat.fall(player, 0.0F);
        player.setOnGround(true);
    }

    private static void use(Item item, double y) {
        hold(new ItemStack(item, 4));
        Compat.interact(player, tester, new Vec3(0, y, 0));
        swing();
    }

    private static DamageTesterEntity place(Item item, BlockPos below) {
        hold(new ItemStack(item));
        Vec3 hit = Vec3.atCenterOf(below).add(0, 0.5, 0);
        player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, Direction.UP, below, false));
        List<DamageTesterEntity> list = level.getEntitiesOfClass(DamageTesterEntity.class, new AABB(below.above()).inflate(1.0));
        return list.isEmpty() ? null : list.get(0);
    }

    private static void build() {
        // ---- the set: grass, flowers, a fence, hay bales; the camera (= the player) stands at the origin
        step(40, () -> {
            origin = player.blockPosition();
            command("gamerule doDaylightCycle false");
            command("gamerule advance_time false");
            command("gamerule doMobSpawning false");
            command("gamerule spawn_mobs false");
            command("gamerule sendCommandFeedback false");
            command("gamerule send_command_feedback false");
            command("time set 6000");
            command("weather clear");
            command("difficulty peaceful");
            command("gamemode survival " + player.getScoreboardName());
            command("effect give " + player.getScoreboardName() + " minecraft:resistance 99999 4 true");
            command("effect give " + player.getScoreboardName() + " minecraft:saturation 99999 4 true");
            command("gamerule announceAdvancements false");
            command("gamerule show_advancement_messages false");
            command("recipe give " + player.getScoreboardName() + " *");
            command(fill(-12, 0, -6, 12, 8, 16, "minecraft:air"));
            command(fill(-12, -1, -6, 12, -1, 16, "minecraft:grass_block"));
            command(fill(-7, 0, 9, 7, 0, 9, "minecraft:oak_fence"));
            command(fill(-7, 0, 9, -7, 0, 9, "minecraft:oak_log"));
            command(fill(7, 0, 9, 7, 0, 9, "minecraft:oak_log"));
            command(set(-4, 0, 7, "minecraft:hay_block"));
            command(set(-5, 0, 7, "minecraft:hay_block"));
            command(set(-4, 1, 7, "minecraft:hay_block"));
            command(set(4, 0, 8, "minecraft:hay_block"));
            command(set(5, 0, 6, "minecraft:target"));
            String[] flowers = {"minecraft:poppy", "minecraft:dandelion", "minecraft:cornflower", "minecraft:oxeye_daisy", "minecraft:azure_bluet"};
            int[][] spots = {{-3, 3}, {3, 4}, {-2, 6}, {2, 7}, {-6, 4}, {6, 3}, {1, 8}, {-1, 8}, {-5, 2}, {4, 2}, {0, 7}, {-3, 8}};
            for (int i = 0; i < spots.length; i++) {
                command(set(spots[i][0], 0, spots[i][1], flowers[i % flowers.length]));
            }
            player.getInventory().clearContent();
            // every item of the choreography once in the backpack now, so the advancement toasts
            // ("Suit Up" etc.) pop and fade before recording starts
            Item[] props = {Items.DIAMOND_SWORD, Items.NETHERITE_SWORD, Items.HAY_BLOCK, Items.JACK_O_LANTERN,
                    Items.IRON_HELMET, Items.DIAMOND_CHESTPLATE, Compat.dye(net.minecraft.world.item.DyeColor.RED)};
            for (int i = 0; i < props.length; i++) {
                player.getInventory().setItem(9 + i, new ItemStack(props[i]));
            }
            camera();
        });
        // ---- testers
        step(20, () -> {
            tester = place(ModContent.DAMAGE_TESTER_ITEM.get(), origin.offset(0, -1, 3));
            side = place(ModContent.TESTER_ITEMS.get(net.star1mp.damagetester.entity.TesterVariant.SMALL).get(), origin.offset(-1, -1, 6));
            camera();
            hold(new ItemStack(Items.DIAMOND_SWORD));
        });
        step(200, () -> mark("ready")); // recipe and chat toasts fade out
        // ---- the choreography (about 16 seconds)
        step(10, () -> mark("start"));
        step(22, () -> {
            mark("hit1");
            hit(false);
        });
        step(26, () -> {
            mark("crit1");
            hit(true);
        });
        step(16, () -> {
            mark("straw");
            use(Items.HAY_BLOCK, 1.0);
        });
        step(22, () -> {
            hold(new ItemStack(Items.DIAMOND_SWORD));
            mark("hit2");
            hit(false);
        });
        step(16, () -> {
            mark("jack");
            use(Items.JACK_O_LANTERN, 1.0);
        });
        step(24, () -> {
            hold(new ItemStack(Items.DIAMOND_SWORD));
            mark("crit2");
            hit(true);
        });
        step(12, () -> {
            mark("helmet");
            use(Items.IRON_HELMET, 1.0);
        });
        step(16, () -> {
            mark("chest");
            use(Items.DIAMOND_CHESTPLATE, 1.0);
        });
        step(22, () -> {
            hold(new ItemStack(Items.NETHERITE_SWORD));
            mark("hit3");
            hit(false);
        });
        step(16, () -> {
            mark("dye");
            use(Compat.dye(net.minecraft.world.item.DyeColor.RED), 1.0);
        });
        step(26, () -> {
            hold(new ItemStack(Items.NETHERITE_SWORD));
            mark("crit3");
            hit(true);
        });
        step(30, () -> {
            mark("small");
            DamageTesterEntity keep = tester;
            tester = side;
            hit(true);
            tester = keep;
        });
        step(30, () -> mark("end"));
    }

    /**
     * The player stands 4.5 blocks in front of the tester, a bit to the left, looking almost level:
     * the tester sits in the lower half of the vertical frame and the sky above is left for titles.
     */
    private static void camera() {
        command(String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f %.1f %.1f", player.getScoreboardName(),
                origin.getX() + 0.5 - 0.7, (double) origin.getY(), origin.getZ() - 1.0, -8.0F, 3.0F));
    }
}
