package me.aleksilassila.litematica.printer.handler.scan;

import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.minecraft.BlockUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public final class ScanCache {
    public static final ScanCache INSTANCE = new ScanCache();

    private static final int BUDGET_CHECK_INTERVAL = 8;
    private static final int SECTION_SCAN_BUDGET_CHECK_INTERVAL = 8;
    private static final int OWNER_SCAN_BUDGET_PERCENT = 75;
    private static final Direction[] DIRECTIONS = Direction.values();

    private final Map<String, SectionScanSession> sessions = new HashMap<>();
    private final Map<String, MutableScanMetrics> scanMetrics = new HashMap<>();

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
            for (MutableScanMetrics metrics : this.scanMetrics.values()) {
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
        MutableScanMetrics metrics = this.scanMetrics.get(normalizeMetricsOwnerKey(ownerKey));
        return metrics == null ? ScanMetrics.EMPTY : metrics.snapshot();
    }

    public void invalidate(BlockPos pos) {
        if (pos == null) {
            return;
        }
        int sectionX = sectionCoord(pos.getX());
        int sectionY = sectionCoord(pos.getY());
        int sectionZ = sectionCoord(pos.getZ());
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
        return this.iterable(ownerKey, List.of(sourceBox), null, null, player, scanGuardLimit, ScanIntent.CUSTOM, pos -> true, preFilter, false);
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
        return this.iterable(ownerKey, List.of(sourceBox), level, schematic, player, Integer.MAX_VALUE, intent, exactPredicate, preFilter, true);
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
        return this.iterable(ownerKey, List.of(sourceBox), level, schematic, player, scanGuardLimit, intent, exactPredicate, preFilter, false);
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
        if (sourceBoxes == null || sourceBoxes.isEmpty()) {
            return List.of();
        }
        return this.iterable(ownerKey, sourceBoxes, level, schematic, player, scanGuardLimit, intent, exactPredicate, preFilter, false);
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
            boolean unbounded
    ) {
        int scanLimit = unbounded ? Integer.MAX_VALUE : this.getScanLimit(scanGuardLimit);
        String cacheOwnerKey = this.cacheOwnerKey(ownerKey, intent);
        String metricsOwnerKey = normalizeMetricsOwnerKey(ownerKey);
        MutableScanMetrics metrics = this.metrics(metricsOwnerKey);
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
                    while (this.considered < scanLimit && session.canScan(tickTime)) {
                        if (!unbounded && ++this.budgetChecks % BUDGET_CHECK_INTERVAL == 0 && isScanBudgetExceeded(budgetStart)) {
                            budgetHit = true;
                            break;
                        }

                        Candidate candidate = session.next(level, schematic, tickTime,
                                () -> !unbounded && isScanBudgetExceeded(budgetStart),
                                preFilter,
                                unbounded);
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
                this.scanLimitHit = session.hasPendingSource(tickTime) && (budgetHit || this.considered >= scanLimit);
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
            MutableScanMetrics metrics,
            ScanIntent intent,
            PrinterBox sourceBounds,
            List<PrinterBox> sourceBoxes,
            LocalPlayer player
    ) {
        SectionRegion region = SectionRegion.from(sourceBounds, player);
        String key = ownerKey + ":" + intent.name();
        SectionScanSession session = this.sessions.get(key);
        if (session == null || !session.canReuse(region, sourceBoxes)) {
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

    private void recordScanBudget(MutableScanMetrics metrics, long ownerBudgetStartNanos) {
        long elapsed = Math.max(0L, System.nanoTime() - ownerBudgetStartNanos);
        this.globalScanBudgetUsedNanos += elapsed;
        metrics.scanNanos += elapsed;
    }

    private MutableScanMetrics metrics(String ownerKey) {
        return this.scanMetrics.computeIfAbsent(ownerKey, key -> new MutableScanMetrics());
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

    private static int exhaustedRescanDelayTicks() {
        // A completed pass may restart on the next client tick.  Lazy admission is owned by
        // Module and must count real empty passes; coupling this delay to LAZY_ENTER_TICKS made
        // the session stay unavailable for the entire admission window.  The module then
        // entered LAZY without checking the world again, producing periodic scan/lazy bursts.
        return 1;
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

    private static long sectionKey(int sectionX, int sectionY, int sectionZ) {
        return ((long) sectionX & 0x3FFFFFL) << 42
                | ((long) sectionZ & 0x3FFFFFL) << 20
                | ((long) sectionY & 0xFFFFFL);
    }

    private static int sectionCoord(int blockCoord) {
        return blockCoord >> 4;
    }

    private static long distanceSqr(BlockPos pos, int centerX, int centerY, int centerZ) {
        long dx = pos.getX() - (long) centerX;
        long dy = pos.getY() - (long) centerY;
        long dz = pos.getZ() - (long) centerZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private record Candidate(BlockPos pos, byte flags) {
        boolean acceptedByFlags(ScanIntent intent) {
            return intent.acceptsByFlags(this.flags);
        }
    }

    private record SectionRegion(
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            int minSectionX,
            int minSectionY,
            int minSectionZ,
            int maxSectionX,
            int maxSectionY,
            int maxSectionZ,
            int centerX,
            int centerY,
            int centerZ,
            int centerSectionX,
            int centerSectionY,
            int centerSectionZ,
            int maxRadius
    ) {
        static SectionRegion from(PrinterBox box, LocalPlayer player) {
            int centerX = player == null ? (box.minX + box.maxX) >> 1 : (int) Math.floor(player.getX());
            int centerY = player == null ? (box.minY + box.maxY) >> 1 : (int) Math.floor(player.getEyeY());
            int centerZ = player == null ? (box.minZ + box.maxZ) >> 1 : (int) Math.floor(player.getZ());
            int centerSectionX = sectionCoord(centerX);
            int centerSectionY = sectionCoord(centerY);
            int centerSectionZ = sectionCoord(centerZ);
            int minSectionX = sectionCoord(box.minX);
            int minSectionY = sectionCoord(box.minY);
            int minSectionZ = sectionCoord(box.minZ);
            int maxSectionX = sectionCoord(box.maxX);
            int maxSectionY = sectionCoord(box.maxY);
            int maxSectionZ = sectionCoord(box.maxZ);
            int maxRadius = Math.max(
                    Math.max(Math.max(Math.abs(minSectionX - centerSectionX), Math.abs(maxSectionX - centerSectionX)),
                            Math.max(Math.abs(minSectionY - centerSectionY), Math.abs(maxSectionY - centerSectionY))),
                    Math.max(Math.abs(minSectionZ - centerSectionZ), Math.abs(maxSectionZ - centerSectionZ))
            );
            return new SectionRegion(
                    box.minX,
                    box.minY,
                    box.minZ,
                    box.maxX,
                    box.maxY,
                    box.maxZ,
                    minSectionX,
                    minSectionY,
                    minSectionZ,
                    maxSectionX,
                    maxSectionY,
                    maxSectionZ,
                    centerX,
                    centerY,
                    centerZ,
                    centerSectionX,
                    centerSectionY,
                    centerSectionZ,
                    maxRadius
            );
        }

        boolean containsSection(int sectionX, int sectionY, int sectionZ) {
            return sectionX >= this.minSectionX && sectionX <= this.maxSectionX
                    && sectionY >= this.minSectionY && sectionY <= this.maxSectionY
                    && sectionZ >= this.minSectionZ && sectionZ <= this.maxSectionZ;
        }

        boolean sameSectionWindow(SectionRegion other) {
            return this.minSectionX == other.minSectionX
                    && this.minSectionY == other.minSectionY
                    && this.minSectionZ == other.minSectionZ
                    && this.maxSectionX == other.maxSectionX
                    && this.maxSectionY == other.maxSectionY
                    && this.maxSectionZ == other.maxSectionZ;
        }

        boolean sameCenterSection(SectionRegion other) {
            return this.centerSectionX == other.centerSectionX
                    && this.centerSectionY == other.centerSectionY
                    && this.centerSectionZ == other.centerSectionZ;
        }

        boolean sameCenterBlock(SectionRegion other) {
            return this.centerX == other.centerX
                    && this.centerY == other.centerY
                    && this.centerZ == other.centerZ;
        }

        boolean sameBlockBounds(SectionRegion other) {
            return this.minX == other.minX
                    && this.minY == other.minY
                    && this.minZ == other.minZ
                    && this.maxX == other.maxX
                    && this.maxY == other.maxY
                    && this.maxZ == other.maxZ;
        }

        boolean containsBlock(BlockPos pos) {
            return pos.getX() >= this.minX && pos.getX() <= this.maxX
                    && pos.getY() >= this.minY && pos.getY() <= this.maxY
                    && pos.getZ() >= this.minZ && pos.getZ() <= this.maxZ;
        }

        boolean containsBlock(int x, int y, int z) {
            return x >= this.minX && x <= this.maxX
                    && y >= this.minY && y <= this.maxY
                    && z >= this.minZ && z <= this.maxZ;
        }
    }

    private final class SectionScanSession {
        private final ScanIntent intent;
        private final MutableScanMetrics metrics;
        private SectionRegion region;
        private List<PrinterBox> sourceBoxes;
        private PlayerDistanceCursor distanceCursor;
        private long exhaustedUntilTick = Long.MIN_VALUE;
        private final PriorityQueue<DirtyPosition> dirtyPositions = new PriorityQueue<>();
        private final LongSet dirtyPositionKeys = new LongOpenHashSet();
        private boolean paused;
        private final BlockPos.MutableBlockPos liveMutable = new BlockPos.MutableBlockPos();
        private final BlockPos.MutableBlockPos liveNeighbor = new BlockPos.MutableBlockPos();
        private int lastChunkX = Integer.MIN_VALUE;
        private int lastChunkZ = Integer.MIN_VALUE;
        private boolean lastChunkLoaded;

        private SectionScanSession(
                SectionRegion region,
                List<PrinterBox> sourceBoxes,
                ScanIntent intent,
                MutableScanMetrics metrics
        ) {
            this.region = region;
            this.sourceBoxes = List.copyOf(sourceBoxes);
            this.intent = intent;
            this.metrics = metrics;
            this.distanceCursor = new PlayerDistanceCursor(this.sourceBoxes, region);
        }

        boolean canReuse(SectionRegion region, List<PrinterBox> sourceBoxes) {
            // Keep the in-flight distance cursor while the scanned section window is stable.
            // The interaction/selection intersection normally moves by one block with the
            // player; rebuilding the cursor for every such movement repeatedly rescanned the
            // nearest blocks and could starve targets near the edge of a large work range.
            return this.region.sameSectionWindow(region);
        }

        void updateRegion(SectionRegion region, List<PrinterBox> sourceBoxes) {
            boolean boxesChanged = !this.sourceBoxes.equals(sourceBoxes);
            this.region = region;
            if (boxesChanged) {
                this.sourceBoxes = List.copyOf(sourceBoxes);
            }
            // Do not reset progress here. The current cursor may still contain positions from
            // the previous block-precise window; contains()/preFilter discard positions that
            // are no longer valid. Once the pass completes, resetProgress() starts the next
            // pass from the latest region and includes newly exposed positions.
        }

        boolean canScan(long tickTime) {
            if (this.exhaustedUntilTick == Long.MIN_VALUE) {
                return true;
            }
            if (tickTime < this.exhaustedUntilTick) {
                return false;
            }
            this.resetProgress();
            return true;
        }

        private void resetProgress() {
            this.distanceCursor = new PlayerDistanceCursor(this.sourceBoxes, this.region);
            this.exhaustedUntilTick = Long.MIN_VALUE;
            this.dirtyPositions.clear();
            this.dirtyPositionKeys.clear();
            this.lastChunkX = Integer.MIN_VALUE;
            this.lastChunkZ = Integer.MIN_VALUE;
            this.lastChunkLoaded = false;
        }

        boolean hasPendingSource(long tickTime) {
            return this.canScan(tickTime)
                    && (!this.dirtyPositions.isEmpty()
                    || !this.distanceCursor.complete);
        }

        boolean wasPaused() {
            return this.paused;
        }

        boolean contains(BlockPos pos) {
            return containsAny(this.sourceBoxes, pos);
        }

        void invalidate(BlockPos pos) {
            this.addDirtyPosition(pos);
            if (this.intent == ScanIntent.FILL) {
                for (Direction direction : DIRECTIONS) {
                    this.addDirtyPosition(pos.relative(direction));
                }
            }
            this.paused = false;
        }

        private void addDirtyPosition(BlockPos pos) {
            if (pos == null || !containsAny(this.sourceBoxes, pos)) {
                return;
            }
            this.exhaustedUntilTick = Long.MIN_VALUE;
            long key = ScanCache.key(pos);
            if (this.dirtyPositionKeys.add(key)) {
                this.dirtyPositions.add(new DirtyPosition(
                        pos.immutable(),
                        distanceSqr(pos, this.region.centerX(), this.region.centerY(), this.region.centerZ())
                ));
            }
        }

        Candidate next(
                ClientLevel level,
                WorldSchematic schematic,
                long tickTime,
                BooleanSupplier shouldPause,
                Predicate<BlockPos> preFilter,
                boolean unbounded
        ) {
            this.paused = false;
            if (!this.canScan(tickTime)) {
                return null;
            }
            return this.nextByPlayerDistance(level, schematic, tickTime, shouldPause, preFilter, unbounded);
        }

        private Candidate nextByPlayerDistance(
                ClientLevel level,
                WorldSchematic schematic,
                long tickTime,
                BooleanSupplier shouldPause,
                Predicate<BlockPos> preFilter,
                boolean unbounded
        ) {
            if (level == null) {
                this.markExhausted(tickTime);
                return null;
            }
            int scanned = 0;
            while (true) {
                if (!unbounded
                        && scanned > 0
                        && scanned % SECTION_SCAN_BUDGET_CHECK_INTERVAL == 0
                        && shouldPause.getAsBoolean()) {
                    this.paused = true;
                    return null;
                }

                BlockPos dirtyPos = this.pollDirtyPositionBefore(this.distanceCursor.peekDistanceSqr());
                int x;
                int y;
                int z;
                if (dirtyPos != null) {
                    x = dirtyPos.getX();
                    y = dirtyPos.getY();
                    z = dirtyPos.getZ();
                    this.liveMutable.set(x, y, z);
                } else {
                    if (!this.distanceCursor.next(this.liveMutable)) {
                        this.markExhausted(tickTime);
                        return null;
                    }
                    x = this.liveMutable.getX();
                    y = this.liveMutable.getY();
                    z = this.liveMutable.getZ();
                }
                scanned++;

                if (!preFilter.test(this.liveMutable)) {
                    continue;
                }

                int chunkX = sectionCoord(x);
                int chunkZ = sectionCoord(z);
                if (chunkX != this.lastChunkX || chunkZ != this.lastChunkZ) {
                    this.lastChunkX = chunkX;
                    this.lastChunkZ = chunkZ;
                    this.lastChunkLoaded = level.hasChunk(chunkX, chunkZ);
                }
                if (!this.lastChunkLoaded) {
                    continue;
                }

                this.metrics.recordScannedSection(sectionKey(chunkX, sectionCoord(y), chunkZ));

                BlockState state = level.getBlockState(this.liveMutable);
                this.metrics.scannedBlocks++;
                if (this.intent == ScanIntent.CUSTOM) {
                    return new Candidate(new BlockPos(x, y, z), (byte) 0);
                }
                byte flags = this.liveFlags(level, schematic, x, y, z, state);
                if (flags != 0) {
                    return new Candidate(new BlockPos(x, y, z), flags);
                }
            }
        }

        private BlockPos pollDirtyPositionBefore(long sourceDistanceSqr) {
            while (!this.dirtyPositions.isEmpty()) {
                DirtyPosition dirty = this.dirtyPositions.peek();
                if (dirty.distanceSqr() > sourceDistanceSqr) {
                    return null;
                }
                this.dirtyPositions.poll();
                BlockPos pos = dirty.pos();
                this.dirtyPositionKeys.remove(ScanCache.key(pos));
                if (containsAny(this.sourceBoxes, pos)) {
                    return pos;
                }
            }
            return null;
        }

        private void markExhausted(long tickTime) {
            this.exhaustedUntilTick = tickTime + exhaustedRescanDelayTicks();
            this.metrics.completedPasses++;
        }

        /**
         * 当场判定一个方块对 world intent 的候选标志,等价于旧缓存路径的 worldSolid/worldFluid/worldFillBase 归类。
         * 返回 0 表示「非该 intent 候选,跳过」(不发射,省去 exactPredicate 调用)。
         */
        private byte liveFlags(ClientLevel level, WorldSchematic schematic, int x, int y, int z, BlockState state) {
            switch (this.intent) {
                case PRINT -> {
                    if (schematic == null) {
                        return 0;
                    }
                    BlockState requiredState = schematic.getBlockState(this.liveMutable);
                    if (requiredState.equals(state)
                            && !(requiredState.getBlock() instanceof BaseRailBlock)) {
                        return 0;
                    }
                    if (!requiredState.isAir()) {
                        return (byte) (ScanFlags.SCHEMATIC_SAMPLED | ScanFlags.SCHEMATIC_NON_AIR);
                    }
                    if (Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue()
                            && !state.isAir()
                            && !(state.getBlock() instanceof LiquidBlock)) {
                        return (byte) (ScanFlags.SCHEMATIC_SAMPLED | ScanFlags.WORLD_NON_AIR);
                    }
                    return 0;
                }
                case MINE -> {
                    if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
                        return 0;
                    }
                    return ScanFlags.WORLD_NON_AIR;
                }
                case FLUID -> {
                    if (state.getFluidState().isEmpty()) {
                        return 0;
                    }
                    return (byte) (ScanFlags.WORLD_NON_AIR | ScanFlags.WORLD_FLUID);
                }
                case FILL -> {
                    boolean potential = state.isAir()
                            || state.getBlock() instanceof LiquidBlock
                            || BlockUtils.isReplaceable(state);
                    if (!potential || !this.hasFillSupportNeighborLive(level, x, y, z)) {
                        return 0;
                    }
                    return ScanFlags.BASE_FILL_TARGET;
                }
                default -> {
                    return 0;
                }
            }
        }

        private boolean hasFillSupportNeighborLive(ClientLevel level, int x, int y, int z) {
            for (Direction direction : DIRECTIONS) {
                this.liveNeighbor.set(
                        x + direction.getStepX(),
                        y + direction.getStepY(),
                        z + direction.getStepZ()
                );
                BlockState neighbor = level.getBlockState(this.liveNeighbor);
                if (!neighbor.isAir()
                        && !(neighbor.getBlock() instanceof LiquidBlock)
                        && !BlockUtils.isReplaceable(neighbor)) {
                    return true;
                }
            }
            return false;
        }

    }

    private static final class PlayerDistanceCursor {
        private final List<PrinterBox> boxes;
        private final PriorityQueue<BoxCursorNode> cursors;
        private boolean complete;

        private PlayerDistanceCursor(List<PrinterBox> boxes, SectionRegion region) {
            this.boxes = boxes;
            this.cursors = new PriorityQueue<>();
            for (int index = 0; index < boxes.size(); index++) {
                BoxDistanceCursor cursor = new BoxDistanceCursor(
                        boxes.get(index),
                        region.centerX(),
                        region.centerY(),
                        region.centerZ()
                );
                BlockPos.MutableBlockPos first = new BlockPos.MutableBlockPos();
                if (cursor.next(first)) {
                    this.cursors.add(new BoxCursorNode(
                            index,
                            cursor,
                            first.getX(),
                            first.getY(),
                            first.getZ(),
                            region
                    ));
                }
            }
            this.complete = this.cursors.isEmpty();
        }

        long peekDistanceSqr() {
            BoxCursorNode node = this.cursors.peek();
            return node == null ? Long.MAX_VALUE : node.distanceSqr();
        }

        boolean next(BlockPos.MutableBlockPos target) {
            while (!this.cursors.isEmpty()) {
                BoxCursorNode node = this.cursors.poll();
                int resultX = node.x;
                int resultY = node.y;
                int resultZ = node.z;
                if (node.cursor.next(node.following)) {
                    node.x = node.following.getX();
                    node.y = node.following.getY();
                    node.z = node.following.getZ();
                    this.cursors.add(node);
                }
                if (this.claimedByEarlierBox(node.boxIndex, resultX, resultY, resultZ)) {
                    continue;
                }
                target.set(resultX, resultY, resultZ);
                return true;
            }
            this.complete = true;
            return false;
        }

        private boolean claimedByEarlierBox(int boxIndex, int x, int y, int z) {
            for (int index = 0; index < boxIndex; index++) {
                PrinterBox box = this.boxes.get(index);
                if (x >= box.minX && x <= box.maxX
                        && y >= box.minY && y <= box.maxY
                        && z >= box.minZ && z <= box.maxZ) {
                    return true;
                }
            }
            return false;
        }

        private static final class BoxCursorNode implements Comparable<BoxCursorNode> {
            private final int boxIndex;
            private final BoxDistanceCursor cursor;
            private final int centerX;
            private final int centerY;
            private final int centerZ;
            private final BlockPos.MutableBlockPos following = new BlockPos.MutableBlockPos();
            private int x;
            private int y;
            private int z;

            private BoxCursorNode(
                    int boxIndex,
                    BoxDistanceCursor cursor,
                    int x,
                    int y,
                    int z,
                    SectionRegion region
            ) {
                this.boxIndex = boxIndex;
                this.cursor = cursor;
                this.x = x;
                this.y = y;
                this.z = z;
                this.centerX = region.centerX();
                this.centerY = region.centerY();
                this.centerZ = region.centerZ();
            }

            @Override
            public int compareTo(BoxCursorNode other) {
                int result = Long.compare(this.distanceSqr(), other.distanceSqr());
                if (result != 0) {
                    return result;
                }
                result = Integer.compare(this.x, other.x);
                if (result != 0) {
                    return result;
                }
                result = Integer.compare(this.y, other.y);
                if (result != 0) {
                    return result;
                }
                result = Integer.compare(this.z, other.z);
                if (result != 0) {
                    return result;
                }
                return Integer.compare(this.boxIndex, other.boxIndex);
            }

            private long distanceSqr() {
                long dx = this.x - (long) this.centerX;
                long dy = this.y - (long) this.centerY;
                long dz = this.z - (long) this.centerZ;
                return dx * dx + dy * dy + dz * dz;
            }
        }
    }

    private record DirtyPosition(BlockPos pos, long distanceSqr) implements Comparable<DirtyPosition> {
        @Override
        public int compareTo(DirtyPosition other) {
            int result = Long.compare(this.distanceSqr, other.distanceSqr);
            if (result != 0) {
                return result;
            }
            result = Integer.compare(this.pos.getX(), other.pos.getX());
            if (result != 0) {
                return result;
            }
            result = Integer.compare(this.pos.getY(), other.pos.getY());
            if (result != 0) {
                return result;
            }
            return Integer.compare(this.pos.getZ(), other.pos.getZ());
        }
    }

    /**
     * 单个扫描盒内按玩家位置的全局平方距离遍历方块。
     *
     * 三个轴分别按离玩家中心的距离排序，再用一个无 visited 集合的最小堆归并笛卡尔积。
     * 每个三轴索引组合只有唯一父节点，因此不会重复，也不需要为整个扫描盒保存访问标记。
     */
    private static final class BoxDistanceCursor {
        private static final int STATE_BITS = 21;
        private static final long STATE_MASK = (1L << STATE_BITS) - 1L;

        private final int[] xCoordinates;
        private final int[] yCoordinates;
        private final int[] zCoordinates;
        private final long[] xDistanceSqr;
        private final long[] yDistanceSqr;
        private final long[] zDistanceSqr;
        private long[] heap = new long[64];
        private int heapSize;
        private boolean complete;

        private BoxDistanceCursor(PrinterBox box, int centerX, int centerY, int centerZ) {
            this.xCoordinates = buildAxisCoordinates(box.minX, box.maxX, centerX);
            this.yCoordinates = buildAxisCoordinates(box.minY, box.maxY, centerY);
            this.zCoordinates = buildAxisCoordinates(box.minZ, box.maxZ, centerZ);
            this.xDistanceSqr = buildAxisDistances(this.xCoordinates, centerX);
            this.yDistanceSqr = buildAxisDistances(this.yCoordinates, centerY);
            this.zDistanceSqr = buildAxisDistances(this.zCoordinates, centerZ);
            if (this.xCoordinates.length == 0 || this.yCoordinates.length == 0 || this.zCoordinates.length == 0) {
                this.complete = true;
            } else {
                this.push(packState(0, 0, 0));
            }
        }

        boolean next(BlockPos.MutableBlockPos target) {
            if (this.complete) {
                return false;
            }
            if (this.heapSize == 0) {
                this.complete = true;
                return false;
            }

            long state = this.pop();
            int xIndex = xIndex(state);
            int yIndex = yIndex(state);
            int zIndex = zIndex(state);

            if (xIndex + 1 < this.xCoordinates.length) {
                this.push(packState(xIndex + 1, yIndex, zIndex));
            }
            if (xIndex == 0 && yIndex + 1 < this.yCoordinates.length) {
                this.push(packState(0, yIndex + 1, zIndex));
            }
            if (xIndex == 0 && yIndex == 0 && zIndex + 1 < this.zCoordinates.length) {
                this.push(packState(0, 0, zIndex + 1));
            }

            target.set(
                    this.xCoordinates[xIndex],
                    this.yCoordinates[yIndex],
                    this.zCoordinates[zIndex]
            );
            return true;
        }

        private void push(long state) {
            if (this.heapSize >= this.heap.length) {
                long[] expanded = new long[this.heap.length << 1];
                System.arraycopy(this.heap, 0, expanded, 0, this.heap.length);
                this.heap = expanded;
            }
            int index = this.heapSize++;
            while (index > 0) {
                int parent = (index - 1) >>> 1;
                long parentState = this.heap[parent];
                if (this.compare(parentState, state) <= 0) {
                    break;
                }
                this.heap[index] = parentState;
                index = parent;
            }
            this.heap[index] = state;
        }

        private long pop() {
            long result = this.heap[0];
            long tail = this.heap[--this.heapSize];
            if (this.heapSize == 0) {
                return result;
            }

            int index = 0;
            int half = this.heapSize >>> 1;
            while (index < half) {
                int left = (index << 1) + 1;
                int right = left + 1;
                int child = left;
                if (right < this.heapSize && this.compare(this.heap[right], this.heap[left]) < 0) {
                    child = right;
                }
                if (this.compare(tail, this.heap[child]) <= 0) {
                    break;
                }
                this.heap[index] = this.heap[child];
                index = child;
            }
            this.heap[index] = tail;
            return result;
        }

        private int compare(long left, long right) {
            long leftDistance = this.distanceSqr(left);
            long rightDistance = this.distanceSqr(right);
            int result = Long.compare(leftDistance, rightDistance);
            if (result != 0) {
                return result;
            }

            long leftMaxAxisDistance = this.maxAxisDistanceSqr(left);
            long rightMaxAxisDistance = this.maxAxisDistanceSqr(right);
            result = Long.compare(leftMaxAxisDistance, rightMaxAxisDistance);
            if (result != 0) {
                return result;
            }
            return Long.compareUnsigned(left, right);
        }

        private long distanceSqr(long state) {
            return this.xDistanceSqr[xIndex(state)]
                    + this.yDistanceSqr[yIndex(state)]
                    + this.zDistanceSqr[zIndex(state)];
        }

        private long maxAxisDistanceSqr(long state) {
            return Math.max(
                    Math.max(this.xDistanceSqr[xIndex(state)], this.yDistanceSqr[yIndex(state)]),
                    this.zDistanceSqr[zIndex(state)]
            );
        }

        private static int[] buildAxisCoordinates(int min, int max, int center) {
            if (max < min) {
                return new int[0];
            }
            int[] coordinates = new int[max - min + 1];
            int pivot = Math.max(min, Math.min(max, center));
            int left = pivot;
            int right = pivot + 1;
            int index = 0;
            while (left >= min || right <= max) {
                if (left < min) {
                    coordinates[index++] = right++;
                    continue;
                }
                if (right > max) {
                    coordinates[index++] = left--;
                    continue;
                }
                long leftDistance = axisDistanceSqr(left, center);
                long rightDistance = axisDistanceSqr(right, center);
                if (leftDistance <= rightDistance) {
                    coordinates[index++] = left--;
                } else {
                    coordinates[index++] = right++;
                }
            }
            return coordinates;
        }

        private static long[] buildAxisDistances(int[] coordinates, int center) {
            long[] distances = new long[coordinates.length];
            for (int index = 0; index < coordinates.length; index++) {
                distances[index] = axisDistanceSqr(coordinates[index], center);
            }
            return distances;
        }

        private static long axisDistanceSqr(int coordinate, int center) {
            long delta = coordinate - (long) center;
            return delta * delta;
        }

        private static long packState(int xIndex, int yIndex, int zIndex) {
            return (long) xIndex << STATE_BITS * 2
                    | (long) yIndex << STATE_BITS
                    | zIndex;
        }

        private static int xIndex(long state) {
            return (int) (state >>> STATE_BITS * 2);
        }

        private static int yIndex(long state) {
            return (int) (state >>> STATE_BITS & STATE_MASK);
        }

        private static int zIndex(long state) {
            return (int) (state & STATE_MASK);
        }
    }

    private static final class MutableScanMetrics {
        private long scanNanos;
        private int scannedBlocks;
        private int scannedSections;
        private final LongSet scannedSectionKeys = new LongOpenHashSet();
        private int sourceCandidates;
        private int acceptedTargets;
        private int budgetPauses;
        private int completedPasses;

        private void reset() {
            this.scanNanos = 0L;
            this.scannedBlocks = 0;
            this.scannedSections = 0;
            this.scannedSectionKeys.clear();
            this.sourceCandidates = 0;
            this.acceptedTargets = 0;
            this.budgetPauses = 0;
            this.completedPasses = 0;
        }

        private ScanMetrics snapshot() {
            return new ScanMetrics(
                    this.scanNanos,
                    this.scannedBlocks,
                    this.scannedSections,
                    this.sourceCandidates,
                    this.acceptedTargets,
                    this.budgetPauses,
                    this.completedPasses
            );
        }

        private void recordScannedSection(long sectionKey) {
            if (this.scannedSectionKeys.add(sectionKey)) {
                this.scannedSections++;
            }
        }
    }

}
