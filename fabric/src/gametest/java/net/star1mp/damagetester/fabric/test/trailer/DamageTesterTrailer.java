package net.star1mp.damagetester.fabric.test.trailer;

import it.unimi.dsi.fastutil.ints.IntList;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.star1mp.damagetester.ModContent;
import net.star1mp.damagetester.entity.DamageTesterEntity;
import net.star1mp.damagetester.entity.TesterVariant;
import net.star1mp.damagetester.fabric.test.trailer.Cine.Cam;
import net.star1mp.damagetester.fabric.test.trailer.Cine.Pose;

/**
 * Records the clips for the mod trailer. Not a test: only runs when {@code DT_TRAILER} is set
 * (a group name, "all", or a comma separated list of shot names). Each shot becomes one mp4 in
 * {@code DT_TRAILER_OUT}, plus a CSV of hit events used for sound effects in the edit.
 */
public class DamageTesterTrailer implements FabricClientGameTest {
    private ClientGameTestContext ctx;
    private TestSingleplayerContext sp;
    private final TrailerWorld w = new TrailerWorld();
    private Path out;
    private Cine.EventLog events;

    // current shot
    private String clip;
    private int tick;
    private double fpsMul;
    private final Map<Integer, List<Runnable>> actions = new HashMap<>();
    /** First tester each actor strikes in the current shot: actors face it before recording starts. */
    private final Map<Integer, Integer> firstTarget = new LinkedHashMap<>();
    /** Wanted yaw per actor, written by the server thread and applied to the client mannequin every frame. */
    private static final Map<Integer, Float> FACING = new java.util.concurrent.ConcurrentHashMap<>();
    /** Entity an actor keeps its eyes on (head follows it every frame, the body catches up). */
    private static final Map<Integer, Integer> LOOK = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<Integer> WALKING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static int frameCounter;
    /** Wanted yaw of testers on the client (testers "turn their head" in the comedy shorts). */
    private static final Map<Integer, Float> TYAW = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<Integer> TSNAP = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Entities whose screen position is logged every frame (for speech bubbles in the edit). */
    private final Map<String, Integer> speakers = new LinkedHashMap<>();
    private final AtomicInteger cameraTarget = new AtomicInteger();
    private final List<IntConsumer> everyTick = new ArrayList<>();
    private int actorCounter;

