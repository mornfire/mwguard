package com.mwguard.status;

import org.slf4j.Logger;

/**
 * Injection probes. The mixin config uses require = 0, so a changed target signature
 * would fail silently without these flags.
 *
 * <p>This class cannot live in com.mwguard.mixin: Mixin refuses to load ordinary
 * classes from a package declared as a mixin package.</p>
 */
public final class MixinStatus {

    public static volatile boolean jamedSeen;
    public static volatile boolean jamedRedirected;
    public static volatile boolean targetSeen;
    public static volatile boolean targetRedirected;
    public static volatile boolean explosionSeen;
    public static volatile boolean explosionCancelled;
    public static volatile long redirectCalls;

    private MixinStatus() {
    }

    public static String describe() {
        return "jamed[seen=" + jamedSeen + ",redirect=" + jamedRedirected + "]"
                + " targetPointer[seen=" + targetSeen + ",redirect=" + targetRedirected + "]"
                + " explosion[seen=" + explosionSeen + ",cancelled=" + explosionCancelled + "]"
                + " calls=" + redirectCalls;
    }

    public static void checkAfterStartup(String modId, Logger logger, long tick) {
        if (jamedSeen && !jamedRedirected) {
            logger.warn("[{}] JamedSTARTProcedure ticks but the getEntitiesOfClass redirect never fired. "
                    + "The descriptor probably changed; M1 is inactive and vanilla lookups run.", modId);
        }
        if (targetSeen && !targetRedirected) {
            logger.warn("[{}] TargetpointerworkProcedure ticks but its redirect never fired (M1b inactive).", modId);
        }
        if (!jamedSeen) {
            // Not an error on a server that has not ticked any entity yet.
            logger.info("[{}] JamedSTARTProcedure has not ticked yet.", modId);
        }
    }
}
