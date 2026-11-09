package com.mwguard.config;

/**
 * What the guardrail does when a threshold is exceeded.
 */
public enum GuardAction {
    /** Only log a warning, never touch the world. */
    WARN,
    /** Discard the oldest projectiles until the projectile count is back under the limit. */
    DISCARD_OLDEST_PROJECTILE,
    /** Suppress explosions for as long as MSPT stays above the critical threshold. */
    CANCEL_EXPLOSION
}
