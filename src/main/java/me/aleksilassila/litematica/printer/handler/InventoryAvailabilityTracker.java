package me.aleksilassila.litematica.printer.handler;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;

import java.util.IdentityHashMap;
import java.util.Map;

/** Tracks material gains once per client tick without treating normal consumption as a rescan event. */
public final class InventoryAvailabilityTracker implements RuntimeComponent {
    private final Map<Item, Integer> previousCounts = new IdentityHashMap<>();
    private final Map<Item, Integer> currentCounts = new IdentityHashMap<>();
    private boolean initialized;
    private long gainRevision;

    public InventoryAvailabilityTracker() {
    }

    public void tick(LocalPlayer player) {
        if (player == null) {
            this.reset();
            return;
        }
        this.currentCounts.clear();
        int size = player.getInventory().getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                this.currentCounts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        if (this.initialized) {
            for (Map.Entry<Item, Integer> entry : this.currentCounts.entrySet()) {
                if (entry.getValue() > this.previousCounts.getOrDefault(entry.getKey(), 0)) {
                    this.gainRevision++;
                    break;
                }
            }
        } else {
            this.initialized = true;
        }
        this.previousCounts.clear();
        this.previousCounts.putAll(this.currentCounts);
    }

    public long gainRevision() {
        return this.gainRevision;
    }

    public void reset() {
        this.previousCounts.clear();
        this.currentCounts.clear();
        this.initialized = false;
        this.gainRevision++;
    }

    @Override public void onEpochChanged(RuntimeEvent.EpochChanged event) { this.reset(); }
}
