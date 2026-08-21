package me.aleksilassila.litematica.printer.integration.inventory;

/** Ordered-storage restore lifecycle exposed without implementation details. */
public interface InventoryRestoreSession {
    boolean hasPendingRestore();

    boolean isWaitingForContainer();

    void reset();
}
