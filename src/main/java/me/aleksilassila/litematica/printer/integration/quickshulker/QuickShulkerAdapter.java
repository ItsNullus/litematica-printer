package me.aleksilassila.litematica.printer.integration.quickshulker;

import me.aleksilassila.litematica.printer.integration.inventory.InventoryProvider;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;

/** Public adapter around the Quick Shulker request and ordered-restore controllers. */
public final class QuickShulkerAdapter implements InventoryProvider, RuntimeComponent {
    public static final QuickShulkerAdapter INSTANCE = new QuickShulkerAdapter();
    private static final String LEASE_OWNER = "quick_shulker";
    private boolean resourcesAcquired;

    private QuickShulkerAdapter() {
        PrinterRuntime.get().register(this);
    }

    @Override
    public String id() {
        return "quick_shulker";
    }

    @Override
    public MaterialReservation request(MaterialRequest request) {
        if (!this.acquireResources()) {
            return new MaterialReservation(request.token(), MaterialReservation.State.PENDING);
        }
        QuickShulkerRequestController.requestItem(request.item());
        MaterialReservation.State state = QuickShulkerRequestController.hasPendingSwitchRequest()
                ? MaterialReservation.State.PENDING : MaterialReservation.State.UNAVAILABLE;
        return new MaterialReservation(request.token(), state);
    }

    public void requestItem(Item item) {
        if (item != null && this.acquireResources()) QuickShulkerRequestController.requestItem(item);
    }

    public boolean switchItem() {
        return QuickShulkerRequestController.switchItem();
    }

    public boolean hasPendingRequest() {
        return QuickShulkerRequestController.hasPendingSwitchRequest();
    }

    public boolean isOpenHandler() {
        return QuickShulkerRequestController.isOpenHandler();
    }

    public boolean shouldPause() {
        return QuickShulkerRequestController.shouldPauseForSwitchRequest();
    }

    public boolean shouldSuppressContainerScreen() {
        return QuickShulkerRequestController.shouldSuppressContainerScreen();
    }

    @Override
    public void tick() {
        QuickShulkerRequestController.tick();
        this.synchronizeResources();
    }

    public void onInventoryContent() {
        if (QuickShulkerRequestController.isOpenHandler()) {
            QuickShulkerRequestController.switchInv();
        }
        if (OrderedStorageController.isWaitingForRestoreContainer()) {
            OrderedStorageController.restorePendingItem();
        }
    }

    public void onMainHandUse(LocalPlayer player) {
        OrderedStorageController.onMainHandUse(player);
    }

    @Override
    public void reset() {
        OrderedStorageController.reSet();
        QuickShulkerRequestController.resetRuntime();
        this.releaseResources();
    }

    @Override
    public void onEpochChanged(RuntimeEvent.EpochChanged event) {
        this.reset();
    }

    private void synchronizeResources() {
        boolean busy = QuickShulkerRequestController.hasPendingSwitchRequest()
                || QuickShulkerRequestController.isOpenHandler()
                || OrderedStorageController.hasPendingRestore();
        if (busy) {
            this.acquireResources();
        } else {
            this.releaseResources();
        }
    }

    private boolean acquireResources() {
        if (this.resourcesAcquired) return true;
        this.resourcesAcquired = ActionBroker.INSTANCE.tryAcquire(
                LEASE_OWNER,
                java.util.EnumSet.of(ResourceLease.MAIN_HAND, ResourceLease.INVENTORY, ResourceLease.CONTAINER),
                0L
        );
        return this.resourcesAcquired;
    }

    private void releaseResources() {
        if (this.resourcesAcquired) {
            ActionBroker.INSTANCE.releaseOwner(LEASE_OWNER);
            this.resourcesAcquired = false;
        }
    }
}
