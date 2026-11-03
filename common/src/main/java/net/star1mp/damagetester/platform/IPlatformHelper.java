package net.star1mp.damagetester.platform;

import java.nio.file.Path;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.Entity;

/**
 * The small set of things that really differ between Fabric and NeoForge.
 * Everything else lives in common code.
 */
public interface IPlatformHelper {
    /** "fabric" or "neoforge". */
    String getPlatformName();

    Path getConfigDir();

    /**
     * Registers an object. On Fabric this happens immediately, on NeoForge it is deferred
     * until the registry event fires, so callers must only use the supplier lazily.
     */
    <T> Supplier<T> register(Registry<? super T> registry, String name, Supplier<T> factory);

    /** A creative tab builder that works with the loader's tab pages. */
    net.minecraft.world.item.CreativeModeTab.Builder creativeTabBuilder();

    /** Sends a clientbound payload to every player that currently tracks {@code entity}. */
    void sendToTrackingPlayers(Entity entity, CustomPacketPayload payload);
}
