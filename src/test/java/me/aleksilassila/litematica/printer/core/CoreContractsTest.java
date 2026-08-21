package me.aleksilassila.litematica.printer.core;

import me.aleksilassila.litematica.printer.core.action.ActionRequest;
import me.aleksilassila.litematica.printer.core.action.ActionResult;
import me.aleksilassila.litematica.printer.core.action.ConfirmationPolicy;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.core.action.RetryPolicy;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.core.scan.ScanBatch;
import me.aleksilassila.litematica.printer.core.scan.ScanCoordinate;
import me.aleksilassila.litematica.printer.core.scan.ScanGeneration;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreContractsTest {
    @Test
    void actionContractValuesAndValidationAreStable() {
        assertEquals(8, ActionResult.values().length);
        assertEquals(4, ConfirmationPolicy.values().length);
        assertEquals(5, ResourceLease.values().length);
        assertEquals(new RetryPolicy(1, 0), RetryPolicy.NONE);
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, -1));

        ActionRequest empty = new ActionRequest(
                "scan", RuntimeEpoch.INITIAL, Set.of(), 0L, ConfirmationPolicy.NONE, RetryPolicy.NONE);
        assertTrue(empty.resources().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ActionRequest(
                " ", RuntimeEpoch.INITIAL, Set.of(), 0L, ConfirmationPolicy.NONE, RetryPolicy.NONE));
        assertThrows(NullPointerException.class, () -> new ActionRequest(
                null, RuntimeEpoch.INITIAL, Set.of(), 0L, ConfirmationPolicy.NONE, RetryPolicy.NONE));
        assertThrows(NullPointerException.class, () -> new ActionRequest(
                "scan", null, Set.of(), 0L, ConfirmationPolicy.NONE, RetryPolicy.NONE));
        assertThrows(NullPointerException.class, () -> new ActionRequest(
                "scan", RuntimeEpoch.INITIAL, null, 0L, ConfirmationPolicy.NONE, RetryPolicy.NONE));
        assertThrows(NullPointerException.class, () -> new ActionRequest(
                "scan", RuntimeEpoch.INITIAL, Set.of(), 0L, null, RetryPolicy.NONE));
        assertThrows(NullPointerException.class, () -> new ActionRequest(
                "scan", RuntimeEpoch.INITIAL, Set.of(), 0L, ConfirmationPolicy.NONE, null));

        EnumSet<ResourceLease> mutable = EnumSet.of(ResourceLease.LOOK);
        ActionRequest copied = new ActionRequest(
                "print", RuntimeEpoch.INITIAL, mutable, 0L, ConfirmationPolicy.CLIENT_STATE, RetryPolicy.NONE);
        mutable.add(ResourceLease.MAIN_HAND);
        assertEquals(Set.of(ResourceLease.LOOK), copied.resources());
    }

    @Test
    void scanTransferContractsRejectInvalidGenerationsAndCopyBatches() {
        RuntimeEpoch epoch = new RuntimeEpoch(3L);
        assertThrows(IllegalArgumentException.class, () -> new ScanGeneration(null, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ScanGeneration(epoch, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ScanGeneration(epoch, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ScanGeneration(epoch, 0, 0, -1));

        ScanGeneration generation = new ScanGeneration(epoch, 1, 2, 3);
        List<ScanCoordinate> mutable = new java.util.ArrayList<>();
        mutable.add(new ScanCoordinate(1, 2, 3, 14));
        ScanBatch batch = new ScanBatch(generation, mutable, true);
        mutable.clear();
        assertEquals(1, batch.coordinates().size());
        assertTrue(batch.complete());
        assertThrows(IllegalArgumentException.class, () -> new ScanBatch(null, List.of(), false));
        assertThrows(NullPointerException.class, () -> new ScanBatch(generation, null, false));
    }
}
