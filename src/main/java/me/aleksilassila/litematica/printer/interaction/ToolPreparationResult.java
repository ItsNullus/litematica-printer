package me.aleksilassila.litematica.printer.interaction;

/** Result of preparing the held tool before any durability-producing packet is emitted. */
public enum ToolPreparationResult {
    READY,
    SWITCHED_WAITING_SYNC,
    UNAVAILABLE
}
