package com.mwguard.cache;

import com.mwguard.config.MwConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Owns the two cache instances and drives their refresh passes. */
public final class CacheRegistry {

    /** JamedSTARTProcedure. One call site, one class, result only used for isEmpty(). */
    public static final TargetCache JAMED_START = new TargetCache("jamed_start", true);

    /** TargetpointerworkProcedure. Per player, returns a real list, off by default. */
    public static final TargetCache TARGET_POINTER = new TargetCache("target_pointer", false);

    private CacheRegistry() {
    }

    public static void tick(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            JAMED_START.tick(level);
            if (MwConfig.targetPointerCacheEnabled) {
                TARGET_POINTER.tick(level);
            }
        }
    }

    public static void unload(Level level) {
        JAMED_START.clear(level);
        TARGET_POINTER.clear(level);
    }

    public static void clearAll() {
        JAMED_START.clearAll();
        TARGET_POINTER.clearAll();
    }
}
