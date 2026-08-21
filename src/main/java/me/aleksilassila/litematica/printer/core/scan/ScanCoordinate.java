package me.aleksilassila.litematica.printer.core.scan;

/** A world-independent coordinate produced by the asynchronous traversal worker. */
public record ScanCoordinate(int x, int y, int z, long distanceSqr) {
}
