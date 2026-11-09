package com.mwguard.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server config. The spec writes config/mwguard-server.toml; the plain fields at the
 * bottom are a snapshot so the tick path never touches the spec (no boxing, no lookups).
 */
public final class MwConfig {

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.BooleanValue ENABLE_ENTITY_CACHE;
    private static final ModConfigSpec.IntValue CACHE_TTL_TICKS;
    private static final ModConfigSpec.IntValue MAX_RADIUS;
    private static final ModConfigSpec.IntValue CACHE_CELL_BLOCKS;
    private static final ModConfigSpec.IntValue CACHE_MAX_ENTRIES;
    private static final ModConfigSpec.IntValue CACHE_SAMPLE_CAP;
    private static final ModConfigSpec.IntValue REFRESH_BUDGET_PER_TICK;
    private static final ModConfigSpec.BooleanValue ENABLE_TARGET_POINTER_CACHE;

    private static final ModConfigSpec.BooleanValue MUTE_APT_SPAWN_LOG;

    private static final ModConfigSpec.BooleanValue ENABLE_GUARDRAILS;
    private static final ModConfigSpec.IntValue GUARD_SAMPLE_INTERVAL_TICKS;
    private static final ModConfigSpec.DoubleValue MSPT_WARN;
    private static final ModConfigSpec.DoubleValue MSPT_CRITICAL;
    private static final ModConfigSpec.IntValue MAX_PROJECTILES_PER_LEVEL;
    private static final ModConfigSpec.IntValue MAX_ENTITIES_PER_LEVEL;
    private static final ModConfigSpec.IntValue MAX_DISCARD_PER_PASS;
    private static final ModConfigSpec.EnumValue<GuardAction> GUARD_ACTION;

    private static final ModConfigSpec.IntValue DIAG_PERMISSION_LEVEL;

    // Defaults are the safe ones: if the config never loads, the cache forwards
    // everything to vanilla and the guardrails stay passive.
    public static volatile boolean cacheEnabled = true;
    public static volatile int cacheTtlTicks = 10;
    public static volatile int maxRadius = 64;
    public static volatile int cacheCellBlocks = 32;
    public static volatile int cacheMaxEntries = 4096;
    public static volatile int cacheSampleCap = 8;
    public static volatile int refreshBudgetPerTick = 64;
    public static volatile boolean targetPointerCacheEnabled = false;

    public static volatile boolean muteAptSpawnLog = true;

    public static volatile boolean guardrailsEnabled = true;
    public static volatile int guardSampleIntervalTicks = 20;
    public static volatile double msptWarn = 45.0D;
    public static volatile double msptCritical = 55.0D;
    public static volatile int maxProjectilesPerLevel = 1500;
    public static volatile int maxEntitiesPerLevel = 4000;
    public static volatile int maxDiscardPerPass = 50;
    public static volatile GuardAction guardAction = GuardAction.WARN;

    public static volatile int diagPermissionLevel = 3;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Entity lookup cache for the NewModernWarfare procedures.")
                .push("m1_entity_cache");
        ENABLE_ENTITY_CACHE = b.comment("false = every call goes to vanilla.")
                .define("enableEntityCache", true);
        CACHE_TTL_TICKS = b.comment("Ticks a cached cell is served before it expires.")
                .defineInRange("cacheTtlTicks", 10, 1, 200);
        MAX_RADIUS = b.comment("Half-extent limit in blocks. AABB.ofSize(60,60,60) has half-extent 30.",
                        "Larger queries get an empty list instead of a scan.")
                .defineInRange("maxRadius", 64, 8, 256);
        CACHE_CELL_BLOCKS = b.comment("Grid edge in blocks. Query centres snap to this grid, which is the",
                        "only reason a moving entity ever hits the cache.")
                .defineInRange("cacheCellBlocks", 32, 8, 128);
        CACHE_MAX_ENTRIES = b.comment("Cap on cached cells. Expired entries are evicted first.")
                .defineInRange("cacheMaxEntries", 4096, 256, 65536);
        CACHE_SAMPLE_CAP = b.comment("Max entities stored per cell. The caller only asks isEmpty(), so a few is enough.")
                .defineInRange("cacheSampleCap", 8, 1, 64);
        REFRESH_BUDGET_PER_TICK = b.comment("Max cell scans per ServerTickEvent.Post.")
                .defineInRange("refreshBudgetPerTick", 64, 1, 1024);
        ENABLE_TARGET_POINTER_CACHE = b.comment("Route TargetpointerworkProcedure through the cache too. Off by default.")
                .define("enableTargetPointerCache", false);
        b.pop();

