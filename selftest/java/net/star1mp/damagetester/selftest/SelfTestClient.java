package net.star1mp.damagetester.selftest;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.star1mp.damagetester.DamageTester;
import net.star1mp.damagetester.ModContent;
import net.star1mp.damagetester.SelfTestHook;

/** Client-side checks: translations, textures, creative tabs (this copy: 26.x). */
final class SelfTestClient {
    private SelfTestClient() {
    }

    static void run() {
        Minecraft mc = Minecraft.getInstance();
        List<String> missing = new ArrayList<>();
        String[] keys = {"item.damage_tester.damage_tester", "item.damage_tester.small_damage_tester", "item.damage_tester.hanging_damage_tester",
                "item.damage_tester.minecart_damage_tester", "block.damage_tester.damage_counter", "entity.damage_tester.damage_tester",
                "entity.damage_tester.small_damage_tester", "entity.damage_tester.hanging_damage_tester", "entity.damage_tester.minecart_damage_tester",
                "itemGroup.damage_tester", "message.damage_tester.stats", "counter.damage_tester.no_tester", "counter.damage_tester.waiting",
                "counter.damage_tester.last", "counter.damage_tester.dps", "counter.damage_tester.total", "counter.damage_tester.hits",
                "counter.damage_tester.max"};
        for (String key : keys) {
            if (!Language.getInstance().has(key)) {
                missing.add(key);
            }
        }
        SelfTestHook.clientEvent(missing.isEmpty() ? "clientcheck lang ok" : "clientcheck lang FAIL " + missing);

        List<String> textures = new ArrayList<>();
        String[] paths = {"textures/entity/damage_tester.png", "textures/entity/damage_tester_cloth.png", "textures/entity/damage_tester_jack.png",
                "textures/entity/damage_tester_jack_glow.png", "textures/entity/damage_tester_no_cloth.png", "textures/entity/damage_tester_straw.png",
                "textures/entity/damage_tester_wood_mask.png", "textures/block/damage_counter_front.png", "textures/block/damage_counter_side.png",
                "textures/block/damage_counter_top.png", "textures/item/damage_tester.png", "textures/item/small_damage_tester.png",
                "textures/item/hanging_damage_tester.png", "textures/item/minecart_damage_tester.png", "blockstates/damage_counter.json",
                "models/item/damage_tester.json"};
        List<String> all = new ArrayList<>(List.of(paths));
        all.addAll(List.of(Compat.resources()));
        for (String path : all) {
            if (mc.getResourceManager().getResource(DamageTester.id(path)).isEmpty()) {
                textures.add(path);
            }
        }
        SelfTestHook.clientEvent(textures.isEmpty() ? "clientcheck textures ok" : "clientcheck textures FAIL " + textures);

        List<String> tabs = new ArrayList<>();
        CreativeModeTabs.tryRebuildTabContents(mc.level.enabledFeatures(), true, mc.level.registryAccess());
        Item[] testers = ModContent.testerItems();
        expect(tabs, ModContent.COMBAT_TAB, testers);
        expect(tabs, ModContent.FUNCTIONAL_TAB, testers);
        expect(tabs, ModContent.REDSTONE_TAB, ModContent.DAMAGE_COUNTER_ITEM.get());
        CreativeModeTab mod = ModContent.CREATIVE_TAB.get();
        for (Item item : testers) {
            if (!mod.contains(new ItemStack(item))) {
                tabs.add("mod tab lacks " + item);
            }
        }
        if (!mod.contains(new ItemStack(ModContent.DAMAGE_COUNTER_ITEM.get()))) {
            tabs.add("mod tab lacks counter");
        }
        SelfTestHook.clientEvent(tabs.isEmpty() ? "clientcheck tabs ok" : "clientcheck tabs FAIL " + tabs);
    }

    private static void expect(List<String> problems, ResourceKey<CreativeModeTab> key, Item... items) {
        CreativeModeTab tab = Compat.tab(key);
        for (Item item : items) {
            if (!tab.contains(new ItemStack(item))) {
                problems.add(Compat.path(key) + " lacks " + item);
            }
        }
    }
}
