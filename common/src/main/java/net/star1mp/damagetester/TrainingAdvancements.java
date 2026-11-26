package net.star1mp.damagetester;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.star1mp.damagetester.entity.DamageTesterEntity;
import net.star1mp.damagetester.entity.TesterVariant;

/** Award progress to the player performing the action, independently of the tester's shared DPS. */
public final class TrainingAdvancements {
    private TrainingAdvancements() {
    }

    private static void award(ServerPlayer player, String path, String criterion) {
        var advancement = player.level().getServer().getAdvancements().get(DamageTester.id("training/" + path));
        if (advancement != null) {
            player.getAdvancements().award(advancement, criterion);
        }
    }

    public static void placed(Player player, TesterVariant variant) {
        if (player instanceof ServerPlayer serverPlayer) {
            award(serverPlayer, "root", "done");
            award(serverPlayer, "workshop", variant.id());
        }
    }

    public static void hit(DamageTesterEntity tester, DamageSource source, float damage) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        award(player, "root", "done");
        award(player, "first_hit", "done");
        award(player, "field_test", tester.getVariant().id());
        if (damage >= 10) award(player, "heavy", "done");
        if (damage >= 100) award(player, "hundred", "done");
        if (source.is(DamageTypeTags.IS_PROJECTILE)) {
            award(player, "marksman", "done");
            if (player.distanceToSqr(tester) >= 32 * 32) award(player, "longshot", "done");
            var cart = tester.getVehicle();
            if (tester.getVariant().ridesMinecart() && cart != null && cart.getDeltaMovement().horizontalDistanceSqr() > 0.0001) {
                award(player, "moving_target", "done");
            }
        }
    }

    public static void critical(Player player) {
        if (player instanceof ServerPlayer serverPlayer) award(serverPlayer, "critical", "done");
    }

    public static void equipped(Player player, DamageTesterEntity tester) {
        if (player instanceof ServerPlayer serverPlayer) {
            award(serverPlayer, "root", "done");
            award(serverPlayer, "armor", "done");
            if (tester.hasItemInSlot(EquipmentSlot.HEAD) && tester.hasItemInSlot(EquipmentSlot.CHEST)
                    && tester.hasItemInSlot(EquipmentSlot.LEGS) && tester.hasItemInSlot(EquipmentSlot.FEET)) {
                award(serverPlayer, "armorer", "done");
            }
        }
    }

    public static void styled(Player player, String skin) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        award(serverPlayer, "root", "done");
        award(serverPlayer, "stylist", "done");
        String material;
        if (skin.startsWith(DamageTesterEntity.SKIN_DYE_PREFIX)) {
            material = "cloth";
        } else if (skin.startsWith(DamageTesterEntity.SKIN_PLANKS_PREFIX)) {
            material = "wood";
        } else if (skin.startsWith(DamageTesterEntity.SKIN_PLAYER_PREFIX)) {
            material = "player";
            award(serverPlayer, "double", "done");
        } else if (skin.equals(DamageTesterEntity.SKIN_SNOW)) {
            material = "snow";
            award(serverPlayer, "snowman", "done");
        } else {
            material = skin.equals(DamageTesterEntity.SKIN_JACK) ? "pumpkin" : "straw";
        }
        award(serverPlayer, "wardrobe", material);
    }
}
