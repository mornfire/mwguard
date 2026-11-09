package com.mwguard.guard;

import com.mwguard.MwGuard;
import com.mwguard.config.GuardAction;
import com.mwguard.config.MwConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * M3. Watches MSPT, projectiles and per-dimension entity counts and applies the
 * configured reaction. WARN is the default; the two active reactions are bounded so a
 * single pass cannot wipe a farm.
 */
public final class Guardrails {

    private static boolean cancelExplosions;
    private static long lastWarnTick = Long.MIN_VALUE;

    private Guardrails() {
    }

    /** Runs before the level tick, so Explosion#explode already sees the flag. */
    public static void preTick(MinecraftServer server) {
        cancelExplosions = false;
        if (!MwConfig.guardrailsEnabled || MwConfig.guardAction != GuardAction.CANCEL_EXPLOSION) {
            return;
        }
        if (ServerStats.mspt() < MwConfig.msptCritical) {
            return;
        }
        cancelExplosions = true;
        warnRateLimited(server, "MSPT {} is over the critical threshold {}, suppressing explosions this tick.",
                String.format("%.2f", ServerStats.mspt()), MwConfig.msptCritical);
    }

    public static boolean cancelExplosionsThisTick() {
        return cancelExplosions;
    }

    public static void postTick(MinecraftServer server) {
        cancelExplosions = false;

        int interval = MwConfig.guardSampleIntervalTicks;
        if (interval > 1 && server.getTickCount() % interval != 0L) {
            return;
        }
        ServerStats.sample(server);
        if (!MwConfig.guardrailsEnabled) {
            return;
        }

        boolean msptHot = ServerStats.mspt() > MwConfig.msptWarn;
        boolean tooManyProjectiles = ServerStats.projectiles() > MwConfig.maxProjectilesPerLevel;
        boolean tooManyEntities = ServerStats.maxEntitiesPerLevel() > MwConfig.maxEntitiesPerLevel;
        if (!msptHot && !tooManyProjectiles && !tooManyEntities) {
            return;
        }

        switch (MwConfig.guardAction) {
            case WARN -> warnRateLimited(server, "guardrail: MSPT={}, projectiles={}, worst level entities={}",
                    String.format("%.2f", ServerStats.mspt()), ServerStats.projectiles(),
                    ServerStats.maxEntitiesPerLevel());
            case DISCARD_OLDEST_PROJECTILE -> {
                if (tooManyProjectiles) {
                    discardOldest(server);
                } else {
                    warnRateLimited(server,
                            "guardrail: MSPT={} and worst level entities={} are high, but projectiles={} is not; "
                                    + "nothing to discard.",
                            String.format("%.2f", ServerStats.mspt()), ServerStats.maxEntitiesPerLevel(),
                            ServerStats.projectiles());
                }
            }
            case CANCEL_EXPLOSION -> {
                // preTick does the work; only report the case it does not cover.
                if (!msptHot) {
                    warnRateLimited(server,
                            "guardrail: entities={} and projectiles={} are high while MSPT is fine; "
                                    + "CANCEL_EXPLOSION only reacts to MSPT.",
                            ServerStats.maxEntitiesPerLevel(), ServerStats.projectiles());
                }
            }
        }
    }

    private static void discardOldest(MinecraftServer server) {
        List<Projectile> all = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Projectile projectile && !projectile.isRemoved()) {
                    all.add(projectile);
                }
            }
        }

        int excess = all.size() - MwConfig.maxProjectilesPerLevel;
        if (excess <= 0) {
            return;
        }
        excess = Math.min(excess, MwConfig.maxDiscardPerPass);
        all.sort(Comparator.comparingInt((Projectile projectile) -> projectile.tickCount).reversed());

        int discarded = 0;
        for (int i = 0; i < excess && i < all.size(); i++) {
            Projectile projectile = all.get(i);
            if (!projectile.isRemoved()) {
                projectile.discard();
                discarded++;
            }
        }
        MwGuard.LOGGER.warn("guardrail: discarded {} oldest projectiles ({} total, limit {}).",
                discarded, all.size(), MwConfig.maxProjectilesPerLevel);
    }

    private static void warnRateLimited(MinecraftServer server, String message, Object... args) {
        long tick = server.getTickCount();
        if (lastWarnTick != Long.MIN_VALUE && tick - lastWarnTick < 200L) {
            return;
        }
        lastWarnTick = tick;
        MwGuard.LOGGER.warn(message, args);
    }
}
