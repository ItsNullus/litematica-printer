package me.aleksilassila.litematica.printer.printer.zxy.chesttracker;

import me.aleksilassila.litematica.printer.utils.mods.ModLoadUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Chest Tracker 远程取物功能的跨版本安全门面。
 *
 * 除 1.21.4 外的 MC 版本（没有 chesttracker 依赖）所有方法都是安全的 no-op，
 * 共享代码可以直接调用而不需要预处理器分支。
 * 注意：1.21.4 专属内容必须用 //$$ 前缀（否则基础版本直接编译根源码时会引入 chesttracker 依赖）。
 */
public final class ChestTrackerBridge {
    private ChestTrackerBridge() {
    }

    public static boolean isChestTrackerLoaded() {
        return ModLoadUtils.isChestTrackerLoaded();
    }

    /** 世界加入/退出时加载/保存打印机库存（InitHandler 调用） */
    public static void init() {
        //#if MC == 12104
        //$$ if (!isChestTrackerLoaded()) {
        //$$     return;
        //$$ }
        //$$ net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
        //$$         (handler, sender, client) -> client.execute(() -> {
        //$$             PrinterMemory.createOrLoad();
        //$$             BoxIndex.load();
        //$$         }));
        //$$ net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
        //$$         (handler, client) -> {
        //$$             PrinterMemory.unload();
        //$$             BoxIndex.unload();
        //$$         });
        //#endif
    }

    /** 每客户端 tick 驱动各状态机（ClientPlayerTickManager 调用） */
    public static void tick() {
        //#if MC == 12104
        //$$ if (!isChestTrackerLoaded()) {
        //$$     return;
        //$$ }
        //$$ try {
        //$$     RemoteChestOpener.tick();
        //$$     ChestTakeController.tick();
        //$$     ChestTrackerAdder.tick();
        //$$     BoxIndex.tick();
        //$$ } catch (Exception e) {
        //$$     me.aleksilassila.litematica.printer.Reference.LOGGER.warn("[ChestTracker] tick 异常", e);
        //$$     ChestTrackerAdder.abort();
        //$$     RemoteChestOpener.closeAndReset();
        //$$ }
        //#endif
    }

    /** 容器内容包到达（MixinClientPacketListener 调用） */
    public static void onContainerContent() {
        //#if MC == 12104
        //$$ if (!isChestTrackerLoaded()) {
        //$$     return;
        //$$ }
        //$$ RemoteChestOpener.onContainerContent();
        //$$ // 玩家手动开箱（非远程操作）：刷新盒内容索引。
        //$$ // 远程操作时 RemoteChestOpener 仍 active，由取物/添加流程自己刷新，这里不会重复。
        //$$ if (!RemoteChestOpener.isActive()) {
        //$$     BoxIndex.refreshCurrentOpen();
        //$$ }
        //#endif
    }

    /** 远程操作进行中时抑制容器界面（MixinContainerScreenGuard 调用） */
    public static boolean shouldSuppressContainerScreen() {
        //#if MC == 12104
        //$$ if (!isChestTrackerLoaded()) {
        //$$     return false;
        //$$ }
        //$$ return RemoteChestOpener.isActive() || ChestTakeController.isAwaiting() || ChestTrackerAdder.isActive();
        //#else
        return false;
        //#endif
    }

    /** 是否有取物在等待（打印暂停判断用） */
    public static boolean isAwaitingStack() {
        //#if MC == 12104
        //$$ return isChestTrackerLoaded() && ChestTakeController.isAwaiting();
        //#else
        return false;
        //#endif
    }

    /** 远程开箱是否进行中（内容包未到） */
    public static boolean isRemoteOpening() {
        //#if MC == 12104
        //$$ return isChestTrackerLoaded() && RemoteChestOpener.isActive();
        //#else
        return false;
        //#endif
    }

