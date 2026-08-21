package me.aleksilassila.litematica.printer.utils.mods;

import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
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
            requestItem(item, MaterialRequest.Source.OTHER);
        }
    }

    public static MaterialReservation requestItem(Item item, MaterialRequest.Source source) {
        if (item == null) {
            return new MaterialReservation(0L, MaterialReservation.State.UNAVAILABLE);
        }
        return PrinterRuntime.get().materialRequests().request(item, source);
    }

    public static boolean switchItem() {
        return PrinterRuntime.get().quickShulkerAdapter().switchItem();
    }

    public static boolean hasPendingRequest() {
        return PrinterRuntime.get().quickShulkerAdapter().hasPendingRequest();
    }

    public static boolean isOpenHandler() {
        return PrinterRuntime.get().quickShulkerAdapter().isOpenHandler();
    }

    public static boolean shouldPause() {
        return PrinterRuntime.get().quickShulkerAdapter().shouldPause();
    }

    public static boolean shouldSuppressContainerScreen() {
        return PrinterRuntime.get().quickShulkerAdapter().shouldSuppressContainerScreen();
    }

    public static void onTick() {
        PrinterRuntime.get().quickShulkerAdapter().tick();
    }

    public static void onInventoryContent() {
        PrinterRuntime.get().quickShulkerAdapter().onInventoryContent();
    }

    public static void onMainHandUse(LocalPlayer player) {
        PrinterRuntime.get().quickShulkerAdapter().onMainHandUse(player);
    }

    public static void resetRuntime() {
        PrinterRuntime.get().quickShulkerAdapter().reset();
    }
}
