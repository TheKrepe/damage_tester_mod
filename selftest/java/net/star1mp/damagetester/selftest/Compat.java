package net.star1mp.damagetester.selftest;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;

/** The few calls that differ between Minecraft versions (this copy: 26.x). */
final class Compat {
    private Compat() {
    }

    static boolean hasRecipe(MinecraftServer server, Identifier id) {
        return server.getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE, id)).isPresent();
    }

    static String[] advancementIds() {
        return new String[]{"recipes/combat/damage_tester", "recipes/combat/small_damage_tester", "recipes/combat/hanging_damage_tester",
                "recipes/combat/minecart_damage_tester", "recipes/combat/damage_counter"};
    }

    static boolean hasAdvancement(MinecraftServer server, Identifier id) {
        return server.getAdvancements().get(id) != null;
    }

    static boolean hasLootTable(MinecraftServer server, Identifier id) {
        return server.reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE, id)) != LootTable.EMPTY;
    }

    static ItemStack named(ItemStack stack, String name) {
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    static net.minecraft.world.item.Item dye(net.minecraft.world.item.DyeColor color) {
        return net.minecraft.world.item.Items.DYE.pick(color);
    }

    static void kill(Entity entity, ServerLevel level) {
        entity.kill(level);
    }
}
