package me.aleksilassila.litematica.printer.handler.scan;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * Interleaves cached section candidates by distance instead of walking every coordinate in the
 * selection. Sections are loaded lazily; a section can only enter the ready heap when its lower
 * distance bound is no farther than the current candidate, so nearby sections are not allowed to
 * monopolize the stream.
 */
final class SectionCandidateCursor {
    private final List<Section> sections;
    private final ScanRegion region;
    private final List<PrinterBox> sourceBoxes;
    private final ScanIntent intent;
    private final boolean breakExtraBlocks;
    private final SectionSnapshotStore snapshots;
    private final WorldObservationPort source;
    private final Predicate<BlockPos> preFilter;
    private final PriorityQueue<SectionState> ready = new PriorityQueue<>(SectionState.ORDER);
    private int nextSection;
    private boolean complete;

    SectionCandidateCursor(
            ScanRegion region,
            List<PrinterBox> sourceBoxes,
            ScanIntent intent,
            boolean breakExtraBlocks,
            SectionSnapshotStore snapshots,
            WorldObservationPort source,
            Predicate<BlockPos> preFilter
    ) {
        this.region = region;
        this.sourceBoxes = sourceBoxes;
        this.intent = intent;
        this.breakExtraBlocks = breakExtraBlocks;
        this.snapshots = snapshots;
        this.source = source;
        this.preFilter = preFilter;
        this.sections = buildSections(sourceBoxes, region);
        this.complete = this.sections.isEmpty();
    }

    boolean isComplete() {
        return this.complete;
    }

    void reset() {
        this.ready.clear();
        this.nextSection = 0;
        this.complete = this.sections.isEmpty();
    }

    long peekDistanceSqr() {
        if (!this.prepare()) {
            return Long.MAX_VALUE;
        }
        return this.ready.peek().distanceSqr();
    }

    boolean next(BlockPos.MutableBlockPos target) {
        if (!this.prepare()) {
            return false;
        }
        SectionState state = this.ready.poll();
        target.set(state.x(), state.y(), state.z());
        state.advance();
        if (state.hasNext()) {
            this.ready.add(state);
        }
        return true;
    }

    private boolean prepare() {
        while (true) {
            while (this.nextSection < this.sections.size()
                    && (this.ready.isEmpty()
                    || this.sections.get(this.nextSection).lowerBound() <= this.ready.peek().distanceSqr())) {
                Section section = this.sections.get(this.nextSection++);
                if (!this.source.hasChunk(section.x(), section.z())
                        || !this.source.hasCandidatesInChunk(
                        this.intent, section.x(), section.z(), this.breakExtraBlocks)) {
                    continue;
                }
                short[] candidates = this.buildCandidates(section);
                SectionState state = new SectionState(section, candidates, this.region, this.sourceBoxes,
                        this.snapshots, this.source, this.intent, this.breakExtraBlocks);
                if (state.hasNext()) {
                    this.ready.add(state);
                }
            }
            if (!this.ready.isEmpty()) {
                return true;
            }
            this.complete = true;
            return false;
        }
    }

