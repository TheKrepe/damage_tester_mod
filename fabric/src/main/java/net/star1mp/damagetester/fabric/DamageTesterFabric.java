package net.star1mp.damagetester.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.world.item.Items;
import net.star1mp.damagetester.DamageTester;
import net.star1mp.damagetester.ModContent;
import net.star1mp.damagetester.entity.DamageTesterEntity;

public class DamageTesterFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        DamageTester.init();
        ModContent.TESTER_TYPES.values().forEach(type -> FabricDefaultAttributeRegistry.register(type.get(), DamageTesterEntity.createAttributes()));
        ModContent.registerDispenserBehavior();

        // Vanilla tabs: testers in Combat and next to the armor stand, the counter next to the target block.
        ItemGroupEvents.modifyEntriesEvent(ModContent.COMBAT_TAB).register(output -> {
            for (var item : ModContent.testerItems()) {
                output.accept(item);
            }
        });
        ItemGroupEvents.modifyEntriesEvent(ModContent.FUNCTIONAL_TAB)
                .register(output -> output.addAfter(Items.ARMOR_STAND, ModContent.testerItems()));
        ItemGroupEvents.modifyEntriesEvent(ModContent.REDSTONE_TAB)
                .register(output -> output.addAfter(Items.TARGET, ModContent.DAMAGE_COUNTER_ITEM.get()));
    }
}
