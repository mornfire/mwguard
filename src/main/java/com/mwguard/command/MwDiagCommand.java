package com.mwguard.command;

import com.mwguard.cache.CacheRegistry;
import com.mwguard.cache.TargetCache;
import com.mwguard.config.MwConfig;
import com.mwguard.guard.ServerStats;
import com.mwguard.status.MixinStatus;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * /mwdiag - TPS, MSPT, heap, per-dimension counts, top entity types and cache stats.
 * Permission level comes from the config (3 by default).
 */
public final class MwDiagCommand {

    private MwDiagCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("mwdiag")
                        .requires(source -> source.hasPermission(MwConfig.diagPermissionLevel))
                        .executes(context -> report(context.getSource()))
                        .then(Commands.literal("reset")
                                .executes(context -> reset(context.getSource()))));
    }

    private static int reset(CommandSourceStack source) {
        CacheRegistry.JAMED_START.resetStats();
        CacheRegistry.TARGET_POINTER.resetStats();
        source.sendSuccess(() -> Component.literal("mwguard: cache counters reset.").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int report(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        ServerStats.sample(server);

        StringBuilder out = new StringBuilder(1024);
        out.append("=== ModernWarfare Guard ===\n");
        out.append(String.format("TPS %.2f | MSPT %.2f ms | tick %d\n",
                ServerStats.tps(), ServerStats.mspt(), server.getTickCount()));

        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L;
        long totalMb = runtime.totalMemory() / 1048576L;
        out.append(String.format("Heap %d / %d MB | entities %d | projectiles %d\n",
                usedMb, totalMb, ServerStats.entities(), ServerStats.projectiles()));

        Map<EntityType<?>, Integer> counts = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            int levelCount = 0;
            for (Entity entity : level.getAllEntities()) {
                levelCount++;
                counts.merge(entity.getType(), 1, Integer::sum);
            }
            out.append("  ").append(level.dimension().location()).append(": ").append(levelCount).append('\n');
        }

        List<Map.Entry<EntityType<?>, Integer>> top = new ArrayList<>(counts.entrySet());
        top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        out.append("Top entities:");
        int shown = Math.min(5, top.size());
        for (int i = 0; i < shown; i++) {
            Map.Entry<EntityType<?>, Integer> entry = top.get(i);
            out.append(' ').append(BuiltInRegistries.ENTITY_TYPE.getKey(entry.getKey()))
                    .append('=').append(entry.getValue());
        }
        if (shown == 0) {
            out.append(" (none)");
        }
        out.append('\n');

        appendCache(out, CacheRegistry.JAMED_START);
        appendCache(out, CacheRegistry.TARGET_POINTER);
        out.append("Mixin: ").append(MixinStatus.describe()).append('\n');

        source.sendSuccess(() -> Component.literal(out.toString()).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static void appendCache(StringBuilder out, TargetCache cache) {
        double rate = cache.hitRatePercent();
        out.append("Cache[").append(cache.name()).append("]: hits=").append(cache.hits())
                .append(" misses=").append(cache.misses())
                .append(" rate=").append(rate < 0.0D ? "n/a" : String.format("%.1f%%", rate))
                .append(" scans=").append(cache.scans())
                .append(" refused=").append(cache.refused())
                .append(" fallbacks=").append(cache.fallbacks())
                .append('\n');
    }
}
