package com.mwguard.mixin;

import com.mwguard.cache.CacheRegistry;
import com.mwguard.status.MixinStatus;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Predicate;

/**
 * M1. Targets the single hot getEntitiesOfClass call in JamedSTARTProcedure.
 *
 * <p>javap -p -c against 2.5.3 shows the call at offset 38, owner LevelAccessor
 * (the method is inherited from EntityGetter), box 60x60x60, and that the procedure
 * only calls isEmpty() on the result. The class ticks every entity in every dimension.</p>
 */
@Mixin(targets = "net.mcreator.myfirstmod.procedures.JamedSTARTProcedure", remap = false)
public abstract class JamedStartProcedureMixin {

    @Inject(
            method = "execute(Lnet/neoforged/bus/api/Event;Lnet/minecraft/world/level/LevelAccessor;DDDLnet/minecraft/world/entity/Entity;)V",
            at = @At("HEAD"),
            require = 0,
            remap = false)
    private static void mwguard$markSeen(CallbackInfo ci) {
        MixinStatus.jamedSeen = true;
    }

    @Redirect(
            method = "execute(Lnet/neoforged/bus/api/Event;Lnet/minecraft/world/level/LevelAccessor;DDDLnet/minecraft/world/entity/Entity;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/LevelAccessor;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"),
            require = 0,
            remap = false)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends Entity> List<T> mwguard$cachedLookup(
            LevelAccessor level, Class<T> type, AABB box, Predicate<? super T> predicate) {
        MixinStatus.jamedRedirected = true;
        MixinStatus.redirectCalls++;

        if (level instanceof Level mcLevel) {
            List<Entity> cached = CacheRegistry.JAMED_START.lookup(mcLevel, type, box, (Predicate) predicate);
            if (cached != null) {
                return (List<T>) cached;
            }
        }
        return level.getEntitiesOfClass(type, box, predicate);
    }
}
