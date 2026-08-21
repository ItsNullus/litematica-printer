package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.core.scan.ScanGeneration;
import me.aleksilassila.litematica.printer.core.scan.ScanHandle;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncPositionCursorTest {
    @Test
    void asynchronousTraversalMatchesSynchronousOrderAndOverlapDeduplication() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            List<PrinterBox> boxes = List.of(
                    new PrinterBox(-6, 10, -5, 8, 13, 7),
                    new PrinterBox(2, 11, 1, 12, 14, 10)
            );
            List<BlockPos> expected = drain(new SynchronousPositionCursor(boxes, 1, 12, 2, 24));

            try (AsyncPositionCursorScheduler scheduler = new AsyncPositionCursorScheduler();
                 AsyncPositionCursor cursor = new AsyncPositionCursor(scheduler, boxes, 1, 12, 2, 24)) {
                assertEquals(expected, drain(cursor));
            }
        });
    }

    @Test
    void closedSchedulerMakesCursorFailInsteadOfHanging() {
        AsyncPositionCursorScheduler scheduler = new AsyncPositionCursorScheduler();
        scheduler.close();
        try (AsyncPositionCursor cursor = new AsyncPositionCursor(
                scheduler,
                List.of(new PrinterBox(0, 0, 0, 2, 2, 2)),
                0, 0, 0, 8
        )) {
            assertEquals(PositionCursor.PollResult.FAILED, cursor.poll(new BlockPos.MutableBlockPos()));
        }
    }

    @Test
    void cancelledGenerationCannotEmitQueuedCoordinates() {
        ScanHandle handle = new ScanHandle(new ScanGeneration(RuntimeEpoch.INITIAL, 1L, 2L, 3L));
        try (AsyncPositionCursorScheduler scheduler = new AsyncPositionCursorScheduler();
             AsyncPositionCursor cursor = new AsyncPositionCursor(
                     scheduler,
                     List.of(new PrinterBox(-16, 0, -16, 16, 2, 16)),
                     0, 1, 0, 32, handle
             )) {
            handle.close();
            assertEquals(PositionCursor.PollResult.COMPLETE, cursor.poll(new BlockPos.MutableBlockPos()));
        }
    }

    private static List<BlockPos> drain(PositionCursor cursor) {
        List<BlockPos> result = new ArrayList<>();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        int pendingSpins = 0;
        while (true) {
            PositionCursor.PollResult poll = cursor.poll(mutable);
            if (poll == PositionCursor.PollResult.AVAILABLE) {
                result.add(mutable.immutable());
                pendingSpins = 0;
                continue;
            }
            if (poll == PositionCursor.PollResult.COMPLETE) {
                return result;
            }
            assertTrue(poll != PositionCursor.PollResult.FAILED, "asynchronous traversal failed");
            assertTrue(++pendingSpins < 1_000_000, "asynchronous traversal did not make progress");
            Thread.onSpinWait();
        }
    }
}
