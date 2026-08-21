package me.aleksilassila.litematica.printer.integration.inventory;

/** Capability boundary implemented by inventory integrations. */
public interface InventoryProvider {
    String id();

    MaterialReservation request(MaterialRequest request);

    /** Polls a request previously accepted by this provider without issuing it again. */
    default MaterialReservation status(MaterialRequest request) {
        return new MaterialReservation(request.token(), MaterialReservation.State.UNAVAILABLE);
    }

    default void tick() {
    }

    default void reset() {
    }
}
