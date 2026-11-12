package net.star1mp.damagetester.selftest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.star1mp.damagetester.DamageTester;
import net.star1mp.damagetester.ModContent;
import net.star1mp.damagetester.SelfTestHook;
import net.star1mp.damagetester.block.DamageCounterBlockEntity;
import net.star1mp.damagetester.entity.DamageTesterEntity;
import net.star1mp.damagetester.entity.TesterVariant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end test driver. Runs on the dedicated server once a real client has joined, and drives
 * the same server code paths as real clicks: item use on blocks, interacting with and attacking
 * entities, dispensers, commands. The client half logs what it received and rendered
 * ({@code [DT-SELFTEST-CLIENT]}); the CI script compares both logs and takes screenshots at every
 * {@code SHOT} marker.
 *
 * <p>Phase 1 runs the whole scenario and stops the server; phase 2 (the next start) checks that a
 * tester's skin, armor and name survived saving and loading.</p>
 */
public final class SelfTest {
    private static final Logger LOG = LoggerFactory.getLogger("DT-SELFTEST");
    private static final Path STATE = Path.of("selftest_state.txt");

    private record Step(String name, int waitAfter, Runnable action) {
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static int stepIndex;
    private static long nextTick = -1;
    private static int passed;
    private static int failed;
    private static boolean done;
    private static boolean phase2;
    private static int waitTicks;
    private static BooleanSupplier waitCondition;
    private static String waitName;
    private static int waitBudget;

    // world state shared between steps
    private static MinecraftServer server;
    private static ServerLevel level;
    private static ServerPlayer player;
    private static BlockPos origin;
    private static DamageTesterEntity standard;
    private static DamageTesterEntity small;
    private static DamageTesterEntity hanging;
    private static DamageTesterEntity minecart;
    private static DamageTesterEntity onTarget;
    private static BlockPos counterPos;
    private static BlockPos targetPos;
    private static BlockPos hangSupport;

    private SelfTest() {
    }

    public static void install() {
        SelfTestHook.setServerTicker(SelfTest::tick);
        try {
            Class.forName("net.minecraft.client.main.Main", false, SelfTest.class.getClassLoader());
            SelfTestHook.setClientCheck(() -> SelfTestClient.run());
        } catch (ClassNotFoundException e) {
            // dedicated server
        }
    }

    // ================================================================== plumbing

    private static void tick(MinecraftServer srv) {
        if (done) {
            return;
        }
        server = srv;
        level = srv.overworld();
        long now = level.getGameTime();
        if (STEPS.isEmpty()) {
            phase2 = Files.exists(STATE);
            if (phase2) {
                buildPhase2();
            } else {
                buildPhase1();
            }
            LOG.info("[DT-SELFTEST] START phase={} steps={}", phase2 ? 2 : 1, STEPS.size());
            nextTick = now + 20;
        }
        if (now < nextTick) {
            return;
        }
        if (waitCondition != null) {
            if (waitCondition.getAsBoolean()) {
                pass(waitName, "after " + (waitBudget - waitTicks) + " ticks");
                waitCondition = null;
            } else if (--waitTicks <= 0) {
                fail(waitName, "timed out");
                waitCondition = null;
            } else {
                return;
            }
        }
        if (stepIndex >= STEPS.size()) {
            finish();
            return;
        }
        Step step = STEPS.get(stepIndex++);
        LOG.info("[DT-SELFTEST] STEP {}", step.name());
        try {
            step.action().run();
        } catch (Throwable t) {
            fail(step.name(), "crashed: " + t);
            LOG.error("[DT-SELFTEST] step crashed", t);
        }
        nextTick = level.getGameTime() + step.waitAfter();
    }

