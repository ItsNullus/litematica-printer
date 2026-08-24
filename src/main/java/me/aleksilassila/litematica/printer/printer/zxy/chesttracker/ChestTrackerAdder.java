package me.aleksilassila.litematica.printer.printer.zxy.chesttracker;

//#if MC == 12104
//$$ import me.aleksilassila.litematica.printer.Reference;
//$$ import me.aleksilassila.litematica.printer.printer.PrinterBox;
//$$ import me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils;
//$$ import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
//$$ import me.aleksilassila.litematica.printer.utils.mods.LitematicaUtils;
//$$ import net.minecraft.client.Minecraft;
//$$ import net.minecraft.client.player.LocalPlayer;
//$$ import net.minecraft.core.BlockPos;
//$$ import net.minecraft.resources.ResourceLocation;
//$$ import net.minecraft.world.inventory.AbstractContainerMenu;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import net.minecraft.world.level.block.Block;
//$$ import red.jackf.chesttracker.api.memory.Memory;
//$$ import red.jackf.chesttracker.api.providers.MemoryBuilder;
//$$ import red.jackf.chesttracker.api.providers.ProviderUtils;

//$$ import java.util.ArrayDeque;
//$$ import java.util.ArrayList;
//$$ import java.util.Deque;
//$$ import java.util.List;

//$$ /**
//$$  * 添加打印机库存状态机。
//$$  *
//$$  * 触发后扫描当前 litematica 选区内的所有容器方块，逐个远程开箱，
//$$  * 在容器内容包到达时把内容写入打印机库存（PrinterMemory），全程抑制界面。
//$$  */
//$$ public final class ChestTrackerAdder {
//$$     private static final Deque<BlockPos> pending = new ArrayDeque<>();
//$$     private static boolean active;
//$$     private static int total;

//$$     private ChestTrackerAdder() {
//$$     }

//$$     public static boolean isActive() {
//$$         return active;
//$$     }

//$$     public static void start() {
//$$         Minecraft mc = Minecraft.getInstance();
//$$         if (mc.player == null || mc.level == null) {
//$$             Reference.LOGGER.warn("[ChestTracker] 添加库存: 玩家/世界为空");
//$$             return;
//$$         }
//$$         if (active) {
//$$             Reference.LOGGER.info("[ChestTracker] 添加库存: 已在添加中");
//$$             return;
//$$         }
//$$         if (RemoteChestOpener.isActive() || ChestTakeController.isAwaiting()) {
//$$             MessageUtils.setOverlayMessage("请等待当前远程操作完成");
//$$             return;
//$$         }
//$$         try {
//$$             if (!PrinterMemory.isReady()) {
//$$                 PrinterMemory.createOrLoad();
//$$             }
//$$         } catch (Exception e) {
//$$             Reference.LOGGER.warn("[ChestTracker] 添加库存: 打印机库存初始化失败", e);
//$$             MessageUtils.setOverlayMessage("打印机库存初始化失败: " + e.getClass().getSimpleName());
//$$             return;
//$$         }
//$$         pending.clear();
//$$         List<PrinterBox> boxes = LitematicaUtils.createSelection1Boxes();
//$$         if (boxes.isEmpty()) {
//$$             MessageUtils.setOverlayMessage("请先创建 litematica 选区");
//$$             return;
//$$         }
//$$         for (PrinterBox box : boxes) {
//$$             for (BlockPos pos : BlockPos.betweenClosed(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) {
//$$                 if (InventoryUtils.isInventory(mc.level, pos)) {
//$$                     pending.add(pos.immutable());
//$$                 }
//$$             }
//$$         }
//$$         total = pending.size();
//$$         if (total == 0) {
//$$             MessageUtils.setOverlayMessage("选区内没有容器");
//$$             return;
//$$         }
//$$         active = true;
//$$         Reference.LOGGER.info("[ChestTracker] 开始添加打印机库存: {} 个容器", total);
//$$         MessageUtils.setOverlayMessage("开始添加打印机库存: " + total + " 个容器");
//$$     }

