package net.star1mp.damagetester.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.star1mp.damagetester.entity.DamageTesterEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla calls {@code crit(target)} (implemented by {@code ServerPlayer}) from {@code Player.attack} only when the melee hit was
 * critical. We listen there so the tester can show crits.
 */
@Mixin(ServerPlayer.class)
public abstract class PlayerCritMixin {
    @Inject(method = "crit", at = @At("HEAD"))
    private void damageTester$onCrit(Entity target, CallbackInfo ci) {
        if (target instanceof DamageTesterEntity tester) {
            tester.markCriticalHit();
        }
    }
}
