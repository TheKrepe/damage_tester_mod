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
