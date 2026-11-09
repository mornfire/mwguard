package com.mwguard.log;

import com.mwguard.MwGuard;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;

/**
 * Raises "Adaptive Performance Tweaks.spawn" from DEBUG to INFO. Every natural spawn
 * attempt logs one "[Natural Spawn] Denied ..." line at DEBUG, which filled the server's
 * debug.log on its own. Only that child logger is touched.
 */
public final class AptLogMute {

    public static final String APT_SPAWN_LOGGER = "Adaptive Performance Tweaks.spawn";

    private static Boolean lastApplied;

    private AptLogMute() {
    }

    public static void apply(boolean mute) {
        try {
            Configurator.setLevel(APT_SPAWN_LOGGER, mute ? Level.INFO : Level.DEBUG);

            boolean debugEnabled = LogManager.getLogger(APT_SPAWN_LOGGER).isDebugEnabled();
            if (mute == debugEnabled) {
                MwGuard.LOGGER.warn("Could not change the level of '{}' (debug still {}); another mod may reset it.",
                        APT_SPAWN_LOGGER, debugEnabled);
            } else if (lastApplied == null || lastApplied != mute) {
                MwGuard.LOGGER.info("Logger '{}' set to {} (APT spawn noise muted: {}).",
                        APT_SPAWN_LOGGER, mute ? "INFO" : "DEBUG", mute);
            }
            lastApplied = mute;
        } catch (Throwable t) {
            // Missing log4j-core should never take the server down.
            MwGuard.LOGGER.warn("Failed to adjust logger '{}'.", APT_SPAWN_LOGGER, t);
        }
    }
}
