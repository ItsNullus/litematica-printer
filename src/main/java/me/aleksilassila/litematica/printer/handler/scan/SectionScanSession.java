package me.aleksilassila.litematica.printer.handler.scan;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.core.scan.ScanGeneration;
import me.aleksilassila.litematica.printer.core.scan.ScanHandle;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.LongSupplier;

/**
 * Owns one resumable, player-distance ordered scan pass.
 *
 * <p>The cache decides when a session may run and how much time it receives. This class only
 * owns traversal progress, invalidated positions and live world/schematic classification.</p>
 */
final class SectionScanSession {
    private static final int BUDGET_CHECK_INTERVAL = 8;
    private static final Direction[] DIRECTIONS = Direction.values();

    private final ScanIntent intent;
    private final MutableMetrics metrics;
    private final AsyncPositionCursorScheduler asyncScheduler;
    private Region region;
    private List<PrinterBox> sourceBoxes;
    private PositionCursor distanceCursor;
    private final boolean configuredAsync;
    private final RuntimeEpoch epoch;
    private final LongSupplier snapshotRevision;
    private final LongSupplier generationSequence;
    private boolean asynchronous;
    private long sourceRevision;
    private long cursorRevision;
    private long exhaustedUntilTick = Long.MIN_VALUE;
    private final PriorityQueue<DirtyPosition> dirtyPositions = new PriorityQueue<>();
    private final LongSet dirtyPositionKeys = new LongOpenHashSet();
    private boolean paused;
    private boolean closed;
    private ScanHandle scanHandle;
    private final BlockPos.MutableBlockPos liveMutable = new BlockPos.MutableBlockPos();
    private int lastChunkX = Integer.MIN_VALUE;
    private int lastChunkZ = Integer.MIN_VALUE;
    private boolean lastChunkLoaded;

    SectionScanSession(
            Region region,
            List<PrinterBox> sourceBoxes,
            ScanIntent intent,
            MutableMetrics metrics,
            AsyncPositionCursorScheduler asyncScheduler,
            boolean asynchronous,
            RuntimeEpoch epoch,
            LongSupplier snapshotRevision,
            LongSupplier generationSequence
    ) {
        this.region = region;
        this.sourceBoxes = List.copyOf(sourceBoxes);
        this.intent = intent;
        this.metrics = metrics;
        this.asyncScheduler = asyncScheduler;
        this.configuredAsync = asynchronous;
        this.epoch = epoch;
        this.snapshotRevision = snapshotRevision;
        this.generationSequence = generationSequence;
        this.asynchronous = asynchronous;
        this.scanHandle = this.createScanHandle();
        this.distanceCursor = this.createDistanceCursor();
    }

    SectionScanSession(
            Region region,
            List<PrinterBox> sourceBoxes,
            ScanIntent intent,
            MutableMetrics metrics
    ) {
        this(region, sourceBoxes, intent, metrics, null, false, RuntimeEpoch.INITIAL, () -> 0L, () -> 0L);
    }

    boolean canReuse(Region region) {
        return this.region.sameSectionWindow(region)
                && this.region.maxDistanceBand() == region.maxDistanceBand();
    }

    boolean usesAsyncTraversal() {
        return this.configuredAsync;
    }

    void updateRegion(Region region, List<PrinterBox> sourceBoxes) {
        boolean boxesChanged = !this.sourceBoxes.equals(sourceBoxes);
        // Compare the section window (16-block granularity), not the exact center. The center
        // follows the player's block position; bumping the revision for every single block of
        // movement made finishPass() rebuild the cursor from scratch on every pass, which
        // showed up as a permanent full rescan in the HUD. Within a section the in-flight
        // cursor is reused and fine center movement only shifts the reach shape slightly.
        boolean windowChanged = !this.region.sameSectionWindow(region);
        this.region = region;
        if (boxesChanged) {
            this.sourceBoxes = List.copyOf(sourceBoxes);
        }
        if (boxesChanged || windowChanged) {
            this.sourceRevision++;
        }
        // Keep the active cursor while the section window remains stable. Rebuilding it for every
        // block of player movement repeatedly walks the already-scanned prefix and starves the
        // edge of large ranges. A revision check at the end of this pass schedules exactly one
        // follow-up pass over the latest center and boxes before completion is reported.
    }

    boolean canScan(long tickTime, boolean restartCompletedPass) {
        if (this.closed) {
            return false;
        }
        if (this.exhaustedUntilTick == Long.MIN_VALUE) {
            return true;
        }
        if (tickTime < this.exhaustedUntilTick || !restartCompletedPass) {
            return false;
        }
        this.resetProgress();
        return true;
    }

    private void resetProgress() {
        this.rebuildDistanceCursor();
        this.dirtyPositions.clear();
        this.dirtyPositionKeys.clear();
    }

