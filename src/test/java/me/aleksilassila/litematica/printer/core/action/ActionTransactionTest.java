package me.aleksilassila.litematica.printer.core.action;

import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionTransactionTest {
    @Test
    void confirmedActionFollowsOneExplicitLifecycle() {
        RuntimeEpoch epoch = new RuntimeEpoch(4L);
        ActionCoordinator coordinator = new ActionCoordinator();
        ActionTransaction transaction = coordinator.tryBegin(
                request(epoch, ConfirmationPolicy.SERVER_BLOCK_UPDATE, RetryPolicy.NONE, 1_000L),
                10L
        ).orElseThrow();

        assertEquals(ActionResult.ADMITTED, transaction.state());
        assertEquals(ActionResult.WAITING_CONFIRMATION, transaction.markSent(epoch));
        assertEquals(1, transaction.attempts());
        assertEquals(ActionResult.CONFIRMED, transaction.confirm(epoch));
        assertTrue(transaction.isTerminal());
    }

    @Test
    void oldEpochResultCanNeverConfirmInTheNextWorld() {
        RuntimeEpoch epoch = new RuntimeEpoch(7L);
        ActionTransaction transaction = new ActionCoordinator().tryBegin(
                request(epoch, ConfirmationPolicy.SERVER_BLOCK_UPDATE, RetryPolicy.NONE, 0L),
                10L
        ).orElseThrow();
        transaction.markSent(epoch);

        assertEquals(ActionResult.STALE, transaction.confirm(epoch.next()));
        assertEquals(ActionResult.STALE, transaction.state());
    }

    @Test
    void retryBackoffAndAttemptLimitArePerAction() {
        RuntimeEpoch epoch = RuntimeEpoch.INITIAL;
        ActionTransaction transaction = new ActionCoordinator().tryBegin(
                request(epoch, ConfirmationPolicy.SERVER_INVENTORY_UPDATE, new RetryPolicy(2, 3), 0L),
                10L
        ).orElseThrow();

        assertEquals(ActionResult.RETRY, transaction.reject(epoch, 20L));
        assertEquals(ActionResult.RETRY, transaction.poll(epoch, 22L, 20L));
        assertEquals(ActionResult.ADMITTED, transaction.poll(epoch, 23L, 20L));
        assertEquals(ActionResult.FAILED, transaction.reject(epoch, 23L));
    }

    @Test
    void deadlineAndIllegalConfirmationAreExplicit() {
        RuntimeEpoch epoch = RuntimeEpoch.INITIAL;
        ActionTransaction expired = new ActionCoordinator().tryBegin(
                request(epoch, ConfirmationPolicy.CLIENT_STATE, RetryPolicy.NONE, 50L),
                10L
        ).orElseThrow();

        assertEquals(ActionResult.FAILED, expired.poll(epoch, 0L, 50L));

        ActionTransaction admitted = new ActionCoordinator().tryBegin(
                request(epoch, ConfirmationPolicy.CLIENT_STATE, RetryPolicy.NONE, 0L),
                10L
        ).orElseThrow();
        assertThrows(IllegalStateException.class, () -> admitted.confirm(epoch));
    }

    private static ActionRequest request(
            RuntimeEpoch epoch,
            ConfirmationPolicy confirmation,
            RetryPolicy retry,
            long deadline
    ) {
        return new ActionRequest(
                "print",
                epoch,
                EnumSet.of(ResourceLease.LOOK, ResourceLease.MAIN_HAND, ResourceLease.INTERACTION),
                deadline,
                confirmation,
                retry
        );
    }
}
