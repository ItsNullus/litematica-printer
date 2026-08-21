package me.aleksilassila.litematica.printer.handler.runtime;

@FunctionalInterface
public interface RuntimeResetter {
    void reset(String reason);
}
