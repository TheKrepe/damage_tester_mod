package net.star1mp.damagetester.neoforge;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.star1mp.damagetester.DamageTester;
import net.star1mp.damagetester.platform.IPlatformHelper;

public class NeoForgePlatformHelper implements IPlatformHelper {
    private static final Map<ResourceKey<?>, DeferredRegister<?>> REGISTERS = new LinkedHashMap<>();

    @Override
    public String getPlatformName() {
        return "neoforge";
    }

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> Supplier<T> register(Registry<? super T> registry, String name, Supplier<T> factory) {
        DeferredRegister<Object> register = (DeferredRegister<Object>) REGISTERS.computeIfAbsent(registry.key(),
                key -> DeferredRegister.create((ResourceKey) key, DamageTester.MOD_ID));
        return register.register(name, factory);
    }

    @Override
    public net.minecraft.world.item.CreativeModeTab.Builder creativeTabBuilder() {
        return net.minecraft.world.item.CreativeModeTab.builder();
    }

    @Override
    public void sendToTrackingPlayers(Entity entity, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayersTrackingEntity(entity, payload);
    }

    static void registerAll(IEventBus modBus) {
        REGISTERS.values().forEach(register -> register.register(modBus));
    }
}
