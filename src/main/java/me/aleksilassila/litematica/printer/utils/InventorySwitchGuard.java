package me.aleksilassila.litematica.printer.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;

import java.util.function.LongSupplier;

public final class InventorySwitchGuard {
    private static final int MAX_SETTLE_TICKS = 20;
    private final Minecraft client;
    private final LongSupplier tickClock;
    private Item pendingItem;
    private long pendingStartedTick;

    public InventorySwitchGuard(Minecraft client, LongSupplier tickClock) {
        this.client = client;
        this.tickClock = tickClock;
    }

    public void reset() {
        clear();
    }

    public boolean markSwitchIfNeeded(Item item) {
        if (item == null) {
            return false;
        }
        pendingItem = item;
        pendingStartedTick = this.tickClock.getAsLong();
        if (isMainHandReady(item)) {
            clear();
        }
        return true;
    }

    public boolean isWaiting() {
        if (pendingItem == null) {
            return false;
        }
        long age = this.tickClock.getAsLong() - pendingStartedTick;
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

    private void clear() {
        pendingItem = null;
        pendingStartedTick = 0L;
    }

    private boolean isMainHandReady(Item item) {
        return client.player != null && client.player.getMainHandItem().is(item);
    }
}
