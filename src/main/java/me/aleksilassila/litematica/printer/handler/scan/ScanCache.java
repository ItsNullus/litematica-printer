package me.aleksilassila.litematica.printer.handler.scan;

import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.scan.SectionScanSession.Candidate;
import me.aleksilassila.litematica.printer.handler.scan.SectionScanSession.MutableMetrics;
import me.aleksilassila.litematica.printer.handler.scan.SectionScanSession.Region;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class ScanCache {
    public static final ScanCache INSTANCE = new ScanCache();

    private static final int BUDGET_CHECK_INTERVAL = 8;
    private static final int OWNER_SCAN_BUDGET_PERCENT = 75;

    /** Controls what a completed cursor may do on a later tick. */
    public enum PassPolicy {
        /** Start another full pass when the previous pass has completed. */
        RESTART,
        /** Stay completed until a block update invalidates a position in the scan region. */
        INVALIDATIONS_ONLY
    }

    private final Map<String, SectionScanSession> sessions = new HashMap<>();
    private final Map<String, MutableMetrics> scanMetrics = new HashMap<>();

    private Object levelIdentity;
    private Object schematicIdentity;
    private long tickTime = Long.MIN_VALUE;
    private long scanBudgetTickTime = Long.MIN_VALUE;
    private long globalScanBudgetUsedNanos;

    private ScanCache() {
    }

    public void clear() {
        this.sessions.clear();
        this.scanMetrics.clear();
        this.levelIdentity = null;
        this.schematicIdentity = null;
        this.tickTime = Long.MIN_VALUE;
        this.scanBudgetTickTime = Long.MIN_VALUE;
        this.globalScanBudgetUsedNanos = 0L;
        DirtyRegionTracker.INSTANCE.clear();
    }

    public record ScanMetrics(
            long scanNanos,
            int scannedBlocks,
            int scannedSections,
            int sourceCandidates,
            int acceptedTargets,
            int budgetPauses,
            int completedPasses
    ) {
        private static final ScanMetrics EMPTY = new ScanMetrics(0L, 0, 0, 0, 0, 0, 0);

        public boolean hasActivity() {
            return this.scanNanos > 0L
                    || this.scannedBlocks > 0
                    || this.scannedSections > 0
                    || this.sourceCandidates > 0
                    || this.acceptedTargets > 0
                    || this.budgetPauses > 0
                    || this.completedPasses > 0;
        }
    }

    public static long key(BlockPos pos) {
        return key(pos.getX(), pos.getY(), pos.getZ());
    }

    public static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
                | ((long) z & 0x3FFFFFFL) << 12
                | ((long) y & 0xFFFL);
    }

    public void beginTick(ClientLevel level, WorldSchematic schematic, long tickTime) {
        if (this.levelIdentity == level && this.schematicIdentity == schematic && this.tickTime == tickTime) {
            return;
        }
        if (this.levelIdentity != level || this.schematicIdentity != schematic) {
            this.sessions.clear();
            this.scanMetrics.clear();
            DirtyRegionTracker.INSTANCE.clear();
            this.levelIdentity = level;
            this.schematicIdentity = schematic;
        }
        if (this.tickTime != tickTime) {
            for (MutableMetrics metrics : this.scanMetrics.values()) {
                metrics.reset();
            }
        }
        this.tickTime = tickTime;
        if (this.scanBudgetTickTime != tickTime) {
            this.scanBudgetTickTime = tickTime;
            this.globalScanBudgetUsedNanos = 0L;
        }
    }

    public ScanMetrics metricsFor(String ownerKey) {
        MutableMetrics metrics = this.scanMetrics.get(normalizeMetricsOwnerKey(ownerKey));
        return metrics == null ? ScanMetrics.EMPTY : metrics.snapshot();
    }

    public void invalidate(BlockPos pos) {
        if (pos == null) {
            return;
        }
        for (SectionScanSession session : this.sessions.values()) {
            session.invalidate(pos);
        }
    }

    public void resetOwner(String ownerKey) {
        if (ownerKey == null || ownerKey.isBlank()) {
            return;
        }
        String prefix = ownerKey + ":";
        this.sessions.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public Iterable<BlockPos> rawIterable(
            String ownerKey,
            PrinterBox sourceBox,
            LocalPlayer player,
            int scanGuardLimit,
            Predicate<BlockPos> preFilter
    ) {
        return this.iterable(ownerKey, List.of(sourceBox), null, null, player, scanGuardLimit,
                ScanIntent.CUSTOM, pos -> true, preFilter, false, PassPolicy.RESTART);
    }

    public Iterable<BlockPos> iterable(
            String ownerKey,
            PrinterBox sourceBox,
            ClientLevel level,
            WorldSchematic schematic,
            LocalPlayer player,
            int scanGuardLimit,
            ScanIntent intent,
            Predicate<BlockPos> exactPredicate
    ) {
        return this.iterable(ownerKey, sourceBox, level, schematic, player, scanGuardLimit, intent, exactPredicate, pos -> true);
    }

    public Iterable<BlockPos> unboundedIterable(
            String ownerKey,
            PrinterBox sourceBox,
            ClientLevel level,
            WorldSchematic schematic,
            LocalPlayer player,
            ScanIntent intent,
            Predicate<BlockPos> exactPredicate,
            Predicate<BlockPos> preFilter
    ) {
        return this.iterable(ownerKey, List.of(sourceBox), level, schematic, player, Integer.MAX_VALUE,
                intent, exactPredicate, preFilter, true, PassPolicy.RESTART);
    }

    public Iterable<BlockPos> iterable(
            String ownerKey,
            PrinterBox sourceBox,
            ClientLevel level,
            WorldSchematic schematic,
            LocalPlayer player,
            int scanGuardLimit,
            ScanIntent intent,
            Predicate<BlockPos> exactPredicate,
            Predicate<BlockPos> preFilter
    ) {
        return this.iterable(ownerKey, List.of(sourceBox), level, schematic, player, scanGuardLimit,
                intent, exactPredicate, preFilter, false, PassPolicy.RESTART);
    }

    public Iterable<BlockPos> iterable(
            String ownerKey,
            List<PrinterBox> sourceBoxes,
            ClientLevel level,
            WorldSchematic schematic,
            LocalPlayer player,
            int scanGuardLimit,
            ScanIntent intent,
            Predicate<BlockPos> exactPredicate
    ) {
        return this.iterable(
                ownerKey,
                sourceBoxes,
                level,
                schematic,
                player,
                scanGuardLimit,
                intent,
                exactPredicate,
                pos -> true
        );
    }

    public Iterable<BlockPos> iterable(
            String ownerKey,
            List<PrinterBox> sourceBoxes,
            ClientLevel level,
            WorldSchematic schematic,
            LocalPlayer player,
            int scanGuardLimit,
            ScanIntent intent,
            Predicate<BlockPos> exactPredicate,
            Predicate<BlockPos> preFilter
    ) {
        return this.iterable(
                ownerKey,
                sourceBoxes,
                level,
                schematic,
                player,
                scanGuardLimit,
                intent,
                exactPredicate,
                preFilter,
                PassPolicy.RESTART
        );
    }

    public Iterable<BlockPos> iterable(
            String ownerKey,
            List<PrinterBox> sourceBoxes,
            ClientLevel level,
            WorldSchematic schematic,
            LocalPlayer player,
            int scanGuardLimit,
            ScanIntent intent,
            Predicate<BlockPos> exactPredicate,
            Predicate<BlockPos> preFilter,
            PassPolicy passPolicy
    ) {
        if (sourceBoxes == null || sourceBoxes.isEmpty()) {
            return List.of();
        }
        return this.iterable(ownerKey, sourceBoxes, level, schematic, player, scanGuardLimit,
                intent, exactPredicate, preFilter, false, passPolicy);
    }

    private Iterable<BlockPos> iterable(
            String ownerKey,
            List<PrinterBox> sourceBoxes,
            ClientLevel level,
            WorldSchematic schematic,
            LocalPlayer player,
            int scanGuardLimit,
            ScanIntent intent,
            Predicate<BlockPos> exactPredicate,
            Predicate<BlockPos> preFilter,
            boolean unbounded,
            PassPolicy passPolicy
    ) {
        int scanLimit = unbounded ? Integer.MAX_VALUE : this.getScanLimit(scanGuardLimit);
        String cacheOwnerKey = this.cacheOwnerKey(ownerKey, intent);
        String metricsOwnerKey = normalizeMetricsOwnerKey(ownerKey);
        MutableMetrics metrics = this.metrics(metricsOwnerKey);
        PrinterBox sourceBounds = enclosingBox(sourceBoxes);
        if (sourceBounds == null) {
            return List.of();
        }
        SectionScanSession session = this.session(
                cacheOwnerKey,
                metrics,
                intent,
                sourceBounds,
                sourceBoxes,
                player
        );
        return () -> new Iterator<>() {
            private final LongSet emitted = new LongOpenHashSet();
            private BlockPos next;
            private boolean prepared;
            private boolean scanLimitHit;
            private boolean sentinelReturned;
            private int considered;
            private int budgetChecks;

            private void prepare() {
                if (this.prepared) {
                    return;
                }
                this.prepared = true;

                long budgetStart = System.nanoTime();
                boolean budgetHit = false;
                try {
                    boolean restartCompletedPass = passPolicy == PassPolicy.RESTART;
                    while (this.considered < scanLimit && session.canScan(tickTime, restartCompletedPass)) {
                        if (!unbounded && ++this.budgetChecks % BUDGET_CHECK_INTERVAL == 0 && isScanBudgetExceeded(budgetStart)) {
                            budgetHit = true;
                            break;
                        }

                        Candidate candidate = session.next(level, schematic, tickTime,
                                () -> !unbounded && isScanBudgetExceeded(budgetStart),
                                preFilter,
                                unbounded,
                                restartCompletedPass);
                        if (candidate == null) {
                            if (session.wasPaused()) {
                                budgetHit = true;
                            }
                            break;
                        }
                        metrics.sourceCandidates++;
                        this.considered++;
                        BlockPos pos = candidate.pos();
                        if (!session.contains(pos)) {
                            continue;
                        }
                        boolean target = candidate.acceptedByFlags(intent);
                        if (!target && intent.shouldRunExactPredicate(candidate.flags())) {
                            target = exactPredicate.test(pos);
                        }
                        if (!target) {
                            continue;
                        }
                        long posKey = key(pos);
                        if (this.emitted.add(posKey)) {
                            metrics.acceptedTargets++;
                            this.next = pos;
                            return;
                        }
                    }
                } finally {
                    if (!unbounded) {
                        recordScanBudget(metrics, budgetStart);
                    }
                }

                if (budgetHit) {
                    metrics.budgetPauses++;
                }
                this.scanLimitHit = session.hasPendingSource(tickTime, passPolicy == PassPolicy.RESTART)
                        && (budgetHit || this.considered >= scanLimit);
            }

            @Override
            public boolean hasNext() {
                this.prepare();
                return this.next != null || this.scanLimitHit && !this.sentinelReturned;
            }

            @Override
            public BlockPos next() {
                this.prepare();
                if (this.next != null) {
                    BlockPos result = this.next;
                    this.next = null;
                    this.prepared = false;
                    return result;
                }
                if (this.scanLimitHit && !this.sentinelReturned) {
                    this.sentinelReturned = true;
                    return null;
                }
                return null;
            }

        };
    }

    private String cacheOwnerKey(String ownerKey, ScanIntent intent) {
        if (intent == ScanIntent.PRINT && Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue()) {
            return ownerKey + ":breakExtra";
        }
        return ownerKey;
    }

    private SectionScanSession session(
            String ownerKey,
            MutableMetrics metrics,
            ScanIntent intent,
            PrinterBox sourceBounds,
            List<PrinterBox> sourceBoxes,
            LocalPlayer player
    ) {
        Region region = Region.from(sourceBounds, player);
        String key = ownerKey + ":" + intent.name();
        SectionScanSession session = this.sessions.get(key);
        if (session == null || !session.canReuse(region)) {
            session = new SectionScanSession(region, sourceBoxes, intent, metrics);
            this.sessions.put(key, session);
        } else {
            session.updateRegion(region, sourceBoxes);
        }
        return session;
    }

    private static PrinterBox enclosingBox(List<PrinterBox> boxes) {
        PrinterBox result = null;
        for (PrinterBox box : boxes) {
            if (box == null) {
                continue;
            }
            if (result == null) {
                result = box;
                continue;
            }
            result = new PrinterBox(
                    Math.min(result.minX, box.minX),
                    Math.min(result.minY, box.minY),
                    Math.min(result.minZ, box.minZ),
                    Math.max(result.maxX, box.maxX),
                    Math.max(result.maxY, box.maxY),
                    Math.max(result.maxZ, box.maxZ)
            );
        }
        return result;
    }

    private static boolean containsAny(List<PrinterBox> boxes, BlockPos pos) {
        for (PrinterBox box : boxes) {
            if (box.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    private boolean isScanBudgetExceeded(long ownerBudgetStartNanos) {
        long elapsed = Math.max(0L, System.nanoTime() - ownerBudgetStartNanos);
        long globalBudgetNanos = this.globalScanBudgetNanos();
        long ownerBudgetNanos = this.ownerScanBudgetNanos(globalBudgetNanos);
        return elapsed >= ownerBudgetNanos
                || this.globalScanBudgetUsedNanos + elapsed >= globalBudgetNanos;
    }

    private void recordScanBudget(MutableMetrics metrics, long ownerBudgetStartNanos) {
        long elapsed = Math.max(0L, System.nanoTime() - ownerBudgetStartNanos);
        this.globalScanBudgetUsedNanos += elapsed;
        metrics.scanNanos += elapsed;
    }

    private MutableMetrics metrics(String ownerKey) {
        return this.scanMetrics.computeIfAbsent(ownerKey, key -> new MutableMetrics());
    }

    private static String normalizeMetricsOwnerKey(String ownerKey) {
        int separator = ownerKey.length();
        int underscore = ownerKey.indexOf('_');
        if (underscore >= 0) {
            separator = Math.min(separator, underscore);
        }
        int colon = ownerKey.indexOf(':');
        if (colon >= 0) {
            separator = Math.min(separator, colon);
        }
        return ownerKey.substring(0, separator);
    }

    private long globalScanBudgetNanos() {
        return Math.max(1L, Configs.Core.SCAN_TIME_BUDGET_MS.getIntegerValue()) * 1_000_000L;
    }

    private long ownerScanBudgetNanos(long globalBudgetNanos) {
        return Math.max(500_000L, globalBudgetNanos * OWNER_SCAN_BUDGET_PERCENT / 100L);
    }

    private int getScanLimit(int scanGuardLimit) {
        return scanGuardLimit > 0 ? scanGuardLimit : Integer.MAX_VALUE;
    }


}