//$$     public static void tick() {
//$$         if (!active) {
//$$             return;
//$$         }
//$$         if (RemoteChestOpener.isActive()) {
//$$             return; // 等待当前容器处理完成
//$$         }
//$$         // 上一个箱子开箱超时（包已发但无响应）→ 跳过，避免卡死
//$$         if (RemoteChestOpener.consumeTimedOut()) {
//$$             BlockPos failed = pending.peek();
//$$             pending.poll();
//$$             Reference.LOGGER.warn("[ChestTracker] 添加库存: 箱子 {} 开箱超时, 跳过 (剩余 {} 个)", failed, pending.size());
//$$         }
//$$         Minecraft mc = Minecraft.getInstance();
//$$         if (mc.player == null || mc.level == null) {
//$$             abort();
//$$             return;
//$$         }
//$$         BlockPos pos = pending.peek();
//$$         if (pos == null) {
//$$             active = false;
//$$             PrinterMemory.save();
//$$             BoxIndex.save();
//$$             MessageUtils.setOverlayMessage("打印机库存添加完成");
//$$             return;
//$$         }
//$$         if (!RemoteChestOpener.open(pos, mc.level.dimension())) {
//$$             pending.poll();
//$$         if (pending.isEmpty()) {
//$$             active = false;
//$$             PrinterMemory.save();
//$$             BoxIndex.save();
//$$             MessageUtils.setOverlayMessage("打印机库存添加完成");
//$$             }
//$$         }
//$$     }

//$$     /** 由 RemoteChestOpener 在容器内容包到达时调用 */
//$$     public static void onRemoteContainerContent(AbstractContainerMenu menu) {
//$$         if (!active) {
//$$             return;
//$$         }
//$$         Minecraft mc = Minecraft.getInstance();
//$$         LocalPlayer player = mc.player;
//$$         if (player == null || mc.level == null || !PrinterMemory.isReady()) {
//$$             Reference.LOGGER.warn("[ChestTracker] 添加库存: 内容包到达但环境不完整 player={} level={} bank={}", player != null, mc.level != null, PrinterMemory.isReady());
//$$             cancelCurrent();
//$$             return;
//$$         }
//$$         BlockPos pos = RemoteChestOpener.getOpenPos();
//$$         if (pos == null) {
//$$             cancelCurrent();
//$$             return;
//$$         }
//$$         int containerSize = getContainerSize(menu);
//$$         if (containerSize <= 0) {
//$$             cancelCurrent();
//$$             return;
//$$         }
//$$         // 读取容器槽位
//$$         List<ItemStack> items = new ArrayList<>();
//$$         for (int i = 0; i < containerSize; i++) {
//$$             ItemStack stack = menu.slots.get(i).getItem();
//$$             if (!stack.isEmpty()) {
//$$                 items.add(stack.copy());
//$$             }
//$$         }
//$$         Block containerBlock = mc.level.getBlockState(pos).getBlock();
//$$         Memory memory = MemoryBuilder.create(items).inContainer(containerBlock).build();
//$$         ResourceLocation key = ProviderUtils.getPlayersCurrentKey().orElse(mc.level.dimension().location());
//$$         PrinterMemory.get().addMemory(key, pos, memory);
//$$         // 同步刷新盒内容索引（记忆会被 CT 剥掉盒内容，索引保留完整盒内物品 → O(1) 定位）
//$$         BoxIndex.refreshFromMenu(key, pos, menu, containerSize);
//$$         pending.poll();
//$$         Reference.LOGGER.info("[ChestTracker] 已记录容器 {} 剩余 {} 个", pos, pending.size());
//$$         // 关箱继续下一个
//$$         if (!player.containerMenu.equals(player.inventoryMenu)) {
//$$             player.closeContainer();
//$$         }
//$$         RemoteChestOpener.reset();
//$$     }

//$$     private static int getContainerSize(AbstractContainerMenu menu) {
//$$         if (menu.slots.isEmpty()) {
//$$             return 0;
//$$         }
//$$         return Math.min(menu.slots.size(), menu.slots.get(0).container.getContainerSize());
//$$     }

//$$     private static void cancelCurrent() {
//$$         Minecraft mc = Minecraft.getInstance();
//$$         if (mc.player != null && !mc.player.containerMenu.equals(mc.player.inventoryMenu)) {
//$$             mc.player.closeContainer();
//$$         }
//$$         RemoteChestOpener.reset();
//$$     }

//$$     public static void abort() {
//$$         cancelCurrent();
//$$         pending.clear();
//$$         active = false;
//$$     }
//$$ }
//#endif
