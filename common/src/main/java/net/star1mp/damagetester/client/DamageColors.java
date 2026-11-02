package net.star1mp.damagetester.client;

import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageType;

/** Picks a color for a floating number from its damage type. */
public final class DamageColors {
    public static final int CRITICAL = 0xFFD23F;
    private static final int DEFAULT = 0xFFFFFF;

    private static final Map<String, Integer> BY_ID = Map.ofEntries(
            Map.entry("minecraft:magic", 0xC77DFF),
            Map.entry("minecraft:indirect_magic", 0xC77DFF),
            Map.entry("minecraft:wither", 0x5A5A5A),
            Map.entry("minecraft:wither_skull", 0x5A5A5A),
            Map.entry("minecraft:thorns", 0x4CAF50),
            Map.entry("minecraft:cactus", 0x4CAF50),
            Map.entry("minecraft:sweet_berry_bush", 0x4CAF50),
            Map.entry("minecraft:sonic_boom", 0x0E8C8C),
            Map.entry("minecraft:trident", 0x2FD5C2),
            Map.entry("minecraft:dragon_breath", 0xE75CFF),
            Map.entry("minecraft:wind_charge", 0xBFE9FF),
            Map.entry("minecraft:mace_smash", 0xFF8A3D),
            Map.entry("minecraft:generic", 0x9CCC65)
    );

    private DamageColors() {
    }

    public static int get(Holder<DamageType> type) {
        String id = type.unwrapKey().map(ResourceKey::identifier).map(Identifier::toString).orElse("");
        Integer byId = BY_ID.get(id);
        if (byId != null) {
            return byId;
        }
        if (type.is(DamageTypeTags.IS_FIRE)) {
            return 0xFF7A1A;
        }
        if (type.is(DamageTypeTags.IS_EXPLOSION)) {
            return 0xFFB03B;
        }
        if (type.is(DamageTypeTags.IS_FREEZING)) {
            return 0x7FD8FF;
        }
        if (type.is(DamageTypeTags.IS_LIGHTNING)) {
            return 0xFFF35C;
        }
        if (type.is(DamageTypeTags.IS_DROWNING)) {
            return 0x3D7BFF;
        }
        if (type.is(DamageTypeTags.IS_PROJECTILE)) {
            return 0xE0E0E0;
        }
        if (type.is(DamageTypeTags.WITCH_RESISTANT_TO)) {
            return 0xC77DFF;
        }
        return DEFAULT;
    }
}