    private void rebuildDistanceCursor() {
        this.distanceCursor.close();
        this.scanHandle.close();
        this.scanHandle = this.createScanHandle();
        this.distanceCursor = this.createDistanceCursor();
        this.cursorRevision = this.sourceRevision;
        this.exhaustedUntilTick = Long.MIN_VALUE;
        this.lastChunkX = Integer.MIN_VALUE;
        this.lastChunkZ = Integer.MIN_VALUE;
        this.lastChunkLoaded = false;
    }

    private PositionCursor createDistanceCursor() {
        if (this.asynchronous) {
            return new AsyncPositionCursor(
                    this.asyncScheduler,
                    this.sourceBoxes,
                    this.region.centerX(),
                    this.region.centerY(),
                    this.region.centerZ(),
                    this.region.maxDistanceBand(),
                    this.scanHandle
            );
        }
        return new SynchronousPositionCursor(
                this.sourceBoxes,
                this.region.centerX(),
                this.region.centerY(),
                this.region.centerZ(),
                this.region.maxDistanceBand()
        );
    }

    boolean hasPendingSource(long tickTime, boolean restartCompletedPass) {
        return this.canScan(tickTime, restartCompletedPass)
                && (!this.dirtyPositions.isEmpty() || !this.distanceCursor.isComplete());
    }

    boolean wasPaused() {
        return this.paused;
    }

    boolean belongsTo(RuntimeEpoch epoch) {
        return !this.closed && this.epoch.equals(epoch) && this.scanHandle.accepts(this.scanHandle.generation());
    }

    boolean contains(BlockPos pos) {
        return containsAny(this.sourceBoxes, pos);
    }

