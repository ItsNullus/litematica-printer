package me.aleksilassila.litematica.printer.core.runtime;

public sealed interface RuntimeEvent permits RuntimeEvent.EpochChanged {
    record EpochChanged(RuntimeEpoch previous, RuntimeEpoch current, String reason) implements RuntimeEvent {
    }
}
