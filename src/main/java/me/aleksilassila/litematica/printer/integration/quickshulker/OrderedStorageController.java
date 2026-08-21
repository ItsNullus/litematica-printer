package me.aleksilassila.litematica.printer.integration.quickshulker;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import me.aleksilassila.litematica.printer.utils.mods.ModLoadUtils;
import me.aleksilassila.litematica.printer.utils.mods.ShulkerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

final class OrderedStorageController {
    private static final int RESTORE_TIMEOUT_TICKS = 40;
    private static final int PRESSURE_TRIGGER_FREE_SLOTS = 4;
    private static final int PRESSURE_TARGET_FREE_SLOTS = 8;
    private static final int EMERGENCY_FREE_SLOTS = 1;
    private static final int RECENT_USE_PROTECTION_TICKS = 40;
    private static final int IDLE_RESTORE_DELAY_TICKS = 100;
    private static final Minecraft client = Minecraft.getInstance();
    private static final List<OrderedStorageEntry> trackedItems = new ArrayList<>();
    private static final RestoreSession<OrderedStorageEntry> RESTORE_SESSION = new RestoreSession<>();

    public static void newItem(
            ItemStack itemStack,
            ItemStack sourceShulker,
            int sourceContainerSlot,
            int shulkerInventoryMenuSlot,
            int playerInventorySlot
    ) {
        if (itemStack == null || itemStack.isEmpty()
                || sourceContainerSlot < 0
                || shulkerInventoryMenuSlot < 0
                || playerInventorySlot < 0
                || playerInventorySlot >= 36) {
            return;
        }
        trackedItems.removeIf(statistics -> statistics.playerInventorySlot == playerInventorySlot);
        trackedItems.add(new OrderedStorageEntry(
                itemStack,
                sourceShulker,
                sourceContainerSlot,
                shulkerInventoryMenuSlot,
                playerInventorySlot,
                currentGameTick()
        ));
        markPrinterActivity();
    }

    public static void moveTrackedItem(int oldPlayerSlot, int newPlayerSlot) {
        OrderedStorageEntry moved = null;
        for (OrderedStorageEntry statistics : trackedItems) {
            if (statistics.playerInventorySlot == oldPlayerSlot) {
                moved = statistics;
                break;
            }
        }
        if (moved == null) {
            return;
        }
        if (newPlayerSlot < 0 || newPlayerSlot >= 36) {
            trackedItems.remove(moved);
            if (RESTORE_SESSION.pending() == moved) {
                clearPendingRestore();
            }
            return;
        }
        OrderedStorageEntry movedRecord = moved;
        trackedItems.removeIf(statistics -> statistics != movedRecord
                && statistics.playerInventorySlot == newPlayerSlot);
        moved.playerInventorySlot = newPlayerSlot;
    }

    public static void onMainHandUse(LocalPlayer player) {
        if (player == null) {
            return;
        }
        long currentTick = currentGameTick();
        RESTORE_SESSION.markActivity(currentTick);
        int selectedSlot = me.aleksilassila.litematica.printer.utils.InventoryUtils
                .getSelectedSlot(player.getInventory());
        ItemStack mainHandStack = player.getMainHandItem();
        OrderedStorageEntry statistics = OrderedStorageTracking.findAtSlot(
                trackedItems, selectedSlot, mainHandStack);
        if (statistics == null) {
            for (OrderedStorageEntry candidate : trackedItems) {
                if (!OrderedStorageTracking.isValid(player, candidate)
                        && OrderedStorageStacks.matches(candidate.itemStack, mainHandStack)) {
                    candidate.playerInventorySlot = selectedSlot;
                    statistics = candidate;
                    break;
                }
            }
        }
        if (statistics != null) {
            statistics.markUsed(currentTick);
        }
    }

    /**
     * Keep enough free inventory slots while printing, then return every
     * tracked stack after the printer has been idle for a short period.
     */
    public static boolean maintainOrderlyStorage() {
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.gameMode == null
                //#if MC > 260100
                //$$ || client.gui.screen() != null
                //#else
                || client.screen != null
                //#endif
                || !player.containerMenu.equals(player.inventoryMenu)) {
            return RESTORE_SESSION.hasPending();
        }
        if (RESTORE_SESSION.hasPending()) {
            if (!RESTORE_SESSION.isWaitingForContainer()) {
                openPendingShulker();
            }
            return true;
        }

