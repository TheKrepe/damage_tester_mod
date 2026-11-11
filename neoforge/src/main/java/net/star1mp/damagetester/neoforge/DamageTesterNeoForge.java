package net.star1mp.damagetester.neoforge;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
        event.registrar("1").playToClient(DamageNumberPayload.TYPE, DamageNumberPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> net.star1mp.damagetester.client.DamageTesterClient.handleDamageNumber(payload)));
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(ModContent::registerDispenserBehavior);
    }

    private static void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        // Vanilla tabs: testers in Combat and next to the armor stand, the counter next to the target block.
        if (event.getTabKey().equals(ModContent.COMBAT_TAB)) {
            for (Item item : ModContent.testerItems()) {
                event.accept(item);
            }
        } else if (event.getTabKey().equals(ModContent.FUNCTIONAL_TAB)) {
            insertAfter(event, Items.ARMOR_STAND, ModContent.testerItems());
        } else if (event.getTabKey().equals(ModContent.REDSTONE_TAB)) {
            insertAfter(event, Items.TARGET, ModContent.DAMAGE_COUNTER_ITEM.get());
        }
    }

    private static void insertAfter(BuildCreativeModeTabContentsEvent event, Item anchor, Item... items) {
        ItemStack previous = new ItemStack(anchor);
        for (Item item : items) {
            ItemStack stack = new ItemStack(item);
            event.insertAfter(previous, stack, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            previous = stack;
        }
    }
}