        b.comment("Log noise.")
                .push("m2_logging");
        MUTE_APT_SPAWN_LOG = b.comment("Raise logger 'Adaptive Performance Tweaks.spawn' from DEBUG to INFO.")
                .define("muteAptSpawnLog", true);
        b.pop();

        b.comment("Runtime guardrails. Default action only logs.")
                .push("m3_guardrails");
        ENABLE_GUARDRAILS = b.comment("false = statistics only, no action.")
                .define("enableGuardrails", true);
        GUARD_SAMPLE_INTERVAL_TICKS = b.comment("Sampling period in ticks. 20 = once per second.")
                .defineInRange("guardSampleIntervalTicks", 20, 1, 200);
        MSPT_WARN = b.comment("MSPT above this is reported.")
                .defineInRange("msptWarnThreshold", 45.0D, 1.0D, 1000.0D);
        MSPT_CRITICAL = b.comment("MSPT above this is critical (used by CANCEL_EXPLOSION).")
                .defineInRange("msptCriticalThreshold", 55.0D, 1.0D, 1000.0D);
        MAX_PROJECTILES_PER_LEVEL = b.comment("Projectiles per dimension that trigger DISCARD_OLDEST_PROJECTILE.")
                .defineInRange("maxProjectilesPerLevel", 1500, 16, 100000);
        MAX_ENTITIES_PER_LEVEL = b.comment("Entities per dimension that trigger a warning.")
                .defineInRange("maxEntitiesPerLevel", 4000, 16, 200000);
        MAX_DISCARD_PER_PASS = b.comment("Upper bound on what one pass may discard.")
                .defineInRange("maxDiscardPerPass", 50, 1, 1000);
        GUARD_ACTION = b.comment("What to do when a threshold is exceeded.")
                .defineEnum("guardrailAction", GuardAction.WARN);
        b.pop();

        b.comment("Diagnostics.")
                .push("diagnostics");
        DIAG_PERMISSION_LEVEL = b.comment("Permission level for /mwdiag.")
                .defineInRange("diagPermissionLevel", 3, 0, 4);
        b.pop();

        SPEC = b.build();
        // No refresh() here: reading the spec before it is loaded throws.
    }

    private MwConfig() {
    }

    public static void refresh() {
        cacheEnabled = ENABLE_ENTITY_CACHE.get();
        cacheTtlTicks = CACHE_TTL_TICKS.get();
        maxRadius = MAX_RADIUS.get();
        cacheCellBlocks = CACHE_CELL_BLOCKS.get();
        cacheMaxEntries = CACHE_MAX_ENTRIES.get();
        cacheSampleCap = CACHE_SAMPLE_CAP.get();
        refreshBudgetPerTick = REFRESH_BUDGET_PER_TICK.get();
        targetPointerCacheEnabled = ENABLE_TARGET_POINTER_CACHE.get();

        muteAptSpawnLog = MUTE_APT_SPAWN_LOG.get();

        guardrailsEnabled = ENABLE_GUARDRAILS.get();
        guardSampleIntervalTicks = GUARD_SAMPLE_INTERVAL_TICKS.get();
        msptWarn = MSPT_WARN.get();
        msptCritical = MSPT_CRITICAL.get();
        maxProjectilesPerLevel = MAX_PROJECTILES_PER_LEVEL.get();
        maxEntitiesPerLevel = MAX_ENTITIES_PER_LEVEL.get();
        maxDiscardPerPass = MAX_DISCARD_PER_PASS.get();
        guardAction = GUARD_ACTION.get();

        diagPermissionLevel = DIAG_PERMISSION_LEVEL.get();
    }
}
