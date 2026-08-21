package me.aleksilassila.litematica.printer.integration.inventory;

import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import net.minecraft.client.Minecraft;
import java.util.EnumSet;

/** Reflection-backed Take It Out capability isolated from feature code. */
public final class TakeItOutAdapter implements InventoryProvider {
    private static final String LEASE_OWNER = "take_it_out";
    private final ActionPort actionBroker;
    private boolean resourcesAcquired;

    public TakeItOutAdapter(ActionPort actionBroker) {
        this.actionBroker = actionBroker;
    }

    @Override
    public String id() {
        return "take_it_out";
    }

    @Override
    public MaterialReservation request(MaterialRequest request) {
        if (!this.acquireResources()) {
            return pending(request);
        }
        if (TakeItOutUtils.tryRequestItem(request.item())) {
            return pending(request);
        }
        this.releaseResources();
        return unavailable(request);
    }

    @Override
    public MaterialReservation status(MaterialRequest request) {
        if (InventoryUtils.playerHasItemInInventory(Minecraft.getInstance().player, request.item())) {
            this.releaseResources();
            return new MaterialReservation(request.token(), MaterialReservation.State.AVAILABLE);
        }
        if (!this.resourcesAcquired) {
            return this.request(request);
        }
        if (TakeItOutUtils.isAwaitingItem(request.item())) {
            return pending(request);
        }
        this.releaseResources();
        return unavailable(request);
    }

    @Override
    public void reset() {
        TakeItOutUtils.resetPending();
        this.releaseResources();
    }

    private static MaterialReservation pending(MaterialRequest request) {
        return new MaterialReservation(request.token(), MaterialReservation.State.PENDING);
    }

    private static MaterialReservation unavailable(MaterialRequest request) {
        return new MaterialReservation(request.token(), MaterialReservation.State.UNAVAILABLE);
    }

    private boolean acquireResources() {
        if (this.resourcesAcquired) {
            return true;
        }
        this.resourcesAcquired = this.actionBroker.tryAcquire(
                LEASE_OWNER,
                EnumSet.of(ResourceLease.MAIN_HAND, ResourceLease.INVENTORY, ResourceLease.CONTAINER),
                0L
        );
        return this.resourcesAcquired;
    }

    private void releaseResources() {
        if (!this.resourcesAcquired) {
            return;
        }
        this.actionBroker.releaseOwner(LEASE_OWNER);
        this.resourcesAcquired = false;
    }
}
