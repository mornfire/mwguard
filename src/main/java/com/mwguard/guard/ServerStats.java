package com.mwguard.guard;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;

/**
 * Numbers for the guardrails and /mwdiag. Sampling walks every loaded entity, so it runs
 * once per {@code guardSampleIntervalTicks}, not every tick.
 */
public final class ServerStats {

    private static volatile double mspt;
    private static volatile double tps = 20.0D;
    private static volatile int entities;
    private static volatile int projectiles;
    private static volatile int maxEntitiesPerLevel;
    private static volatile long sampledAtTick = -1L;

    private ServerStats() {
    }

    public static void sample(MinecraftServer server) {
        double average = server.getAverageTickTimeNanos() / 1_000_000.0D;
        int totalEntities = 0;
        int totalProjectiles = 0;
        int worstLevel = 0;

        for (ServerLevel level : server.getAllLevels()) {
            int count = 0;
            for (Entity entity : level.getAllEntities()) {
                count++;
                if (entity instanceof Projectile) {
                    totalProjectiles++;
                }
            }
            totalEntities += count;
            worstLevel = Math.max(worstLevel, count);
        }

        mspt = average;
        tps = average <= 50.0D ? 20.0D : Math.min(20.0D, 1000.0D / average);
        entities = totalEntities;
        projectiles = totalProjectiles;
        maxEntitiesPerLevel = worstLevel;
        sampledAtTick = server.getTickCount();
    }

    public static double mspt() {
        return mspt;
    }

    public static double tps() {
        return tps;
    }

    public static int entities() {
        return entities;
    }

    public static int projectiles() {
        return projectiles;
    }

    public static int maxEntitiesPerLevel() {
        return maxEntitiesPerLevel;
    }

    public static long sampledAtTick() {
        return sampledAtTick;
    }
}
