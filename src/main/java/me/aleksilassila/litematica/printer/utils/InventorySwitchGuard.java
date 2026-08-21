package me.aleksilassila.litematica.printer.utils;

import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;

public final class InventorySwitchGuard {
    private static final Minecraft client = Minecraft.getInstance();
    private static final int MAX_SETTLE_TICKS = 20;
    private static Item pendingItem;
    private static long pendingStartedTick;

    private InventorySwitchGuard() {
    }

    public static void reset() {
        clear();
    }

    public static boolean markSwitchIfNeeded(Item item) {
        if (item == null) {
            return false;
        }
        pendingItem = item;
        pendingStartedTick = ClientPlayerTickManager.getCurrentHandlerTime();
        PrinterRuntime.get().actionBroker().cancelQueue();
        return true;
    }

    public static boolean isWaiting() {
        if (pendingItem == null) {
            return false;
        }
        PrinterRuntime.get().actionBroker().cancelQueue();
        long age = ClientPlayerTickManager.getCurrentHandlerTime() - pendingStartedTick;
        if (age <= 0) {
            return true;
        }
        if (isMainHandReady(pendingItem)) {
            clear();
            return false;
        }
        // Accepted container clicks are client-predicted and normally have no acknowledgement.
        // Unlocking on the following tick is safe once the local main hand matches: the swap
        // packet was already queued before any later placement packet on the same connection.
        // If prediction did not settle, release only to let the normal item selection retry;
        // every executor validates the main hand again before sending an action.
        if (age > MAX_SETTLE_TICKS) {
            clear();
            return false;
        }
        return true;
    }

    private static void clear() {
        pendingItem = null;
        pendingStartedTick = 0L;
    }

    private static boolean isMainHandReady(Item item) {
        return client.player != null && client.player.getMainHandItem().is(item);
    }
}
