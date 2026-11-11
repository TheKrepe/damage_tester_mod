package net.star1mp.damagetester.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.star1mp.damagetester.ModContent;
import net.star1mp.damagetester.client.DamageCounterRenderer;
import net.star1mp.damagetester.client.DamageTesterClient;
import net.star1mp.damagetester.client.DamageTesterRenderer;
import net.star1mp.damagetester.network.DamageNumberPayload;

public class DamageTesterFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ModContent.TESTER_TYPES.values().forEach(type -> net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(type.get(), DamageTesterRenderer::new));
        BlockEntityRenderers.register(ModContent.DAMAGE_COUNTER_BLOCK_ENTITY.get(), DamageCounterRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(DamageNumberPayload.ID, (client, handler, buf, sender) -> {
            DamageNumberPayload payload = DamageNumberPayload.read(buf);
            client.execute(() -> DamageTesterClient.handleDamageNumber(payload));
        });
    }
}
