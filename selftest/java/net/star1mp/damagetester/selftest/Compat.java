package net.star1mp.damagetester.selftest;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;

/** The few calls that differ between Minecraft versions (this copy: 1.21.1). */
final class Compat {
    private Compat() {
    }

    static boolean isMinecart(Entity entity) {
        return entity instanceof net.minecraft.world.entity.vehicle.AbstractMinecart;
    }

    static void interact(net.minecraft.server.level.ServerPlayer player, Entity entity, net.minecraft.world.phys.Vec3 location) {
        if (!entity.interactAt(player, location, net.minecraft.world.InteractionHand.MAIN_HAND).consumesAction()) {
            player.interactOn(entity, net.minecraft.world.InteractionHand.MAIN_HAND);
        }
    }

    static void fall(net.minecraft.server.level.ServerPlayer player, float distance) {
        player.fallDistance = distance;
    }

    static boolean hasRecipe(MinecraftServer server, ResourceLocation id) {
        return server.getRecipeManager().byKey(id).isPresent();
    }

    static String[] advancementIds() {
        return new String[]{"recipes/combat/damage_tester", "recipes/combat/small_damage_tester", "recipes/combat/hanging_damage_tester",
                "recipes/combat/minecart_damage_tester", "recipes/combat/damage_counter"};
    }

    static boolean hasAdvancement(MinecraftServer server, ResourceLocation id) {
        return server.getAdvancements().get(id) != null;
    }

    static boolean hasLootTable(MinecraftServer server, ResourceLocation id) {
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
        entity.kill();
    }

    /** For the client checks. */
    static net.minecraft.world.item.CreativeModeTab tab(ResourceKey<net.minecraft.world.item.CreativeModeTab> key) {
        return net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB.getOrThrow(key);
    }

    static String path(ResourceKey<?> key) {
        return key.location().getPath();
    }

    static String[] resources() {
        return new String[]{"models/item/damage_counter.json"};
    }
}
