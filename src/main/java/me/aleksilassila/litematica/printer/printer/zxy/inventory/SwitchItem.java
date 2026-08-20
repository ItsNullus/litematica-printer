package me.aleksilassila.litematica.printer.printer.zxy.inventory;

import fi.dy.masa.malilib.util.InventoryUtils;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import me.aleksilassila.litematica.printer.utils.mods.ModLoadUtils;
import me.aleksilassila.litematica.printer.utils.mods.ShulkerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SwitchItem {
    private static final int RESTORE_TIMEOUT_TICKS = 40;
    private static final int PRESSURE_TRIGGER_FREE_SLOTS = 4;
    private static final int PRESSURE_TARGET_FREE_SLOTS = 8;
    private static final int EMERGENCY_FREE_SLOTS = 1;
    private static final int RECENT_USE_PROTECTION_TICKS = 40;
    private static final int IDLE_RESTORE_DELAY_TICKS = 100;
    private static final Minecraft client = Minecraft.getInstance();
    private static final List<ItemStatistics> trackedItems = new ArrayList<>();
    private static final RestoreSession<ItemStatistics> RESTORE_SESSION = new RestoreSession<>();

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
        trackedItems.add(new ItemStatistics(
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
        ItemStatistics moved = null;
        for (ItemStatistics statistics : trackedItems) {
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
        ItemStatistics movedRecord = moved;
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
        ItemStatistics statistics = findTrackedAtSlot(selectedSlot, mainHandStack);
        if (statistics == null) {
            for (ItemStatistics candidate : trackedItems) {
                if (!isTrackedStackValid(player, candidate) && matches(candidate.itemStack, mainHandStack)) {
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

        reconcileTrackedSlots(player);
        if (trackedItems.isEmpty()) {
            RESTORE_SESSION.clearPressureRecovery();
            RESTORE_SESSION.clearActivity();
            return false;
        }

        long currentTick = client.level.getGameTime();
        normalizeActivityTicks(currentTick);
        int freeSlots = countEmptyInventorySlots(player);
        updatePressureRecovery(freeSlots);
        if (RESTORE_SESSION.isPressureRecoveryActive()) {
            boolean emergency = freeSlots <= EMERGENCY_FREE_SLOTS;
            return scheduleRestore(player, currentTick, emergency, false);
        }
        if (!me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils
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
        reconcileTrackedSlots(player);
        if (trackedItems.isEmpty()) {
            RESTORE_SESSION.clearPressureRecovery();
            return false;
        }
        long currentTick = client.level.getGameTime();
        normalizeActivityTicks(currentTick);
        int freeSlots = countEmptyInventorySlots(player);
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

        ItemStatistics statistics = RESTORE_SESSION.pending();
        RESTORE_SESSION.stopContainerWait();
        int playerMenuSlot = findPlayerInventoryMenuSlot(menu, statistics);
        if (playerMenuSlot < 0) {
            finishPendingRestore(false);
            player.closeContainer();
            return;
        }

        Slot playerSlot = menu.slots.get(playerMenuSlot);
        ItemStack returningStack = playerSlot.getItem();
        List<Integer> destinations = buildRestoreDestinations(menu, statistics, returningStack);
        int totalCapacity = 0;
        for (int destination : destinations) {
            totalCapacity += availableCapacity(menu.slots.get(destination), returningStack);
            if (totalCapacity >= returningStack.getCount()) {
                break;
            }
        }
        if (!matches(statistics.itemStack, returningStack)) {
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

    private static int countEmptyInventorySlots(LocalPlayer player) {
        int emptySlots = 0;
        int size = Math.min(36, player.getInventory().getContainerSize());
        for (int slot = 0; slot < size; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) {
                emptySlots++;
            }
        }
        return emptySlots;
    }

    private static long currentGameTick() {
        return client.level == null ? 0L : client.level.getGameTime();
    }

    private static void markPrinterActivity() {
        RESTORE_SESSION.markActivity(currentGameTick());
    }

    private static void normalizeActivityTicks(long currentTick) {
        RESTORE_SESSION.normalizeActivity(currentTick);
        for (ItemStatistics statistics : trackedItems) {
            if (currentTick < statistics.lastUseTick) {
                statistics.lastUseTick = currentTick;
            }
        }
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
        ItemStatistics selected = selectRestoreCandidate(
                player,
                currentTick,
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

    private static ItemStatistics selectRestoreCandidate(
            LocalPlayer player,
            long currentTick,
            boolean allowRecentlyUsed,
            boolean allowCurrentMainHand
    ) {
        int selectedSlot = me.aleksilassila.litematica.printer.utils.InventoryUtils
                .getSelectedSlot(player.getInventory());
        ItemStatistics selected = null;
        for (ItemStatistics statistics : trackedItems) {
            if (!isTrackedStackValid(player, statistics)
                    || (!allowCurrentMainHand && isCurrentMainHand(player, selectedSlot, statistics))
                    || isRequestedItem(statistics)) {
                continue;
            }
            long age = currentTick - statistics.lastUseTick;
            if (!allowRecentlyUsed && age < RECENT_USE_PROTECTION_TICKS) {
                continue;
            }
            if (selected == null
                    || statistics.lastUseTick < selected.lastUseTick
                    || statistics.lastUseTick == selected.lastUseTick
                    && statistics.playerInventorySlot < selected.playerInventorySlot) {
                selected = statistics;
            }
        }
        return selected;
    }

    private static boolean isCurrentMainHand(
            LocalPlayer player,
            int selectedSlot,
            ItemStatistics statistics
    ) {
        return statistics.playerInventorySlot == selectedSlot
                && matches(statistics.itemStack, player.getMainHandItem());
    }

    private static boolean isRequestedItem(ItemStatistics statistics) {
        return me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils
                .lastNeedItemList.contains(statistics.itemStack.getItem());
    }

    private static void clearPressureRecoveryIfSatisfied() {
        LocalPlayer player = client.player;
        if (player != null && countEmptyInventorySlots(player) >= PRESSURE_TARGET_FREE_SLOTS) {
            RESTORE_SESSION.clearPressureRecovery();
        }
    }

    private static void openPendingShulker() {
        LocalPlayer player = client.player;
        ItemStatistics pendingRestore = RESTORE_SESSION.pending();
        if (player == null || client.gameMode == null || pendingRestore == null
                || RESTORE_SESSION.isWaitingForContainer()
                || ModLoadUtils.closeScreen > 0
                || !player.containerMenu.equals(player.inventoryMenu)) {
            return;
        }
        reconcileTrackedSlots(player);
        if (!trackedItems.contains(pendingRestore)) {
            clearPendingRestore();
            return;
        }

        int shulkerMenuSlot = findBestShulkerMenuSlot(player, pendingRestore);
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

    private static void reconcileTrackedSlots(LocalPlayer player) {
        Set<Integer> claimedSlots = new HashSet<>();
        List<ItemStatistics> unresolved = new ArrayList<>();
        for (ItemStatistics statistics : trackedItems) {
            if (isTrackedStackValid(player, statistics)
                    && claimedSlots.add(statistics.playerInventorySlot)) {
                continue;
            }
            unresolved.add(statistics);
        }
        for (ItemStatistics statistics : unresolved) {
            int relocatedSlot = findMatchingInventorySlot(player, statistics.itemStack, claimedSlots);
            if (relocatedSlot >= 0) {
                statistics.playerInventorySlot = relocatedSlot;
                claimedSlots.add(relocatedSlot);
            } else {
                trackedItems.remove(statistics);
            }
        }
    }

    private static int findMatchingInventorySlot(
            LocalPlayer player,
            ItemStack expected,
            Set<Integer> claimedSlots
    ) {
        int selectedSlot = me.aleksilassila.litematica.printer.utils.InventoryUtils
                .getSelectedSlot(player.getInventory());
        if (!claimedSlots.contains(selectedSlot)
                && matches(expected, player.getInventory().getItem(selectedSlot))) {
            return selectedSlot;
        }
        int size = Math.min(36, player.getInventory().getContainerSize());
        for (int slot = 0; slot < size; slot++) {
            if (!claimedSlots.contains(slot) && matches(expected, player.getInventory().getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private static int findBestShulkerMenuSlot(LocalPlayer player, ItemStatistics statistics) {
        ItemStack returningStack = player.getInventory().getItem(statistics.playerInventorySlot);
        int bestSlot = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int menuSlot = 0; menuSlot < player.inventoryMenu.slots.size(); menuSlot++) {
            Slot slot = player.inventoryMenu.slots.get(menuSlot);
            ItemStack shulkerStack = slot.getItem();
            if (!(slot.container instanceof Inventory) || !isShulkerBox(shulkerStack)) {
                continue;
            }
            if (statistics.attemptedShulkerMenuSlots.contains(menuSlot)) {
                continue;
            }
            boolean recordedSlot = menuSlot == statistics.shulkerInventoryMenuSlot;
            boolean sameShulkerType = statistics.shulkerStack.isEmpty()
                    || shulkerStack.getItem().equals(statistics.shulkerStack.getItem());
            List<ItemStack> storedItems = readStoredItems(shulkerStack);
            boolean snapshotMatches = storedShulkerMatchesSnapshot(storedItems, statistics);
            boolean originalSlotFits = storedOriginalSlotFits(storedItems, statistics, returningStack);
            boolean hasCapacity = storedShulkerHasCapacity(storedItems, returningStack);
            int score = ShulkerSelectionPolicy.score(
                    recordedSlot,
                    sameShulkerType,
                    snapshotMatches,
                    originalSlotFits,
                    hasCapacity
            );
            if (score < bestScore) {
                bestScore = score;
                bestSlot = menuSlot;
            }
        }
        return bestSlot;
    }

    private static boolean storedShulkerMatchesSnapshot(
            List<ItemStack> storedItems,
            ItemStatistics statistics
    ) {
        if (statistics.shulkerSnapshot.isEmpty()) {
            return false;
        }
        if (storedItems.size() != statistics.shulkerSnapshot.size()) {
            return false;
        }
        for (int slot = 0; slot < storedItems.size(); slot++) {
            if (slot == statistics.sourceContainerSlot) {
                continue;
            }
            ItemStack expected = statistics.shulkerSnapshot.get(slot);
            ItemStack actual = storedItems.get(slot);
            if (expected.isEmpty() != actual.isEmpty()) {
                return false;
            }
            if (!expected.isEmpty()
                    && (expected.getCount() != actual.getCount() || !matches(expected, actual))) {
                return false;
            }
        }
        return true;
    }

    private static boolean storedOriginalSlotFits(
            List<ItemStack> storedItems,
            ItemStatistics statistics,
            ItemStack returningStack
    ) {
        if (statistics.sourceContainerSlot < 0 || statistics.sourceContainerSlot >= storedItems.size()) {
            return false;
        }
        return stackCapacity(storedItems.get(statistics.sourceContainerSlot), returningStack)
                >= returningStack.getCount();
    }

    private static boolean storedShulkerHasCapacity(
            List<ItemStack> storedItems,
            ItemStack returningStack
    ) {
        int capacity = 0;
        for (ItemStack stored : storedItems) {
            capacity += stackCapacity(stored, returningStack);
            if (capacity >= returningStack.getCount()) {
                return true;
            }
        }
        return false;
    }

    private static List<ItemStack> readStoredItems(ItemStack shulkerStack) {
        try {
            return InventoryUtils.getStoredItems(shulkerStack, -1);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static List<Integer> buildRestoreDestinations(
            AbstractContainerMenu menu,
            ItemStatistics statistics,
            ItemStack returningStack
    ) {
        List<Integer> destinations = new ArrayList<>();
        addRestoreDestination(menu, destinations, statistics.sourceContainerSlot, returningStack, false);
        addRestoreDestination(menu, destinations, statistics.sourceContainerSlot, returningStack, true);
        for (int slot = 0; slot < menu.slots.size(); slot++) {
            if (slot == statistics.sourceContainerSlot) continue;
            addRestoreDestination(menu, destinations, slot, returningStack, false);
        }
        for (int slot = 0; slot < menu.slots.size(); slot++) {
            if (slot == statistics.sourceContainerSlot) continue;
            addRestoreDestination(menu, destinations, slot, returningStack, true);
        }
        return destinations;
    }

    private static void addRestoreDestination(
            AbstractContainerMenu menu,
            List<Integer> destinations,
            int slotIndex,
            ItemStack returningStack,
            boolean emptyOnly
    ) {
        if (slotIndex < 0 || slotIndex >= menu.slots.size() || destinations.contains(slotIndex)) {
            return;
        }
        Slot slot = menu.slots.get(slotIndex);
        ItemStack stored = slot.getItem();
        if (slot.container instanceof Inventory || !slot.mayPlace(returningStack)) {
            return;
        }
        if (emptyOnly ? stored.isEmpty() : !stored.isEmpty() && matches(stored, returningStack)) {
            if (availableCapacity(slot, returningStack) > 0) {
                destinations.add(slotIndex);
            }
        }
    }

    private static int findPlayerInventoryMenuSlot(AbstractContainerMenu menu, ItemStatistics statistics) {
        int expectedSlot = findPlayerInventoryMenuSlot(menu, statistics.playerInventorySlot);
        if (expectedSlot >= 0 && matches(statistics.itemStack, menu.slots.get(expectedSlot).getItem())) {
            return expectedSlot;
        }
        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            Slot slot = menu.slots.get(menuSlot);
            if (slot.container instanceof Inventory && matches(statistics.itemStack, slot.getItem())) {
                return menuSlot;
            }
        }
        return -1;
    }

    private static int findPlayerInventoryMenuSlot(AbstractContainerMenu menu, int playerInventorySlot) {
        if (playerInventorySlot < 0 || playerInventorySlot >= 36) {
            return -1;
        }
        int wantedOrdinal = playerInventorySlot < 9
                ? 27 + playerInventorySlot
                : playerInventorySlot - 9;
        int ordinal = 0;
        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            if (menu.slots.get(menuSlot).container instanceof Inventory) {
                if (ordinal == wantedOrdinal) {
                    return menuSlot;
                }
                ordinal++;
            }
        }
        return -1;
    }

    private static ItemStatistics findTrackedAtSlot(int playerSlot, ItemStack stack) {
        for (ItemStatistics statistics : trackedItems) {
            if (statistics.playerInventorySlot == playerSlot && matches(statistics.itemStack, stack)) {
                return statistics;
            }
        }
        return null;
    }

    private static boolean isTrackedStackValid(LocalPlayer player, ItemStatistics statistics) {
        return statistics.playerInventorySlot >= 0
                && statistics.playerInventorySlot < player.getInventory().getContainerSize()
                && matches(statistics.itemStack, player.getInventory().getItem(statistics.playerInventorySlot));
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().contains("shulker_box")
                && stack.getCount() == 1;
    }

    private static int availableCapacity(Slot slot, ItemStack returningStack) {
        if (slot.container instanceof Inventory || !slot.mayPlace(returningStack)) {
            return 0;
        }
        return stackCapacity(slot.getItem(), returningStack);
    }

    private static int stackCapacity(ItemStack stored, ItemStack returningStack) {
        if (returningStack == null || returningStack.isEmpty()) {
            return 0;
        }
        if (stored.isEmpty()) {
            return returningStack.getMaxStackSize();
        }
        return matches(stored, returningStack)
                ? Math.max(0, returningStack.getMaxStackSize() - stored.getCount())
                : 0;
    }

    private static boolean matches(ItemStack expected, ItemStack actual) {
        if (expected == null || actual == null || expected.isEmpty() || actual.isEmpty()) {
            return false;
        }
        ItemStack normalizedExpected = expected.copy();
        ItemStack normalizedActual = actual.copy();
        normalizedExpected.setCount(1);
        normalizedActual.setCount(1);
        return InventoryUtils.areStacksEqual(normalizedExpected, normalizedActual);
    }

    private static List<ItemStack> snapshotShulker(ItemStack shulkerStack) {
        List<ItemStack> snapshot = new ArrayList<>();
        if (shulkerStack == null || shulkerStack.isEmpty()) {
            return snapshot;
        }
        for (ItemStack stored : readStoredItems(shulkerStack)) {
            snapshot.add(stored.copy());
        }
        return snapshot;
    }

    private static void finishPendingRestore(boolean success) {
        ItemStatistics completed = RESTORE_SESSION.pending();
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
        ItemStatistics pendingRestore = RESTORE_SESSION.pending();
        if (pendingRestore != null && pendingRestore.shulkerInventoryMenuSlot >= 0) {
            pendingRestore.attemptedShulkerMenuSlots.add(pendingRestore.shulkerInventoryMenuSlot);
        }
        RESTORE_SESSION.stopContainerWait();
    }

    private static void clearPendingRestore() {
        RESTORE_SESSION.clearPending();
    }

    private static class ItemStatistics {
        private final ItemStack itemStack;
        private final ItemStack shulkerStack;
        private final List<ItemStack> shulkerSnapshot;
        private final Set<Integer> attemptedShulkerMenuSlots = new HashSet<>();
        private final int sourceContainerSlot;
        private int shulkerInventoryMenuSlot;
        private int playerInventorySlot;
        private long lastUseTick;

        private ItemStatistics(
                ItemStack itemStack,
                ItemStack shulkerStack,
                int sourceContainerSlot,
                int shulkerInventoryMenuSlot,
                int playerInventorySlot,
                long currentTick
        ) {
            this.itemStack = itemStack.copy();
            this.itemStack.setCount(1);
            this.shulkerStack = shulkerStack == null ? ItemStack.EMPTY : shulkerStack.copy();
            if (!this.shulkerStack.isEmpty()) {
                this.shulkerStack.setCount(1);
            }
            this.shulkerSnapshot = snapshotShulker(shulkerStack);
            this.sourceContainerSlot = sourceContainerSlot;
            this.shulkerInventoryMenuSlot = shulkerInventoryMenuSlot;
            this.playerInventorySlot = playerInventorySlot;
            this.lastUseTick = currentTick;
        }

        private void markUsed(long currentTick) {
            this.lastUseTick = currentTick;
        }
    }
}
