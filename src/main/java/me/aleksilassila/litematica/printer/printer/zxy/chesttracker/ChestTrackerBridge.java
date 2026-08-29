package me.aleksilassila.litematica.printer.printer.zxy.chesttracker;

import me.aleksilassila.litematica.printer.utils.mods.ModLoadUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Chest Tracker 远程取物功能的跨版本安全门面。
 *
 * <p>对齐 master-4：选区允许列表 + CT MemoryBank，不再使用私有 PrinterMemory / 主动扫箱。</p>
 */
public final class ChestTrackerBridge {
    private ChestTrackerBridge() {
    }

    public static boolean isChestTrackerLoaded() {
        return ModLoadUtils.isChestTrackerLoaded();
    }

    public static void init() {
        // Cache is lazy-loaded from disk; no join-time PrinterMemory bootstrap.
    }

    public static void tick() {
        if (!isChestTrackerLoaded()) {
            return;
        }
        try {
            ChestTakeController.tick();
        } catch (Exception e) {
            me.aleksilassila.litematica.printer.Reference.LOGGER.warn("[ChestTracker] tick 异常", e);
            ChestTakeController.reset();
        }
    }

    public static void onContainerOpen(int containerId) {
        if (!isChestTrackerLoaded()) {
            return;
        }
        ChestTakeController.onContainerOpen(containerId);
    }

    public static void onContainerContent(int containerId) {
        if (!isChestTrackerLoaded()) {
            return;
        }
        ChestTakeController.onContainerContent(containerId);
    }

    public static boolean shouldSuppressContainerScreen() {
        return isChestTrackerLoaded() && ChestTakeController.shouldSuppressContainerScreen();
    }

    public static boolean isAwaitingStack() {
        return isChestTrackerLoaded() && ChestTakeController.isAwaiting();
    }

    /** 是否正等待本模组预期的容器界面（供 MixinContainerScreenGuard 区分玩家手动打开与自动化打开） */
    public static boolean isExpectingContainerScreen() {
        return isChestTrackerLoaded() && ChestTakeController.isExpectingContainerScreen();
    }

    /** @deprecated use {@link #shouldSuppressContainerScreen()} */
    @Deprecated
    public static boolean isRemoteOpening() {
        return shouldSuppressContainerScreen();
    }

    /** @deprecated use {@link #shouldSuppressContainerScreen()} */
    @Deprecated
    public static boolean isAwaitingRemoteContent() {
        return shouldSuppressContainerScreen();
    }

    /** @deprecated cache hotkey replaces adder */
    @Deprecated
    public static boolean isAdderActive() {
        return false;
    }

    public static boolean requestMissingItem(Item[] requiredItems) {
        if (!isChestTrackerLoaded() || requiredItems == null || requiredItems.length == 0) {
            return false;
        }
        List<Item> items = new ArrayList<>();
        for (Item item : requiredItems) {
            if (item != null && item != Items.AIR) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            return false;
        }
        return ChestTakeController.requestItems(items);
    }

    public static boolean takeFromScreen(ItemStack stack) {
        return isChestTrackerLoaded() && ChestTakeController.requestFromScreen(stack);
    }

    public static int addSelectionToCache() {
        if (!isChestTrackerLoaded()) {
            MessageUtils.setOverlayMessage("未检测到 Chest Tracker");
            return 0;
        }
        try {
            return ChestTakeController.addSelectionToCache();
        } catch (Exception e) {
            me.aleksilassila.litematica.printer.Reference.LOGGER.warn("[ChestTracker] 缓存选区异常", e);
            MessageUtils.setOverlayMessage("缓存选区异常: " + e.getClass().getSimpleName());
            return 0;
        }
    }

    public static int clearSelectionCache() {
        if (!isChestTrackerLoaded()) {
            MessageUtils.setOverlayMessage("未检测到 Chest Tracker");
            return 0;
        }
        try {
            return ChestTakeController.clearSelectionCache();
        } catch (Exception e) {
            me.aleksilassila.litematica.printer.Reference.LOGGER.warn("[ChestTracker] 清空缓存异常", e);
            MessageUtils.setOverlayMessage("清空缓存异常: " + e.getClass().getSimpleName());
            return 0;
        }
    }

    public static int selectedCacheSize() {
        return isChestTrackerLoaded() ? ChestTakeController.selectedCacheSize() : 0;
    }

    public static void reset() {
        if (!isChestTrackerLoaded()) {
            return;
        }
        ChestTakeController.reset();
    }

    /** 中断进行中的远程取物（开关关闭时调用） */
    public static void abortRemoteOps() {
        reset();
    }

    /** @deprecated use {@link #addSelectionToCache()} */
    @Deprecated
    public static void startAddPrinterInventory() {
        int added = addSelectionToCache();
        MessageUtils.setOverlayMessage("Chest Tracker: 已加入 " + added + " 个选区容器");
    }

    /** @deprecated use {@link #clearSelectionCache()} */
    @Deprecated
    public static void clearPrinterMemory() {
        int removed = clearSelectionCache();
        MessageUtils.setOverlayMessage("Chest Tracker: 已清除 " + removed + " 个容器缓存");
    }
}
