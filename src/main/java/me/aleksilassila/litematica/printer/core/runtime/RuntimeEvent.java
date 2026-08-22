package me.aleksilassila.litematica.printer.core.runtime;

public sealed interface RuntimeEvent permits RuntimeEvent.EpochChanged, RuntimeEvent.BlockUpdated {
    record EpochChanged(RuntimeEpoch previous, RuntimeEpoch current, String reason) implements RuntimeEvent {
    }

    /** Exact server world update. Consumers may also wake immediate neighbours when required. */
    record BlockUpdated(int x, int y, int z) implements RuntimeEvent {
    }
}
