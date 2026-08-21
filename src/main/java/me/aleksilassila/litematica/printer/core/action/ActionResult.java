package me.aleksilassila.litematica.printer.core.action;

public enum ActionResult {
    ADMITTED,
    WAITING_RESOURCE,
    SENT,
    CONFIRMED,
    RETRY,
    STALE,
    FAILED
}
