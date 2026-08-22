package me.aleksilassila.litematica.printer.handler.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionSnapshotStoreTest {
    @Test
    void unchangedClassificationIsObservedOnlyOnce() {
        SectionSnapshotStore store = new SectionSnapshotStore();
        CountingObservation source = new CountingObservation();
        BlockPos pos = new BlockPos(3, 20, 5);

        store.classify(pos, ScanIntent.PRINT, false, source);
        store.classify(pos, ScanIntent.PRINT, false, source);
        assertEquals(1, source.worldReads);
        assertEquals(1, source.schematicReads);

        store.invalidateWorld(pos);
        store.classify(pos, ScanIntent.PRINT, false, source);
        assertEquals(2, source.worldReads);
        assertEquals(2, source.schematicReads);
    }

    @Test
    void neighborInvalidationRefreshesSupportDependency() {
        SectionSnapshotStore store = new SectionSnapshotStore();
        CountingObservation source = new CountingObservation();
        BlockPos target = new BlockPos(15, 31, 15);

        store.classify(target, ScanIntent.FILL, false, source);
        store.classify(target, ScanIntent.FILL, false, source);
        assertEquals(1, source.supportReads);

        store.invalidateWorld(target.east());
        store.classify(target, ScanIntent.FILL, false, source);
        assertEquals(2, source.supportReads);
    }

    @Test
    void publishesDefensiveCompactSnapshot() {
        SectionSnapshotStore store = new SectionSnapshotStore();
        CountingObservation source = new CountingObservation();
        BlockPos pos = new BlockPos(1, 2, 3);
        byte expected = store.classify(pos, ScanIntent.PRINT, false, source);

        long key = ScanGeometry.sectionKey(0, 0, 0);
        SectionSnapshot snapshot = store.snapshot(key);
        assertNotNull(snapshot);
        int index = (pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | pos.getX() & 15;
        assertTrue(snapshot.isObserved(ScanIntent.PRINT, index));
        assertEquals(expected, snapshot.flags(ScanIntent.PRINT, index));
        snapshot.flags()[ScanIntent.PRINT.ordinal()][index] = 0;
        assertEquals(expected, store.classify(pos, ScanIntent.PRINT, false, source));
        assertEquals(1, source.worldReads);
    }

    private static final class CountingObservation implements WorldObservationPort {
        private int worldReads;
        private int schematicReads;
        private int supportReads;

        @Override public boolean hasChunk(int chunkX, int chunkZ) { return true; }

        @Override
        public BlockState worldState(BlockPos pos) {
            this.worldReads++;
            return Blocks.STONE.defaultBlockState();
        }

        @Override
        public BlockState schematicState(BlockPos pos) {
            this.schematicReads++;
            return Blocks.GLASS.defaultBlockState();
        }

        @Override
        public boolean hasFillSupport(BlockPos pos) {
            this.supportReads++;
            return true;
        }
    }
}
