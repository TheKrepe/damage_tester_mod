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
            int color = DamageColors.get(payload.damageType());
            tester.onClientHit(payload.amount(), color, payload.critical());
        }
    }
}
