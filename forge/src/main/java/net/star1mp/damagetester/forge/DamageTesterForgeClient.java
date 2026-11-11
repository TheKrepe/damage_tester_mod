package net.star1mp.damagetester.forge;

import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.star1mp.damagetester.ModContent;
import net.star1mp.damagetester.client.DamageCounterRenderer;
import net.star1mp.damagetester.client.DamageTesterRenderer;

/** Client-only registration. */
public final class DamageTesterForgeClient {
    private DamageTesterForgeClient() {
    }

    static void init(IEventBus modBus) {
        modBus.addListener(DamageTesterForgeClient::onRegisterRenderers);
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        ModContent.TESTER_TYPES.values().forEach(type -> event.registerEntityRenderer(type.get(), DamageTesterRenderer::new));
        event.registerBlockEntityRenderer(ModContent.DAMAGE_COUNTER_BLOCK_ENTITY.get(), DamageCounterRenderer::new);
    }
}
