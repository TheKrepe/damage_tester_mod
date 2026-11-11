package net.star1mp.damagetester.fabric;

import java.nio.file.Path;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.star1mp.damagetester.DamageTester;
import net.star1mp.damagetester.platform.IPlatformHelper;

public class FabricPlatformHelper implements IPlatformHelper {
    @Override
    public String getPlatformName() {
        return "fabric";
    }

    @Override
    public Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public <T> Supplier<T> register(Registry<? super T> registry, String name, Supplier<T> factory) {
        T value = factory.get();
        Registry.register(registry, DamageTester.id(name), value);
        return () -> value;
    }

    @Override
    public net.minecraft.world.item.CreativeModeTab createCreativeTab(net.minecraft.network.chat.Component title,
            Supplier<net.minecraft.world.item.ItemStack> icon, Supplier<net.minecraft.world.item.Item[]> items) {
        return net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup.builder()
                .title(title)
                .icon(icon)
                .displayItems((parameters, output) -> {
                    for (net.minecraft.world.item.Item item : items.get()) {
                        output.accept(item);
                    }
                })
                .build();
    }

    @Override
    public void sendToTrackingPlayers(Entity entity, CustomPacketPayload payload) {
        for (ServerPlayer player : PlayerLookup.tracking(entity)) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
