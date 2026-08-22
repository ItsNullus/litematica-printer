package me.aleksilassila.litematica.printer.handler.scan;

/** Describes why a scan candidate source currently has no next item. */
public enum ScanAvailability {
    READY,
    WAITING_FOR_BATCH,
    COMPLETE,
    CANCELLED,
    FAILED
}
