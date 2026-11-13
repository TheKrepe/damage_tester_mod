package net.star1mp.damagetester.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.star1mp.damagetester.entity.DamageTesterEntity;
import net.star1mp.damagetester.network.DamageNumberPayload;

/** Client-only glue shared by both loaders. */
public final class DamageTesterClient {
    private DamageTesterClient() {
    }

    /** Must be called on the client main thread. */
    public static void handleDamageNumber(DamageNumberPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Entity entity = minecraft.level.getEntity(payload.entityId());
        if (entity instanceof DamageTesterEntity tester) {
            int color = minecraft.level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE)
                    .getHolder(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE, payload.damageType()))
                    .map(DamageColors::get).orElse(0xFFFFFF);
            tester.onClientHit(payload.amount(), color, payload.critical(), payload.dirX(), payload.dirZ());
            if (net.star1mp.damagetester.SelfTestHook.ENABLED) {
                net.star1mp.damagetester.SelfTestHook.clientEvent(String.format(java.util.Locale.ROOT, "hit id=%d amount=%.2f crit=%b color=%06x",
                        payload.entityId(), payload.amount(), payload.critical(), color & 0xFFFFFF));
            }
        }
    }
}
