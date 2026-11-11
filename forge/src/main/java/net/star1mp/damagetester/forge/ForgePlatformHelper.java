package net.star1mp.damagetester.forge;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.DeferredRegister;
import net.star1mp.damagetester.DamageTester;
import net.star1mp.damagetester.platform.IPlatformHelper;

public class ForgePlatformHelper implements IPlatformHelper {
    private static final Map<ResourceKey<?>, DeferredRegister<?>> REGISTERS = new LinkedHashMap<>();

    @Override
    public String getPlatformName() {
        return "forge";
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
    public net.minecraft.world.item.CreativeModeTab createCreativeTab(net.minecraft.network.chat.Component title,
            Supplier<net.minecraft.world.item.ItemStack> icon, Supplier<net.minecraft.world.item.Item[]> items) {
        return net.minecraft.world.item.CreativeModeTab.builder()
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
    public void sendToTrackingPlayers(Entity entity, net.star1mp.damagetester.network.DamageNumberPayload payload) {
        ForgeNetwork.sendToTracking(entity, payload);
    }

    static void registerAll(IEventBus modBus) {
        REGISTERS.values().forEach(register -> register.register(modBus));
    }
}
