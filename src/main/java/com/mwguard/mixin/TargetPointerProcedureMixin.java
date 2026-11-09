package com.mwguard.mixin;

import com.mwguard.cache.CacheRegistry;
import com.mwguard.config.MwConfig;
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
 * M1b. Same call-site shape as JamedSTART but reached from PlayerTickEvent.Post, so it is
 * one call per player per tick. Off by default; when disabled the handler just forwards.
 */
@Mixin(targets = "net.mcreator.myfirstmod.procedures.TargetpointerworkProcedure", remap = false)
public abstract class TargetPointerProcedureMixin {

    @Inject(
            method = "execute(Lnet/neoforged/bus/api/Event;Lnet/minecraft/world/level/LevelAccessor;DDDLnet/minecraft/world/entity/Entity;)V",
            at = @At("HEAD"),
            require = 0,
            remap = false)
    private static void mwguard$markSeen(CallbackInfo ci) {
        MixinStatus.targetSeen = true;
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
        MixinStatus.targetRedirected = true;

        if (MwConfig.targetPointerCacheEnabled && level instanceof Level mcLevel) {
            List<Entity> cached = CacheRegistry.TARGET_POINTER.lookup(mcLevel, type, box, (Predicate) predicate);
            if (cached != null) {
                return (List<T>) cached;
            }
        }
        return level.getEntitiesOfClass(type, box, predicate);
    }
}
