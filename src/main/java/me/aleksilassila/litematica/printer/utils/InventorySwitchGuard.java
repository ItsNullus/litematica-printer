package me.aleksilassila.litematica.printer.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.LongSupplier;

public final class InventorySwitchGuard {
    // Inventory clicks are client-predicted. Keep a short recovery window for a delayed packet,
    // but do not freeze the whole printer for a full second when the prediction was rejected.
    private static final int MAX_SETTLE_TICKS = 4;
    private final Minecraft client;
    private final LongSupplier tickClock;
    private Item pendingItem;
    private int pendingDamage = -1;
    private boolean matchDamage;
    private final SwitchConfirmationWindow confirmationWindow = new SwitchConfirmationWindow(MAX_SETTLE_TICKS);

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
        pendingDamage = -1;
        matchDamage = false;
        this.confirmationWindow.begin(this.tickClock.getAsLong());
        return true;
    }

    /** Records a tool switch where two stacks may contain the same item but different durability. */
    public boolean markSwitchIfNeeded(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        pendingItem = stack.getItem();
        matchDamage = stack.isDamageableItem();
        pendingDamage = matchDamage ? stack.getDamageValue() : -1;
        this.confirmationWindow.begin(this.tickClock.getAsLong());
        return true;
    }

    public boolean isWaiting() {
        if (pendingItem == null) {
            return false;
        }
        return this.confirmationWindow.isWaiting(this.tickClock.getAsLong(), this.isMainHandReady());
    }

    private void clear() {
        pendingItem = null;
        pendingDamage = -1;
        matchDamage = false;
        this.confirmationWindow.clear();
    }

    private boolean isMainHandReady() {
        if (client.player == null || pendingItem == null) {
            return false;
        }
        ItemStack hand = client.player.getMainHandItem();
        return hand.is(pendingItem)
                && (!this.matchDamage || hand.getDamageValue() == this.pendingDamage);
    }
}