    void invalidate(BlockPos pos) {
        this.addDirtyPosition(pos);
        if (this.intent == ScanIntent.FILL) {
            // Placement changes support availability for adjacent fill targets. Revisit those
            // cells after the server confirms the changed block. Fluid does not need this:
            // the server already broadcasts the neighboring block updates it produces, so the
            // explicit 6-neighbor expansion only multiplied dirty positions and kept
            // INVALIDATIONS_ONLY sessions permanently unpaused.
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
            WorldObservationPort observation,
            long tickTime,
            BooleanSupplier shouldPause,
            Predicate<BlockPos> preFilter,
            boolean unbounded,
            boolean restartCompletedPass
    ) {
        this.paused = false;
        if (!this.canScan(tickTime, restartCompletedPass)) {
            return null;
        }
        return this.nextByPlayerDistance(observation, tickTime, shouldPause, preFilter, unbounded);
    }

    private Candidate nextByPlayerDistance(
            WorldObservationPort observation,
            long tickTime,
            BooleanSupplier shouldPause,
            Predicate<BlockPos> preFilter,
            boolean unbounded
    ) {
        if (observation == null) {
            this.cursorRevision = this.sourceRevision;
            this.finishPass(tickTime);
            return null;
        }
        int scanned = 0;
        while (true) {
            if (!unbounded
                    && scanned > 0
                    && scanned % BUDGET_CHECK_INTERVAL == 0
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
                PositionCursor.PollResult pollResult = this.distanceCursor.poll(this.liveMutable);
                if (pollResult == PositionCursor.PollResult.PENDING) {
                    this.paused = true;
                    return null;
                }
                if (pollResult == PositionCursor.PollResult.FAILED) {
                    // A worker failure must never stop a feature. Restart this pass through the
                    // synchronous cursor and keep all live-world reads on the client thread.
                    this.asynchronous = false;
                    this.rebuildDistanceCursor();
                    continue;
                }
                if (pollResult == PositionCursor.PollResult.COMPLETE) {
                    if (this.finishPass(tickTime)) {
                        return null;
                    }
                    continue;
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
                this.lastChunkLoaded = observation.hasChunk(chunkX, chunkZ);
            }
            if (!this.lastChunkLoaded) {
                continue;
            }

            this.metrics.recordScannedSection(sectionKey(chunkX, sectionCoord(y), chunkZ));

            BlockState state = observation.worldState(this.liveMutable);
            this.metrics.scannedBlocks++;
            if (this.intent == ScanIntent.CUSTOM) {
                return new Candidate(new BlockPos(x, y, z), (byte) 0);
            }
            BlockState schematicState = this.intent == ScanIntent.PRINT
                    ? observation.schematicState(this.liveMutable)
                    : null;
            boolean hasFillSupport = this.intent == ScanIntent.FILL
                    && observation.hasFillSupport(this.liveMutable);
            byte flags = ScanClassifier.flags(
                    this.intent,
                    state,
                    schematicState,
                    hasFillSupport,
                    Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue()
            );
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

    private boolean finishPass(long tickTime) {
        if (this.cursorRevision != this.sourceRevision) {
            this.rebuildDistanceCursor();
            return false;
        }
        // Restart on the next client tick. Lazy admission is owned by the feature runtime and must count
        // real empty passes instead of coupling availability to the lazy admission window.
        this.exhaustedUntilTick = tickTime + 1;
        this.metrics.completedPasses++;
        return true;
    }

    private static boolean containsAny(List<PrinterBox> boxes, BlockPos pos) {
        for (PrinterBox box : boxes) {
            if (box.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    private static int sectionCoord(int blockCoord) {
        return blockCoord >> 4;
    }

    private static long sectionKey(int sectionX, int sectionY, int sectionZ) {
        return ((long) sectionX & 0x3FFFFFL) << 42
                | ((long) sectionZ & 0x3FFFFFL) << 20
                | ((long) sectionY & 0xFFFFFL);
    }

    private static long distanceSqr(BlockPos pos, int centerX, int centerY, int centerZ) {
        long dx = pos.getX() - (long) centerX;
        long dy = pos.getY() - (long) centerY;
        long dz = pos.getZ() - (long) centerZ;
        return dx * dx + dy * dy + dz * dz;
    }

    void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.scanHandle.close();
        this.distanceCursor.close();
        this.dirtyPositions.clear();
        this.dirtyPositionKeys.clear();
    }

    private ScanHandle createScanHandle() {
        return new ScanHandle(new ScanGeneration(
                this.epoch,
                this.sourceRevision,
                this.snapshotRevision.getAsLong(),
                this.generationSequence.getAsLong()
        ));
    }

    record Candidate(BlockPos pos, byte flags) {
        boolean acceptedByFlags(ScanIntent intent) {
            return intent.acceptsByFlags(this.flags);
        }
    }

    record Region(
            int centerX,
            int centerY,
            int centerZ,
            int minSectionX,
            int minSectionY,
            int minSectionZ,
            int maxSectionX,
            int maxSectionY,
            int maxSectionZ,
            int maxDistanceBand
    ) {
        static Region from(PrinterBox box, LocalPlayer player) {
            int centerX = player == null ? (box.minX + box.maxX) >> 1 : (int) Math.floor(player.getX());
            int centerY = player == null ? (box.minY + box.maxY) >> 1 : (int) Math.floor(player.getEyeY());
            int centerZ = player == null ? (box.minZ + box.maxZ) >> 1 : (int) Math.floor(player.getZ());
            int maxDistanceBand = farthestDistanceBand(box, centerX, centerY, centerZ);
            if (player != null) {
                Object shape = Configs.Core.ITERATOR_SHAPE.getOptionListValue();
                if (shape == RadiusShapeType.SPHERE || shape == RadiusShapeType.OCTAHEDRON) {
                    maxDistanceBand = Math.min(maxDistanceBand, ConfigUtils.getWorkRange() + 2);
                }
                if (Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.getBooleanValue()) {
                    int interactionBand = (int) Math.ceil(
                            PlayerUtils.getPlayerBlockInteractionRange(5.0D) + 3.0D
                    );
                    maxDistanceBand = Math.min(maxDistanceBand, interactionBand);
                }
            }
            return new Region(
                    centerX,
                    centerY,
                    centerZ,
                    sectionCoord(box.minX),
                    sectionCoord(box.minY),
                    sectionCoord(box.minZ),
                    sectionCoord(box.maxX),
                    sectionCoord(box.maxY),
                    sectionCoord(box.maxZ),
                    maxDistanceBand
            );
        }

        boolean sameSectionWindow(Region other) {
            return this.minSectionX == other.minSectionX
                    && this.minSectionY == other.minSectionY
                    && this.minSectionZ == other.minSectionZ
                    && this.maxSectionX == other.maxSectionX
                    && this.maxSectionY == other.maxSectionY
                    && this.maxSectionZ == other.maxSectionZ;
        }

        private static int farthestDistanceBand(PrinterBox box, int centerX, int centerY, int centerZ) {
            long dx = Math.max(Math.abs((long) box.minX - centerX), Math.abs((long) box.maxX - centerX));
            long dy = Math.max(Math.abs((long) box.minY - centerY), Math.abs((long) box.maxY - centerY));
            long dz = Math.max(Math.abs((long) box.minZ - centerZ), Math.abs((long) box.maxZ - centerZ));
            return (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz));
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

    static final class MutableMetrics {
        long scanNanos;
        int scannedBlocks;
        int scannedSections;
        private final LongSet scannedSectionKeys = new LongOpenHashSet();
        int sourceCandidates;
        int acceptedTargets;
        int budgetPauses;
        int completedPasses;

        void reset() {
            this.scanNanos = 0L;
            this.scannedBlocks = 0;
            this.scannedSections = 0;
            this.scannedSectionKeys.clear();
            this.sourceCandidates = 0;
            this.acceptedTargets = 0;
            this.budgetPauses = 0;
            this.completedPasses = 0;
        }

        ScanCache.ScanMetrics snapshot() {
            return new ScanCache.ScanMetrics(
                    this.scanNanos,
                    this.scannedBlocks,
                    this.scannedSections,
                    this.sourceCandidates,
                    this.acceptedTargets,
                    this.budgetPauses,
                    this.completedPasses
            );
        }

        void recordScannedSection(long sectionKey) {
            if (this.scannedSectionKeys.add(sectionKey)) {
                this.scannedSections++;
            }
        }
    }
}