    private static void finish() {
        done = true;
        LOG.info("[DT-SELFTEST] PHASE{} DONE passed={} failed={}", phase2 ? 2 : 1, passed, failed);
        if (!phase2) {
            try {
                Files.writeString(STATE, savedUuid == null ? "" : savedUuid.toString(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                LOG.error("[DT-SELFTEST] could not write state", e);
            }
        }
        command("save-all flush");
        server.halt(false);
    }

    private static void step(String name, int waitAfter, Runnable action) {
        STEPS.add(new Step(name, waitAfter, action));
    }

    /** Waits (up to {@code ticks}) until the condition is true; passes or fails the named check. */
    private static void waitFor(String name, int ticks, BooleanSupplier condition) {
        waitName = name;
        waitTicks = ticks;
        waitBudget = ticks;
        waitCondition = condition;
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass(name, detail);
        } else {
            fail(name, detail);
        }
    }

    private static void pass(String name, String detail) {
        passed++;
        LOG.info("[DT-SELFTEST] PASS {} ({})", name, detail);
    }

    private static void fail(String name, String detail) {
        failed++;
        LOG.info("[DT-SELFTEST] FAIL {} ({})", name, detail);
    }

    /** The client must log this (the CI script checks it). */
    private static void expectClient(String text) {
        LOG.info("[DT-SELFTEST] EXPECT-CLIENT {}", text);
    }

    private static void shot(String name) {
        LOG.info("[DT-SELFTEST] SHOT {} {}", name, System.currentTimeMillis());
    }

    private static void command(String cmd) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
    }

    private static String name() {
        return player.getScoreboardName();
    }

    private static void view(double dx, double dy, double dz, float yaw, float pitch) {
        command(String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f %.1f %.1f", name(),
                origin.getX() + 0.5 + dx, origin.getY() + dy, origin.getZ() + 0.5 + dz, yaw, pitch));
    }

    private static BlockPos at(int dx, int dy, int dz) {
        return origin.offset(dx, dy, dz);
    }

    private static void setBlock(BlockPos pos, BlockState state) {
        level.setBlockAndUpdate(pos, state);
    }

