package com.mwguard.mixin;

import com.mwguard.guard.Guardrails;
import com.mwguard.status.MixinStatus;
import net.minecraft.world.level.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * M3, CANCEL_EXPLOSION. Entity damage and block collection both happen in explode();
 * Level#explode calls it and then finalizeExplosion, which becomes a no-op when the
 * internal lists are empty. The flag is per tick and only raised by Guardrails.
 */
@Mixin(value = Explosion.class, remap = false)
public abstract class ExplosionGuardMixin {

    @Inject(
            method = "explode()V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false)
    private void mwguard$guardExplosion(CallbackInfo ci) {
        MixinStatus.explosionSeen = true;
        if (Guardrails.cancelExplosionsThisTick()) {
            MixinStatus.explosionCancelled = true;
            ci.cancel();
        }
    }
}
