package net.star1mp.damagetester.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.star1mp.damagetester.DamageTester;
import net.star1mp.damagetester.ModContent;
import net.star1mp.damagetester.entity.DamageTesterEntity;
import net.star1mp.damagetester.network.DamageNumberPayload;

public class DamageTesterFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        DamageTester.init();
        PayloadTypeRegistry.clientboundPlay().register(DamageNumberPayload.TYPE, DamageNumberPayload.STREAM_CODEC);
        ModContent.TESTER_TYPES.values().forEach(type -> FabricDefaultAttributeRegistry.register(type.get(), DamageTesterEntity.createAttributes()));
        ModContent.registerDispenserBehavior();
        CreativeModeTabEvents.modifyOutputEvent(ModContent.COMBAT_TAB)
                .register(output -> {
                    for (var item : ModContent.creativeTabItems()) {
                        output.accept(item);
                    }
                });
    }
}
