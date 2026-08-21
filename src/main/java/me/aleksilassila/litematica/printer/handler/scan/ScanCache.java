package me.aleksilassila.litematica.printer.handler.scan;

import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.handler.scan.SectionScanSession.Candidate;
import me.aleksilassila.litematica.printer.handler.scan.SectionScanSession.MutableMetrics;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

public final class ScanCache {
    private static final int BUDGET_CHECK_INTERVAL = 8;
    private static final int OWNER_SCAN_BUDGET_PERCENT = 75;

    /** Controls what a completed cursor may do on a later tick. */
    public enum PassPolicy {
        /** Start another full pass when the previous pass has completed. */
        RESTART,
        /** Stay completed until a block update invalidates a position in the scan region. */
        INVALIDATIONS_ONLY
    }

    private final AsyncPositionCursorScheduler asyncScheduler = new AsyncPositionCursorScheduler();
    private final ScanSessionStore sessions = new ScanSessionStore(this.asyncScheduler);
    private final DirtyRegionTracker dirtyRegions;

    private Object levelIdentity;
    private Object schematicIdentity;
    private RuntimeEpoch runtimeEpoch = RuntimeEpoch.INITIAL;
    private long snapshotRevision;
    private long generationSequence;
    private long tickTime = Long.MIN_VALUE;
    private long scanBudgetTickTime = Long.MIN_VALUE;
    private long globalScanBudgetUsedNanos;

    public ScanCache() {
        this(new DirtyRegionTracker());
    }

    public ScanCache(DirtyRegionTracker dirtyRegions) {
        this.dirtyRegions = dirtyRegions;
    }

    public void clear() {
        this.sessions.close();
        this.sessions.clearMetrics();
        this.levelIdentity = null;
        this.schematicIdentity = null;
        this.snapshotRevision = 0L;
        this.tickTime = Long.MIN_VALUE;
        this.scanBudgetTickTime = Long.MIN_VALUE;
        this.globalScanBudgetUsedNanos = 0L;
        this.dirtyRegions.clear();
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

        static ScanMetrics empty() {
            return EMPTY;
        }

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

    public void beginTick(ClientLevel level, WorldSchematic schematic, long tickTime, RuntimeEpoch epoch) {
        if (this.levelIdentity == level && this.schematicIdentity == schematic
                && this.tickTime == tickTime && this.runtimeEpoch.equals(epoch)) {
            return;
        }
        if (!this.runtimeEpoch.equals(epoch) || this.levelIdentity != level || this.schematicIdentity != schematic) {
            this.sessions.close();
            this.sessions.clearMetrics();
            this.dirtyRegions.clear();
            this.levelIdentity = level;
            this.schematicIdentity = schematic;
            this.runtimeEpoch = epoch;
            this.snapshotRevision++;
        }
        if (this.tickTime != tickTime) {
            this.sessions.resetMetrics();
        }
        this.tickTime = tickTime;
        if (this.scanBudgetTickTime != tickTime) {
            this.scanBudgetTickTime = tickTime;
            this.globalScanBudgetUsedNanos = 0L;
        }
    }

    public ScanMetrics metricsFor(String ownerKey) {
        return this.sessions.metricsFor(normalizeMetricsOwnerKey(ownerKey));
    }

    public void invalidate(BlockPos pos) {
        if (pos == null) {
            return;
        }
        this.snapshotRevision++;
        this.dirtyRegions.markDirty(pos);
        this.sessions.invalidate(pos);
    }

    public long dirtyVersion() {
        return this.dirtyRegions.currentVersion();
    }

    public DirtyRegionTracker.DirtySnapshot dirtySnapshotAfter(long lastSeenVersion, @Nullable PrinterBox bounds) {
        return this.dirtyRegions.snapshotAfter(lastSeenVersion, bounds);
    }

    public void resetOwner(String ownerKey) {
        if (ownerKey == null || ownerKey.isBlank()) {
            return;
        }
        this.sessions.resetOwner(ownerKey);
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
            private final WorldObservationPort observation = level == null
                    ? null
                    : new LiveWorldObservation(level, schematic);
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

                        Candidate candidate = session.next(this.observation, tickTime,
                                () -> !unbounded && isScanBudgetExceeded(budgetStart),
                                preFilter,
                                unbounded,
                                restartCompletedPass);
                        if (!session.belongsTo(runtimeEpoch)) {
                            break;
                        }
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
        boolean asynchronous = Configs.Core.ASYNC_SCAN.getBooleanValue();
        return this.sessions.getOrCreate(
                ownerKey,
                metrics,
                intent,
                sourceBounds,
                sourceBoxes,
                player,
                asynchronous,
                this.runtimeEpoch,
                () -> this.snapshotRevision,
                () -> ++this.generationSequence
        );
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
        return this.sessions.metrics(ownerKey);
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
