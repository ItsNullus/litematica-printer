package me.aleksilassila.litematica.printer.integration.inventory;

/** Provider response bound to the request token that created it. */
public record MaterialReservation(long token, State state) {
    public enum State {
        AVAILABLE,
        PENDING,
        UNAVAILABLE
    }
}
