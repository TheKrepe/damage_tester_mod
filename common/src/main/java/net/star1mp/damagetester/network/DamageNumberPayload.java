package net.star1mp.damagetester.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.star1mp.damagetester.DamageTester;

/**
 * Server → client: "the tester with this id just took this much damage".
 * The client uses it to wobble the tester and pop a floating number.
 *
 * @param entityId   network id of the damage tester
 * @param amount     final damage after armor / enchantments / effects
 * @param damageType id of the damage type, used for the number's color
 * @param critical   whether the hit was a melee critical hit
 * @param dirX       horizontal direction the hit travels in (x), 0 when it has none
 * @param dirZ       horizontal direction the hit travels in (z), 0 when it has none
 */
public record DamageNumberPayload(int entityId, float amount, ResourceLocation damageType, boolean critical, float dirX, float dirZ) {
    public static final ResourceLocation ID = DamageTester.id("damage_number");

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(this.entityId);
        buf.writeFloat(this.amount);
        buf.writeResourceLocation(this.damageType);
        buf.writeBoolean(this.critical);
        buf.writeFloat(this.dirX);
        buf.writeFloat(this.dirZ);
    }

    public static DamageNumberPayload read(FriendlyByteBuf buf) {
        return new DamageNumberPayload(buf.readVarInt(), buf.readFloat(), buf.readResourceLocation(), buf.readBoolean(), buf.readFloat(), buf.readFloat());
    }
}
