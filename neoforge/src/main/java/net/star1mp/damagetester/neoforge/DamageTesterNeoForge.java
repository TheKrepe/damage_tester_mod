package net.star1mp.damagetester.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.star1mp.damagetester.DamageTester;
import net.star1mp.damagetester.ModContent;
import net.star1mp.damagetester.entity.DamageTesterEntity;
import net.star1mp.damagetester.network.DamageNumberPayload;

@Mod(DamageTester.MOD_ID)
public class DamageTesterNeoForge {
    public DamageTesterNeoForge(IEventBus modBus) {
        DamageTester.init();
        NeoForgePlatformHelper.registerAll(modBus);
        modBus.addListener(DamageTesterNeoForge::onAttributes);
        modBus.addListener(DamageTesterNeoForge::onRegisterPayloads);
        modBus.addListener(DamageTesterNeoForge::onCommonSetup);
        modBus.addListener(DamageTesterNeoForge::onBuildCreativeTabs);
    }

    private static void onAttributes(EntityAttributeCreationEvent event) {
        ModContent.TESTER_TYPES.values().forEach(type -> event.put(type.get(), DamageTesterEntity.createAttributes().build()));
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        // The client handler is attached in DamageTesterNeoForgeClient.
        event.registrar("1").playToClient(DamageNumberPayload.TYPE, DamageNumberPayload.STREAM_CODEC);
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(ModContent::registerDispenserBehavior);
    }

    private static void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(ModContent.COMBAT_TAB)) {
            for (var item : ModContent.creativeTabItems()) {
                event.accept(item);
            }
        }
    }
}
