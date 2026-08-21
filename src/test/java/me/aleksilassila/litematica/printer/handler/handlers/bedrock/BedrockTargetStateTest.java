package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockTargetStateTest {
    @Test
    void preservesTheOriginalTickAdvanceRules() {
        BedrockTargetState state = new BedrockTargetState();

        state.beginTick(true);
        assertEquals(0, state.tickTimes());

        state.setStatus(BedrockTarget.Status.EXTENDED);
        state.beginTick(false);
        assertEquals(0, state.tickTimes());
        state.beginTick(true);
        assertEquals(1, state.tickTimes());

        state.setStatus(BedrockTarget.Status.NEEDS_WAITING);
        state.beginTick(false);
        assertEquals(2, state.tickTimes());
    }

    @Test
    void resetAttemptClearsOnlyAttemptState() {
        BedrockTargetState state = new BedrockTargetState();
        state.setStatus(BedrockTarget.Status.NEEDS_WAITING);
        state.setHasTried(true);
        state.setExecuteTick(7);
        state.setInitializeTick(3);
        state.incrementStuckTicks();
        state.markThroughputAction();

        state.resetAttempt();

        assertEquals(0, state.tickTimes());
        assertEquals(-1, state.executeTick());
        assertEquals(-1, state.initializeTick());
        assertEquals(0, state.stuckTicks());
        assertFalse(state.hasTried());
        assertTrue(state.consumedThroughput());
        assertEquals(BedrockTarget.Status.NEEDS_WAITING, state.status());
    }

    @Test
    void throughputMarkerIsPerTick() {
        BedrockTargetState state = new BedrockTargetState();
        state.markThroughputAction();
        assertTrue(state.consumedThroughput());

        state.beginTick(false);

        assertFalse(state.consumedThroughput());
    }
}