        OrderedStorageTracking.reconcile(trackedItems, player);
        if (trackedItems.isEmpty()) {
            RESTORE_SESSION.clearPressureRecovery();
            RESTORE_SESSION.clearActivity();
            return false;
        }

        long currentTick = client.level.getGameTime();
        OrderedStoragePolicy.normalizeActivityTicks(RESTORE_SESSION, trackedItems, currentTick);
        int freeSlots = OrderedStoragePolicy.countEmptyInventorySlots(player);
        updatePressureRecovery(freeSlots);
        if (RESTORE_SESSION.isPressureRecoveryActive()) {
            boolean emergency = freeSlots <= EMERGENCY_FREE_SLOTS;
            return scheduleRestore(player, currentTick, emergency, false);
        }
        if (!QuickShulkerRequestController
                .lastNeedItemList.isEmpty()) {
            return false;
        }

        if (currentTick - RESTORE_SESSION.lastActivityTick() < IDLE_RESTORE_DELAY_TICKS) {
            return false;
        }

        return scheduleRestore(player, currentTick, false, true);
    }

    public static boolean tryRestoreForInventoryPressure() {
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.gameMode == null
                || !player.containerMenu.equals(player.inventoryMenu)) {
            return false;
        }
        OrderedStorageTracking.reconcile(trackedItems, player);
        if (trackedItems.isEmpty()) {
            RESTORE_SESSION.clearPressureRecovery();
            return false;
        }
        long currentTick = client.level.getGameTime();
        OrderedStoragePolicy.normalizeActivityTicks(RESTORE_SESSION, trackedItems, currentTick);
        int freeSlots = OrderedStoragePolicy.countEmptyInventorySlots(player);
        updatePressureRecovery(freeSlots);
        return RESTORE_SESSION.isPressureRecoveryActive()
                && scheduleRestore(player, currentTick, freeSlots <= EMERGENCY_FREE_SLOTS, false);
    }

    public static boolean hasPendingRestore() {
        return RESTORE_SESSION.hasPending();
    }

    public static boolean isWaitingForRestoreContainer() {
        return RESTORE_SESSION.isWaitingForContainer();
    }

    /**
     * Restore to the original inner slot first, then matching partial stacks, then empty slots.
     */
    public static void restorePendingItem() {
        if (!isWaitingForRestoreContainer() || client.player == null || client.gameMode == null) {
            return;
        }
        LocalPlayer player = client.player;
        AbstractContainerMenu menu = player.containerMenu;
        if (menu.equals(player.inventoryMenu)) {
            return;
        }

        OrderedStorageEntry statistics = RESTORE_SESSION.pending();
        RESTORE_SESSION.stopContainerWait();
        int playerMenuSlot = OrderedStorageStacks.findPlayerInventoryMenuSlot(menu, statistics);
        if (playerMenuSlot < 0) {
            finishPendingRestore(false);
            player.closeContainer();
            return;
        }

        Slot playerSlot = menu.slots.get(playerMenuSlot);
        ItemStack returningStack = playerSlot.getItem();
        List<Integer> destinations = OrderedStorageStacks.buildRestoreDestinations(menu, statistics, returningStack);
        int totalCapacity = 0;
        for (int destination : destinations) {
            totalCapacity += OrderedStorageStacks.availableCapacity(menu.slots.get(destination), returningStack);
            if (totalCapacity >= returningStack.getCount()) {
                break;
            }
        }
        if (!OrderedStorageStacks.matches(statistics.itemStack, returningStack)) {
            finishPendingRestore(false);
            player.closeContainer();
            return;
        }
        if (totalCapacity < returningStack.getCount()) {
            retryPendingRestore();
            player.closeContainer();
            return;
        }

        client.gameMode.handleContainerInput(menu.containerId, playerMenuSlot, 0, ContainerInput.PICKUP, player);
        for (int destination : destinations) {
            if (menu.getCarried().isEmpty()) {
                break;
            }
            client.gameMode.handleContainerInput(menu.containerId, destination, 0, ContainerInput.PICKUP, player);
        }
        boolean restored = menu.getCarried().isEmpty();
        if (!restored) {
            client.gameMode.handleContainerInput(menu.containerId, playerMenuSlot, 0, ContainerInput.PICKUP, player);
        }
        if (restored) {
            finishPendingRestore(true);
        } else {
            retryPendingRestore();
        }
        player.closeContainer();
    }

    public static void tick() {
        if (!RESTORE_SESSION.tickContainerTimeout()) {
            return;
        }
        LocalPlayer player = client.player;
        retryPendingRestore();
        if (player != null && !player.containerMenu.equals(player.inventoryMenu)) {
            player.closeContainer();
        }
    }

    public static void reSet() {
        trackedItems.clear();
        RESTORE_SESSION.reset();
    }

    private static long currentGameTick() {
        return client.level == null ? 0L : client.level.getGameTime();
    }

    private static void markPrinterActivity() {
        RESTORE_SESSION.markActivity(currentGameTick());
    }

    private static void updatePressureRecovery(int freeSlots) {
        RESTORE_SESSION.updatePressureRecovery(
                freeSlots,
                PRESSURE_TRIGGER_FREE_SLOTS,
                PRESSURE_TARGET_FREE_SLOTS
        );
    }

    private static boolean scheduleRestore(
            LocalPlayer player,
            long currentTick,
            boolean allowRecentlyUsed,
            boolean allowCurrentMainHand
    ) {
        if (RESTORE_SESSION.hasPending()) {
            if (!RESTORE_SESSION.isWaitingForContainer()) {
                openPendingShulker();
            }
            return true;
        }
        OrderedStorageEntry selected = OrderedStoragePolicy.selectRestoreCandidate(
                player,
                trackedItems,
                QuickShulkerRequestController.lastNeedItemList,
                currentTick,
                RECENT_USE_PROTECTION_TICKS,
                allowRecentlyUsed,
                allowCurrentMainHand
        );
        if (selected == null) {
            return false;
        }
        RESTORE_SESSION.schedule(selected);
        openPendingShulker();
        return RESTORE_SESSION.hasPending();
    }

    private static void clearPressureRecoveryIfSatisfied() {
        LocalPlayer player = client.player;
        if (player != null
                && OrderedStoragePolicy.countEmptyInventorySlots(player) >= PRESSURE_TARGET_FREE_SLOTS) {
            RESTORE_SESSION.clearPressureRecovery();
        }
    }

    private static void openPendingShulker() {
        LocalPlayer player = client.player;
        OrderedStorageEntry pendingRestore = RESTORE_SESSION.pending();
        if (player == null || client.gameMode == null || pendingRestore == null
                || RESTORE_SESSION.isWaitingForContainer()
                || ModLoadUtils.closeScreen > 0
                || !player.containerMenu.equals(player.inventoryMenu)) {
            return;
        }
        OrderedStorageTracking.reconcile(trackedItems, player);
        if (!trackedItems.contains(pendingRestore)) {
            clearPendingRestore();
            return;
        }

        int shulkerMenuSlot = OrderedStorageStacks.findBestShulkerMenuSlot(player, pendingRestore);
        if (shulkerMenuSlot < 0) {
            finishPendingRestore(false);
            return;
        }
        pendingRestore.shulkerInventoryMenuSlot = shulkerMenuSlot;
        ItemStack shulkerStack = player.inventoryMenu.slots.get(shulkerMenuSlot).getItem();
        if (!ShulkerUtils.openShulker(shulkerStack, shulkerMenuSlot)) {
            retryPendingRestore();
            return;
        }
        ModLoadUtils.closeScreen++;
        RESTORE_SESSION.beginContainerWait(RESTORE_TIMEOUT_TICKS);
    }

    private static void finishPendingRestore(boolean success) {
        OrderedStorageEntry completed = RESTORE_SESSION.pending();
        clearPendingRestore();
        if (completed != null) {
            trackedItems.remove(completed);
        }
        if (success) {
            clearPressureRecoveryIfSatisfied();
        }
        if (!success) {
            MessageUtils.setOverlayMessage(I18n.INVENTORY_RESTORE_FAILED.getName(), false);
        }
    }

    private static void retryPendingRestore() {
        OrderedStorageEntry pendingRestore = RESTORE_SESSION.pending();
        if (pendingRestore != null && pendingRestore.shulkerInventoryMenuSlot >= 0) {
            pendingRestore.attemptedShulkerMenuSlots.add(pendingRestore.shulkerInventoryMenuSlot);
        }
        RESTORE_SESSION.stopContainerWait();
    }

    private static void clearPendingRestore() {
        RESTORE_SESSION.clearPending();
    }

}
