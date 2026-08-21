package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanPassPolicyTest {
    @Test
    void completedPassCanWaitForInvalidationWithoutRestarting() {
        SectionScanSession session = session();

        assertNull(session.next(null, 10L, () -> false, pos -> true, false, true));
        assertFalse(session.canScan(11L, false));

        session.invalidate(new BlockPos(0, 0, 0));
        assertTrue(session.canScan(11L, false));
    }

    @Test
    void normalPolicyRestartsACompletedPassOnTheNextTick() {
        SectionScanSession session = session();

        assertNull(session.next(null, 20L, () -> false, pos -> true, false, true));
        assertFalse(session.canScan(20L, true));
        assertTrue(session.canScan(21L, true));
    }

    @Test
    void closedSessionCannotBeRevivedByAnOldIterator() {
        SectionScanSession session = session();

        session.close();
        session.invalidate(new BlockPos(0, 0, 0));

        assertFalse(session.canScan(30L, true));
        assertFalse(session.hasPendingSource(30L, true));
    }

    private static SectionScanSession session() {
        PrinterBox box = new PrinterBox(-1, -1, -1, 1, 1, 1);
        SectionScanSession.Region region = SectionScanSession.Region.from(box, null);
        return new SectionScanSession(
                region,
                List.of(box),
                ScanIntent.FLUID,
                new SectionScanSession.MutableMetrics()
        );
    }
}
