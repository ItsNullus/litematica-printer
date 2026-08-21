package me.aleksilassila.litematica.printer.core.scan;

import java.util.List;

/** Bounded immutable transfer unit from the scan worker to the client thread. */
public record ScanBatch(
        ScanGeneration generation,
        List<ScanCoordinate> coordinates,
        boolean complete
) {
    public ScanBatch {
        if (generation == null) throw new IllegalArgumentException("generation must not be null");
        coordinates = List.copyOf(coordinates);
    }
}
