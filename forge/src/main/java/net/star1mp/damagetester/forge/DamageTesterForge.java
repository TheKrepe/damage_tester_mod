package net.star1mp.damagetester.forge;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.star1mp.damagetester.DamageTester;
import net.star1mp.damagetester.ModContent;
import net.star1mp.damagetester.entity.DamageTesterEntity;

@Mod(DamageTester.MOD_ID)
public class DamageTesterForge {
    public DamageTesterForge() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        DamageTester.init();
        ForgePlatformHelper.registerAll(modBus);
        ForgeNetwork.init();
        modBus.addListener(DamageTesterForge::onAttributes);
        modBus.addListener(DamageTesterForge::onCommonSetup);
        modBus.addListener(DamageTesterForge::onBuildCreativeTabs);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> DamageTesterForgeClient.init(modBus));
    }

    private static void onAttributes(EntityAttributeCreationEvent event) {
        ModContent.TESTER_TYPES.values().forEach(type -> event.put(type.get(), DamageTesterEntity.createAttributes().build()));
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(ModContent::registerDispenserBehavior);
    }

    private static void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        // Vanilla tabs: testers in Combat and next to the armor stand, the counter next to the target block.
        if (event.getTabKey().equals(ModContent.COMBAT_TAB)) {
            for (Item item : ModContent.testerItems()) {
                event.accept(new ItemStack(item), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
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
            event.getEntries().putAfter(previous, stack, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            previous = stack;
        }
    }
}
