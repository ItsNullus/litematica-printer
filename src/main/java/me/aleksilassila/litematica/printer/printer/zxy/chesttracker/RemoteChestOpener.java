package me.aleksilassila.litematica.printer.printer.zxy.chesttracker;

//#if MC == 12104
//$$ import me.aleksilassila.litematica.printer.Reference;
//$$ import me.aleksilassila.litematica.printer.mixin_extension.MultiPlayerGameModeExtension;
//$$ import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
//$$ import net.minecraft.client.Minecraft;
//$$ import net.minecraft.client.player.LocalPlayer;
//$$ import net.minecraft.core.BlockPos;
//$$ import net.minecraft.core.Direction;
//$$ import net.minecraft.resources.ResourceKey;
//$$ import net.minecraft.world.InteractionHand;
//$$ import net.minecraft.world.InteractionResult;
//$$ import net.minecraft.world.inventory.AbstractContainerMenu;
//$$ import net.minecraft.world.level.Level;
//$$ import net.minecraft.world.phys.BlockHitResult;
//$$ import net.minecraft.world.phys.Vec3;

//$$ /**
//$$  * 远程开箱状态机。
//$$  *
//$$  * 复用 v4 现成的 litematica_printer$useItemOn(false, ...) 直接发送 ServerboundUseItemOnPacket，
//$$  * 绕过客户端交互距离检测（只查世界边界）。服务端 reach 足够高时即可远程打开同维度容器，
//$$  * 无需服务端安装任何 mod。
//$$  *
//$$  * 打开后等待容器内容包（handleContainerContent），到达后交给当前控制器
//$$  * （添加库存 ChestTrackerAdder / 远程取物 ChestTakeController）处理。
//$$  */
//$$ public final class RemoteChestOpener {
//$$     private static final long OPEN_TIMEOUT_MS = 3000L;

//$$     private static BlockPos openPos;
//$$     private static ResourceKey<Level> openKey;
//$$     private static long openedAtMs;
//$$     private static boolean awaitingContent;
//$$     private static boolean active;

//$$     private RemoteChestOpener() {
//$$     }

//$$     /**
//$$      * 远程打开容器。仅支持与玩家同维度的容器（服务端按玩家当前维度处理交互包）。
//$$      *
//$$      * @return 是否成功发出打开请求
//$$      */
//$$     public static boolean open(BlockPos pos, ResourceKey<Level> dim) {
//$$         Minecraft mc = Minecraft.getInstance();
//$$         if (pos == null || dim == null || mc.player == null || mc.level == null) {
//$$             Reference.LOGGER.warn("[ChestTracker] open 前置失败: pos={} dim={} player={} level={}", pos, dim, mc.player != null, mc.level != null);
//$$             return false;
//$$         }
//$$         if (active) {
//$$             Reference.LOGGER.warn("[ChestTracker] open 被拒绝: 已有远程操作进行中");
//$$             return false;
//$$         }
//$$         // 只支持同维度
//$$         if (!dim.equals(mc.level.dimension())) {
//$$             MessageUtils.setOverlayMessage("远程开箱仅支持当前维度");
//$$             Reference.LOGGER.warn("[ChestTracker] open 被拒绝: 维度不匹配 target={} current={}", dim, mc.level.dimension());
//$$             return false;
//$$         }
//$$         LocalPlayer player = mc.player;
//$$         if (!player.containerMenu.equals(player.inventoryMenu)) {
//$$             player.closeContainer();
//$$         }
//$$         if (!(mc.gameMode instanceof MultiPlayerGameModeExtension extension)) {
//$$             Reference.LOGGER.warn("[ChestTracker] open 被拒绝: gameMode 不是 MultiPlayerGameModeExtension");
//$$             return false;
//$$         }
//$$         BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
//$$         InteractionResult result = extension.litematica_printer$useItemOn(false, InteractionHand.MAIN_HAND, hit);
//$$         if (result == InteractionResult.FAIL) {
//$$             Reference.LOGGER.warn("[ChestTracker] open 被拒绝: useItemOn 返回 FAIL");
//$$             return false;
//$$         }
//$$         openPos = pos.immutable();
//$$         openKey = dim;
//$$         openedAtMs = System.currentTimeMillis();
//$$         awaitingContent = true;
//$$         active = true;
//$$         Reference.LOGGER.info("[ChestTracker] 已发送远程开箱请求 pos={}", pos);
//$$         return true;
//$$     }

