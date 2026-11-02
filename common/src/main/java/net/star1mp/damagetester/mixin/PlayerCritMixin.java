package net.star1mp.damagetester.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.star1mp.damagetester.entity.DamageTesterEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla decides whether a melee hit is critical inside {@code Player.attack} and then calls
 * {@code attackVisualEffects(target, critical, ...)}. We listen there so the tester can show crits.
 */
@Mixin(Player.class)
public abstract class PlayerCritMixin {
    @Inject(method = "attackVisualEffects", at = @At("HEAD"))
    private void damageTester$onAttackEffects(Entity target, boolean critical, boolean sweep, boolean fullStrength,
                                              boolean stab, float magicBoost, CallbackInfo ci) {
        if (critical && target instanceof DamageTesterEntity tester) {
            tester.markCriticalHit();
        }
    }
}
