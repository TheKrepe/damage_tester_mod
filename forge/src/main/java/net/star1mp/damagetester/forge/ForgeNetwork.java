package net.star1mp.damagetester.forge;

import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.star1mp.damagetester.DamageTester;
import net.star1mp.damagetester.network.DamageNumberPayload;

/** The mod's network channel. Clients without the mod may still join (the channel is optional). */
public final class ForgeNetwork {
    private static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(DamageTester.id("main"),
            () -> VERSION, v -> true, v -> true);

    private ForgeNetwork() {
    }

    static void init() {
        CHANNEL.registerMessage(0, DamageNumberPayload.class, DamageNumberPayload::write, DamageNumberPayload::read,
                ForgeNetwork::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    private static void handle(DamageNumberPayload payload, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.star1mp.damagetester.client.DamageTesterClient.handleDamageNumber(payload)));
        context.get().setPacketHandled(true);
    }

    static void sendToTracking(Entity entity, DamageNumberPayload payload) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> entity), payload);
    }
}
