package com.mwguard;

import com.mwguard.cache.CacheRegistry;
import com.mwguard.command.MwDiagCommand;
import com.mwguard.config.MwConfig;
import com.mwguard.guard.Guardrails;
import com.mwguard.log.AptLogMute;
import com.mwguard.status.MixinStatus;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-only entry point. {@code dist = DEDICATED_SERVER} keeps the mixins out of
 * the client jar; {@code displayTest = IGNORE_ALL_VERSION} lets vanilla clients join.
 */
@Mod(value = MwGuard.MOD_ID, dist = Dist.DEDICATED_SERVER)
public final class MwGuard {

    public static final String MOD_ID = "mwguard";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private boolean startupStatusLogged;

    public MwGuard(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, MwConfig.SPEC);

        modBus.addListener(this::onConfig);

        NeoForge.EVENT_BUS.addListener(this::onTickPre);
        NeoForge.EVENT_BUS.addListener(this::onTickPost);
        NeoForge.EVENT_BUS.addListener(this::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(MwDiagCommand::register);

        LOGGER.info("ModernWarfare Guard loaded (server-side only).");
    }

    private void onConfig(ModConfigEvent event) {
        if (event.getConfig().getType() != ModConfig.Type.SERVER
                || event.getConfig().getSpec() != MwConfig.SPEC) {
            return;
        }
        // Unloading fires after the values are gone; get() would throw.
        if (!(event instanceof ModConfigEvent.Loading) && !(event instanceof ModConfigEvent.Reloading)) {
            return;
        }
        MwConfig.refresh();
        AptLogMute.apply(MwConfig.muteAptSpawnLog);
        if (event instanceof ModConfigEvent.Reloading) {
            CacheRegistry.clearAll();
            LOGGER.info("Config reloaded, caches cleared.");
        }
    }

    private void onTickPre(ServerTickEvent.Pre event) {
        Guardrails.preTick(event.getServer());
    }

    private void onTickPost(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        CacheRegistry.tick(server);
        Guardrails.postTick(server);

        if (!startupStatusLogged && server.getTickCount() >= 200L) {
            startupStatusLogged = true;
            MixinStatus.checkAfterStartup(MOD_ID, LOGGER, server.getTickCount());
            LOGGER.info("Mixin status: {}", MixinStatus.describe());
        }
    }

    private void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level) {
            CacheRegistry.unload(level);
        }
    }

    private void onServerStarted(ServerStartedEvent event) {
        // Another mod or a config reload may have reset the logger level.
        AptLogMute.apply(MwConfig.muteAptSpawnLog);
        LOGGER.info("Entity cache: enabled={} ttl={}t cell={}b maxRadius={} targetPointer={}",
                MwConfig.cacheEnabled, MwConfig.cacheTtlTicks, MwConfig.cacheCellBlocks,
                MwConfig.maxRadius, MwConfig.targetPointerCacheEnabled);
        LOGGER.info("Guardrails: enabled={} action={} warnMspt={} criticalMspt={} maxProjectiles={}",
                MwConfig.guardrailsEnabled, MwConfig.guardAction, MwConfig.msptWarn,
                MwConfig.msptCritical, MwConfig.maxProjectilesPerLevel);
    }

    private void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("Cache stats: jamed_start {} (hits={} misses={} scans={}), target_pointer {} "
                        + "(hits={} misses={} scans={})",
                CacheRegistry.JAMED_START.hitRatePercent(),
                CacheRegistry.JAMED_START.hits(), CacheRegistry.JAMED_START.misses(), CacheRegistry.JAMED_START.scans(),
                CacheRegistry.TARGET_POINTER.hitRatePercent(),
                CacheRegistry.TARGET_POINTER.hits(), CacheRegistry.TARGET_POINTER.misses(),
                CacheRegistry.TARGET_POINTER.scans());
        CacheRegistry.clearAll();
    }
}
