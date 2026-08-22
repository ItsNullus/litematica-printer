package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.config.Configs;

import java.util.HashSet;
import java.util.Set;

/** Owns the per-tick global and per-owner scan time budget. */
final class ScanBudget {
    private long tickTime = Long.MIN_VALUE;
    private long usedNanos;
    private int ownerCount = 1;
    private final Set<String> knownOwners = new HashSet<>();

    void reset() {
        this.tickTime = Long.MIN_VALUE;
        this.usedNanos = 0L;
        this.ownerCount = 1;
        this.knownOwners.clear();
    }

    void beginTick(long tickTime) {
        if (this.tickTime == tickTime) {
            return;
        }
        this.tickTime = tickTime;
        this.usedNanos = 0L;
        this.ownerCount = Math.max(1, this.knownOwners.size());
    }

    void registerOwner(String ownerKey) {
        if (ownerKey != null && !ownerKey.isBlank()) {
            this.knownOwners.add(ownerKey);
        }
    }

    void removeOwner(String ownerKey) {
        if (ownerKey != null) {
            this.knownOwners.remove(ownerKey);
            this.ownerCount = Math.max(1, this.knownOwners.size());
        }
    }

    boolean isExceeded(String ownerKey, long ownerStartNanos) {
        long elapsed = elapsedSince(ownerStartNanos);
        long globalBudget = globalNanos();
        long ownerBudget = Math.max(100_000L, globalBudget / this.ownerCount);
        return elapsed >= ownerBudget || this.usedNanos + elapsed >= globalBudget;
    }

    void record(String ownerKey, ScanMetricsAccumulator metrics, long ownerStartNanos) {
        long elapsed = elapsedSince(ownerStartNanos);
        this.usedNanos += elapsed;
        metrics.scanNanos += elapsed;
        this.registerOwner(ownerKey);
    }

    private static long elapsedSince(long startNanos) {
        return Math.max(0L, System.nanoTime() - startNanos);
    }

    private static long globalNanos() {
        return Math.max(1L, Configs.Core.SCAN_TIME_BUDGET_MS.getIntegerValue()) * 1_000_000L;
    }
}
