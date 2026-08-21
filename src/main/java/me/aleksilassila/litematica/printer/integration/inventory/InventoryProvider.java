package me.aleksilassila.litematica.printer.integration.inventory;

/** Capability boundary implemented by inventory integrations. */
public interface InventoryProvider {
    String id();

    MaterialReservation request(MaterialRequest request);

    default void tick() {
    }

    default void reset() {
    }
}