    private short[] buildCandidates(Section section) {
        short[] values = new short[16 * 16 * 16];
        int count = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int index = 0; index < values.length; index++) {
            pos.set(
                    (section.x() << 4) + (index & 15),
                    (section.y() << 4) + (index >>> 8 & 15),
                    (section.z() << 4) + (index >>> 4 & 15)
            );
            if (this.preFilter.test(pos)
                    && this.intent.shouldConsider(
                    this.snapshots.classify(pos, this.intent, this.breakExtraBlocks, this.source))) {
                values[count++] = (short) index;
            }
        }
        return java.util.Arrays.copyOf(values, count);
    }

    private static List<Section> buildSections(List<PrinterBox> boxes, ScanRegion region) {
        List<Section> result = new ArrayList<>();
        LongSet seen = new LongOpenHashSet();
        for (PrinterBox box : boxes) {
            for (int x = box.minX >> 4; x <= box.maxX >> 4; x++) {
                for (int y = box.minY >> 4; y <= box.maxY >> 4; y++) {
                    for (int z = box.minZ >> 4; z <= box.maxZ >> 4; z++) {
                        long key = ScanGeometry.sectionKey(x, y, z);
                        if (seen.add(key)) {
                            result.add(new Section(x, y, z, lowerBound(x, y, z, region)));
                        }
                    }
                }
            }
        }
        result.sort(Comparator.comparingLong(Section::lowerBound)
                .thenComparingInt(Section::x)
                .thenComparingInt(Section::y)
                .thenComparingInt(Section::z));
        return result;
    }

    private static long lowerBound(int sectionX, int sectionY, int sectionZ, ScanRegion region) {
        int minX = sectionX << 4;
        int minY = sectionY << 4;
        int minZ = sectionZ << 4;
        int maxX = minX + 15;
        int maxY = minY + 15;
        int maxZ = minZ + 15;
        long dx = axisDistance(region.centerX(), minX, maxX);
        long dy = axisDistance(region.centerY(), minY, maxY);
        long dz = axisDistance(region.centerZ(), minZ, maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static long axisDistance(int value, int min, int max) {
        if (value < min) return min - (long) value;
        if (value > max) return value - (long) max;
        return 0L;
    }

    private record Section(int x, int y, int z, long lowerBound) {
    }

    private static final class SectionState {
        private static final Comparator<SectionState> ORDER = Comparator
                .comparingLong(SectionState::distanceSqr)
                .thenComparingInt(SectionState::x)
                .thenComparingInt(SectionState::y)
                .thenComparingInt(SectionState::z);

        private final Section section;
        private final short[] candidates;
        private final ScanRegion region;
        private final List<PrinterBox> sourceBoxes;
        private final SectionSnapshotStore snapshots;
        private final WorldObservationPort source;
        private final ScanIntent intent;
        private final boolean breakExtraBlocks;
        private int index;
        private int x;
        private int y;
        private int z;
        private long distanceSqr;
        private boolean valid;

        private SectionState(
                Section section,
                short[] candidates,
                ScanRegion region,
                List<PrinterBox> sourceBoxes,
                SectionSnapshotStore snapshots,
                WorldObservationPort source,
                ScanIntent intent,
                boolean breakExtraBlocks
        ) {
            this.section = section;
            this.candidates = candidates;
            this.region = region;
            this.sourceBoxes = sourceBoxes;
            this.snapshots = snapshots;
            this.source = source;
            this.intent = intent;
            this.breakExtraBlocks = breakExtraBlocks;
            this.advance();
        }

        private boolean hasNext() {
            return this.valid;
        }

        private void advance() {
            while (this.index < this.candidates.length) {
                int local = this.candidates[this.index++] & 0xFFFF;
                this.x = (this.section.x() << 4) + (local & 15);
                this.z = (this.section.z() << 4) + (local >>> 4 & 15);
                this.y = (this.section.y() << 4) + (local >>> 8 & 15);
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(this.x, this.y, this.z);
                if (!ScanGeometry.containsAny(this.sourceBoxes, pos)
                        || ScanGeometry.distanceSqr(pos, this.region.centerX(), this.region.centerY(), this.region.centerZ())
                        > (long) this.region.maxDistanceBand() * this.region.maxDistanceBand()
                        || this.snapshots.classify(pos, this.intent, this.breakExtraBlocks, this.source) == 0) {
                    continue;
                }
                this.distanceSqr = ScanGeometry.distanceSqr(pos, this.region.centerX(), this.region.centerY(), this.region.centerZ());
                this.valid = true;
                return;
            }
            this.valid = false;
        }

        private long distanceSqr() {
            return this.distanceSqr;
        }

        private int x() {
            return this.x;
        }

        private int y() {
            return this.y;
        }

        private int z() {
            return this.z;
        }
    }
}
