package me.aleksilassila.litematica.printer.utils.mods;

import me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerAdapter;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;

/**
 * Compatibility boundary for the historical quick-shulker implementation.
 *
 * <p>The implementation remains unchanged for now. New code should use this
 * bridge instead of depending on the legacy {@code zxy} package directly.</p>
 */
public final class QuickShulkerBridge {
    private static final QuickShulkerAdapter ADAPTER = QuickShulkerAdapter.INSTANCE;

    private QuickShulkerBridge() {
    }

    public static void requestItem(Item item) {
        if (item != null) {
            ADAPTER.requestItem(item);
        }
    }

    public static boolean switchItem() {
        return ADAPTER.switchItem();
    }

    public static boolean hasPendingRequest() {
        return ADAPTER.hasPendingRequest();
    }

    public static boolean isOpenHandler() {
        return ADAPTER.isOpenHandler();
    }

    public static boolean shouldPause() {
        return ADAPTER.shouldPause();
    }

    public static boolean shouldSuppressContainerScreen() {
        return ADAPTER.shouldSuppressContainerScreen();
    }

    public static void onTick() {
        ADAPTER.tick();
    }

    public static void onInventoryContent() {
        ADAPTER.onInventoryContent();
    }

    public static void onMainHandUse(LocalPlayer player) {
        ADAPTER.onMainHandUse(player);
    }

    public static void resetRuntime() {
        ADAPTER.reset();
    }
}
