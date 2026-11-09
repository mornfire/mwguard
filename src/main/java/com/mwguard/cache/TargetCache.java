package com.mwguard.cache;

import com.mwguard.config.MwConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * Area cache for one procedure call site.
 *
 * <p>Lookups never scan: a miss or an expired cell returns an empty list and queues the
 * cell for {@link #tick(Level)}. Keys are grid cells because the procedure is called with
 * the ticking entity's own position, and exact positions would never be reused.</p>
 */
public final class TargetCache {

    public static final List<Entity> EMPTY = Collections.emptyList();

    private static final int COORD_BITS = 21;
    private static final long COORD_MASK = (1L << COORD_BITS) - 1L;
    private static final int COORD_SIGN = 1 << (COORD_BITS - 1);
    private static final int COORD_LIMIT = COORD_SIGN - 1;
    private static final int COORD_WRAP = 1 << COORD_BITS;

    private final String name;
    private final boolean reuseResultList;

    private final Reference2ObjectOpenHashMap<Level, Long2ObjectOpenHashMap<Cell>> cells =
            new Reference2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<Level, LongArrayList> pending =
            new Reference2ObjectOpenHashMap<>();

    private final ArrayList<Entity> scratch = new ArrayList<>();
    private final ArrayList<Entity> result;

    private Class<?> boundType;
    @SuppressWarnings("rawtypes")
    private EntityTypeTest typeTest;

    private long hits;
    private long misses;
    private long scans;
    private long refused;
    private long fallbacks;
    private long lastRefusedWarnTick = Long.MIN_VALUE;

    public TargetCache(String name, boolean reuseResultList) {
        this.name = name;
        this.reuseResultList = reuseResultList;
        this.result = reuseResultList ? new ArrayList<>() : null;
    }

    public String name() {
        return name;
    }

    /**
     * Returns the cached matches, {@link #EMPTY} on a miss, or {@code null} when the
     * caller has to run the vanilla lookup itself.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Entity> lookup(Level level, Class<?> type, AABB box, Predicate<? super Entity> predicate) {
        if (!MwConfig.cacheEnabled || level.isClientSide) {
            fallbacks++;
            return null;
        }
        if (boundType == null) {
            boundType = type;
            typeTest = (EntityTypeTest) EntityTypeTest.forClass((Class) type);
        } else if (boundType != type) {
            fallbacks++;
            return null;
        }

        int halfExtent = halfExtent(box);
        if (halfExtent > MwConfig.maxRadius) {
            refused++;
            warnRefused(level, halfExtent);
            return EMPTY;
        }

        int grid = MwConfig.cacheCellBlocks;
        long key = key(
                cellCoord(box.minX + box.maxX, grid),
                cellCoord(box.minY + box.maxY, grid),
                cellCoord(box.minZ + box.maxZ, grid));

        Long2ObjectOpenHashMap<Cell> map = cells.get(level);
        if (map == null) {
            map = new Long2ObjectOpenHashMap<>(64);
            cells.put(level, map);
        }

        long now = level.getGameTime();
        Cell cell = map.get(key);
        if (cell == null) {
            if (map.size() >= MwConfig.cacheMaxEntries) {
                evict(map, now);
                if (map.size() >= MwConfig.cacheMaxEntries) {
                    misses++;
                    return EMPTY;
                }
            }
            cell = new Cell();
            cell.halfExtent = halfExtent;
            cell.predicate = predicate;
            map.put(key, cell);
            enqueue(level, key);
            misses++;
            return EMPTY;
        }

        if (halfExtent > cell.halfExtent) {
            cell.halfExtent = halfExtent;
            cell.hits = EMPTY;
            cell.saturated = false;
            cell.refreshedAt = Long.MIN_VALUE;
        }
        if (cell.refreshedAt == Long.MIN_VALUE || now - cell.refreshedAt >= MwConfig.cacheTtlTicks) {
            cell.predicate = predicate;
            enqueue(level, key);
            misses++;
            return EMPTY;
        }

        hits++;
        if (cell.saturated) {
            // More matches than cacheSampleCap: the area is hot, so skip the per-entity
            // box test. The caller only asks isEmpty().
            return cell.hits;
        }
        return filter(cell, box, (Predicate) predicate);
    }

    /** Runs the queued cell scans for one dimension. Called from ServerTickEvent.Post. */
    public void tick(Level level) {
        LongArrayList queue = pending.get(level);
        if (queue == null || queue.isEmpty()) {
            return;
        }
        Long2ObjectOpenHashMap<Cell> map = cells.get(level);
        if (map == null) {
            queue.clear();
            return;
        }

        long now = level.getGameTime();
        int budget = MwConfig.refreshBudgetPerTick;
        while (budget-- > 0 && !queue.isEmpty()) {
            int last = queue.size() - 1;
            long key = queue.getLong(last);
            queue.removeLong(last);

            Cell cell = map.get(key);
            if (cell != null && cell.pending) {
                scan(level, key, cell, now);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void scan(Level level, long key, Cell cell, long now) {
        int grid = MwConfig.cacheCellBlocks;
        int half = cell.halfExtent;
        double minX = unpack(key, 42) * (double) grid;
        double minY = unpack(key, 21) * (double) grid;
        double minZ = unpack(key, 0) * (double) grid;

        // Every query box whose centre falls in this cell, unioned.
        AABB scanBox = new AABB(
                minX - half, minY - half, minZ - half,
                minX + grid + half, minY + grid + half, minZ + grid + half);

        scratch.clear();
        level.getEntities(typeTest, scanBox, (Predicate) cell.predicate, scratch, MwConfig.cacheSampleCap);
        if (scratch.isEmpty()) {
            cell.hits = EMPTY;
            cell.saturated = false;
        } else {
            cell.hits = new ArrayList<>(scratch);
            cell.saturated = scratch.size() >= MwConfig.cacheSampleCap;
        }
        cell.refreshedAt = now;
        cell.pending = false;
        scans++;
    }

    private List<Entity> filter(Cell cell, AABB box, Predicate<Entity> predicate) {
        List<Entity> source = cell.hits;
        if (source.isEmpty()) {
            return EMPTY;
        }

        List<Entity> out = reuseResultList ? result : new ArrayList<>(Math.min(source.size(), 16));
        out.clear();
        for (int i = 0; i < source.size(); i++) {
            Entity entity = source.get(i);
            if (entity == null || entity.isRemoved()) {
                continue;
            }
            if (!entity.getBoundingBox().intersects(box)) {
                continue;
            }
            if (!predicate.test(entity)) {
                continue;
            }
            out.add(entity);
        }
        return out.isEmpty() ? EMPTY : out;
    }

    private void enqueue(Level level, long key) {
        Long2ObjectOpenHashMap<Cell> map = cells.get(level);
        if (map == null) {
            return;
        }
        Cell cell = map.get(key);
        if (cell == null || cell.pending) {
            return;
        }
        cell.pending = true;

        LongArrayList list = pending.get(level);
        if (list == null) {
            list = new LongArrayList();
            pending.put(level, list);
        }
        list.add(key);
    }

    private void evict(Long2ObjectOpenHashMap<Cell> map, long now) {
        long cutoff = (long) MwConfig.cacheTtlTicks * 10L;
        LongIterator it = map.keySet().iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            Cell cell = map.get(key);
            if (cell == null || cell.refreshedAt == Long.MIN_VALUE || now - cell.refreshedAt > cutoff) {
                it.remove();
            }
        }
    }

    private void warnRefused(Level level, int halfExtent) {
        long now = level.getGameTime();
        if (lastRefusedWarnTick == Long.MIN_VALUE || now - lastRefusedWarnTick > 1200L) {
            lastRefusedWarnTick = now;
            com.mwguard.MwGuard.LOGGER.warn(
                    "TargetCache[{}]: refused a lookup of half-extent {} (maxRadius={}), returned empty.",
                    name, halfExtent, MwConfig.maxRadius);
        }
    }

    public void clear(Level level) {
        cells.remove(level);
        pending.remove(level);
    }

    public void clearAll() {
        cells.clear();
        pending.clear();
        scratch.clear();
    }

    public void resetStats() {
        hits = 0;
        misses = 0;
        scans = 0;
        refused = 0;
        fallbacks = 0;
    }

    public long hits() {
        return hits;
    }

    public long misses() {
        return misses;
    }

    public long scans() {
        return scans;
    }

    public long refused() {
        return refused;
    }

    public long fallbacks() {
        return fallbacks;
    }

    /** 0..100, or -1 before the first lookup. */
    public double hitRatePercent() {
        long total = hits + misses;
        return total == 0L ? -1.0D : (hits * 100.0D) / total;
    }

    // Three signed 21-bit cell coordinates packed into a long.
    private static long key(int x, int y, int z) {
        return ((long) (x & (int) COORD_MASK) << 42)
                | ((long) (y & (int) COORD_MASK) << 21)
                | (z & (int) COORD_MASK);
    }

    private static int unpack(long key, int shift) {
        int value = (int) ((key >>> shift) & COORD_MASK);
        return value >= COORD_SIGN ? value - COORD_WRAP : value;
    }

    private static int cellCoord(double doubledCenter, int grid) {
        double cell = Math.floor((doubledCenter * 0.5D) / grid);
        if (cell >= COORD_LIMIT) {
            return COORD_LIMIT;
        }
        if (cell <= -COORD_LIMIT) {
            return -COORD_LIMIT;
        }
        return (int) cell;
    }

    private static int halfExtent(AABB box) {
        double x = (box.maxX - box.minX) * 0.5D;
        double y = (box.maxY - box.minY) * 0.5D;
        double z = (box.maxZ - box.minZ) * 0.5D;
        return (int) Math.ceil(Math.max(x, Math.max(y, z)));
    }

    private static final class Cell {
        List<Entity> hits = EMPTY;
        Predicate<? super Entity> predicate;
        long refreshedAt = Long.MIN_VALUE;
        int halfExtent;
        boolean pending;
        boolean saturated;
    }
}
