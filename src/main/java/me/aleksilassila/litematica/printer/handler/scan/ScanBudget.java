package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.config.Configs;

/** Owns the per-tick global and per-owner scan time budget. */
final class ScanBudget {
    private static final int OWNER_BUDGET_PERCENT = 75;

    private long tickTime = Long.MIN_VALUE;
    private long usedNanos;

    void reset() {
        this.tickTime = Long.MIN_VALUE;
        this.usedNanos = 0L;
    }

    void beginTick(long tickTime) {
        if (this.tickTime == tickTime) {
            return;
        }
        this.tickTime = tickTime;
        this.usedNanos = 0L;
    }

    boolean isExceeded(long ownerStartNanos) {
        long elapsed = elapsedSince(ownerStartNanos);
        long globalBudget = globalNanos();
        long ownerBudget = Math.max(500_000L, globalBudget * OWNER_BUDGET_PERCENT / 100L);
        return elapsed >= ownerBudget || this.usedNanos + elapsed >= globalBudget;
    }

    void record(SectionScanSession.MutableMetrics metrics, long ownerStartNanos) {
        long elapsed = elapsedSince(ownerStartNanos);
        this.usedNanos += elapsed;
        metrics.scanNanos += elapsed;
    }

    private static long elapsedSince(long startNanos) {
        return Math.max(0L, System.nanoTime() - startNanos);
    }

    private static long globalNanos() {
        return Math.max(1L, Configs.Core.SCAN_TIME_BUDGET_MS.getIntegerValue()) * 1_000_000L;
    }
}