//$$     public static boolean isActive() {
//$$         return active;
//$$     }

//$$     public static boolean isAwaitingContent() {
//$$         return awaitingContent;
//$$     }
//$$
//$$     /** 最近一次开箱是否超时（包已发但无响应），供调用方跳过该箱子 */
//$$     private static boolean timedOut;
//$$
//$$     public static boolean consumeTimedOut() {
//$$         boolean t = timedOut;
//$$         timedOut = false;
//$$         return t;
//$$     }

//$$     public static BlockPos getOpenPos() {
//$$         return openPos;
//$$     }

//$$     public static ResourceKey<Level> getOpenKey() {
//$$         return openKey;
//$$     }

//$$     /** 由 MixinClientPacketListener 在容器内容包到达时调用 */
//$$     public static void onContainerContent() {
//$$         if (!active || !awaitingContent) {
//$$             return;
//$$         }
//$$         Minecraft mc = Minecraft.getInstance();
//$$         if (mc.player == null) {
//$$             reset();
//$$             return;
//$$         }
//$$         AbstractContainerMenu menu = mc.player.containerMenu;
//$$         if (menu.equals(mc.player.inventoryMenu)) {
//$$             reset();
//$$             return;
//$$         }
//$$         awaitingContent = false;
//$$         try {
//$$             if (ChestTrackerAdder.isActive()) {
//$$                 ChestTrackerAdder.onRemoteContainerContent(menu);
//$$             } else if (ChestTakeController.isAwaiting()) {
//$$                 ChestTakeController.onRemoteContainerContent(menu);
//$$             } else {
//$$                 reset();
//$$             }
//$$         } catch (Exception e) {
//$$             Reference.LOGGER.warn("[ChestTracker] 远程容器内容处理异常", e);
//$$             MessageUtils.setOverlayMessage("远程取物异常: " + e.getClass().getSimpleName());
//$$             closeAndReset();
//$$         }
//$$     }

//$$     public static void tick() {
//$$         if (!active) {
//$$             return;
//$$         }
//$$         Minecraft mc = Minecraft.getInstance();
//$$         // 单人 = 本地服务端, 响应应瞬时 → 极短超时; 多人 = 0.5s 视为最高 500ms ping
//$$         long timeoutMs = mc.getSingleplayerServer() != null ? 100L : 500L;
//$$         if (System.currentTimeMillis() - openedAtMs > timeoutMs) {
//$$             timedOut = true;
//$$             Reference.LOGGER.warn("[ChestTracker] 远程开箱超时 pos={} block={}",
//$$                     openPos,
//$$                     openPos != null && mc.level != null
//$$                             ? mc.level.getBlockState(openPos).getBlock() : "?");
//$$             MessageUtils.setOverlayMessage(me.aleksilassila.litematica.printer.I18n.REMOTE_OPEN_TIMEOUT.getName());
//$$             closeAndReset();
//$$         }
//$$     }

//$$     /** 关闭可能打开的容器并复位（取物/添加流程失败或完成时调用） */
//$$     public static void closeAndReset() {
//$$         Minecraft mc = Minecraft.getInstance();
//$$         if (mc.player != null && !mc.player.containerMenu.equals(mc.player.inventoryMenu)) {
//$$             mc.player.closeContainer();
//$$         }
//$$         reset();
//$$     }

//$$     public static void reset() {
//$$         openPos = null;
//$$         openKey = null;
//$$         awaitingContent = false;
//$$         active = false;
//$$         timedOut = false;
//$$     }
//$$ }
//#endif
