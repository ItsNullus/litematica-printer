package me.aleksilassila.litematica.printer.handler.scan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanIdlePolicyTest {
    @Test
    void cannotSleepBeforeOneCompleteEmptyPass() {
        ScanIdlePolicy policy = new ScanIdlePolicy();

        for (int tick = 0; tick < 20; tick++) {
            assertFalse(policy.recordFullIteration(false, false, false, 10));
        }
        assertTrue(policy.recordFullIteration(false, false, true, 10));
    }

    @Test
    void activityInvalidatesPreviousEmptyPassEvidence() {
        ScanIdlePolicy policy = new ScanIdlePolicy();
        assertFalse(policy.recordFullIteration(false, false, true, 10));
        assertFalse(policy.recordFullIteration(true, true, false, 10));

        for (int tick = 0; tick < 20; tick++) {
            assertFalse(policy.recordFullIteration(false, false, false, 10));
        }
        assertTrue(policy.recordFullIteration(false, false, true, 10));
    }

    @Test
    void disabledLazyModeNeverSleeps() {
        ScanIdlePolicy policy = new ScanIdlePolicy();
        for (int tick = 0; tick < 100; tick++) {
            assertFalse(policy.recordFullIteration(false, false, true, 0));
        }
    }

    @Test
    void pendingWorkPreventsFullScannerFromSleeping() {
        ScanIdlePolicy policy = new ScanIdlePolicy();

        assertFalse(policy.recordFullIteration(false, false, true, true, 1));
        assertTrue(policy.recordFullIteration(false, false, true, false, 1));
    }

    @Test
    void pendingWorkImmediatelyWakesLazyScanner() {
        ScanIdlePolicy policy = new ScanIdlePolicy();

        for (int tick = 1; tick < 20; tick++) {
            assertFalse(policy.shouldRunLazyProbe(40));
        }
        assertFalse(policy.shouldWakeForPendingWork(false));
        assertTrue(policy.shouldWakeForPendingWork(true));

        for (int tick = 1; tick < 40; tick++) {
            assertFalse(policy.shouldRunLazyProbe(40));
        }
        assertTrue(policy.shouldRunLazyProbe(40));
    }

    @Test
    void lazyProbeRunsAtStableIntervalAndActivityWakesScanner() {
        ScanIdlePolicy policy = new ScanIdlePolicy();
        for (int tick = 1; tick < 40; tick++) {
            assertFalse(policy.shouldRunLazyProbe(40));
        }
        assertTrue(policy.shouldRunLazyProbe(40));
        assertFalse(policy.recordLazyProbe(false, false));

        for (int tick = 1; tick < 40; tick++) {
            assertFalse(policy.shouldRunLazyProbe(40));
        }
        assertTrue(policy.shouldRunLazyProbe(40));
        assertTrue(policy.recordLazyProbe(false, true));
    }
}
