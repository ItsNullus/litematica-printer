package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

/**
 * Pure scheduling policy for bedrock target advancement.
 *
 * <p>Credits are stored in interval-sized units, allowing rates such as one action every two
 * ticks without a wall-clock gate. The available budget is split between the critical execution
 * lane and machine preparation; an odd credit alternates lanes to prevent stage synchronization.</p>
 */
final class BedrockThroughputScheduler {
    private int credits;
    private int configuredThroughput = -1;
    private int configuredInterval = -1;
    private boolean preferCriticalExtra = true;

    void reset() {
        this.credits = 0;
        this.configuredThroughput = -1;
        this.configuredInterval = -1;
        this.preferCriticalExtra = true;
    }

    Allocation allocate(int requestedThroughput, int requestedInterval) {
        int throughput = Math.max(1, requestedThroughput);
        int interval = Math.max(1, requestedInterval);
        int capacity = throughput + interval - 1;

        if (throughput != this.configuredThroughput || interval != this.configuredInterval) {
            this.configuredThroughput = throughput;
            this.configuredInterval = interval;
            this.credits = capacity;
        } else {
            this.credits = Math.min(capacity, this.credits + throughput);
        }

        int total = this.credits / interval;
        int critical = total / 2;
        if ((total & 1) != 0 && this.preferCriticalExtra) {
            critical++;
        }
        if ((total & 1) != 0) {
            this.preferCriticalExtra = !this.preferCriticalExtra;
        }
        return new Allocation(total, critical, total - critical, interval);
    }

    void consume(Allocation allocation, int unusedActions) {
        if (allocation == null) {
            return;
        }
        int unused = Math.max(0, Math.min(allocation.total(), unusedActions));
        int consumed = allocation.total() - unused;
        this.credits = Math.max(0, this.credits - consumed * allocation.interval());
    }

    record Allocation(int total, int critical, int preparation, int interval) {
    }
}