    /** 远程开箱是否在等内容包 */
    public static boolean isAwaitingRemoteContent() {
        //#if MC == 12104
        //$$ return isChestTrackerLoaded() && RemoteChestOpener.isAwaitingContent();
        //#else
        return false;
        //#endif
    }

    /** 添加库存是否进行中 */
    public static boolean isAdderActive() {
        //#if MC == 12104
        //$$ return isChestTrackerLoaded() && ChestTrackerAdder.isActive();
        //#else
        return false;
        //#endif
    }

    /** 打印缺料时发起远程取物（PrintPlacementExecutor 调用） */
    public static boolean requestMissingItem(Item[] requiredItems) {
        //#if MC == 12104
        //$$ if (!isChestTrackerLoaded() || requiredItems == null || requiredItems.length == 0) {
        //$$     me.aleksilassila.litematica.printer.Reference.LOGGER.info("[ChestTracker] 缺料取物: 前置不满足 ctLoaded={} items={}", isChestTrackerLoaded(), requiredItems == null ? "null" : requiredItems.length);
        //$$     return false;
        //$$ }
        //$$ LocalPlayer player = Minecraft.getInstance().player;
        //$$ if (player == null) {
        //$$     me.aleksilassila.litematica.printer.Reference.LOGGER.info("[ChestTracker] 缺料取物: 玩家为空");
        //$$     return false;
        //$$ }
        //$$ for (Item item : requiredItems) {
        //$$     if (item != null && item != Items.AIR) {
        //$$         return ChestTakeController.requestItem(item);
        //$$     }
        //$$ }
        //$$ me.aleksilassila.litematica.printer.Reference.LOGGER.info("[ChestTracker] 缺料取物: 所需物品为空(AIR)");
        //$$ return false;
        //#else
        return false;
        //#endif
    }

    /** CT 屏幕右键取物（ItemListWidgetMixin 调用） */
    public static boolean takeFromScreen(ItemStack stack) {
        //#if MC == 12104
        //$$ return isChestTrackerLoaded() && ChestTakeController.requestFromScreen(stack);
        //#else
        return false;
        //#endif
    }

    /** 添加快捷键回调 */
    public static void startAddPrinterInventory() {
        //#if MC == 12104
        //$$ try {
        //$$     if (isChestTrackerLoaded()) {
        //$$         ChestTrackerAdder.start();
        //$$     } else {
        //$$         MessageUtils.setOverlayMessage("未检测到 Chest Tracker");
        //$$     }
        //$$ } catch (Exception e) {
        //$$     me.aleksilassila.litematica.printer.Reference.LOGGER.warn("[ChestTracker] 添加快捷键回调异常", e);
        //$$     MessageUtils.setOverlayMessage("添加库存异常: " + e.getClass().getSimpleName());
        //$$ }
        //#endif
    }

    /** 清空快捷键回调 */
    public static void clearPrinterMemory() {
        //#if MC == 12104
        //$$ try {
        //$$     if (isChestTrackerLoaded()) {
        //$$         PrinterMemory.clear();
        //$$         BoxIndex.clear();
        //$$         MessageUtils.setOverlayMessage("打印机库存已清空");
        //$$     } else {
        //$$         MessageUtils.setOverlayMessage("未检测到 Chest Tracker");
        //$$     }
        //$$ } catch (Exception e) {
        //$$     me.aleksilassila.litematica.printer.Reference.LOGGER.warn("[ChestTracker] 清空快捷键回调异常", e);
        //$$     MessageUtils.setOverlayMessage("清空库存异常: " + e.getClass().getSimpleName());
        //$$ }
        //#endif
    }

    /** 中断所有进行中的远程操作（远程取物开关关闭时调用） */
    public static void abortRemoteOps() {
        //#if MC == 12104
        //$$ if (!isChestTrackerLoaded()) {
        //$$     return;
        //$$ }
        //$$ ChestTakeController.abort();
        //$$ ChestTrackerAdder.abort();
        //$$ RemoteChestOpener.closeAndReset();
        //#endif
    }
}
