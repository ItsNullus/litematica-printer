package me.aleksilassila.litematica.printer.utils.mods;

import me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils;
import me.aleksilassila.litematica.printer.printer.zxy.inventory.SwitchItem;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;

/**
 * Compatibility boundary for the historical quick-shulker implementation.
 *
 * <p>The implementation remains unchanged for now. New code should use this
 * bridge instead of depending on the legacy {@code zxy} package directly.</p>
 */
public final class QuickShulkerBridge {
    private QuickShulkerBridge() {
    }

    public static void requestItem(Item item) {
        if (item != null) {
            InventoryUtils.requestItem(item);
        }
    }

    public static boolean switchItem() {
        return InventoryUtils.switchItem();
    }

    public static boolean hasPendingRequest() {
        return InventoryUtils.hasPendingSwitchRequest();
    }

    public static boolean isOpenHandler() {
        return InventoryUtils.isOpenHandler();
    }

    public static boolean shouldPause() {
        return InventoryUtils.shouldPauseForSwitchRequest();
    }

    public static boolean shouldSuppressContainerScreen() {
        return InventoryUtils.shouldSuppressContainerScreen();
    }

    public static void onTick() {
        InventoryUtils.tick();
    }

    public static void onInventoryContent() {
        if (InventoryUtils.isOpenHandler()) {
            InventoryUtils.switchInv();
        }
        if (SwitchItem.isWaitingForRestoreContainer()) {
            SwitchItem.restorePendingItem();
        }
    }

    public static void onMainHandUse(LocalPlayer player) {
        SwitchItem.onMainHandUse(player);
    }

    public static void resetRuntime() {
        SwitchItem.reSet();
        InventoryUtils.resetRuntime();
    }
}
