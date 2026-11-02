package net.star1mp.damagetester.network;

import net.minecraft.core.Holder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.damagesource.DamageType;
import net.star1mp.damagetester.DamageTester;

/**
 * Server → client: "the tester with this id just took this much damage".
 * The client uses it to wobble the tester and pop a floating number.
 *
 * @param entityId   network id of the damage tester
 * @param amount     final damage after armor / enchantments / effects
 * @param damageType damage type holder, used for the number's color
 * @param critical   whether the hit was a melee critical hit
 */
public record DamageNumberPayload(int entityId, float amount, Holder<DamageType> damageType, boolean critical)
        implements CustomPacketPayload {

    public static final Type<DamageNumberPayload> TYPE = new Type<>(DamageTester.id("damage_number"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DamageNumberPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DamageNumberPayload::entityId,
            ByteBufCodecs.FLOAT, DamageNumberPayload::amount,
            DamageType.STREAM_CODEC, DamageNumberPayload::damageType,
            ByteBufCodecs.BOOL, DamageNumberPayload::critical,
            DamageNumberPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
