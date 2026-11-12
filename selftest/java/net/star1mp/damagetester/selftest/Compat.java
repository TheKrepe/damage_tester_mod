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

/** The few calls that differ between Minecraft versions (this copy: 26.2). */
final class Compat {
    private Compat() {
    }

    static boolean isMinecart(Entity entity) {
        return entity instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
    }

    static void interact(net.minecraft.server.level.ServerPlayer player, Entity entity, net.minecraft.world.phys.Vec3 location) {
        player.interactOn(entity, net.minecraft.world.InteractionHand.MAIN_HAND, location);
    }

    static void fall(net.minecraft.server.level.ServerPlayer player, float distance) {
        player.fallDistance = distance;
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
        return net.minecraft.world.item.DyeItem.byColor(color);
    }

    static void kill(Entity entity, ServerLevel level) {
        entity.kill(level);
    }

    /** For the client checks. */
    static net.minecraft.world.item.CreativeModeTab tab(ResourceKey<net.minecraft.world.item.CreativeModeTab> key) {
        return net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB.getValueOrThrow(key);
    }

    static String path(ResourceKey<?> key) {
        return key.identifier().getPath();
    }

    static String[] resources() {
        return new String[]{"items/damage_tester.json", "items/damage_counter.json"};
    }
}
