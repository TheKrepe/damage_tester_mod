package net.star1mp.damagetester.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.star1mp.damagetester.DamageTester;
import net.star1mp.damagetester.ModContent;
import net.star1mp.damagetester.client.DamageTesterClient;
import net.star1mp.damagetester.client.DamageTesterRenderer;
import net.star1mp.damagetester.network.DamageNumberPayload;

@Mod(value = DamageTester.MOD_ID, dist = Dist.CLIENT)
public class DamageTesterNeoForgeClient {
    public DamageTesterNeoForgeClient(IEventBus modBus) {
        modBus.addListener(DamageTesterNeoForgeClient::onRegisterRenderers);
        modBus.addListener(DamageTesterNeoForgeClient::onRegisterClientPayloads);
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModContent.DAMAGE_TESTER.get(), DamageTesterRenderer::new);
    }

    private static void onRegisterClientPayloads(RegisterClientPayloadHandlersEvent event) {
        event.register(DamageNumberPayload.TYPE,
                (payload, context) -> context.enqueueWork(() -> DamageTesterClient.handleDamageNumber(payload)));
    }
}
