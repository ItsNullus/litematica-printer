package me.aleksilassila.litematica.printer.handler.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.List;
import java.util.function.Predicate;


/**
 * Compact incremental classification cache.
 *
 * <p>The scanner only consumes classification flags. Retaining two 4096-entry BlockState arrays
 * per visited section made large schematic selections retain hundreds of megabytes, so this
 * store keeps one byte per observed position and intent plus an observed bitset.</p>
 */
final class SectionSnapshotStore {
    private static final Direction[] DIRECTIONS = Direction.values();

    private final Long2ObjectOpenHashMap<MutableSection> sections = new Long2ObjectOpenHashMap<>();

    byte classify(BlockPos pos, ScanIntent intent, boolean breakExtraBlocks, WorldObservationPort source) {
        MutableSection section = this.section(pos);
        int index = index(pos);
        int cacheSlot = cacheSlot(intent, breakExtraBlocks);
        if (!section.isObserved(cacheSlot, index)) {
            section.put(cacheSlot, index, source.classify(intent, pos, breakExtraBlocks));
        }
        return section.flags(cacheSlot, index);
    }

    boolean hasCompleteCandidates(
            List<me.aleksilassila.litematica.printer.printer.PrinterBox> boxes,
            ScanIntent intent,
            boolean breakExtraBlocks,
            Predicate<BlockPos> preFilter,
            WorldObservationPort source
    ) {
        int cacheSlot = cacheSlot(intent, breakExtraBlocks);
        for (me.aleksilassila.litematica.printer.printer.PrinterBox box : boxes) {
            for (int x = box.minX >> 4; x <= box.maxX >> 4; x++) {
                for (int y = box.minY >> 4; y <= box.maxY >> 4; y++) {
                    for (int z = box.minZ >> 4; z <= box.maxZ >> 4; z++) {
                        if (!source.hasChunk(x, z)
                                || !source.hasCandidatesInChunk(intent, x, z, breakExtraBlocks)) {
                            continue;
                        }
                        MutableSection section = this.sections.get(ScanGeometry.sectionKey(x, y, z));
                        if (section == null || !section.isComplete(cacheSlot, x, y, z, preFilter)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    void invalidateWorld(BlockPos pos) {
        if (pos == null) return;
        this.invalidateCell(pos);
        for (Direction direction : DIRECTIONS) this.invalidateCell(pos.relative(direction));
    }

    void clear() {
        this.sections.clear();
    }

    int cachedSectionCount() {
        return this.sections.size();
    }

    private MutableSection section(BlockPos pos) {
        long key = ScanGeometry.sectionKey(
                ScanGeometry.sectionCoord(pos.getX()),
                ScanGeometry.sectionCoord(pos.getY()),
                ScanGeometry.sectionCoord(pos.getZ())
        );
        return this.sections.computeIfAbsent(key, ignored -> new MutableSection());
    }

    private void invalidateCell(BlockPos pos) {
        long key = ScanGeometry.sectionKey(
                ScanGeometry.sectionCoord(pos.getX()),
                ScanGeometry.sectionCoord(pos.getY()),
                ScanGeometry.sectionCoord(pos.getZ())
        );
        MutableSection section = this.sections.get(key);
        if (section != null) section.invalidate(index(pos));
    }

    private static int cacheSlot(ScanIntent intent, boolean breakExtraBlocks) {
        return intent == ScanIntent.PRINT && breakExtraBlocks ? ScanIntent.values().length : intent.ordinal();
    }

    private static int index(BlockPos pos) {
        return (pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | pos.getX() & 15;
    }

    private static final class MutableSection {
        private static final int SECTION_VOLUME = 16 * 16 * 16;
        private static final int CACHE_SLOTS = ScanIntent.values().length + 1;
        private static final int OBSERVED_WORDS = SECTION_VOLUME / Long.SIZE;
        private final byte[][] flags = new byte[CACHE_SLOTS][];
        private final long[][] observed = new long[CACHE_SLOTS][];

        private void ensureSlot(int slot) {
            if (this.flags[slot] == null) {
                this.flags[slot] = new byte[SECTION_VOLUME];
                this.observed[slot] = new long[OBSERVED_WORDS];
            }
        }

        private boolean isObserved(int slot, int index) {
            if (this.observed[slot] == null) return false;
            long bit = 1L << (index & 63);
            int word = index >>> 6;
            return (this.observed[slot][word] & bit) != 0L;
        }

        private void put(int slot, int index, byte value) {
            this.ensureSlot(slot);
            this.flags[slot][index] = value;
            this.observed[slot][index >>> 6] |= 1L << (index & 63);
        }

        private byte flags(int slot, int index) {
            return this.flags[slot][index];
        }

        private void invalidate(int index) {
            long clearMask = ~(1L << (index & 63));
            int word = index >>> 6;
            for (int slot = 0; slot < CACHE_SLOTS; slot++) {
                if (this.observed[slot] != null) {
                    this.observed[slot][word] &= clearMask;
                    this.flags[slot][index] = 0;
                }
            }
        }

        private boolean isComplete(
                int cacheSlot,
                int sectionX,
                int sectionY,
                int sectionZ,
                Predicate<BlockPos> preFilter
        ) {
            if (this.observed[cacheSlot] == null) {
                return false;
            }
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int baseX = sectionX << 4;
            int baseY = sectionY << 4;
            int baseZ = sectionZ << 4;
            for (int index = 0; index < SECTION_VOLUME; index++) {
                int x = baseX + (index & 15);
                int z = baseZ + (index >>> 4 & 15);
                int y = baseY + (index >>> 8 & 15);
                pos.set(x, y, z);
                if (preFilter.test(pos) && !this.isObserved(cacheSlot, index)) {
                    return false;
                }
            }
            return true;
        }

    }
}