    public static boolean enabled() {
        return System.getenv("DT_TRAILER") != null;
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!enabled()) {
            return;
        }
        this.ctx = context;
        this.out = Path.of(System.getenv().getOrDefault("DT_TRAILER_OUT", "trailer")).toAbsolutePath();
        this.events = new Cine.EventLog(this.out.resolve("events.csv"));
        context.waitTicks(40);
        Cine.VERTICAL = System.getenv("DT_TRAILER").toUpperCase(Locale.ROOT).startsWith("K");
        if (Cine.VERTICAL) {
            context.getInput().resizeWindow(1080, 1920);
        } else {
            context.getInput().resizeWindow(1920, 1080);
        }
        context.waitTicks(5);
        context.runOnClient(mc -> {
            mc.options.guiScale().set(3);
            mc.options.fov().set(70);
            mc.options.renderDistance().set(12);
            mc.resizeGui();
        });
        try (TestSingleplayerContext world = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(s -> {
                    s.setSeed("damage tester trailer");
                    s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                    s.setGenerateStructures(false);
                    s.getGameRules().set(GameRules.ADVANCE_TIME, false, null);
                    s.getGameRules().set(GameRules.ADVANCE_WEATHER, false, null);
                    s.getGameRules().set(GameRules.SPAWN_MOBS, false, null);
                    s.getGameRules().set(GameRules.SPAWN_MONSTERS, false, null);
                    s.getGameRules().set(GameRules.MOB_GRIEFING, false, null);
                    s.getGameRules().set(GameRules.SHOW_ADVANCEMENT_MESSAGES, false, null);
                    s.getGameRules().set(GameRules.SEND_COMMAND_FEEDBACK, false, null);
                })
                .create()) {
            this.sp = world;
            this.server();
            this.setupWorld();
            this.runShots();
        }
    }

    // ================================================================== world

    private void setupWorld() {
        long t0 = System.currentTimeMillis();
        this.cmd("weather clear");
        this.cmd("time set 1000");
        this.cmd("gamemode spectator @a");
        this.srv(level -> this.w.findSite(level, new BlockPos(0, 80, 0)));
        this.cmd(String.format(Locale.ROOT, "tp @a %d %d %d", this.w.cx, this.w.g + 20, this.w.cz + 10));
        this.ctx.waitTicks(40);
        this.srv(level -> {
            this.w.flatten(level);
        });
        for (int[] t : TrailerWorld.TREES) {
            int x = this.w.cx + t[0];
            int z = this.w.cz + t[1];
            int y = this.sp.getServer().computeOnServer(s -> this.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
            String tree = (t[0] * 31 + t[1]) % 3 == 0 ? "birch" : (t[0] + t[1]) % 4 == 0 ? "fancy_oak" : "oak";
            this.cmd("place feature minecraft:" + tree + " " + x + " " + y + " " + z);
        }
        this.srv(this.w::decorate);
        this.srv(this.w::buildAll);
        this.ctx.waitTicks(60);
        // let the client see all sets once so chunk meshes exist
        for (Vec3 p : new Vec3[]{this.w.v(0, 30, 10), this.w.v(-35, 25, 30), this.w.v(30, 25, 34), this.w.v(0, 25, 40)}) {
            this.ctx.runOnClient(mc -> Cine.placeCamera(mc, new Pose(p.x, p.y, p.z, 0, 50)));
            this.ctx.waitTicks(30);
        }
        try {
            this.sp.getConnection().waitForChunksRender(200);
        } catch (Throwable e) {
            System.out.println("[trailer] chunk render wait: " + e);
        }
        System.out.println("[trailer] world ready in " + (System.currentTimeMillis() - t0) + " ms");
    }

    // ================================================================== shot runner

    private final class Shot {
        final String name;
        final String group;
        final int ticks;
        double fps = 1.0;
        Cam cam;
        boolean hud;
        boolean survival = true;
        int fov = -1;
        long time = 1000;
        String weather = "clear";
        Runnable setup = () -> {
        };

        Shot(String name, String group, int ticks) {
            this.name = name;
            this.group = group;
            this.ticks = ticks;
        }

        Shot cam(Cam c) {
            this.cam = c;
            return this;
        }

        Shot fp() {
            this.hud = true;
            return this;
        }

        Shot fps(double m) {
            this.fps = m;
            return this;
        }

        Shot time(long t) {
            this.time = t;
            return this;
        }

        Shot fov(int f) {
            this.fov = f;
            return this;
        }

        Shot weather(String wth) {
            this.weather = wth;
            return this;
        }

        Shot setup(Runnable r) {
            this.setup = r;
            return this;
        }
    }

    private final List<Shot> shots = new ArrayList<>();

    private Shot shot(String name, String group, int ticks) {
        Shot s = new Shot(name, group, ticks);
        this.shots.add(s);
        return s;
    }

    private void runShots() {
        this.defineShots();
        String sel = System.getenv("DT_TRAILER");
        Set<String> wanted = new HashSet<>(List.of(sel.split(",")));
        int maxTicks = Integer.parseInt(System.getenv().getOrDefault("DT_TRAILER_MAXTICKS", "100000"));
        for (Shot s : this.shots) {
            if (wanted.contains("all") || wanted.contains(s.group) || wanted.contains(s.name)) {
                this.run(s, Math.min(s.ticks, maxTicks));
            }
        }
    }

    private void run(Shot s, int ticks) {
        long t0 = System.currentTimeMillis();
        this.clip = s.name;
        this.fpsMul = s.fps;
        this.actions.clear();
        this.everyTick.clear();
        this.firstTarget.clear();
        this.speakers.clear();
        FACING.clear();
        LOOK.clear();
        WALKING.clear();
        TYAW.clear();
        TSNAP.clear();
        this.cleanup();
        this.cmd("time set " + s.time);
        this.cmd("weather " + s.weather);
        this.cmd(s.cam != null ? "gamemode spectator @a" : s.survival ? "gamemode survival @a" : "gamemode creative @a");
        this.srv(level -> {
            ServerPlayer pl = this.player();
            pl.getInventory().clearContent();
            pl.setHealth(20.0F);
            pl.getFoodData().setFoodLevel(20);
            pl.getAbilities().flying = false;
            pl.onUpdateAbilities();
        });
        this.tick = 0;
        s.setup.run();
        this.faceTargets();
        this.hud(s.hud);
        int fov = s.fov > 0 ? s.fov : s.cam != null ? 58 : 70;
        this.ctx.runOnClient(mc -> {
            mc.options.fov().set(fov);
            mc.options.fovEffectScale().set(0.0);
            mc.gui.hud.getChat().clearMessages(false);
            mc.gui.toastManager().clear();
        });
        // pre-roll: settle entities, interpolation and chunk meshes
        for (int k = 0; k < 40; k++) {
            if (s.cam != null) {
                this.ctx.runOnClient(mc -> Cine.placeCamera(mc, s.cam.at(0, 0, mc, 0)));
            }
            this.ctx.runOnClient(mc -> alignBodies(mc, true));
            this.ctx.waitTick();
        }
        this.ctx.runOnClient(mc -> {
            mc.gui.hud.getChat().clearMessages(false);
            mc.gui.toastManager().clear();
        });
        Cine.Recorder rec = new Cine.Recorder(this.out.resolve("clips").resolve(s.name + ".mp4"));
        double step = Cine.TICKS_PER_FRAME / s.fps;
        int frame = 0;
        for (int i = 1; i <= ticks; i++) {
            this.tick = i;
            List<Runnable> now = this.actions.get(i);
            if (now != null) {
                now.forEach(Runnable::run);
            }
            for (IntConsumer c : this.everyTick) {
                c.accept(i);
            }
            this.ctx.waitTick();
            while (frame * step < i) {
                double t = frame * step;
                float partial = (float) (t - (i - 1));
                int index = frame;
                double u = t / ticks;
                this.ctx.runOnClient(mc -> {
                    mc.gui.toastManager().clear();
                    if (s.cam != null) {
                        Cine.placeCamera(mc, s.cam.at(u, t, mc, partial));
                    }
                    alignBodies(mc, false);
                    if (!this.speakers.isEmpty() && s.cam != null) {
                        this.logSpeakers(mc, s, index, s.cam.at(u, t, mc, partial), partial);
                    }
                    Cine.renderFrame(mc, partial, rec, index);
                });
                frame++;
            }
        }
        int expected = frame;
        for (int k = 0; k < 200 && rec.written() < expected; k++) {
            this.ctx.waitTick();
        }
        rec.close();
        this.hud(true);
        long ms = System.currentTimeMillis() - t0;
        System.out.printf(Locale.ROOT, "[trailer] %s: %d frames (%d written) in %.1f s (%.2f s/frame)%n",
                s.name, expected, rec.written(), ms / 1000.0, ms / 1000.0 / Math.max(1, expected));
    }

    private void logSpeakers(net.minecraft.client.Minecraft mc, Shot s, int frame, Pose cam, float partial) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : this.speakers.entrySet()) {
            Entity en = mc.level.getEntity(e.getValue());
            if (en == null) {
                continue;
            }
            double x = net.minecraft.util.Mth.lerp(partial, en.xo, en.getX());
            double y = net.minecraft.util.Mth.lerp(partial, en.yo, en.getY()) + en.getBbHeight();
            double z = net.minecraft.util.Mth.lerp(partial, en.zo, en.getZ());
            sb.append(String.format(Locale.ROOT, "%s,%d,%s,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.2f,%.2f,%d%n", s.name, frame, e.getKey(),
                    x, y, z, cam.x(), cam.y(), cam.z(), cam.yaw(), cam.pitch(), mc.options.fov().get()));
        }
        try {
            java.nio.file.Files.createDirectories(this.out);
            java.nio.file.Files.writeString(this.out.resolve("speakers.csv"), sb.toString(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void pose(int t, int actorId, net.minecraft.world.entity.Pose pose) {
        this.at(t, () -> this.srv(level -> {
            Entity e = this.entity(level, actorId);
            if (e != null) {
                e.setPose(pose);
            }
        }));
    }

    /** Walks an actor in a straight line between two ticks, facing the way it goes. */
    private void walk(int from, int to, int actorId, Vec3 a, Vec3 b) {
        float yaw = (float) Math.toDegrees(Math.atan2(b.z - a.z, b.x - a.x)) - 90.0F;
        this.every(i -> {
            if (i < from || i > to) {
                return;
            }
            double u = (double) (i - from) / Math.max(1, to - from);
            this.moveActor(actorId, Cine.lerp(u, a, b));
            if (i == to) {
                WALKING.remove(actorId);
            }
            if (i == from) {
                WALKING.add(actorId);
                LOOK.remove(actorId);
                FACING.put(actorId, yaw);
                this.srv(level -> {
                    if (this.entity(level, actorId) instanceof LivingEntity le) {
                        le.setYRot(yaw);
                    }
                });
            }
        });
    }

    private void testerTurn(int t, int testerId, float yaw, boolean snap) {
        this.at(t, () -> {
            TYAW.put(testerId, yaw);
            if (snap) {
                TSNAP.add(testerId);
            }
        });
    }

    /** Drives a minecart tester along a straight line (the cart rides with its tester). */
    private void driveCart(int from, int to, int testerId, Vec3 a, Vec3 b) {
        this.every(i -> {
            if (i < from || i > to) {
                return;
            }
            double u = (double) (i - from) / Math.max(1, to - from);
            Vec3 p = Cine.lerp(u, a, b);
            this.srv(level -> {
                Entity t = this.entity(level, testerId);
                Entity cart = t == null ? null : t.getVehicle();
                if (cart != null) {
                    cart.setDeltaMovement(Vec3.ZERO);
                    cart.setPos(p.x, p.y, p.z);
                }
            });
        });
    }

    private void hop(int from, int actorId, Vec3 at, double height, int dur) {
        this.every(i -> {
            if (i < from || i > from + dur) {
                return;
            }
            double u = (double) (i - from) / dur;
            this.moveActor(actorId, at.add(0, 4 * height * u * (1 - u), 0));
        });
    }

    private void look(int t, int actorId, int targetId) {
        this.at(t, () -> LOOK.put(actorId, targetId));
    }

    private void turn(int t, int actorId, float yaw) {
        this.at(t, () -> {
            LOOK.remove(actorId);
            FACING.put(actorId, yaw);
            this.srv(level -> {
                Entity e = this.entity(level, actorId);
                if (e != null) {
                    e.setYRot(yaw);
                }
            });
        });
    }

    /** A line of dialogue: logged for the edit (bubble + mumble). */
    private void say(int t, String who, String key) {
        this.at(t, () -> this.event("say", who + ":" + key));
    }

    private void at(int t, Runnable r) {
        this.actions.computeIfAbsent(t, k -> new ArrayList<>()).add(r);
    }

    private void every(IntConsumer c) {
        this.everyTick.add(c);
    }

    /** Event time in the clip (seconds) for something that happens at the current tick. */
    private void event(String type, String detail) {
        double seconds = Math.max(0, this.tick - 1) * this.fpsMul / 20.0;
        this.events.log(this.clip, seconds, type, detail);
    }

    // ================================================================== helpers: game access

    private net.minecraft.server.MinecraftServer server;

    private net.minecraft.server.MinecraftServer server() {
        if (this.server == null) {
            this.server = this.sp.getServer().computeOnServer(s -> s);
        }
        return this.server;
    }

    private ServerLevel level() {
        return this.server().overworld();
    }

    /** Safe from any thread (no blocking hop to the server thread). */
    private ServerPlayer player() {
        return this.server().getPlayerList().getPlayers().get(0);
    }

    private void srv(Consumer<ServerLevel> c) {
        this.sp.getServer().runOnServer(s -> c.accept(s.overworld()));
    }

    private <T> T srvGet(Function<ServerLevel, T> f) {
        return this.sp.getServer().computeOnServer(s -> f.apply(s.overworld()));
    }

    private void cmd(String c) {
        this.sp.getServer().runCommand(c);
    }

    private void hud(boolean visible) {
        this.ctx.runOnClient(mc -> {
            if (mc.gui.hud.isHidden() == visible) {
                mc.gui.hud.toggle();
            }
        });
    }

    private void cleanup() {
        this.srv(level -> {
            List<Entity> all = new ArrayList<>();
            level.getAllEntities().forEach(all::add);
            for (Entity e : all) {
                if (!(e instanceof Player)) {
                    e.discard();
                }
            }
        });
        this.ctx.waitTicks(2);
    }

    // ---------------------------------------------------------------- testers

    private int tester(TesterVariant variant, double x, double y, double z, float yaw, String skin) {
        Vec3 pos = this.w.v(x, y, z);
        return this.srvGet(level -> {
            DamageTesterEntity t = DamageTesterEntity.spawnFromItem(variant, level, ItemStack.EMPTY, null, pos, yaw);
            if (t == null) {
                throw new AssertionError("cannot place tester at " + pos);
            }
            applySkin(t, skin);
            return t.getId();
        });
    }

    private int tester(double x, double z, float yaw, String skin) {
        return this.tester(TesterVariant.STANDARD, x, 0, z, yaw, skin);
    }

    private static void applySkin(DamageTesterEntity t, String skin) {
        if (skin == null || skin.isEmpty()) {
            return;
        }
        if (skin.equals("jeb")) {
            t.setCustomName(Component.literal("jeb_"));
        } else {
            t.setSkin(skin);
        }
    }

    private int hangingTester(int x, int beamY, int z, String skin) {
        double y = beamY - TesterVariant.ROPE_LENGTH - TesterVariant.HANGING.height();
        return this.tester(TesterVariant.HANGING, x + 0.5, y, z + 0.5, 0.0F, skin);
    }

    private int cartTester(int x, int z, float yaw, String skin, double vx, double vz) {
        BlockPos rail = this.w.p(x, 0, z);
        return this.srvGet(level -> {
            DamageTesterEntity t = DamageTesterEntity.spawnInMinecart(level, rail, ItemStack.EMPTY, null, yaw);
            if (t == null) {
                throw new AssertionError("cannot place minecart tester at " + rail);
            }
            applySkin(t, skin);
            Entity cart = t.getVehicle();
            if (cart != null) {
                cart.setDeltaMovement(vx, 0, vz);
            }
            return t.getId();
        });
    }

    private Entity entity(ServerLevel level, int id) {
        return level.getEntity(id);
    }

    private void equip(int id, EquipmentSlot slot, ItemStack stack) {
        this.srv(level -> {
            if (this.entity(level, id) instanceof LivingEntity le) {
                le.setItemSlot(slot, stack);
            }
        });
    }

    private void armor(int id, String material) {
        ItemStack[] set = switch (material) {
            case "netherite" -> new ItemStack[]{new ItemStack(Items.NETHERITE_HELMET), new ItemStack(Items.NETHERITE_CHESTPLATE),
                    new ItemStack(Items.NETHERITE_LEGGINGS), new ItemStack(Items.NETHERITE_BOOTS)};
            case "diamond" -> new ItemStack[]{new ItemStack(Items.DIAMOND_HELMET), new ItemStack(Items.DIAMOND_CHESTPLATE),
                    new ItemStack(Items.DIAMOND_LEGGINGS), new ItemStack(Items.DIAMOND_BOOTS)};
            case "iron" -> new ItemStack[]{new ItemStack(Items.IRON_HELMET), new ItemStack(Items.IRON_CHESTPLATE),
                    new ItemStack(Items.IRON_LEGGINGS), new ItemStack(Items.IRON_BOOTS)};
            case "gold" -> new ItemStack[]{new ItemStack(Items.GOLDEN_HELMET), new ItemStack(Items.GOLDEN_CHESTPLATE),
                    new ItemStack(Items.GOLDEN_LEGGINGS), new ItemStack(Items.GOLDEN_BOOTS)};
            case "leather" -> new ItemStack[]{new ItemStack(Items.LEATHER_HELMET), new ItemStack(Items.LEATHER_CHESTPLATE),
                    new ItemStack(Items.LEATHER_LEGGINGS), new ItemStack(Items.LEATHER_BOOTS)};
            default -> new ItemStack[]{ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY};
        };
        this.equip(id, EquipmentSlot.HEAD, set[0]);
        this.equip(id, EquipmentSlot.CHEST, set[1]);
        this.equip(id, EquipmentSlot.LEGS, set[2]);
        this.equip(id, EquipmentSlot.FEET, set[3]);
    }

    private Vec3 pos(int id) {
        return this.srvGet(level -> this.entity(level, id).position());
    }

    // ---------------------------------------------------------------- actors (mannequins as players)

    static String profileNbt(String skin) {
        String mode = System.getenv().getOrDefault("DT_PROFILE_MODE", "texture");
        String model = switch (skin) {
            case "alex", "zuri", "makena", "sunny", "ari" -> "slim";
            default -> "wide";
        };
        return switch (mode) {
            case "name" -> "\"" + skin + "\"";
            case "texture_ns" -> "{texture:\"minecraft:entity/player/" + model + "/" + skin + "\",model:\"" + model + "\"}";
            default -> "{texture:\"entity/player/" + model + "/" + skin + "\",model:\"" + model + "\"}";
        };
    }

    private int actor(String name, String skin, double x, double z, float yaw, ItemStack mainHand) {
        return this.actor(name, skin, profileNbt(skin), x, 0, z, yaw, mainHand);
    }

    private int actor(String name, String skin, String profile, double x, double y, double z, float yaw, ItemStack mainHand) {
        int n = this.actorCounter++;
        String tag = "dt_actor_" + n;
        Vec3 p = this.w.v(x, y, z);
        String nameNbt = name == null ? "" : ",CustomName:\"" + name + "\",CustomNameVisible:1b";
        String nbt = "{" + (profile == null ? "" : "profile:" + profile + ",") + "hide_description:1b,Invulnerable:1b" + nameNbt
                + ",Tags:[\"" + tag + "\"],Rotation:[" + yaw + "f,0f]}";
        this.cmd(String.format(Locale.ROOT, "summon minecraft:mannequin %.3f %.3f %.3f %s", p.x, p.y, p.z, nbt));
        int id = this.srvGet(level -> {
            for (Entity e : level.getAllEntities()) {
                if (e.entityTags().contains(tag)) {
                    return e.getId();
                }
            }
            throw new AssertionError("mannequin " + tag + " was not summoned: " + nbt);
        });
        this.srv(level -> {
            LivingEntity e = (LivingEntity) this.entity(level, id);
            e.setYRot(yaw);
            e.setYHeadRot(yaw);
            e.setYBodyRot(yaw);
            e.setItemSlot(EquipmentSlot.MAINHAND, mainHand);
            if (name != null) {
                e.setCustomName(Component.literal(name));
                e.setCustomNameVisible(true);
            }
        });
        return id;
    }

    private void face(LivingEntity a, Vec3 target) {
        double dx = target.x - a.getX();
        double dz = target.z - a.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        FACING.put(a.getId(), yaw);
        a.setYRot(yaw);
        a.setYHeadRot(yaw);
        a.setYBodyRot(yaw);
    }

    private void swing(int actorId) {
        this.srv(level -> {
            if (this.entity(level, actorId) instanceof LivingEntity a) {
                a.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
            }
        });
    }

    /**
     * Mannequins have no AI, so on the client their body only drifts after the head. Keep the body
     * turned with the head so actors always look and stand towards what they hit.
     */
    private static void alignBodies(net.minecraft.client.Minecraft mc, boolean snap) {
        if (mc.level == null) {
            return;
        }
        frameCounter++;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof DamageTesterEntity te && TYAW.containsKey(te.getId())) {
                float want = TYAW.get(te.getId());
                float cur = te.yBodyRot;
                float yaw = snap || TSNAP.remove(te.getId()) ? want : cur + net.minecraft.util.Mth.wrapDegrees(want - cur) * 0.12F;
                te.setYRot(yaw);
                te.yRotO = yaw;
                te.yBodyRot = yaw;
                te.yBodyRotO = yaw;
                te.yHeadRot = yaw;
                te.yHeadRotO = yaw;
                continue;
            }
            if (!(e instanceof net.minecraft.world.entity.decoration.Mannequin m)) {
                continue;
            }
            int id = m.getId();
            float body = m.yBodyRot;
            float head = m.yHeadRot;
            float pitch = m.getXRot();
            Float facing = FACING.get(id);
            Integer lookId = LOOK.get(id);
            Entity target = lookId == null ? null : mc.level.getEntity(lookId);
            boolean walking = WALKING.contains(id);
            float wantHead;
            float wantPitch = 0.0F;
            if (target != null) {
                double ex = m.getX();
                double ey = m.getY() + m.getBbHeight() * 0.88;
                double ez = m.getZ();
                double tx = target.getX();
                double ty = target.getY() + target.getBbHeight() * 0.8;
                double tz = target.getZ();
                double dx = tx - ex;
                double dz = tz - ez;
                wantHead = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
                wantPitch = (float) -Math.toDegrees(Math.atan2(ty - ey, Math.sqrt(dx * dx + dz * dz)));
                wantPitch = net.minecraft.util.Mth.clamp(wantPitch, -40.0F, 45.0F);
            } else if (facing != null) {
                wantHead = facing;
            } else {
                wantHead = body;
            }
            if (!walking) {
                // small idle head movement so nobody looks frozen
                wantHead += 5.0F * (float) Math.sin(frameCounter * 0.045 + id * 1.7);
                wantPitch += 3.0F * (float) Math.sin(frameCounter * 0.06 + id * 2.3);
            }
            float wantBody = walking && facing != null ? facing : body;
            if (snap) {
                head = wantHead;
                body = walking && facing != null ? facing : wantHead;
                pitch = wantPitch;
            } else {
                head += net.minecraft.util.Mth.wrapDegrees(wantHead - head) * 0.35F;
                pitch += (wantPitch - pitch) * 0.3F;
                if (walking && facing != null) {
                    body += net.minecraft.util.Mth.wrapDegrees(wantBody - body) * 0.35F;
                } else {
                    float d = net.minecraft.util.Mth.wrapDegrees(head - body);
                    if (Math.abs(d) > 18.0F) {
                        body += (d - Math.signum(d) * 18.0F) * 0.22F;
                    }
                }
            }
            // keep the head within what a neck can do
            float rel = net.minecraft.util.Mth.wrapDegrees(head - body);
            if (Math.abs(rel) > 70.0F) {
                body = head - Math.signum(rel) * 70.0F;
            }
            m.setYRot(body);
            m.yRotO = body;
            m.yBodyRot = body;
            m.yBodyRotO = body;
            m.yHeadRot = head;
            m.yHeadRotO = head;
            m.setXRot(pitch);
            m.xRotO = pitch;
        }
    }

    /** Before recording: every actor turns to its first target (or the nearest tester). */
    private void faceTargets() {
        this.srv(level -> {
            for (Entity e : level.getAllEntities()) {
                if (!(e instanceof net.minecraft.world.entity.decoration.Mannequin a)) {
                    continue;
                }
                Entity target = this.firstTarget.containsKey(a.getId()) ? this.entity(level, this.firstTarget.get(a.getId())) : null;
                if (target == null) {
                    double best = 18 * 18;
                    for (Entity o : level.getAllEntities()) {
                        if (o instanceof DamageTesterEntity && o.distanceToSqr(a) < best) {
                            best = o.distanceToSqr(a);
                            target = o;
                        }
                    }
                }
                if (target != null) {
                    this.face(a, target.position());
                    LOOK.put(a.getId(), target.getId());
                }
            }
        });
    }

    /** Actor swings at tick t and the hit lands 2 ticks later. */
    private void strike(int t, int actorId, int testerId, float amount, boolean crit) {
        this.firstTarget.putIfAbsent(actorId, testerId);
        this.at(Math.max(1, t - 8), () -> LOOK.put(actorId, testerId));
        this.at(Math.max(1, t - 6), () -> this.srv(level -> {
            if (this.entity(level, actorId) instanceof LivingEntity a && this.entity(level, testerId) != null) {
                this.face(a, this.entity(level, testerId).position());
            }
        }));
        this.at(t, () -> {
            this.srv(level -> {
                if (this.entity(level, actorId) instanceof LivingEntity a && this.entity(level, testerId) != null) {
                    this.face(a, this.entity(level, testerId).position());
                }
            });
            this.swing(actorId);
            this.event("swing", "");
        });
        this.at(t + 2, () -> this.hitBy(actorId, testerId, amount, crit));
    }

    private void hitBy(int actorId, int testerId, float amount, boolean crit) {
        this.srv(level -> {
            Entity a = this.entity(level, actorId);
            DamageSource src = a instanceof LivingEntity le ? level.damageSources().mobAttack(le) : level.damageSources().generic();
            this.damage(level, testerId, src, amount, crit);
        });
        this.event(crit ? "hit_crit" : amount >= 9 ? "hit_strong" : "hit", String.valueOf(amount));
    }

    private void damage(ServerLevel level, int testerId, DamageSource src, float amount, boolean crit) {
        if (this.entity(level, testerId) instanceof DamageTesterEntity t) {
            t.setInvulnerableTime(0);
            if (crit) {
                t.markCriticalHit();
                level.sendParticles(ParticleTypes.CRIT, t.getX(), t.getY() + 1.2, t.getZ(), 18, 0.3, 0.4, 0.3, 0.35);
            }
            t.hurtServer(level, src, amount);
        }
    }

    private void hitWith(int t, int testerId, Function<ServerLevel, DamageSource> src, float amount, String sfx) {
        this.at(t, () -> {
            this.srv(level -> this.damage(level, testerId, src.apply(level), amount, false));
            this.event(sfx, String.valueOf(amount));
        });
    }

    private void moveActor(int actorId, Vec3 p) {
        this.srv(level -> {
            Entity e = this.entity(level, actorId);
            if (e != null) {
                e.setPos(p.x, p.y, p.z);
            }
        });
    }

    /** Scripted mace jump: actor leaps from its spot up and onto the tester, smashing at the end. */
    private void maceJump(int start, int actorId, int testerId, float amount, int duration, double peak) {
        AtomicReference<Vec3> from = new AtomicReference<>();
        AtomicReference<Vec3> to = new AtomicReference<>();
        this.at(start, () -> {
            from.set(this.pos(actorId));
            Vec3 tp = this.pos(testerId);
            Vec3 f = from.get();
            Vec3 dir = new Vec3(tp.x - f.x, 0, tp.z - f.z).normalize();
            to.set(new Vec3(tp.x - dir.x * 0.9, tp.y, tp.z - dir.z * 0.9));
            this.event("jump", "");
        });
        this.every(i -> {
            int k = i - start;
            if (k < 0 || k > duration || from.get() == null) {
                return;
            }
            double u = (double) k / duration;
            Vec3 p = Cine.lerp(u, from.get(), to.get()).add(0, 4 * peak * u * (1 - u), 0);
            this.moveActor(actorId, p);
            if (k == duration - 3) {
                this.swing(actorId);
            }
            if (k == duration) {
                this.srv(level -> {
                    Entity a = this.entity(level, actorId);
                    Entity t = this.entity(level, testerId);
                    if (t != null) {
                        this.damage(level, testerId, level.damageSources().mace(a), amount, false);
                        level.sendParticles(new BlockParticleOption(ParticleTypes.DUST_PILLAR, Blocks.GRASS_BLOCK.defaultBlockState()),
                                t.getX(), t.getY(), t.getZ(), 60, 1.2, 0.1, 1.2, 0.3);
                        level.sendParticles(ParticleTypes.EXPLOSION, t.getX(), t.getY() + 0.5, t.getZ(), 2, 0.4, 0.2, 0.4, 0);
                    }
                });
                this.event("mace", String.valueOf(amount));
            }
        });
    }

    private void particles(ParticleOptions p, Vec3 at, int count, double spread, double speed) {
        this.srv(level -> level.sendParticles(p, at.x, at.y, at.z, count, spread, spread, spread, speed));
    }

    private void chat(int t, String who, String text) {
        this.at(t, () -> {
            this.srv(level -> this.player().sendSystemMessage(Component.literal("<" + who + "> " + text)));
            this.event("chat", who);
        });
    }

    private void firework(int t, Vec3 at, int... colors) {
        this.at(t, () -> {
            this.srv(level -> {
                ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
                rocket.set(DataComponents.FIREWORKS, new Fireworks(1, List.of(new FireworkExplosion(
                        FireworkExplosion.Shape.LARGE_BALL, IntList.of(colors), IntList.of(0xFFFFFF), true, true))));
                level.addFreshEntity(new FireworkRocketEntity(level, at.x, at.y, at.z, rocket));
            });
            this.event("firework", "");
        });
    }

    private void shootArrow(int t, int shooterId, int targetId, double lead) {
        this.at(t, () -> {
            this.srv(level -> {
                if (!(this.entity(level, shooterId) instanceof LivingEntity s) || this.entity(level, targetId) == null) {
                    return;
                }
                Entity target = this.entity(level, targetId);
                Vec3 tv = target.getVehicle() != null ? target.getVehicle().getDeltaMovement() : Vec3.ZERO;
                Vec3 aim = target.position().add(0, 1.1, 0).add(tv.scale(lead));
                this.face(s, aim);
                Arrow arrow = new Arrow(level, s, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
                Vec3 d = aim.subtract(arrow.position());
                double dist = d.horizontalDistance();
                arrow.shoot(d.x, d.y + dist * 0.06, d.z, 2.6F, 0.0F);
                level.addFreshEntity(arrow);
                s.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
            });
            this.event("bow", "");
        });
    }

    // ---------------------------------------------------------------- first person input

    private void hold(int t, Function<Options, KeyMapping> key, int duration) {
        this.at(t, () -> this.ctx.getInput().holdKey(key));
        this.at(t + duration, () -> this.ctx.getInput().releaseKey(key));
    }

    private void click(int t, Function<Options, KeyMapping> key) {
        this.at(t, () -> {
            this.ctx.getInput().holdKey(key);
            this.ctx.getInput().releaseKey(key);
        });
    }

    private void attack(int t) {
        this.click(t, o -> o.keyAttack);
        this.at(t + 1, () -> this.event("player_attack", ""));
    }

    private void use(int t) {
        this.click(t, o -> o.keyUse);
    }

    private void hand(int t, ItemStack stack) {
        this.at(t, () -> this.srv(level -> this.player().setItemInHand(InteractionHand.MAIN_HAND, stack.copy())));
    }

    private void fpAt(double x, double y, double z, Vec3 lookAt) {
        Vec3 eye = this.w.v(x, y + 1.62, z);
        Pose p = Pose.look(eye, lookAt);
        this.cmd(String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f %.2f %.2f", eye.x, eye.y - 1.62, eye.z, p.yaw(), p.pitch()));
        this.ctx.waitTicks(2);
        this.ctx.getInput().lookAt(p.yaw(), p.pitch());
        this.srv(level -> {
            ServerPlayer pl = this.player();
            ItemStack[] hotbar = {ItemStack.EMPTY, new ItemStack(Items.BREAD, 12), new ItemStack(Items.TORCH, 32),
                    new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.OAK_LOG, 24), new ItemStack(Items.SHIELD),
                    new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.WATER_BUCKET), new ItemStack(Items.CRAFTING_TABLE)};
            for (int i = 1; i < hotbar.length; i++) {
                pl.getInventory().setItem(i, hotbar[i]);
            }
        });
    }

    // ================================================================== the shots

    private static final float SOUTH = 0.0F;
    private static final float NORTH = 180.0F;

    private void defineShots() {
        TrailerWorld w = this.w;
        final long sunrise = 23700;
        final long morning = 1500;
        final long day = 4500;
        final long sunset = 12350;
        final long night = 18000;

        // main tester in the pen
        final double tx = 2.5;
        final double tz = 4.5;
        Vec3 tHead = w.v(tx, 1.45, tz);
        Vec3 tChest = w.v(tx, 1.1, tz);

        // ------------------------------------------------------------ PROBE
        this.shot("p_profiles", "probe", 60).time(day).cam(Cine.orbit(w.v(0, 1.0, 40), 7, 1.8, -25, 25, 0.2)).setup(() -> {
            int t = this.tester(0.5, 40.5, SOUTH, "");
            String[] modes = {"{texture:\"entity/player/wide/kai\",model:\"wide\"}", "{texture:\"minecraft:entity/player/slim/zuri\",model:\"slim\"}",
                    "\"Noor\"", null};
            String[] names = {"tex", "tex_ns", "name", "none"};
            for (int i = 0; i < 4; i++) {
                int a = this.actor(names[i], "kai", modes[i], -3 + i * 2, 0, 37.5, NORTH, new ItemStack(Items.DIAMOND_SWORD));
                this.strike(10 + i * 10, a, t, 5 + i, i == 1);
            }
        });

        // ------------------------------------------------------------ INTRO (group A)
        this.shot("i01_vista", "A", 200).time(sunrise).cam(Cine.path(
                new Vec3[]{w.v(-80, 42, 80), w.v(-45, 26, 48), w.v(-18, 12, 24), w.v(-2, 4.5, 14)},
                new Vec3[]{w.v(-15, 0, 0), w.v(-10, 1, 0), w.v(-4, 1.5, 3), tHead})).setup(() -> this.tester(tx, tz, SOUTH, ""));

        this.shot("i02_farm_dolly", "A", 140).time(sunrise).cam(Cine.dolly(
                w.v(-19, 1.0, 11), w.v(-4, 1.6, 13), w.v(-11, 0.8, 1), tChest)).setup(() -> this.tester(tx, tz, SOUTH, ""));

        this.shot("i03_first_hit", "A", 64).fps(2).time(sunrise).cam(Cine.dolly(
                w.v(tx + 0.4, 1.55, tz + 5.2), w.v(tx + 0.2, 1.5, tz + 3.4), tHead, tHead)).setup(() -> {
            int t = this.tester(tx, tz, SOUTH, "");
            int hero = this.actor(null, "steve", tx - 1.25, tz + 1.4, NORTH + 40, new ItemStack(Items.DIAMOND_SWORD));
            this.armor(hero, "iron");
            this.strike(40, hero, t, 14, true);
        });

        this.shot("i04_logo_orbit", "A", 240).time(sunrise).cam(Cine.orbit(w.v(tx, 0.9, tz + 0.6), 6.0, 1.3, -55, 35, 0.3)).setup(() -> {
            int t = this.tester(tx, tz, SOUTH, "");
            int hero = this.actor(null, "steve", tx - 0.3, tz + 1.6, NORTH, new ItemStack(Items.DIAMOND_SWORD));
            this.armor(hero, "iron");
            float[] dmg = {7, 7, 12, 7, 8, 13, 7};
            for (int k = 0; k < dmg.length; k++) {
                this.strike(20 + k * 30, hero, t, dmg[k], dmg[k] > 10);
            }
        });

        // ------------------------------------------------------------ SOLO (group A/B)
        this.shot("s01_craft", "A", 110).fp().setup(() -> {
            this.fpAt(-4.5, 0, -3.5, w.v(-5.5, 0.5, -3.5));
            this.at(4, () -> this.srv(level -> this.player().openMenu(new SimpleMenuProvider(
                    (id, inv, p) -> new CraftingMenu(id, inv, ContainerLevelAccess.create(level, w.p(-6, 0, -4))),
                    Component.translatable("container.crafting")))));
            ItemStack[] grid = {new ItemStack(Items.STICK), new ItemStack(Items.HAY_BLOCK), new ItemStack(Items.STICK),
                    ItemStack.EMPTY, new ItemStack(Items.ARMOR_STAND)};
            for (int k = 0; k < grid.length; k++) {
                if (grid[k].isEmpty()) {
                    continue;
                }
                int slot = k + 1;
                ItemStack st = grid[k];
                this.at(20 + k * 10, () -> {
                    this.srv(level -> this.player().containerMenu.getSlot(slot).set(st.copy()));
                    this.event("craft_place", "");
                });
            }
            this.at(68, () -> this.event("craft_result", ""));
            this.at(108, () -> this.srv(level -> this.player().closeContainer()));
        });

        this.shot("s02_place", "A", 80).fp().setup(() -> {
            this.fpAt(tx, 0, tz + 3.2, w.v(tx, 0.0, tz));
            this.hand(1, new ItemStack(ModContent.DAMAGE_TESTER_ITEM.get(), 3));
            this.use(24);
            this.at(25, () -> this.event("place", ""));
        });

        this.shot("s03_hits_fp", "A", 150).fp().setup(() -> {
            this.tester(tx, tz, SOUTH, "");
            this.fpAt(tx + 0.3, 0, tz + 2.85, w.v(tx, 1.75, tz));
            this.hand(1, new ItemStack(Items.NETHERITE_SWORD));
            for (int k = 0; k < 5; k++) {
                this.attack(14 + k * 26);
            }
        });

        this.shot("s04_hits_orbit", "A", 180).time(morning).cam(Cine.orbit(w.v(tx, 0.9, tz + 0.6), 5.0, 1.0, -10, -70, 0.3)).setup(() -> {
            int t = this.tester(tx, tz, SOUTH, "");
            int hero = this.actor(null, "steve", tx + 1.0, tz + 1.3, NORTH + 35, new ItemStack(Items.DIAMOND_SWORD));
            this.armor(hero, "iron");
            float[] dmg = {7, 7, 11, 7, 7, 12};
            for (int k = 0; k < dmg.length; k++) {
                this.strike(16 + k * 26, hero, t, dmg[k], dmg[k] > 10);
            }
        });

        this.shot("s05_armor_fp", "B", 170).fp().time(morning).setup(() -> {
            this.tester(tx, tz, SOUTH, "");
            this.fpAt(tx + 0.4, 0, tz + 2.8, w.v(tx, 1.7, tz));
            this.hand(1, new ItemStack(Items.DIAMOND_SWORD));
            this.attack(12);
            this.hand(34, new ItemStack(Items.DIAMOND_CHESTPLATE));
            this.use(42);
            this.at(43, () -> this.event("equip", ""));
            this.hand(60, new ItemStack(Items.DIAMOND_HELMET));
            this.use(68);
            this.at(69, () -> this.event("equip", ""));
            this.hand(84, new ItemStack(Items.DIAMOND_LEGGINGS));
            this.use(92);
            this.at(93, () -> this.event("equip", ""));
            this.hand(108, new ItemStack(Items.DIAMOND_SWORD));
            this.attack(128);
            this.attack(156);
        });

        this.shot("s06_armor_cine", "B", 130).time(morning).cam(Cine.dolly(
                w.v(tx - 2.8, 1.3, tz + 3.0), w.v(tx - 0.8, 1.5, tz + 3.6), tChest, tChest)).setup(() -> {
            int t = this.tester(tx, tz, SOUTH, "");
            this.armor(t, "netherite");
            int hero = this.actor(null, "steve", tx + 1.25, tz + 1.05, NORTH + 45, new ItemStack(Items.NETHERITE_AXE));
            this.armor(hero, "diamond");
            this.strike(20, hero, t, 4.8F, false);
            this.strike(55, hero, t, 4.8F, false);
            this.strike(92, hero, t, 7.6F, true);
        });

        this.shot("s07_mace_fp", "B", 60).fp().fps(1.5).setup(() -> {
            this.tester(tx, tz, SOUTH, "");
            this.cmd("gamemode creative @a");
            Vec3 eye = w.v(tx, 11.5, tz + 1.4);
            Pose p = Pose.look(eye, tHead);
            String tp = String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f %.2f %.2f", eye.x, eye.y - 1.62, eye.z, p.yaw(), p.pitch());
            this.cmd(tp);
            this.srv(level -> {
                this.player().getAbilities().flying = true;
                this.player().onUpdateAbilities();
            });
            this.ctx.waitTicks(2);
            this.ctx.getInput().lookAt(p.yaw(), p.pitch());
            this.hand(1, new ItemStack(Items.MACE));
            this.at(1, () -> {
                this.cmd(tp);
                this.srv(level -> {
                    this.player().getAbilities().flying = false;
                    this.player().onUpdateAbilities();
                });
            });
            AtomicInteger done = new AtomicInteger();
            this.every(i -> {
                if (done.get() != 0 || i < 4) {
                    return;
                }
                double y = this.ctx.computeOnClient(mc -> mc.player.getY());
                Pose look = Pose.look(new Vec3(eye.x, y + 1.62, eye.z), tChest);
                this.ctx.getInput().lookAt(look.yaw(), look.pitch());
                if (y < w.g + 2.3) {
                    done.set(i);
                    this.ctx.getInput().holdKey(o -> o.keyAttack);
                    this.ctx.getInput().releaseKey(o -> o.keyAttack);
                    this.event("mace", "");
                }
            });
        });

        this.shot("s08_mace_cine", "B", 70).fps(2).time(morning).cam(Cine.orbit(w.v(tx, 1.2, tz), 7.5, 0.6, 70, 40, 1.2)).setup(() -> {
            int t = this.tester(tx, tz, SOUTH, "");
            int hero = this.actor(null, "steve", tx - 4.5, tz + 2.5, NORTH, new ItemStack(Items.MACE));
            this.armor(hero, "netherite");
            this.maceJump(10, hero, t, 34.5F, 34, 5.0);
        });

        this.shot("s09_bow_fp", "B", 140).fp().time(morning).setup(() -> {
            this.tester(tx, tz, SOUTH, "");
            this.fpAt(tx, 0, tz + 12.5, w.v(tx, 2.2, tz));
            this.hand(1, new ItemStack(Items.BOW));
            this.srv(level -> this.player().getInventory().setItem(8, new ItemStack(Items.ARROW, 64)));
            this.hold(12, o -> o.keyUse, 24);
            this.at(36, () -> this.event("bow", ""));
            this.hold(70, o -> o.keyUse, 24);
            this.at(94, () -> this.event("bow", ""));
        });

        AtomicInteger arrowId = new AtomicInteger(-1);
        AtomicReference<Pose> lastArrowPose = new AtomicReference<>();
        this.shot("s10_arrow_chase", "B", 70).fps(1.5).time(morning).cam((u, t, mc, partial) -> {
            Entity a = arrowId.get() < 0 || mc.level == null ? null : mc.level.getEntity(arrowId.get());
            if (a != null && !a.isRemoved()) {
                Vec3 p = new Vec3(net.minecraft.util.Mth.lerp(partial, a.xo, a.getX()), net.minecraft.util.Mth.lerp(partial, a.yo, a.getY()),
                        net.minecraft.util.Mth.lerp(partial, a.zo, a.getZ()));
                Pose pose = Pose.look(p.add(0.9, 0.35, 2.2), p.add(0, 0, -6));
                lastArrowPose.set(pose);
                return pose;
            }
            if (lastArrowPose.get() != null) {
                return lastArrowPose.get();
            }
            Vec3 eye = this.w.v(tx + 0.9, 1.9, tz + 15.5);
            return Pose.look(eye, this.w.v(tx, 1.4, tz));
        }).setup(() -> {
            int t = this.tester(tx, tz, SOUTH, "");
            int archer = this.actor(null, "steve", tx, tz + 13.2, NORTH, new ItemStack(Items.BOW));
            this.at(12, () -> {
                int id = this.srvGet(level -> {
                    LivingEntity s = (LivingEntity) this.entity(level, archer);
                    Arrow arrow = new Arrow(level, s, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
                    Vec3 aim = this.entity(level, t).position().add(0, 1.2, 0);
                    Vec3 d = aim.subtract(arrow.position());
                    arrow.shoot(d.x, d.y + d.horizontalDistance() * 0.05, d.z, 1.6F, 0.0F);
                    level.addFreshEntity(arrow);
                    s.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
                    return arrow.getId();
                });
                arrowId.set(id);
                this.event("bow", "");
            });
        });

        this.shot("s11_counter", "B", 150).time(morning).cam(Cine.dolly(
                w.v(5.6, 1.15, 7.9), w.v(4.9, 0.95, 6.3), w.v(3.9, 0.55, 4.5), w.v(4.3, 0.5, 4.6))).setup(() -> {
            int t = this.tester(tx, tz, SOUTH, "");
            int hero = this.actor(null, "steve", tx - 0.4, tz + 1.7, NORTH, new ItemStack(Items.DIAMOND_SWORD));
            float[] dmg = {7, 7, 12, 7, 8, 13};
            for (int k = 0; k < dmg.length; k++) {
                this.strike(12 + k * 22, hero, t, dmg[k], dmg[k] > 10);
            }
        });

        this.shot("s12_redstone", "B", 180).time(morning).cam(Cine.dolly(
                w.v(8.8, 3.4, 8.6), w.v(10.2, 3.1, 8.2), w.v(18.5, 0.2, 4.5), w.v(19.5, 0.2, 4.5))).setup(() -> {
            int t = this.tester(TesterVariant.STANDARD, 12.5, 1, 4.5, SOUTH, "dye:red");
            int hero = this.actor(null, "steve", 12.5, 6.0, NORTH, new ItemStack(Items.DIAMOND_SWORD));
            this.strike(20, hero, t, 4, false);
            this.strike(70, hero, t, 9, false);
            this.strike(125, hero, t, 16, true);
            this.at(128, () -> this.event("lamp", ""));
        });

        // ------------------------------------------------------------ DAMAGE TYPES (group C)
        this.shot("d01_fire", "C", 80).time(day).cam(Cine.orbit(w.v(tx, 1.0, tz), 4.2, 0.8, -30, 10, 0.2)).setup(() -> {
            int t = this.tester(tx, tz, SOUTH, "");
            int hero = this.actor(null, "steve", tx + 0.2, tz + 1.6, NORTH, new ItemStack(Items.GOLDEN_SWORD));
            this.at(14, () -> this.srv(level -> ((LivingEntity) this.entity(level, t)).igniteForSeconds(5)));
            this.strike(12, hero, t, 6, false);
            this.hitWith(40, t, l -> l.damageSources().inFire(), 4, "fire");
        });

        this.shot("d02_magic", "C", 80).time(day).cam(Cine.dolly(w.v(-2.8, 1.7, 36.2), w.v(-2.0, 1.8, 35.4), w.v(0.5, 1.1, 40.5), w.v(0.8, 1.2, 40.0))).setup(() -> {
            int t = this.tester(0.5, 40.5, SOUTH, "");
            int witch = this.actor("Noor", "noor", 3.5, 37.0, NORTH, new ItemStack(Items.SPLASH_POTION));
            this.at(12, () -> {
                this.srv(level -> {
                    LivingEntity s = (LivingEntity) this.entity(level, witch);
                    this.face(s, this.entity(level, t).position());
                    s.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
                    ThrownSplashPotion potion = new ThrownSplashPotion(level, s,
                            PotionContents.createItemStack(Items.SPLASH_POTION, Potions.STRONG_HARMING));
                    Vec3 d = this.entity(level, t).position().add(0, 1.0, 0).subtract(potion.position());
                    potion.shoot(d.x, d.y + 1.2, d.z, 0.7F, 0.0F);
                    level.addFreshEntity(potion);
                });
                this.event("throw", "");
            });
        });

        this.shot("d03_explosion", "C", 90).time(day).cam(Cine.dolly(w.v(-6.5, 1.9, 35.0), w.v(-7.5, 2.2, 34.4), w.v(-4.0, 0.9, 40.5), w.v(-4.2, 1.1, 40.5))).setup(() -> {
            int t = this.tester(-3.5, 40.5, SOUTH, "");
            AtomicInteger tnt = new AtomicInteger();
            this.at(4, () -> {
                tnt.set(this.srvGet(level -> {
                    Vec3 p = this.w.v(-5.5, 0, 40.5);
                    PrimedTnt e = new PrimedTnt(level, p.x, p.y, p.z, null);
                    e.setFuse(80);
                    level.addFreshEntity(e);
                    return e.getId();
                }));
                this.event("fuse", "");
            });
            this.at(48, () -> {
                this.srv(level -> {
                    Entity e = this.entity(level, tnt.get());
                    Vec3 p = e != null ? e.position() : this.w.v(-5.5, 0, 40.5);
                    if (e != null) {
                        e.discard();
                    }
                    level.explode(null, p.x, p.y, p.z, 3.0F, Level.ExplosionInteraction.NONE);
                });
                this.event("explode", "");
            });
        });

        this.shot("d04_lightning", "C", 70).time(day).cam(Cine.dolly(w.v(-4.5, 1.8, 35.5), w.v(-5.5, 1.6, 36.5), w.v(-7.5, 1.6, 40.5), w.v(-7.5, 1.4, 40.5))).setup(() -> {
            int t = this.tester(-7.5, 40.5, SOUTH, "");
            this.at(30, () -> {
                this.srv(level -> {
                    LightningBolt bolt = net.minecraft.world.entity.EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.COMMAND);
                    Vec3 p = this.entity(level, t).position();
                    bolt.snapTo(p.x, p.y, p.z);
                    bolt.setVisualOnly(true);
                    level.addFreshEntity(bolt);
                    this.damage(level, t, level.damageSources().lightningBolt(), 5, false);
                });
                this.event("thunder", "");
            });
        });

        this.shot("d05_wither", "C", 90).time(day).cam(Cine.orbit(w.v(4.5, 1.0, 40.5), 4.0, 0.8, 25, -10, 0.35)).setup(() -> {
            int t = this.tester(4.5, 40.5, SOUTH, "");
            this.at(6, () -> this.srv(level -> ((LivingEntity) this.entity(level, t)).addEffect(new MobEffectInstance(MobEffects.WITHER, 400, 1))));
            this.hitWith(30, t, l -> l.damageSources().wither(), 4, "wither");
            this.hitWith(62, t, l -> l.damageSources().wither(), 4, "wither");
            this.every(i -> {
                if (i % 4 == 0) {
                    this.particles(ParticleTypes.SMOKE, this.w.v(4.5, 1.2, 40.5), 4, 0.3, 0.02);
                }
            });
        });

        this.shot("d06_freeze", "C", 90).time(day).cam(Cine.dolly(w.v(11.2, 1.8, 37.0), w.v(10.4, 1.7, 36.6), w.v(8.5, 1.1, 40.5), w.v(8.5, 1.2, 40.5))).setup(() -> {
            int t = this.tester(8.5, 40.5, SOUTH, "");
            this.srv(level -> {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx != 0 || dz != 0) {
                            level.setBlock(this.w.p(8 + dx, 0, 40 + dz), Blocks.SNOW.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LAYERS, 2), 3);
                        }
                    }
                }
            });
            this.every(i -> this.srv(level -> {
                if (this.entity(level, t) instanceof LivingEntity le) {
                    le.setTicksFrozen(200);
                }
                if (i % 3 == 0) {
                    level.sendParticles(ParticleTypes.SNOWFLAKE, this.w.cx + 8.5, this.w.g + 1.2, this.w.cz + 40.5, 3, 0.5, 0.6, 0.5, 0.01);
                }
            }));
            this.hitWith(20, t, l -> l.damageSources().freeze(), 5, "freeze");
            this.hitWith(55, t, l -> l.damageSources().freeze(), 5, "freeze");
            this.at(89, () -> this.srv(level -> {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        level.setBlock(this.w.p(8 + dx, 0, 40 + dz), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }));
        });

        this.shot("d07_trident", "C", 70).time(day).cam(Cine.dolly(w.v(4.5, 1.8, 33.5), w.v(3.2, 1.6, 35.5), w.v(0.5, 1.2, 40.5), w.v(0.5, 1.3, 40.5))).setup(() -> {
            int t = this.tester(0.5, 40.5, SOUTH, "");
            int thrower = this.actor("Kai", "kai", -0.5, 33.0, NORTH + 10, new ItemStack(Items.TRIDENT));
            this.at(14, () -> {
                this.srv(level -> {
                    LivingEntity s = (LivingEntity) this.entity(level, thrower);
                    s.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
                    ThrownTrident tr = new ThrownTrident(level, s, new ItemStack(Items.TRIDENT));
                    Vec3 d = this.entity(level, t).position().add(0, 1.2, 0).subtract(tr.position());
                    tr.shoot(d.x, d.y + d.horizontalDistance() * 0.04, d.z, 2.2F, 0.0F);
                    level.addFreshEntity(tr);
                });
                this.event("trident", "");
            });
        });

        this.shot("d08_wind", "C", 60).time(day).cam(Cine.orbit(w.v(-3.5, 1.0, 39.0), 4.6, 0.9, -70, -40, 0.3)).setup(() -> {
            int t = this.tester(-3.5, 40.5, SOUTH, "");
            int caster = this.actor("Ari", "ari", -3.5, 35.0, NORTH, new ItemStack(Items.WIND_CHARGE));
            this.at(10, () -> {
                this.srv(level -> {
                    LivingEntity s = (LivingEntity) this.entity(level, caster);
                    s.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
                    Vec3 from = s.position().add(0, 1.4, 0);
                    Vec3 d = this.entity(level, t).position().add(0, 1.1, 0).subtract(from).normalize();
                    WindCharge wc = new WindCharge(level, from.x, from.y, from.z, d.scale(0.8));
                    level.addFreshEntity(wc);
                });
                this.event("wind", "");
            });
        });

        // ------------------------------------------------------------ FRIENDS (groups C/D)
        String[][] friends = {{"Kai", "kai"}, {"Zuri", "zuri"}, {"Noor", "noor"}, {"Ari", "ari"}};
        ItemStack[] weapons = {new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.NETHERITE_AXE),
                new ItemStack(Items.IRON_SWORD), new ItemStack(Items.MACE)};
        String[] gear = {"diamond", "iron", "gold", "netherite"};

        this.shot("f01_arena_crane", "C", 220).time(day).cam(Cine.crane(w.v(0, 0, 38), 26, 11, 17, 4.5, -35, 20, 0.8)).setup(() -> {
            for (int k = 0; k < 4; k++) {
                int t = this.tester(TrailerWorld.ARENA_X[k + (k >= 2 ? 1 : 0)] + 0.5, TrailerWorld.ARENA_Z + 0.5, SOUTH, k == 1 ? "straw" : "");
                Vec3 tp = this.pos(t);
                int a = this.actor(friends[k][0], friends[k][1], tp.x - w.cx, tp.z - w.cz - 1.8, NORTH, weapons[k]);
                this.armor(a, gear[k]);
                for (int j = 0; j < 8; j++) {
                    float dmg = k == 3 ? 6 : k == 1 ? 9 : 7;
                    this.strike(10 + k * 7 + j * 27, a, t, j % 3 == 2 ? dmg * 1.5F : dmg, j % 3 == 2);
                }
            }
            this.tester(0.5, TrailerWorld.ARENA_Z + 0.5, SOUTH, "dye:yellow");
        });

        this.shot("f02_row_dolly", "C", 160).time(day).cam(Cine.dolly(w.v(-12, 1.5, 44), w.v(11, 1.8, 44), w.v(-6, 1.0, 39), w.v(6, 1.0, 39))).setup(() -> {
            for (int k = 0; k < 5; k++) {
                int t = this.tester(TrailerWorld.ARENA_X[k] + 0.5, TrailerWorld.ARENA_Z + 0.5, SOUTH, k == 2 ? "planks:minecraft:spruce_planks" : "");
                int f = k % 4;
                int a = this.actor(friends[f][0], friends[f][1], TrailerWorld.ARENA_X[k] + 0.5, TrailerWorld.ARENA_Z - 1.3, NORTH, weapons[f]);
                this.armor(a, gear[f]);
                for (int j = 0; j < 6; j++) {
                    this.strike(8 + k * 5 + j * 25, a, t, 6 + (k + j) % 4, (k + j) % 5 == 0);
                }
            }
        });

        this.shot("f03_chat_fp", "D", 200).fp().time(day).setup(() -> {
            int[] ts = new int[5];
            for (int k = 0; k < 5; k++) {
                ts[k] = this.tester(TrailerWorld.ARENA_X[k] + 0.5, TrailerWorld.ARENA_Z + 0.5, SOUTH, k == 0 ? "dye:light_blue" : "");
            }
            int kai = this.actor("Kai", "kai", -7.5, 38.7, NORTH, weapons[0]);
            this.armor(kai, "diamond");
            int zuri = this.actor("Zuri", "zuri", 8.5, 38.7, NORTH, new ItemStack(Items.MACE));
            this.armor(zuri, "netherite");
            int noor = this.actor("Noor", "noor", -3.5, 38.7, NORTH, weapons[2]);
            this.armor(noor, "gold");
            this.fpAt(0.5, 0, 37.9, w.v(0.5, 1.1, 40.5));
            this.hand(1, new ItemStack(Items.NETHERITE_SWORD));
            this.chat(10, "Kai", "first to 15 damage wins!");
            this.strike(30, kai, ts[0], 14, true);
            this.chat(40, "Kai", "14 crit, beat that");
            this.strike(55, noor, ts[1], 8, false);
            this.chat(70, "Zuri", "hold my mace...");
            this.maceJump(84, zuri, ts[4], 31, 30, 4.0);
            this.chat(122, "Noor", "31?! no way");
            this.attack(140);
            this.chat(170, "Ari", "rematch at sunset?");
        });

        this.shot("f04_contest", "D", 190).time(day).cam(Cine.dolly(w.v(5.0, 1.7, 35.2), w.v(4.2, 1.5, 36.2), w.v(0.6, 0.8, 40.5), w.v(0.8, 0.8, 40.5))).setup(() -> {
            this.srv(level -> level.setBlock(this.w.p(1, 0, 40), ModContent.DAMAGE_COUNTER.get().defaultBlockState()
                    .setValue(net.star1mp.damagetester.block.DamageCounterBlock.FACING, net.minecraft.core.Direction.SOUTH), 3));
            int t = this.tester(-0.5, 40.5, SOUTH, "");
            int[] a = new int[4];
            for (int k = 0; k < 4; k++) {
                a[k] = this.actor(friends[k][0], friends[k][1], -3.6 + k * 1.1, 38.6 - (k % 2) * 0.6, NORTH + 25, weapons[k]);
                this.armor(a[k], gear[k]);
            }
            this.strike(15, a[0], t, 8, false);
            this.strike(55, a[1], t, 11, false);
            this.strike(95, a[2], t, 15, true);
            this.maceJump(125, a[3], t, 27, 26, 3.0);
            this.at(189, () -> this.srv(level -> level.setBlock(this.w.p(1, 0, 40), Blocks.AIR.defaultBlockState(), 3)));
        });

        AtomicInteger cartId = new AtomicInteger(-1);
        this.shot("f05_cart_crane", "D", 200).time(day).cam(Cine.crane(w.v(31, 0, 34), 24, 16, 18, 9, 10, 70, 0)).setup(() -> {
            int t = this.cartTester(TrailerWorld.LOOP_X0 + 6, TrailerWorld.LOOP_Z1, SOUTH, "dye:orange", 0.6, 0);
            this.archers(t, 30);
        });

        this.shot("f06_cart_follow", "D", 140).time(day).cam(Cine.follow(-1, Vec3.ZERO, 0)).setup(() -> {
            int t = this.cartTester(TrailerWorld.LOOP_X0 + 2, TrailerWorld.LOOP_Z1, SOUTH, "dye:orange", 0.6, 0);
            cartId.set(t);
            this.archers(t, 18);
        });
        // the follow camera needs the id resolved at run time
        this.shots.get(this.shots.size() - 1).cam((u, t, mc, partial) -> Cine.follow(cartId.get(), new Vec3(-3.2, 2.2, 3.4), 0.8).at(u, t, mc, partial));

        this.shot("f07_bags", "D", 170).time(day).cam(Cine.orbit(w.v(10.5, 1.3, 44.0), 6.2, 0.6, 210, 150, 0.3)).setup(() -> {
            int h1 = this.hangingTester(9, 3, 44, "");
            int h2 = this.hangingTester(12, 3, 44, "straw");
            int kai = this.actor("Kai", "kai", 9.5, 43.35, SOUTH, ItemStack.EMPTY);
            int ari = this.actor("Ari", "ari", 12.5, 43.35, SOUTH, ItemStack.EMPTY);
            for (int j = 0; j < 9; j++) {
                this.strike(10 + j * 17, kai, h1, 1 + j % 2, false);
                this.strike(18 + j * 17, ari, h2, 1, false);
            }
        });

        this.shot("f08_small", "D", 110).time(day).cam(Cine.dolly(w.v(-10.2, 1.3, 31.6), w.v(-8.6, 1.2, 31.0), w.v(-9.6, 0.6, 27.5), w.v(-9.2, 0.7, 27.5))).setup(() -> {
            int small = this.tester(TesterVariant.SMALL, -10.5, 0, 27.5, SOUTH, "dye:pink");
            int big = this.tester(-8.0, 27.5, SOUTH, "");
            int noor = this.actor("Noor", "noor", -11.9, 28.0, -90.0F, new ItemStack(Items.WOODEN_SWORD));
            int kai = this.actor("Kai", "kai", -6.6, 28.0, 90.0F, new ItemStack(Items.STONE_SWORD));
            for (int j = 0; j < 4; j++) {
                this.strike(12 + j * 24, noor, small, 4, false);
                this.strike(20 + j * 24, kai, big, 5, j == 2);
            }
        });

        this.shot("f09_archers", "D", 130).time(day).cam(Cine.dolly(w.v(-6, 1.6, 26.5), w.v(6, 1.8, 26.5), w.v(-2, 1.4, 40), w.v(2, 1.4, 40))).setup(() -> {
            int[] ts = new int[3];
            int[] as = new int[3];
            for (int k = 0; k < 3; k++) {
                ts[k] = this.tester(TrailerWorld.ARENA_X[k + 1] + 0.5, TrailerWorld.ARENA_Z + 0.5, SOUTH, "");
                as[k] = this.actor(friends[k][0], friends[k][1], TrailerWorld.ARENA_X[k + 1] + 0.5, 28.5, NORTH, new ItemStack(Items.BOW));
            }
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    this.shootArrow(14 + j * 36 + k * 3, as[k], ts[k], 0);
                }
            }
        });

        // ------------------------------------------------------------ CUSTOMIZE (group E)
        double sx = -34.5;
        double sz = TrailerWorld.SHOW_Z + 0.5;
        this.shot("c01_skins_fp", "E", 210).fp().time(day).setup(() -> {
            this.tester(sx, sz, SOUTH, "");
            this.fpAt(sx + 0.3, 0, sz + 2.6, w.v(sx, 1.5, sz));
            ItemStack[] items = {new ItemStack(Items.HAY_BLOCK), new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.CHERRY_PLANKS),
                    new ItemStack(Items.WARPED_PLANKS), new ItemStack(Items.DYE.pick(DyeColor.RED)), new ItemStack(Items.DYE.pick(DyeColor.LIGHT_BLUE)),
                    new ItemStack(Items.DYE.pick(DyeColor.LIME)), new ItemStack(Items.JACK_O_LANTERN), new ItemStack(Items.SHEARS)};
            for (int k = 0; k < items.length; k++) {
                this.hand(8 + k * 22, items[k]);
                this.use(14 + k * 22);
                int kk = k;
                this.at(15 + k * 22, () -> this.event(kk == items.length - 1 ? "shears" : "skin", ""));
            }
        });

        String[] row = {"", "straw", "planks:minecraft:oak_planks", "planks:minecraft:cherry_planks", "jack_o_lantern", "dye:red", "jeb"};
        this.shot("c02_row_dolly", "E", 180).time(day).cam(Cine.dolly(w.v(-44, 1.5, 36.8), w.v(-25, 1.7, 36.8), w.v(-40, 1.1, 32.5), w.v(-28, 1.1, 32.5))).setup(() -> {
            for (int k = 0; k < row.length; k++) {
                this.tester(-43 + k * 3 + 0.5, sz, SOUTH, row[k]);
            }
        });

        this.shot("c03_dye_cycle", "E", 100).time(day).cam(Cine.dolly(w.v(sx + 1.0, 1.5, sz + 3.0), w.v(sx + 0.5, 1.45, sz + 2.3), w.v(sx, 1.2, sz), w.v(sx, 1.25, sz))).setup(() -> {
            int t = this.tester(sx, sz, SOUTH, "");
            DyeColor[] colors = DyeColor.values();
            for (int k = 0; k < colors.length; k++) {
                DyeColor c = colors[k];
                this.at(6 + k * 5, () -> {
                    this.srv(level -> ((DamageTesterEntity) this.entity(level, t)).setSkin("dye:" + c.getSerializedName()));
                    this.event("tick", "");
                });
            }
        });

        this.shot("c04_jeb", "E", 110).time(day).cam(Cine.orbit(w.v(sx, 1.0, sz), 3.4, 0.6, -45, 15, 0.35)).setup(() -> {
            int t = this.tester(sx, sz, SOUTH, "jeb");
            int kai = this.actor("Kai", "kai", sx + 1.3, sz + 0.9, NORTH + 55, weapons[0]);
            this.strike(40, kai, t, 7, false);
            this.strike(80, kai, t, 11, true);
        });

        this.shot("c05_night", "E", 200).time(night).cam(Cine.orbit(w.v(-34.5, 1.0, sz), 6.0, 1.2, -35, 30, 0.4)).setup(() -> {
            int a = this.tester(-37.5, sz, SOUTH, "jack_o_lantern");
            int b = this.tester(-34.5, sz, SOUTH, "jack_o_lantern");
            int c = this.tester(-31.5, sz, SOUTH, "jack_o_lantern");
            this.srv(level -> {
                for (int x = -40; x <= -29; x += 3) {
                    level.setBlock(this.w.p(x, 0, 30), Blocks.TORCH.defaultBlockState(), 3);
                }
                level.setBlock(this.w.p(-34, 0, 29), Blocks.CAMPFIRE.defaultBlockState(), 3);
            });
            int kai = this.actor("Kai", "kai", -37.5, sz + 1.6, NORTH, weapons[0]);
            int noor = this.actor("Noor", "noor", -31.5, sz + 1.6, NORTH, weapons[2]);
            for (int j = 0; j < 6; j++) {
                this.strike(16 + j * 28, kai, a, 7, j == 3);
                this.strike(30 + j * 28, noor, c, 6, false);
            }
            this.strike(100, kai, b, 9, false);
        });

        String[] woods = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak", "bamboo", "crimson", "warped"};
        this.shot("c06_planks", "E", 150).time(day).cam(Cine.dolly(w.v(-45, 1.4, 36.3), w.v(-24, 1.6, 36.3), w.v(-42, 1.0, 32.5), w.v(-26, 1.0, 32.5))).setup(() -> {
            for (int k = 0; k < woods.length; k++) {
                this.tester(-45 + k * 2 + 0.5, 32.5, SOUTH, "planks:minecraft:" + woods[k] + "_planks");
            }
        });

        // ------------------------------------------------------------ FINALE (group E)
        this.shot("e01_sunset_all", "E", 260).time(sunset).cam(Cine.crane(w.v(0, 0, 36), 9, 46, 1.6, 30, 15, 70, 2)).setup(() -> {
            for (int k = 0; k < 5; k++) {
                int t = this.tester(TrailerWorld.ARENA_X[k] + 0.5, TrailerWorld.ARENA_Z + 0.5, SOUTH, row[(k * 2) % row.length]);
                int f = k % 4;
                int a = this.actor(friends[f][0], friends[f][1], TrailerWorld.ARENA_X[k] + 0.5, TrailerWorld.ARENA_Z - 1.3, NORTH, weapons[f]);
                this.armor(a, gear[f]);
                for (int j = 0; j < 9; j++) {
                    this.strike(8 + k * 6 + j * 26, a, t, 6 + (k + j) % 5, (k + j) % 4 == 0);
                }
            }
            int cart = this.cartTester(TrailerWorld.LOOP_X0 + 4, TrailerWorld.LOOP_Z1, SOUTH, "dye:orange", 0.6, 0);
            for (int k = 0; k < 8; k++) {
                this.firework(110 + k * 16, w.v(-12 + (k * 7) % 26, 2, 30 + (k * 5) % 16),
                        new int[][]{{0xFFD23F, 0xFF7A1A}, {0xC77DFF, 0xFFFFFF}, {0x2FD5C2, 0xBFE9FF}, {0xFF5555, 0xFFD23F}}[k % 4]);
            }
            int unused = cart;
        });

        // ============================================================ COMEDY SHORTS (vertical, groups K1..K3)
        final float east = -90.0F;
        final float west = 90.0F;

        // ---- K1: expectation vs reality
        this.shot("k1a_warmup", "K1", 110).time(morning).fov(72).cam(Cine.dolly(
                w.v(tx + 2.0, 1.45, tz + 4.6), w.v(tx + 1.5, 1.5, tz + 3.7), w.v(tx + 0.1, 1.15, tz + 1.0), w.v(tx + 0.1, 1.25, tz + 1.1))).setup(() -> {
            int t = this.tester(tx, tz, SOUTH, "");
            int kai = this.actor(null, "steve", tx, tz + 1.55, NORTH, new ItemStack(Items.WOODEN_SWORD));
            this.speakers.put("kai", kai);
            for (int k = 0; k < 4; k++) {
                this.pose(8 + k * 12, kai, net.minecraft.world.entity.Pose.CROUCHING);
                this.pose(14 + k * 12, kai, net.minecraft.world.entity.Pose.STANDING);
            }
            this.turn(60, kai, SOUTH + 20);
            this.say(62, "kai", "watch");
            this.turn(100, kai, NORTH);
            int unused = t;
        });
        this.shot("k1b_swing", "K1", 56).time(morning).fov(70).fps(1.6).cam(Cine.dolly(
                w.v(tx - 1.9, 0.55, tz + 2.9), w.v(tx - 1.6, 0.6, tz + 2.6), w.v(tx + 0.1, 1.3, tz + 0.9), w.v(tx, 1.25, tz + 0.6))).setup(() -> {
            int t = this.tester(tx, tz, SOUTH, "");
            int kai = this.actor(null, "steve", tx, tz + 1.55, NORTH, new ItemStack(Items.WOODEN_SWORD));
            Vec3 a = w.v(tx, 0, tz + 1.55);
            this.every(i -> {
                if (i >= 8 && i <= 26) {
                    double u = (i - 8) / 18.0;
                    this.moveActor(kai, a.add(0, 4 * 1.3 * u * (1 - u), -0.35 * u));
                }
            });
            this.at(6, () -> this.event("jump", ""));
            this.at(22, () -> this.swing(kai));
            this.at(26, () -> {
                this.srv(level -> this.damage(level, t, level.damageSources().generic(), 1.0F, false));
                this.event("hit_weak", "1");
            });
        });
        this.shot("k1c_stare", "K1", 60).time(morning).fov(70).cam(Cine.dolly(
                w.v(tx + 0.6, 1.62, tz + 3.7), w.v(tx + 0.5, 1.62, tz + 3.4), w.v(tx, 1.55, tz + 1.5), w.v(tx, 1.55, tz + 1.5))).setup(() -> {
            int t = this.tester(tx, tz, SOUTH, "");
            int kai = this.actor(null, "steve", tx, tz + 1.2, NORTH, new ItemStack(Items.WOODEN_SWORD));
            this.speakers.put("kai", kai);
            this.look(0, kai, t);
            this.turn(26, kai, SOUTH);
            this.say(30, "kai", "dots");
            int unused = t;
        });
        this.shot("k1d_zuri", "K1", 120).time(morning).fov(72).cam(Cine.dolly(
                w.v(tx + 0.4, 1.9, tz + 7.6), w.v(tx + 0.4, 1.8, tz + 7.0), w.v(tx + 0.4, 1.0, tz + 1.0), w.v(tx + 0.4, 1.0, tz + 1.0))).setup(() -> {
            int t = this.tester(tx, tz, SOUTH, "");
            int kai = this.actor(null, "steve", tx - 1.1, tz + 1.4, NORTH + 30, new ItemStack(Items.WOODEN_SWORD));
            int zuri = this.actor(null, "zuri", tx + 4.5, tz + 2.2, west, new ItemStack(Items.MACE));
            this.speakers.put("kai", kai);
            this.speakers.put("zuri", zuri);
            this.walk(6, 34, zuri, w.v(tx + 4.5, 0, tz + 2.2), w.v(tx + 1.0, 0, tz + 1.9));
            this.look(4, kai, zuri);
            this.look(35, zuri, t);
            this.maceJump(42, zuri, t, 47.0F, 18, 2.6);
            this.look(66, zuri, kai);
            this.say(70, "kai", "what");
            this.look(80, kai, t);
            this.walk(84, 116, zuri, w.v(tx + 0.8, 0, tz + 1.2), w.v(tx + 5.5, 0, tz + 2.6));
        });
        this.shot("k1e_lag", "K1", 90).time(morning).fov(70).cam(Cine.dolly(
                w.v(tx - 1.1 + 0.4, 1.62, tz + 3.9), w.v(tx - 1.1 + 0.3, 1.62, tz + 3.4), w.v(tx - 1.1, 1.55, tz + 1.4), w.v(tx - 1.1, 1.55, tz + 1.4))).setup(() -> {
            int t = this.tester(tx, tz, SOUTH, "");
            int kai = this.actor(null, "steve", tx - 1.1, tz + 1.4, SOUTH, new ItemStack(Items.WOODEN_SWORD));
            this.speakers.put("kai", kai);
            this.say(14, "kai", "lag");
            this.turn(60, kai, SOUTH - 40);
            int unused = t;
        });

        // ---- K2: disguise
        final double rz = 40.5;
        final double[] rowX = {-6.5, -3.5, -0.5, 5.5};
        final double ariX = 2.5;
        this.shot("k2a_row", "K2", 100).time(day).fov(72).cam(Cine.dolly(
                w.v(-9.5, 1.7, rz - 4.5), w.v(-8.5, 1.7, rz - 4.0), w.v(ariX, 1.2, rz), w.v(ariX, 1.2, rz))).setup(() -> {
            for (double x : rowX) {
                this.tester(x, rz, SOUTH, "");
            }
            int ari = this.actor(null, "ari", ariX, rz, SOUTH, ItemStack.EMPTY);
            this.speakers.put("ari", ari);
            this.say(30, "ari", "shh");
        });
        this.shot("k2b_hits", "K2", 150).time(day).fov(72).cam(Cine.dolly(
                w.v(-8.5, 1.8, rz - 4.2), w.v(1.5, 1.8, rz - 4.4), w.v(-6.5, 1.1, rz), w.v(ariX, 1.2, rz))).setup(() -> {
            int[] ts = new int[rowX.length];
            for (int k = 0; k < rowX.length; k++) {
                ts[k] = this.tester(rowX[k], rz, SOUTH, "");
            }
            int ari = this.actor(null, "ari", ariX, rz, SOUTH, ItemStack.EMPTY);
            int kai = this.actor(null, "steve", -8.0, rz - 1.4, east, new ItemStack(Items.IRON_SWORD));
            this.speakers.put("ari", ari);
            this.walk(4, 16, kai, w.v(-8.0, 0, rz - 1.4), w.v(-6.5, 0, rz - 1.4));
            this.strike(20, kai, ts[0], 7, false);
            this.walk(30, 42, kai, w.v(-6.5, 0, rz - 1.4), w.v(-3.5, 0, rz - 1.4));
            this.strike(46, kai, ts[1], 9, false);
            this.walk(56, 68, kai, w.v(-3.5, 0, rz - 1.4), w.v(-0.5, 0, rz - 1.4));
            this.strike(72, kai, ts[2], 12, true);
            this.walk(84, 100, kai, w.v(-0.5, 0, rz - 1.4), w.v(ariX - 0.2, 0, rz - 1.4));
            this.look(102, kai, ari);
            this.look(104, ari, kai);
            this.say(112, "ari", "sweat");
            this.turn(130, ari, SOUTH);
        });
        this.shot("k2c_close", "K2", 70).time(day).fov(68).cam(Cine.dolly(
                w.v(ariX + 2.2, 1.6, rz - 3.0), w.v(ariX + 1.9, 1.6, rz - 2.7), w.v(ariX - 0.1, 1.5, rz - 0.5), w.v(ariX - 0.1, 1.5, rz - 0.5))).setup(() -> {
            int ari = this.actor(null, "ari", ariX, rz, SOUTH, ItemStack.EMPTY);
            int kai = this.actor(null, "steve", ariX - 0.2, rz - 1.4, NORTH, new ItemStack(Items.IRON_SWORD));
            this.tester(-0.5, rz, SOUTH, "");
            this.tester(5.5, rz, SOUTH, "");
            this.speakers.put("ari", ari);
            this.speakers.put("kai", kai);
            this.look(0, kai, ari);
            this.say(8, "kai", "hmm");
            this.look(26, ari, kai);
            this.say(36, "ari", "dots");
            this.turn(58, ari, SOUTH);
        });
        this.shot("k2d_ouch", "K2", 100).time(day).fov(72).cam(Cine.dolly(
                w.v(ariX + 0.5, 2.0, rz - 7.0), w.v(ariX + 1.5, 2.0, rz - 6.6), w.v(ariX + 0.5, 1.1, rz), w.v(ariX + 2.5, 1.1, rz))).setup(() -> {
            for (double x : rowX) {
                this.tester(x, rz, SOUTH, "");
            }
            int ari = this.actor(null, "ari", ariX, rz, SOUTH, ItemStack.EMPTY);
            int kai = this.actor(null, "steve", ariX - 0.2, rz - 1.4, NORTH, new ItemStack(Items.IRON_SWORD));
            this.speakers.put("ari", ari);
            this.speakers.put("kai", kai);
            this.look(0, kai, ari);
            this.look(14, ari, kai);
            this.at(10, () -> this.swing(kai));
            this.at(12, () -> {
                this.srv(level -> {
                    if (this.entity(level, ari) instanceof LivingEntity a && this.entity(level, kai) instanceof LivingEntity k) {
                        level.broadcastDamageEvent(a, level.damageSources().mobAttack(k));
                    }
                });
                this.event("hit", "ari");
                this.event("hurt", "ari");
            });
            this.say(13, "ari", "ouch");
            this.walk(26, 50, ari, w.v(ariX, 0, rz - 0.3), w.v(ariX + 9.0, 0, rz - 2.0));
            this.say(52, "kai", "what");
        });

        // ---- K3: training all night (counter at (4,0,4) next to the pen tester)
        this.shot("k3a_morning", "K3", 90).time(morning).fov(72).cam(Cine.dolly(
                w.v(tx + 2.9, 1.5, tz + 4.4), w.v(tx + 2.6, 1.5, tz + 3.9), w.v(tx + 0.9, 1.0, tz + 0.6), w.v(tx + 0.9, 1.0, tz + 0.6))).setup(() -> {
            int t = this.tester(tx, tz, SOUTH, "");
            int hero = this.actor(null, "steve", tx, tz + 1.45, NORTH, new ItemStack(Items.DIAMOND_SWORD));
            this.speakers.put("hero", hero);
            this.strike(12, hero, t, 7, false);
            this.say(24, "hero", "one_more");
            this.strike(40, hero, t, 7, false);
            this.strike(66, hero, t, 12, true);
        });
        this.shot("k3b_timelapse", "K3", 220).time(2000).fov(72).cam(Cine.dolly(
                w.v(tx + 3.8, 2.6, tz + 5.6), w.v(tx + 3.5, 2.5, tz + 5.1), w.v(tx + 0.9, 0.9, tz + 0.6), w.v(tx + 0.9, 0.9, tz + 0.6))).setup(() -> {
            int t = this.tester(tx, tz, SOUTH, "");
            int hero = this.actor(null, "steve", tx, tz + 1.45, NORTH, new ItemStack(Items.DIAMOND_SWORD));
            float[] d = {6, 7, 8, 6, 9, 7, 12, 6};
            for (int k = 0; k < 34; k++) {
                this.strike(4 + k * 6, hero, t, d[k % d.length], k % 7 == 6);
            }
            this.every(i -> this.cmd("time set " + (2000 + i * 75)));
            int unused = hero;
        });
        this.shot("k3c_night", "K3", 140).time(18200).fov(70).cam(Cine.dolly(
                w.v(tx + 2.5, 1.2, tz + 3.2), w.v(tx + 2.2, 1.15, tz + 2.9), w.v(tx + 0.5, 0.9, tz + 0.9), w.v(tx + 0.5, 0.8, tz + 0.9))).setup(() -> {
            int t = this.tester(tx, tz, SOUTH, "");
            int hero = this.actor(null, "steve", tx, tz + 1.45, NORTH, new ItemStack(Items.DIAMOND_SWORD));
            this.speakers.put("hero", hero);
            this.speakers.put("tester", t);
            this.srv(level -> {
                level.setBlock(w.p(1, 0, 6), Blocks.TORCH.defaultBlockState(), 3);
                level.setBlock(w.p(4, 1, 4), Blocks.LANTERN.defaultBlockState(), 3);
            });
            this.strike(10, hero, t, 1, false);
            this.say(26, "hero", "one_more_tired");
            this.strike(48, hero, t, 0.5F, false);
            this.turn(70, hero, NORTH);
            this.pose(72, hero, net.minecraft.world.entity.Pose.SWIMMING);
            this.at(72, () -> this.event("faceplant", ""));
            this.say(98, "tester", "again");
            this.at(139, () -> this.srv(level -> {
                level.setBlock(w.p(1, 0, 6), Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(w.p(4, 1, 4), Blocks.AIR.defaultBlockState(), 3);
            }));
        });

        // ---- K4: pumpkins at night (showroom row)
        final double pz = 32.5;
        final double[] px = {-40.5, -37.5, -34.5, -31.5};
        final double walkZ = 34.9;
        Runnable nightSet = () -> {
            this.ctx.runOnClient(mc -> mc.options.gamma().set(1.0));
        };
        this.shot("k4a_intro", "K4", 70).time(18000).fov(72).cam(Cine.dolly(
                w.v(-36.0, 3.6, 40.5), w.v(-36.0, 2.6, 39.0), w.v(-36.0, 1.0, 33.0), w.v(-36.0, 1.0, 33.0))).setup(() -> {
            nightSet.run();
            for (double x : px) {
                this.tester(x, pz, SOUTH, "jack_o_lantern");
            }
            int kai = this.actor(null, "steve", -45.0, walkZ, east, new ItemStack(Items.TORCH));
            this.walk(10, 70, kai, w.v(-45.0, 0, walkZ), w.v(-41.0, 0, walkZ));
        });
        this.shot("k4b_walk", "K4", 150).time(18000).fov(72).cam(Cine.dolly(
                w.v(-42.0, 1.5, 39.6), w.v(-31.5, 1.5, 39.6), w.v(-42.0, 1.0, 33.6), w.v(-31.5, 1.0, 33.6))).setup(() -> {
            nightSet.run();
            int[] ts = new int[px.length];
            for (int k = 0; k < px.length; k++) {
                ts[k] = this.tester(px[k], pz, SOUTH, "jack_o_lantern");
            }
            int kai = this.actor(null, "steve", -42.5, walkZ, east, new ItemStack(Items.TORCH));
            this.speakers.put("kai", kai);
            this.walk(4, 92, kai, w.v(-42.5, 0, walkZ), w.v(-30.0, 0, walkZ));
            // the third pumpkin follows Kai with its eyes...
            this.testerTurn(48, ts[2], -35.0F, false);
            this.testerTurn(74, ts[2], -70.0F, false);
            // ...and freezes the moment he turns around
            this.look(98, kai, ts[2]);
            this.testerTurn(99, ts[2], SOUTH, true);
            this.say(112, "kai", "imagination");
            this.turn(140, kai, east);
        });
        this.shot("k4c_boo", "K4", 120).time(18000).fov(70).cam(Cine.dolly(
                w.v(-37.3, 1.35, 35.6), w.v(-37.0, 1.35, 35.3), w.v(-31.0, 1.25, 33.8), w.v(-31.0, 1.25, 33.8))).setup(() -> {
            nightSet.run();
            int t3 = 0;
            for (int k = 0; k < px.length; k++) {
                int id = this.tester(px[k], pz, SOUTH, "jack_o_lantern");
                if (k == 2) {
                    t3 = id;
                }
            }
            final int boo = t3;
            int kai = this.actor(null, "steve", -30.0, walkZ, east, new ItemStack(Items.TORCH));
            this.speakers.put("kai", kai);
            this.speakers.put("tester", boo);
            this.walk(4, 34, kai, w.v(-30.0, 0, walkZ), w.v(-27.5, 0, walkZ));
            this.testerTurn(24, boo, -75.0F, false);
            this.say(40, "tester", "boo");
            this.look(48, kai, boo);
            this.hop(50, kai, w.v(-27.5, 0, walkZ), 0.7, 8);
            this.at(50, () -> this.event("jump", ""));
            this.say(51, "kai", "scream");
            this.walk(66, 96, kai, w.v(-27.5, 0, walkZ), w.v(-17.0, 0, walkZ + 0.4));
            this.testerTurn(100, boo, SOUTH, false);
        });

        // ---- K5: the runaway minecart (straight track along z=50)
        final int railZ = 50;
        Runnable track = () -> this.srv(level -> {
            for (int x = -14; x <= 48; x++) {
                level.setBlock(w.p(x, -1, railZ), Blocks.GRAVEL.defaultBlockState(), 2);
                level.setBlock(w.p(x, 0, railZ), Blocks.RAIL.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.RAIL_SHAPE,
                                net.minecraft.world.level.block.state.properties.RailShape.EAST_WEST), 2);
                for (int dz = -1; dz <= 2; dz++) {
                    if (dz != 0) {
                        net.minecraft.world.level.block.state.BlockState above = level.getBlockState(w.p(x, 0, railZ + dz));
                        if (!above.isAir() && !above.isSolid()) {
                            level.setBlock(w.p(x, 0, railZ + dz), Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                }
            }
        });
        final double rz5 = railZ + 0.5;
        final double kz = railZ + 1.75;
        this.shot("k5a_miss", "K5", 100).time(day).fov(72).cam(Cine.dolly(
                w.v(-1.0, 0.9, railZ + 6.5), w.v(0.0, 0.9, railZ + 6.0), w.v(4.0, 1.0, rz5), w.v(4.5, 1.0, rz5))).setup(() -> {
            track.run();
            int cart = this.cartTester(-9, railZ, east, "dye:orange", 0, 0);
            int kai = this.actor(null, "steve", 4.0, kz, NORTH, new ItemStack(Items.IRON_SWORD));
            this.speakers.put("kai", kai);
            this.look(1, kai, cart);
            this.driveCart(10, 60, cart, w.v(-8.5, 0, rz5), w.v(16.5, 0, rz5));
            this.at(10, () -> this.event("cart", ""));
            this.at(42, () -> this.swing(kai));
            this.at(42, () -> this.event("swing_miss", ""));
            this.say(50, "kai", "hey");
        });
        this.shot("k5b_chase", "K5", 150).time(day).fov(72).cam((u, t, mc, partial) -> Cine.follow(this.cameraTarget.get(),
                new Vec3(-1.6, 1.5, 6.6), 1.0).at(u, t, mc, partial)).setup(() -> {
            track.run();
            int cart = this.cartTester(10, railZ, east, "dye:orange", 0, 0);
            int kai = this.actor(null, "steve", 6.0, kz, east, new ItemStack(Items.IRON_SWORD));
            this.cameraTarget.set(kai);
            this.speakers.put("kai", kai);
            this.driveCart(1, 150, cart, w.v(10.5, 0, rz5), w.v(41.0, 0, rz5));
            this.at(1, () -> this.event("cart", ""));
            this.walk(1, 150, kai, w.v(6.0, 0, kz), w.v(33.0, 0, kz));
            for (int k : new int[]{40, 85, 125}) {
                this.at(k, () -> this.swing(kai));
                this.at(k, () -> this.event("swing_miss", ""));
            }
            this.say(18, "kai", "stop");
        });
        this.shot("k5c_tired", "K5", 80).time(day).fov(70).cam(Cine.dolly(
                w.v(31.3, 1.35, railZ + 5.0), w.v(31.7, 1.3, railZ + 4.6), w.v(33.6, 1.0, kz), w.v(33.6, 0.9, kz))).setup(() -> {
            track.run();
            int cart = this.cartTester(44, railZ, east, "dye:orange", 0, 0);
            int kai = this.actor(null, "steve", 33.5, kz, east, new ItemStack(Items.IRON_SWORD));
            this.speakers.put("kai", kai);
            this.pose(4, kai, net.minecraft.world.entity.Pose.CROUCHING);
            this.look(1, kai, cart);
            this.say(16, "kai", "you_win");
            this.turn(50, kai, SOUTH + 30);
        });
        this.shot("k5d_return", "K5", 140).time(day).fov(72).cam(Cine.dolly(
                w.v(34.5, 1.9, railZ + 8.2), w.v(34.5, 1.8, railZ + 7.6), w.v(34.5, 1.0, rz5), w.v(35.0, 1.0, rz5))).setup(() -> {
            track.run();
            int cart = this.cartTester(44, railZ, east, "dye:orange", 0, 0);
            int kai = this.actor(null, "steve", 33.5, kz, SOUTH + 30, new ItemStack(Items.IRON_SWORD));
            int zuri = this.actor(null, "zuri", 26.0, kz + 0.2, east, new ItemStack(Items.MACE));
            this.speakers.put("kai", kai);
            this.speakers.put("zuri", zuri);
            this.pose(1, kai, net.minecraft.world.entity.Pose.CROUCHING);
            this.driveCart(6, 58, cart, w.v(44.5, 0, rz5), w.v(35.6, 0, rz5));
            this.at(6, () -> this.event("cart", ""));
            this.look(20, kai, cart);
            this.pose(34, kai, net.minecraft.world.entity.Pose.STANDING);
            this.say(60, "kai", "q");
            this.walk(64, 86, zuri, w.v(26.0, 0, kz + 0.2), w.v(34.6, 0, kz - 0.3));
            this.strike(90, zuri, cart, 12, true);
            this.look(92, kai, zuri);
            this.say(98, "kai", "what");
            this.walk(104, 136, zuri, w.v(34.6, 0, kz - 0.3), w.v(46.0, 0, kz + 0.3));
        });

        this.shot("e02_end_bg", "E", 180).time(sunset + 200).cam(Cine.dolly(w.v(tx - 5.2, 0.9, tz + 2.6), w.v(tx - 3.6, 1.0, tz + 1.3), w.v(tx, 1.3, tz), w.v(tx, 1.35, tz))).setup(() -> {
            int t = this.tester(tx, tz, SOUTH, "");
            int hero = this.actor(null, "steve", tx + 0.3, tz + 1.6, NORTH, new ItemStack(Items.DIAMOND_SWORD));
            this.strike(60, hero, t, 13, true);
            this.firework(90, w.v(tx + 8, 1, tz - 10), 0xFFD23F, 0xFF7A1A);
            this.firework(110, w.v(tx - 10, 1, tz - 14), 0xC77DFF, 0xFFFFFF);
        });
    }

    private void archers(int cartTesterId, int period) {
        String[][] friends = {{"Kai", "kai"}, {"Zuri", "zuri"}, {"Noor", "noor"}};
        int[] as = new int[3];
        for (int k = 0; k < 3; k++) {
            as[k] = this.actor(friends[k][0], friends[k][1], 29.5 + k * 2, 33.5 + (k % 2), NORTH, new ItemStack(Items.BOW));
        }
        for (int j = 0; j < 30; j++) {
            this.shootArrow(10 + j * period / 3 * 2, as[j % 3], cartTesterId, 6.0);
        }
    }

    @SuppressWarnings("unused")
    private static Minecraft unusedMc() {
        return null;
    }
}
