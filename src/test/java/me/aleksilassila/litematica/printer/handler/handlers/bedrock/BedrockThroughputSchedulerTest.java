package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BedrockThroughputSchedulerTest {
    @Test
    void oneActionEveryTwoTicksKeepsOriginalCadence() {
        BedrockThroughputScheduler scheduler = new BedrockThroughputScheduler();

        BedrockThroughputScheduler.Allocation first = scheduler.allocate(1, 2);
        assertAllocation(first, 1, 1, 0);
        scheduler.consume(first, 0);

        BedrockThroughputScheduler.Allocation empty = scheduler.allocate(1, 2);
        assertAllocation(empty, 0, 0, 0);
        scheduler.consume(empty, 0);

        assertAllocation(scheduler.allocate(1, 2), 1, 0, 1);
    }

    @Test
    void sixOverTwoIsContinuousAndAlternatesStageExtra() {
        BedrockThroughputScheduler scheduler = new BedrockThroughputScheduler();

        for (int tick = 0; tick < 6; tick++) {
            BedrockThroughputScheduler.Allocation allocation = scheduler.allocate(6, 2);
            assertEquals(3, allocation.total());
            assertEquals(tick % 2 == 0 ? 2 : 1, allocation.critical());
            assertEquals(tick % 2 == 0 ? 1 : 2, allocation.preparation());
            scheduler.consume(allocation, 0);
        }
    }

    @Test
    void unusedBudgetDoesNotAccumulateIntoLargeBurst() {
        BedrockThroughputScheduler scheduler = new BedrockThroughputScheduler();

        for (int tick = 0; tick < 20; tick++) {
            BedrockThroughputScheduler.Allocation allocation = scheduler.allocate(6, 2);
            scheduler.consume(allocation, allocation.total());
        }

        assertEquals(3, scheduler.allocate(6, 2).total());
    }

    @Test
    void changingConfigurationResetsCreditsToNewSafeCapacity() {
        BedrockThroughputScheduler scheduler = new BedrockThroughputScheduler();
        BedrockThroughputScheduler.Allocation initial = scheduler.allocate(6, 2);
        scheduler.consume(initial, 0);

        assertAllocation(scheduler.allocate(1, 4), 1, 0, 1);
        assertEquals(8, scheduler.allocate(8, 1).total());
    }

    private static void assertAllocation(
            BedrockThroughputScheduler.Allocation allocation,
            int total,
            int critical,
            int preparation
    ) {
        assertEquals(total, allocation.total());
        assertEquals(critical, allocation.critical());
        assertEquals(preparation, allocation.preparation());
    }
}