    private static void hold(ItemStack stack) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
    }

    /** Right-click on a block face with the held item, like the client would. */
    private static void useOn(BlockPos pos, Direction face) {
        ItemStack stack = player.getMainHandItem();
        Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
    }

    /** Right-click on an entity at a point relative to its feet. */
    private static void interact(Entity entity, Vec3 location) {
        Compat.interact(player, entity, location);
    }

    private static void attack(Entity entity, boolean critical) {
        if (critical) {
            Compat.fall(player, 1.0F);
            player.setOnGround(false);
        }
        player.setSprinting(false);
        player.attack(entity);
        Compat.fall(player, 0.0F);
        player.setOnGround(true);
    }

    private static ItemStack named(Item item, String name) {
        return Compat.named(new ItemStack(item), name);
    }

    private static String nameOf(ItemStack stack) {
        return stack.getHoverName().getString();
    }

    private static DamageTesterEntity testerAt(BlockPos pos, TesterVariant variant) {
        List<DamageTesterEntity> list = level.getEntitiesOfClass(DamageTesterEntity.class, new AABB(pos).inflate(1.5),
                e -> e.isAlive() && e.getVariant() == variant);
        return list.isEmpty() ? null : list.get(0);
    }

    private static List<ItemStack> dropsAround(BlockPos pos) {
        List<ItemStack> drops = new ArrayList<>();
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3.0))) {
            drops.add(item.getItem());
        }
        return drops;
    }

    private static void clearDrops() {
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(origin).inflate(30.0))) {
            item.discard();
        }
    }

    private static boolean has(List<ItemStack> drops, Item item) {
        return drops.stream().anyMatch(s -> s.is(item));
    }

    private static int count(Item item) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.is(item)) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static void damage(Entity entity, float amount, String type) {
        command(String.format(Locale.ROOT, "damage %s %.1f %s", entity.getStringUUID(), amount, type));
    }

    private static void expectHit(Entity entity, boolean crit) {
        expectClient("hit id=" + entity.getId() + " ");
        if (crit) {
            expectClient("hit id=" + entity.getId() + " amount=*crit=true");
        }
    }

    private static UUID savedUuid;

    // ================================================================== phase 1: everything

    private static void buildPhase1() {
        step("wait for a player", 0, () -> waitFor("player joined", 20 * 120, () -> !server.getPlayerList().getPlayers().isEmpty()));
        step("set up the arena", 40, () -> {
            player = server.getPlayerList().getPlayers().get(0);
            origin = player.blockPosition();
            command("gamerule doDaylightCycle false");
            command("gamerule advance_time false");
            command("gamerule doMobSpawning false");
            command("gamerule spawn_mobs false");
            command("time set noon");
            command("weather clear");
            command("difficulty peaceful");
            command("gamemode survival " + name());
            command("effect give " + name() + " minecraft:resistance 99999 4 true");
            command("effect give " + name() + " minecraft:saturation 99999 4 true");
            command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", origin.getX() - 14, origin.getY(), origin.getZ() - 6,
                    origin.getX() + 14, origin.getY() + 8, origin.getZ() + 12));
            command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:smooth_stone", origin.getX() - 14, origin.getY() - 1, origin.getZ() - 6,
                    origin.getX() + 14, origin.getY() - 1, origin.getZ() + 12));
            command(String.format(Locale.ROOT, "forceload add %d %d %d %d", origin.getX() - 14, origin.getZ() - 6, origin.getX() + 14, origin.getZ() + 12));
            player.getInventory().clearContent();
            view(0, 0, -3, 0, 15);
        });

        step("content is registered", 5, () -> {
            for (TesterVariant v : TesterVariant.values()) {
                check("item " + v.id(), ModContent.TESTER_ITEMS.get(v).get() != null, v.id());
                check("entity type " + v.id(), ModContent.TESTER_TYPES.get(v).get() != null, v.id());
            }
            check("damage counter block", ModContent.DAMAGE_COUNTER.get() != Blocks.AIR, "block");
            check("damage counter item", ModContent.DAMAGE_COUNTER_ITEM.get() != Items.AIR, "item");
            for (String recipe : new String[]{"damage_tester", "small_damage_tester", "hanging_damage_tester", "minecart_damage_tester", "damage_counter"}) {
                check("recipe " + recipe, Compat.hasRecipe(server, DamageTester.id(recipe)), recipe);
            }
            for (String adv : Compat.advancementIds()) {
                check("advancement " + adv, Compat.hasAdvancement(server, DamageTester.id(adv)), adv);
            }
            check("config file", Files.exists(Path.of("config", "damage_tester.json")), "config/damage_tester.json");
            check("counter loot table", Compat.hasLootTable(server, DamageTester.id("blocks/damage_counter")), "blocks/damage_counter");
        });

        // ---------------------------------------------------------------- placing every variant with the item
        step("place a standard tester", 10, () -> {
            view(0, 0, -3, 0, 15);
            hold(new ItemStack(ModContent.DAMAGE_TESTER_ITEM.get(), 2));
            useOn(at(-6, -1, 3), Direction.UP);
            standard = testerAt(at(-6, 0, 3), TesterVariant.STANDARD);
            check("standard tester placed", standard != null, "at " + at(-6, 0, 3).toShortString());
            check("item used up in survival", player.getMainHandItem().getCount() == 1, "count=" + player.getMainHandItem().getCount());
            if (standard != null) {
                float yaw = net.minecraft.util.Mth.wrapDegrees(standard.getYRot());
                check("tester faces the player", Math.abs(Math.abs(yaw) - 180.0F) < 23.0F, "yaw=" + yaw);
            }
        });
        step("place a small tester", 10, () -> {
            hold(new ItemStack(ModContent.TESTER_ITEMS.get(TesterVariant.SMALL).get()));
            useOn(at(-3, -1, 3), Direction.UP);
            small = testerAt(at(-3, 0, 3), TesterVariant.SMALL);
            check("small tester placed", small != null, "");
        });
        step("place a hanging tester", 10, () -> {
            hangSupport = at(0, 3, 3);
            setBlock(hangSupport, Blocks.OAK_PLANKS.defaultBlockState());
            hold(new ItemStack(ModContent.TESTER_ITEMS.get(TesterVariant.HANGING).get(), 2));
            useOn(hangSupport, Direction.UP);
            check("hanging tester refuses the top face", testerAt(at(0, 4, 3), TesterVariant.HANGING) == null, "");
            useOn(hangSupport, Direction.DOWN);
            hanging = testerAt(at(0, 1, 3), TesterVariant.HANGING);
            check("hanging tester placed under the block", hanging != null, "");
            if (hanging != null) {
                check("hanging tester has no gravity", hanging.isNoGravity(), "");
            }
        });
        step("place a minecart tester", 10, () -> {
            setBlock(at(3, 0, 3), Blocks.RAIL.defaultBlockState());
            hold(new ItemStack(ModContent.TESTER_ITEMS.get(TesterVariant.MINECART).get(), 2));
            useOn(at(3, -1, 3), Direction.UP);
            check("minecart tester only goes on rails", player.getMainHandItem().getCount() == 2, "count=" + player.getMainHandItem().getCount());
            useOn(at(3, 0, 3), Direction.UP);
            minecart = testerAt(at(3, 0, 3), TesterVariant.MINECART);
            check("minecart tester placed", minecart != null, "");
            if (minecart != null) {
                check("minecart tester rides a minecart", Compat.isMinecart(minecart.getVehicle()), String.valueOf(minecart.getVehicle()));
            }
        });
        step("tester on a target block", 10, () -> {
            targetPos = at(6, 0, 3);
            setBlock(targetPos, Blocks.TARGET.defaultBlockState());
            hold(new ItemStack(ModContent.DAMAGE_TESTER_ITEM.get()));
            useOn(targetPos, Direction.UP);
            onTarget = testerAt(at(6, 1, 3), TesterVariant.STANDARD);
            check("tester placed on the target block", onTarget != null, "");
        });
        step("place a damage counter", 20, () -> {
            counterPos = at(-8, 0, 3);
            hold(new ItemStack(ModContent.DAMAGE_COUNTER_ITEM.get()));
            useOn(at(-8, -1, 3), Direction.UP);
            BlockState state = level.getBlockState(counterPos);
            check("damage counter placed", state.is(ModContent.DAMAGE_COUNTER.get()), state.toString());
            check("damage counter has its block entity", level.getBlockEntity(counterPos) instanceof DamageCounterBlockEntity, "");
        });
        step("client checks", 2, () -> {
            expectClient("clientcheck lang ok");
            expectClient("clientcheck textures ok");
            expectClient("clientcheck tabs ok");
        });
        step("overview", 30, () -> {
            view(0, 0.5, -4, 0, 12);
            shot("overview");
            for (DamageTesterEntity t : new DamageTesterEntity[]{standard, small, hanging, minecart, onTarget}) {
                if (t != null) {
                    expectClient("render id=" + t.getId() + " variant=" + t.getVariant().id());
                }
            }
            expectClient("counter pos=");
        });

        // ---------------------------------------------------------------- hits, numbers, stats
        step("melee hit", 4, () -> {
            view(-6, 0, 0, 0, 10);
            hold(new ItemStack(Items.IRON_SWORD));
            attack(standard, false);
            expectHit(standard, false);
        });
        step("melee hit visible", 26, () -> {
            shot("melee_hit");
            expectClient("number id=" + standard.getId() + " ");
        });
        step("stats after a hit", 4, () -> {
            DamageTesterEntity.Stats s = standard.getStats();
            check("hit counted", s.hits() == 1 && s.lastHit() > 0.0F, s.toString());
            check("tester survives the hit", standard.isAlive() && !standard.isRemoved(), "");
            check("health untouched", standard.getHealth() == standard.getMaxHealth(), "health=" + standard.getHealth());
        });
        step("critical hit", 4, () -> attack(standard, true));
        step("critical hit visible", 26, () -> {
            shot("critical_hit");
            expectClient("hit id=" + standard.getId() + " amount=");
            expectClient("crit=true");
        });
        step("different damage types", 12, () -> {
            damage(standard, 3.0F, "minecraft:on_fire");
        });
        step("magic damage", 12, () -> damage(standard, 4.0F, "minecraft:magic"));
        step("fall damage", 12, () -> damage(standard, 2.5F, "minecraft:fall"));
        step("explosion damage", 4, () -> damage(standard, 7.0F, "minecraft:explosion"));
        step("damage colors visible", 30, () -> {
            shot("damage_types");
            expectClient("color=");
        });
        step("huge damage", 12, () -> damage(standard, 5000.0F, "minecraft:generic"));
        step("stats after the series", 20, () -> {
            DamageTesterEntity.Stats s = standard.getStats();
            check("tester survives 5000 damage", standard.isAlive(), "");
            check("hits counted", s.hits() == 7, s.toString());
            check("max hit", s.maxHit() >= 4999.0F, s.toString());
            check("dps computed", s.dps() > 0.0F, s.toString());
        });
        step("damage counter shows the stats", 30, () -> {
            DamageCounterBlockEntity counter = (DamageCounterBlockEntity) level.getBlockEntity(counterPos);
            check("counter found the tester", counter != null && counter.hasTester(), "");
            check("counter has the stats", counter != null && counter.getStats().hits() == 7, counter == null ? "-" : counter.getStats().toString());
            expectClient("counter pos=" + counterPos.toShortString() + " hits=7");
            view(-7, 0.3, 0, 0, 20);
            shot("counter");
        });
        step("target block emits redstone", 1, () -> {
            damage(onTarget, 6.0F, "minecraft:generic");
        });
        step("target power", 1, () -> {
            int power = level.getBlockState(targetPos).getValue(BlockStateProperties.POWER);
            check("target block powered by the hit", power >= 6, "power=" + power);
        });
        step("target resets", 40, () -> { });
        step("target reset", 2, () -> {
            int power = level.getBlockState(targetPos).getValue(BlockStateProperties.POWER);
            check("target block resets", power == 0, "power=" + power);
        });
        step("combat resets after a few seconds", 20 * 6, () -> { });
        step("stats inactive", 2, () -> check("combat ended", !standard.getStats().active(), standard.getStats().toString()));
        step("hits on every variant", 3, () -> {
            for (DamageTesterEntity t : new DamageTesterEntity[]{small, hanging, minecart}) {
                if (t != null) {
                    damage(t, 5.0F, "minecraft:generic");
                    expectHit(t, false);
                }
            }
            view(-1.5, 0.5, -3, 0, 10);
        });
        step("variants hit", 25, () -> shot("variants_hit"));

        // ---------------------------------------------------------------- skins
        skin("straw", Items.HAY_BLOCK, DamageTesterEntity.SKIN_STRAW);
        skin("jack o'lantern", Items.JACK_O_LANTERN, DamageTesterEntity.SKIN_JACK);
        skin("oak planks", Items.OAK_PLANKS, DamageTesterEntity.SKIN_PLANKS_PREFIX + "minecraft:oak_planks");
        skin("crimson planks", Items.CRIMSON_PLANKS, DamageTesterEntity.SKIN_PLANKS_PREFIX + "minecraft:crimson_planks");
        skin("red dye", Compat.dye(net.minecraft.world.item.DyeColor.RED), DamageTesterEntity.SKIN_DYE_PREFIX + "red");
        skin("blue dye", Compat.dye(net.minecraft.world.item.DyeColor.BLUE), DamageTesterEntity.SKIN_DYE_PREFIX + "blue");
        step("shears remove the skin", 20, () -> {
            hold(new ItemStack(Items.SHEARS));
            interact(standard, new Vec3(0, 1.0, 0));
            check("shears remove the skin", standard.getSkin().isEmpty(), "skin=" + standard.getSkin());
            check("shears are kept", player.getMainHandItem().is(Items.SHEARS), "");
            expectClient("render id=" + standard.getId() + " variant=damage_tester skin= ");
        });
        step("skins on the other variants", 30, () -> {
            hold(new ItemStack(Items.JACK_O_LANTERN, 8));
            interact(small, new Vec3(0, 0.5, 0));
            hold(new ItemStack(Compat.dye(net.minecraft.world.item.DyeColor.LIME), 8));
            interact(hanging, new Vec3(0, 0.5, 0));
            hold(new ItemStack(Items.BIRCH_PLANKS, 8));
            interact(minecart, new Vec3(0, 1.0, 0));
            check("small tester skin", DamageTesterEntity.SKIN_JACK.equals(small.getSkin()), small.getSkin());
            check("hanging tester skin", (DamageTesterEntity.SKIN_DYE_PREFIX + "lime").equals(hanging.getSkin()), hanging.getSkin());
            check("minecart tester skin", (DamageTesterEntity.SKIN_PLANKS_PREFIX + "minecraft:birch_planks").equals(minecart.getSkin()), minecart.getSkin());
            view(-1.5, 0.5, -3, 0, 10);
            shot("variant_skins");
        });
        step("jeb_ rainbow", 10, () -> {
            hold(named(Items.NAME_TAG, "jeb_"));
            interact(onTarget, new Vec3(0, 1.0, 0));
            check("name tag names the tester", onTarget.hasCustomName() && "jeb_".equals(onTarget.getName().getString()), String.valueOf(onTarget.getCustomName()));
            expectClient("render id=" + onTarget.getId() + " variant=damage_tester skin= texture=" );
            expectClient("name=jeb_");
            view(6, 0.8, -2, 0, 15);
        });
        step("jeb_ visible", 20, () -> shot("jeb_rainbow_1"));
        step("jeb_ visible later", 30, () -> shot("jeb_rainbow_2"));

        // ---------------------------------------------------------------- armor
        step("armor goes on", 20, () -> {
            Item[] armor = {Items.IRON_HELMET, Items.DIAMOND_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.LEATHER_BOOTS};
            for (Item item : armor) {
                hold(new ItemStack(item));
                interact(standard, new Vec3(0, 1.0, 0));
                check("armor equipped: " + item, player.getMainHandItem().isEmpty(), "hand=" + player.getMainHandItem());
            }
            check("helmet slot", standard.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "");
            check("chest slot", standard.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE), "");
            check("legs slot", standard.getItemBySlot(EquipmentSlot.LEGS).is(Items.GOLDEN_LEGGINGS), "");
            check("feet slot", standard.getItemBySlot(EquipmentSlot.FEET).is(Items.LEATHER_BOOTS), "");
            expectClient("render id=" + standard.getId() + " variant=damage_tester skin= texture=");
            expectClient("armor=4");
            view(-6, 0.3, 0.2, 0, 10);
            shot("armor");
        });
        step("swap armor", 10, () -> {
            hold(new ItemStack(Items.NETHERITE_HELMET));
            interact(standard, new Vec3(0, 1.7, 0));
            check("helmet swapped", standard.getItemBySlot(EquipmentSlot.HEAD).is(Items.NETHERITE_HELMET)
                    && player.getMainHandItem().is(Items.IRON_HELMET), "hand=" + player.getMainHandItem());
        });
        step("take armor off", 10, () -> {
            hold(ItemStack.EMPTY);
            interact(standard, new Vec3(0, 1.7, 0));
            check("helmet taken off", standard.getItemBySlot(EquipmentSlot.HEAD).isEmpty() && player.getMainHandItem().is(Items.NETHERITE_HELMET),
                    "hand=" + player.getMainHandItem());
            hold(ItemStack.EMPTY);
            interact(standard, new Vec3(0, 0.1, 0));
            check("boots taken off", standard.getItemBySlot(EquipmentSlot.FEET).isEmpty() && player.getMainHandItem().is(Items.LEATHER_BOOTS),
                    "hand=" + player.getMainHandItem());
        });
        step("pumpkin on the head", 10, () -> {
            hold(new ItemStack(Items.CARVED_PUMPKIN));
            interact(standard, new Vec3(0, 1.7, 0));
            check("carved pumpkin worn", standard.getItemBySlot(EquipmentSlot.HEAD).is(Items.CARVED_PUMPKIN), "");
            hold(new ItemStack(Items.CREEPER_HEAD));
            interact(small, new Vec3(0, 0.8, 0));
            check("mob head worn", small.getItemBySlot(EquipmentSlot.HEAD).is(Items.CREEPER_HEAD), "");
            view(-4.5, 0.3, 0, 0, 10);
            shot("heads");
        });
        step("hit an armored tester", 4, () -> {
            hold(new ItemStack(Items.DIAMOND_SWORD));
            attack(standard, false);
            expectHit(standard, false);
        });
        step("armored hit visible", 25, () -> shot("armored_hit"));

        // ---------------------------------------------------------------- persistence setup for phase 2
        step("tester for the save test", 10, () -> {
            hold(new ItemStack(ModContent.DAMAGE_TESTER_ITEM.get()));
            useOn(at(-10, -1, 6), Direction.UP);
            DamageTesterEntity saved = testerAt(at(-10, 0, 6), TesterVariant.STANDARD);
            check("save test tester placed", saved != null, "");
            if (saved != null) {
                hold(new ItemStack(Compat.dye(net.minecraft.world.item.DyeColor.PURPLE)));
                interact(saved, new Vec3(0, 1, 0));
                hold(new ItemStack(Items.GOLDEN_HELMET));
                interact(saved, new Vec3(0, 1.7, 0));
                hold(named(Items.NAME_TAG, "Saved"));
                interact(saved, new Vec3(0, 1, 0));
                savedUuid = saved.getUUID();
            }
        });

        // ---------------------------------------------------------------- taking testers down
        step("sneak-punch dismantles in survival", 10, () -> {
            clearDrops();
            hold(ItemStack.EMPTY);
            player.setShiftKeyDown(true);
            attack(standard, false);
            player.setShiftKeyDown(false);
            check("tester removed", standard.isRemoved(), "");
        });
        step("dismantle drops", 5, () -> {
            List<ItemStack> drops = dropsAround(at(-6, 0, 3));
            check("drops the tester item", has(drops, ModContent.DAMAGE_TESTER_ITEM.get()), drops.toString());
            check("drops its armor", has(drops, Items.DIAMOND_CHESTPLATE) && has(drops, Items.GOLDEN_LEGGINGS) && has(drops, Items.CARVED_PUMPKIN), drops.toString());
            clearDrops();
        });
        step("named tester keeps its name as an item", 10, () -> {
            player.setShiftKeyDown(true);
            hold(ItemStack.EMPTY);
            attack(onTarget, false);
            player.setShiftKeyDown(false);
        });
        step("named drop", 5, () -> {
            List<ItemStack> drops = dropsAround(at(6, 1, 3));
            ItemStack drop = drops.stream().filter(s -> s.is(ModContent.DAMAGE_TESTER_ITEM.get())).findFirst().orElse(ItemStack.EMPTY);
            check("named item dropped", "jeb_".equals(nameOf(drop)), drops.toString());
            clearDrops();
            if (!drop.isEmpty()) {
                hold(drop.copy());
                useOn(targetPos, Direction.UP);
                onTarget = testerAt(at(6, 1, 3), TesterVariant.STANDARD);
                check("name kept when placed again", onTarget != null && "jeb_".equals(onTarget.getName().getString()),
                        onTarget == null ? "none" : onTarget.getName().getString());
            }
        });
        step("hanging tester falls without support", 30, () -> {
            clearDrops();
            setBlock(hangSupport, Blocks.AIR.defaultBlockState());
        });
        step("hanging tester dropped", 5, () -> {
            check("hanging tester removed", hanging.isRemoved(), "");
            check("hanging tester dropped as an item", has(dropsAround(at(0, 1, 3)), ModContent.TESTER_ITEMS.get(TesterVariant.HANGING).get()),
                    dropsAround(at(0, 1, 3)).toString());
            clearDrops();
        });
        step("breaking the minecart leaves a normal tester item", 20, () -> {
            Entity cart = minecart.getVehicle();
            if (cart != null) {
                Compat.kill(cart, level);
            }
        });
        step("minecart broken", 5, () -> {
            check("minecart tester removed with its cart", minecart.isRemoved(), "");
            check("drops a standard tester", has(dropsAround(at(3, 0, 3)), ModContent.DAMAGE_TESTER_ITEM.get()), dropsAround(at(3, 0, 3)).toString());
            clearDrops();
        });
        step("/kill removes without drops", 10, () -> command("kill " + small.getStringUUID()));
        step("killed", 5, () -> {
            check("small tester removed by /kill", small.isRemoved(), "");
            check("no drop from /kill", !has(dropsAround(at(-3, 0, 3)), ModContent.TESTER_ITEMS.get(TesterVariant.SMALL).get()), dropsAround(at(-3, 0, 3)).toString());
            clearDrops();
        });
        step("creative sneak-punch drops nothing", 10, () -> {
            command("gamemode creative " + name());
            hold(ItemStack.EMPTY);
            player.setShiftKeyDown(true);
            attack(onTarget, false);
            player.setShiftKeyDown(false);
            command("gamemode survival " + name());
        });
        step("creative removal", 5, () -> {
            check("removed in creative", onTarget.isRemoved(), "");
            check("no drop in creative", !has(dropsAround(at(6, 1, 3)), ModContent.DAMAGE_TESTER_ITEM.get()), dropsAround(at(6, 1, 3)).toString());
            clearDrops();
        });

        // ---------------------------------------------------------------- dispensers
        step("dispenser places a tester", 10, () -> {
            BlockPos d = at(9, 0, 5);
            setBlock(d, Blocks.DISPENSER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.NORTH));
            command(String.format(Locale.ROOT, "item replace block %d %d %d container.0 with %s", d.getX(), d.getY(), d.getZ(), "damage_tester:damage_tester"));
            setBlock(d.above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        });
        step("dispensed", 5, () -> {
            DamageTesterEntity t = testerAt(at(9, 0, 4), TesterVariant.STANDARD);
            check("dispenser placed a tester", t != null, "");
            if (t != null) {
                standard = t;
            }
        });
        step("dispenser puts a minecart tester on rails", 10, () -> {
            BlockPos d = at(11, 0, 5);
            setBlock(at(11, 0, 4), Blocks.RAIL.defaultBlockState());
            setBlock(d, Blocks.DISPENSER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.NORTH));
            command(String.format(Locale.ROOT, "item replace block %d %d %d container.0 with %s", d.getX(), d.getY(), d.getZ(), "damage_tester:minecart_damage_tester"));
            setBlock(d.above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        });
        step("dispensed cart", 5, () -> {
            DamageTesterEntity t = testerAt(at(11, 0, 4), TesterVariant.MINECART);
            check("dispenser placed a minecart tester", t != null && Compat.isMinecart(t.getVehicle()), String.valueOf(t));
            view(10, 0.5, 0, 0, 15);
            shot("dispensers");
        });

        // ---------------------------------------------------------------- the counter as a block
        step("counter without a tester", 30, () -> {
            hold(new ItemStack(ModContent.DAMAGE_COUNTER_ITEM.get()));
            view(-13, 0, -2.5, 180, 30);
            useOn(at(-13, -1, -5), Direction.UP);
        });
        step("counter empty state", 10, () -> {
            DamageCounterBlockEntity c = (DamageCounterBlockEntity) level.getBlockEntity(at(-13, 0, -5));
            expectClient("counter pos=" + at(-13, 0, -5).toShortString() + " empty");
            check("lonely counter has no tester", c != null && !c.hasTester(), "");
            shot("counter_empty");
        });
        step("breaking the counter drops it", 10, () -> {
            clearDrops();
            hold(new ItemStack(Items.IRON_AXE));
            player.gameMode.destroyBlock(counterPos);
        });
        step("counter dropped", 5, () -> {
            check("counter block removed", level.getBlockState(counterPos).isAir(), "");
            check("counter drops itself", has(dropsAround(counterPos), ModContent.DAMAGE_COUNTER_ITEM.get()), dropsAround(counterPos).toString());
        });

        // ---------------------------------------------------------------- items in the hotbar (icons) and done
        step("hotbar icons", 30, () -> {
            player.getInventory().clearContent();
            int slot = 0;
            for (Item item : ModContent.testerItems()) {
                player.getInventory().setItem(slot++, new ItemStack(item));
            }
            player.getInventory().setItem(slot, new ItemStack(ModContent.DAMAGE_COUNTER_ITEM.get()));
            view(0, 0, -3, 0, 15);
            shot("hotbar");
        });
    }

    private static void skin(String label, Item item, String expected) {
        step("skin: " + label, 20, () -> {
            hold(new ItemStack(item, 4));
            interact(standard, new Vec3(0, 1.0, 0));
            check("skin " + label, expected.equals(standard.getSkin()), "skin=" + standard.getSkin());
            check("skin item used up: " + label, player.getMainHandItem().getCount() == 3, "count=" + player.getMainHandItem().getCount());
            expectClient("render id=" + standard.getId() + " variant=damage_tester skin=" + expected + " ");
            view(-6, 0.3, 0.2, 0, 10);
            shot("skin_" + label.replace(' ', '_').replace("'", ""));
        });
    }

    // ================================================================== phase 2: saved and loaded

    private static void buildPhase2() {
        step("find the saved tester", 0, () -> {
            String text;
            try {
                text = Files.readString(STATE, StandardCharsets.UTF_8).trim();
            } catch (Exception e) {
                text = "";
            }
            UUID id = text.isEmpty() ? null : UUID.fromString(text);
            waitFor("saved tester loaded", 20 * 30, () -> id != null && level.getEntity(id) instanceof DamageTesterEntity);
            savedUuid = id;
        });
        step("saved tester state", 5, () -> {
            DamageTesterEntity t = (DamageTesterEntity) level.getEntity(savedUuid);
            if (t == null) {
                fail("saved tester", "missing");
                return;
            }
            check("skin saved", (DamageTesterEntity.SKIN_DYE_PREFIX + "purple").equals(t.getSkin()), t.getSkin());
            check("armor saved", t.getItemBySlot(EquipmentSlot.HEAD).is(Items.GOLDEN_HELMET), String.valueOf(t.getItemBySlot(EquipmentSlot.HEAD)));
            check("name saved", "Saved".equals(t.getName().getString()), t.getName().getString());
            check("variant saved", t.getVariant() == TesterVariant.STANDARD, String.valueOf(t.getVariant()));
        });
    }
}
