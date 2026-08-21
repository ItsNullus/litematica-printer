package me.aleksilassila.litematica.printer.core.action;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionCoordinatorTest {
    @Test
    void conflictingOwnersCannotShareAResourceUntilRelease() {
        ActionCoordinator coordinator = new ActionCoordinator();
        ActionTicket print = coordinator.tryAdmit(request("print", 1_000L), 10L).orElseThrow();

        assertTrue(coordinator.isHeld(ResourceLease.MAIN_HAND));
        assertTrue(coordinator.tryAdmit(request("fill", 1_000L), 20L).isEmpty());

        coordinator.release(print);
        assertTrue(coordinator.tryAdmit(request("fill", 1_000L), 30L).isPresent());
    }

    @Test
    void expiredLeasesCannotFreezeLaterActions() {
        ActionCoordinator coordinator = new ActionCoordinator();
        coordinator.tryAdmit(request("print", 50L), 10L).orElseThrow();

        assertTrue(coordinator.tryAdmit(request("mine", 500L), 60L).isPresent());
    }

    @Test
    void resetReleasesEveryResource() {
        ActionCoordinator coordinator = new ActionCoordinator();
        coordinator.tryAdmit(request("print", 1_000L), 10L).orElseThrow();
        assertEquals(3, coordinator.activeLeaseCount());

        coordinator.reset();

        assertEquals(0, coordinator.activeLeaseCount());
        assertFalse(coordinator.isHeld(ResourceLease.LOOK));
    }

    @Test
    void ownershipDistinguishesTheCurrentFeatureFromOtherSessions() {
        ActionCoordinator coordinator = new ActionCoordinator();
        coordinator.tryAdmit(request("quick_shulker", 1_000L), 10L).orElseThrow();

        assertFalse(coordinator.isHeldByOther(ResourceLease.MAIN_HAND, "quick_shulker"));
        assertTrue(coordinator.isHeldByOther(ResourceLease.MAIN_HAND, "print"));
    }

    private static ActionRequest request(String owner, long deadline) {
        return new ActionRequest(
                owner,
                EnumSet.of(ResourceLease.LOOK, ResourceLease.MAIN_HAND, ResourceLease.INTERACTION),
                deadline,
                ConfirmationPolicy.CLIENT_STATE,
                RetryPolicy.NONE
        );
    }
}
