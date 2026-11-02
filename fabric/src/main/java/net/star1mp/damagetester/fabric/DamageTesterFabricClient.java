package net.star1mp.damagetester.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.star1mp.damagetester.ModContent;
import net.star1mp.damagetester.client.DamageTesterClient;
import net.star1mp.damagetester.client.DamageTesterRenderer;
import net.star1mp.damagetester.network.DamageNumberPayload;

public class DamageTesterFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(ModContent.DAMAGE_TESTER.get(), DamageTesterRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(DamageNumberPayload.TYPE,
                (payload, context) -> DamageTesterClient.handleDamageNumber(payload));
    }
}
