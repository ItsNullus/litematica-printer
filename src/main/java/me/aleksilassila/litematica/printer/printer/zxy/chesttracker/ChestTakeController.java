package me.aleksilassila.litematica.printer.printer.zxy.chesttracker;

//#if MC == 12104
//$$ import me.aleksilassila.litematica.printer.Reference;
//$$ import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
//$$ import net.minecraft.client.Minecraft;
//$$ import net.minecraft.client.player.LocalPlayer;
//$$ import net.minecraft.core.BlockPos;
//$$ import net.minecraft.core.registries.Registries;
//$$ import net.minecraft.resources.ResourceKey;
//$$ import net.minecraft.resources.ResourceLocation;
//$$ import net.minecraft.world.inventory.AbstractContainerMenu;
//$$ import net.minecraft.world.inventory.ClickType;
//$$ import net.minecraft.world.item.Item;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import net.minecraft.world.level.Level;
//$$ import red.jackf.chesttracker.api.memory.Memory;
//$$ import red.jackf.chesttracker.api.providers.MemoryBuilder;
//$$ import red.jackf.chesttracker.api.providers.ProviderUtils;
//$$ import red.jackf.chesttracker.impl.memory.MemoryBankAccessImpl;
//$$ import red.jackf.chesttracker.impl.memory.MemoryBankImpl;

//$$ import java.util.ArrayList;
//$$ import java.util.Comparator;
//$$ import java.util.HashMap;
//$$ import java.util.List;
//$$ import java.util.Map;

//$$ /**
//$$  * 远程取物状态机。
//$$  *
//$$  * 三阶段取物：
//$$  * 1. O(1) 盒内容索引定位（BoxIndex：箱子 → 盒内物品，取物只需开目标箱子）
//$$  * 2. 批量快速扫描兜底（记忆里无直接匹配时，逐个立即连续开箱验证实时内容，顺带构建索引）
//$$  * 3. 未命中冷却（全扫一遍没有 → 20 秒内不再扫，期间重复请求直接跳过）
//$$  *
//$$  * 流程：定位/扫描到箱子 → 远程开箱 → 内容包到达后点击目标槽位到背包 → 刷新索引/记忆 → 关箱 → 等同步。
//$$  */
//$$ public final class ChestTakeController {
//$$     private static final long TAKE_TIMEOUT_MS = 4000L;
//$$     private static final long SCAN_TIMEOUT_MS = 8000L;
//$$     private static final long SETTLE_TIMEOUT_MS_SINGLE = 100L;
//$$     private static final long SETTLE_TIMEOUT_MS_MULTI = 500L;

//$$     private static long settleTimeoutMs() {
//$$         Minecraft mc = Minecraft.getInstance();
//$$         return mc.getSingleplayerServer() != null ? SETTLE_TIMEOUT_MS_SINGLE : SETTLE_TIMEOUT_MS_MULTI;
//$$     }
//$$     private static final long NOT_FOUND_COOLDOWN_MS = 20000L;

//$$     private static Item requestedItem;
//$$     private static ItemStack requestedStack; // 屏幕点击时为精确匹配样本
//$$     private static boolean exactMatch;       // true = CT 屏幕右键（isSameItemSameComponents），false = 打印机缺料（按 Item）
//$$     private static ResourceLocation targetKey; // 记忆库 key（= 维度 id）
//$$     private static BlockPos targetPos;
//$$     private static boolean awaiting;
//$$     private static long startedAtMs;
//$$     private static int initialCount;
//$$     private static boolean settling;
//$$     private static long settleStartedAtMs;
//$$     private static boolean nestedTake;      // 取的是潜影盒(内含目标物品)
//$$     private static int initialShulkerCount;

//$$     // 批量扫描状态
//$$     private static boolean scanning;
//$$     private static List<BlockPos> scanQueue = new ArrayList<>();
//$$     private static int scanIndex;

//$$     private static final Map<Item, Long> lastFailedAt = new HashMap<>();

//$$     private ChestTakeController() {
//$$     }

//$$     /** 打印机缺料请求：O(1) 索引 → 批量扫描兜底 */
//$$     public static boolean requestItem(Item item) {
//$$         if (item == null) {
//$$             return false;
//$$         }
//$$         if (awaiting) {
//$$             return true;
//$$         }
//$$         Minecraft mc = Minecraft.getInstance();
//$$         if (mc.player == null || mc.level == null) {
//$$             return false;
//$$         }
//$$         // 背包满预检（预留 2 格：取盒 + 余量）→ 不开箱直接失败，避免阻塞
//$$         if (countEmptyInventorySlots() < 2) {
//$$             Reference.LOGGER.info("[ChestTracker] 取物: 背包空位不足(需2格, 当前{})", countEmptyInventorySlots());
//$$             return false;
//$$         }
//$$         ResourceLocation currentDim = mc.level.dimension().location();
//$$         // 阶段 1：O(1) 索引定位
//$$         BoxIndex.Candidate candidate = BoxIndex.findItem(item, currentDim);
//$$         if (candidate != null) {
//$$             return startIndexedTake(item, candidate);
//$$         }
//$$         // 全扫未命中冷却
//$$         Long lastFailed = lastFailedAt.get(item);
//$$         if (lastFailed != null && System.currentTimeMillis() - lastFailed < NOT_FOUND_COOLDOWN_MS) {
//$$             return false;
//$$         }
//$$         Reference.LOGGER.info("[ChestTracker] 缺料取物: 请求 {}", item.getName().getString());
//$$         // 阶段 2：批量扫描（顺带构建索引）
//$$         if (startScan(item, null, false)) {
//$$             lastFailedAt.remove(item);
//$$             return true;
//$$         }
//$$         lastFailedAt.put(item, System.currentTimeMillis());
//$$         return false;
//$$     }

//$$     /** CT 屏幕右键请求：索引 → 记忆精确搜索 → 扫描兜底 */
//$$     public static boolean requestFromScreen(ItemStack stack) {
//$$         if (stack == null || stack.isEmpty()) {
//$$             return false;
//$$         }
//$$         if (awaiting) {
//$$             return true;
//$$         }
//$$         Minecraft mc = Minecraft.getInstance();
//$$         if (mc.player == null || mc.level == null) {
//$$             return false;
//$$         }
//$$         if (countEmptyInventorySlots() < 2) {
//$$             MessageUtils.setOverlayMessage(me.aleksilassila.litematica.printer.I18n.REMOTE_TAKE_INV_FULL.getName());
//$$             return false;
//$$         }
//$$         ResourceLocation currentDim = mc.level.dimension().location();
//$$         BoxIndex.Candidate candidate = BoxIndex.findItem(stack.getItem(), currentDim);
//$$         if (candidate != null) {
//$$             return startIndexedTake(stack.getItem(), candidate);
//$$         }
//$$         if (start(stack.getItem(), stack, true)) {
//$$             return true;
//$$         }
//$$         return startScan(stack.getItem(), stack, true);
//$$     }

//$$     public static boolean isAwaiting() {
//$$         return awaiting;
//$$     }

//$$     // ========== 取物启动 ==========

//$$     /** 索引命中：直接开目标箱子 */
//$$     private static boolean startIndexedTake(Item item, BoxIndex.Candidate candidate) {
//$$         Reference.LOGGER.info("[ChestTracker] 索引命中: {} -> {} 槽位 {}", item.getName().getString(), candidate.pos(), candidate.slot());
//$$         return begin(item, null, false, candidate.dim(), candidate.pos());
//$$     }

//$$     /** 记忆搜索启动（精确匹配：CT 屏幕右键） */
//$$     private static boolean start(Item item, ItemStack stack, boolean exact) {
//$$         Found found = findContainer(item, stack, exact);
//$$         if (found == null) {
//$$             return false;
//$$         }
//$$         return begin(item, stack, exact, found.key, found.pos);
//$$     }

//$$     /** 批量扫描启动：把当前维度候选箱子排成队列，逐个立即连续开箱 */
//$$     private static boolean startScan(Item item, ItemStack stack, boolean exact) {
//$$         List<BlockPos> queue = buildScanQueue(exact);
//$$         if (queue.isEmpty()) {
//$$             Reference.LOGGER.info("[ChestTracker] 取物扫描: 无候选箱子 ({})", item.getName().getString());
//$$             return false;
//$$         }
//$$         Reference.LOGGER.info("[ChestTracker] 取物扫描: {} 个候选箱子, 开始扫描 {}", queue.size(), item.getName().getString());
//$$         Minecraft mc = Minecraft.getInstance();
//$$         ResourceLocation currentDim = mc.level.dimension().location();
//$$         scanQueue = queue;
//$$         scanIndex = 0;
//$$         scanning = true;
//$$         requestedItem = item;
//$$         requestedStack = exact ? stack.copy() : null;
//$$         exactMatch = exact;
//$$         targetKey = currentDim;
//$$         initialCount = countInventoryItem(item);
//$$         initialShulkerCount = countShulkerBoxes();
//$$         nestedTake = false;
//$$         awaiting = true;
//$$         settling = false;
//$$         startedAtMs = System.currentTimeMillis();
//$$         if (!openScanNext()) {
//$$             awaiting = false;
//$$             scanning = false;
//$$             return false;
//$$         }
//$$         MessageUtils.setOverlayMessage("远程取物: " + item.getName().getString() + " ...");
//$$         return true;
//$$     }

//$$     private static boolean openScanNext() {
//$$         // 清掉可能的开箱超时标记，避免残留影响后续添加流程
//$$         RemoteChestOpener.consumeTimedOut();
//$$         if (scanIndex >= scanQueue.size()) {
//$$             finishScan(false);
//$$             return false;
//$$         }
//$$         BlockPos pos = scanQueue.get(scanIndex);
//$$         targetPos = pos;
//$$         if (!RemoteChestOpener.open(pos, ResourceKey.create(Registries.DIMENSION, targetKey))) {
//$$             scanIndex++;
//$$             return openScanNext();
//$$         }
//$$         return true;
//$$     }

//$$     private static void finishScan(boolean found) {
//$$         scanning = false;
//$$         if (!found && requestedItem != null) {
//$$             lastFailedAt.put(requestedItem, System.currentTimeMillis());
//$$             Reference.LOGGER.info("[ChestTracker] 取物扫描: 全部 {} 个箱子扫描完, 未找到 {}", scanQueue.size(), requestedItem.getName().getString());
//$$         }
//$$     }

//$$     /** 设置状态并远程开箱 */
//$$     private static boolean begin(Item item, ItemStack stack, boolean exact, ResourceLocation dim, BlockPos pos) {
//$$         requestedItem = item;
//$$         requestedStack = exact ? stack.copy() : null;
//$$         exactMatch = exact;
//$$         targetKey = dim;
//$$         targetPos = pos;
//$$         initialCount = countInventoryItem(item);
//$$         initialShulkerCount = countShulkerBoxes();
//$$         nestedTake = false;
//$$         scanning = false;
//$$         awaiting = true;
//$$         settling = false;
//$$         startedAtMs = System.currentTimeMillis();
//$$         if (!RemoteChestOpener.open(targetPos, ResourceKey.create(Registries.DIMENSION, targetKey))) {
//$$             awaiting = false;
//$$             return false;
//$$         }
//$$         MessageUtils.setOverlayMessage(me.aleksilassila.litematica.printer.I18n.REMOTE_TAKE_START.getName(item.getName().getString()));
//$$         return true;
//$$     }
//$$
//$$     private static List<BlockPos> buildScanQueue(boolean exact) {
//$$         Minecraft mc = Minecraft.getInstance();
//$$         LocalPlayer player = mc.player;
//$$         if (player == null) {
//$$             return List.of();
//$$         }
//$$         ResourceLocation currentKey = ProviderUtils.getPlayersCurrentKey().orElse(null);
//$$         if (currentKey == null) {
//$$             return List.of();
//$$         }
//$$         // 候选 = 盒内容索引的箱子（玩家开过/添加过的，内容真实） ∪ 打印机记忆里有潜影盒的箱子
//$$         java.util.Set<BlockPos> queue = new java.util.LinkedHashSet<>();
//$$         try {
//$$             queue.addAll(BoxIndex.getChestsInDimension(currentKey));
//$$         } catch (Exception ignored) {
//$$         }
//$$         if (!exact) {
//$$             MemoryBankImpl bank = PrinterMemory.get();
//$$             if (bank != null && bank.getMemories() != null) {
//$$                 var keyEntry = bank.getMemories().get(currentKey);
//$$                 if (keyEntry != null) {
//$$                     for (var posEntry : keyEntry.getMemories().entrySet()) {
//$$                         if (hasShulkerBox(posEntry.getValue())) {
//$$                             queue.add(posEntry.getKey());
//$$                         }
//$$                     }
//$$                 }
//$$             }
//$$         } else {
//$$             MemoryBankImpl bank = MemoryBankAccessImpl.INSTANCE.getLoadedInternal().orElse(null);
//$$             if (bank != null && bank.getMemories() != null) {
//$$                 var keyEntry = bank.getMemories().get(currentKey);
//$$                 if (keyEntry != null) {
//$$                     queue.addAll(keyEntry.getMemories().keySet());
//$$                 }
//$$             }
//$$         }
//$$         List<BlockPos> sorted = new ArrayList<>(queue);
//$$         // 预过滤：跳过未加载区块内的箱子（开箱必然超时，白等）
//$$         if (mc.level != null) {
//$$             sorted.removeIf(pos -> !mc.level.isLoaded(pos));
//$$         }
//$$         sorted.sort(Comparator.comparingDouble(p -> player.distanceToSqr(p.getCenter())));
//$$         return sorted;
//$$     }

//$$     private static boolean hasShulkerBox(Memory memory) {
//$$         if (memory == null || memory.items() == null) {
//$$             return false;
//$$         }
//$$         for (ItemStack stack : memory.items()) {
//$$             if (isShulkerBoxStack(stack)) {
//$$                 return true;
//$$             }
//$$         }
//$$         return false;
//$$     }

//$$     private record Found(ResourceLocation key, BlockPos pos) {
//$$     }

//$$     private static Found findContainer(Item item, ItemStack stack, boolean exact) {
//$$         MemoryBankImpl bank = exact
//$$                 ? MemoryBankAccessImpl.INSTANCE.getLoadedInternal().orElse(null)
//$$                 : PrinterMemory.get();
//$$         if (bank == null) {
//$$             return null;
//$$         }
//$$         Minecraft mc = Minecraft.getInstance();
//$$         LocalPlayer player = mc.player;
//$$         if (player == null) {
//$$             return null;
//$$         }
//$$         ResourceLocation currentKey = ProviderUtils.getPlayersCurrentKey().orElse(null);
//$$         double bestDistance = Double.MAX_VALUE;
//$$         Found best = null;
//$$         for (var keyEntry : bank.getMemories().entrySet()) {
//$$             if (currentKey != null && !keyEntry.getKey().equals(currentKey)) {
//$$                 continue;
//$$             }
//$$             for (var posEntry : keyEntry.getValue().getMemories().entrySet()) {
//$$                 Memory memory = posEntry.getValue();
//$$                 for (ItemStack memoryStack : memory.items()) {
//$$                     if (stackMatchesTarget(memoryStack)) {
//$$                         double distance = player.distanceToSqr(posEntry.getKey().getCenter());
//$$                         if (distance < bestDistance) {
//$$                             bestDistance = distance;
//$$                             best = new Found(keyEntry.getKey(), posEntry.getKey());
//$$                         }
//$$                         break;
//$$                     }
//$$                 }
//$$             }
//$$         }
//$$         return best;
//$$     }

//$$     // ========== 容器内容处理 ==========

//$$     /** 由 RemoteChestOpener 在容器内容包到达时调用 */
//$$     public static void onRemoteContainerContent(AbstractContainerMenu menu) {
//$$         if (!awaiting) {
//$$             return;
//$$         }
//$$         try {
//$$         Minecraft mc = Minecraft.getInstance();
//$$         LocalPlayer player = mc.player;
//$$         if (player == null || mc.gameMode == null) {
//$$             fail("远程取物失败");
//$$             return;
//$$         }
//$$         int containerSize = getContainerSize(menu);
//$$         if (containerSize <= 0) {
//$$             fail("远程取物失败");
//$$             return;
//$$         }
//$$         // 找目标槽位：优先直接匹配，其次找装有目标物品的潜影盒
//$$         int fromSlot = -1;
//$$         for (int i = 0; i < containerSize; i++) {
//$$             ItemStack slotStack = menu.slots.get(i).getItem();
//$$             if (!slotStack.isEmpty() && matches(slotStack)) {
//$$                 fromSlot = i;
//$$                 break;
//$$             }
//$$         }
//$$         if (fromSlot < 0) {
//$$             for (int i = 0; i < containerSize; i++) {
//$$                 ItemStack slotStack = menu.slots.get(i).getItem();
//$$                 if (!slotStack.isEmpty() && isShulkerBoxStack(slotStack) && shulkerContains(slotStack)) {
//$$                     fromSlot = i;
//$$                     nestedTake = true;
//$$                     Reference.LOGGER.info("[ChestTracker] 取物: 从潜影盒中取 {} (槽位 {})", requestedItem.getName().getString(), i);
//$$                     break;
//$$                 }
//$$             }
//$$         }
//$$         if (fromSlot < 0) {
//$$             // 无论如何刷新索引（盒内真实内容现在知道了，供后续 O(1) 定位）
//$$             BoxIndex.refreshFromMenu(targetKey, targetPos, menu, containerSize);
//$$             if (scanning) {
//$$                 player.closeContainer();
//$$                 RemoteChestOpener.reset();
//$$                 scanIndex++;
//$$                 Reference.LOGGER.info("[ChestTracker] 取物扫描: 箱子 {} 无目标, 继续下一个 ({}/{})",
//$$                         targetPos, scanIndex, scanQueue.size());
//$$                 return;
//$$             }
//$$             fail(me.aleksilassila.litematica.printer.I18n.REMOTE_TAKE_NO_ITEM.getName().getString());
//$$             return;
//$$         }
//$$         // 找落点（优先空热栏，其次空背包，其次同物品可合并）
//$$         int destPlayerSlot = findDestinationPlayerSlot(menu, containerSize);
//$$         if (destPlayerSlot < 0) {
//$$             fail(me.aleksilassila.litematica.printer.I18n.REMOTE_TAKE_INV_FULL.getName().getString());
//$$             return;
//$$         }
//$$         int destMenuSlot = playerSlotToMenuSlot(destPlayerSlot, containerSize);
//$$         // 点击取出
//$$         mc.gameMode.handleInventoryMouseClick(menu.containerId, fromSlot, 0, ClickType.PICKUP, player);
//$$         mc.gameMode.handleInventoryMouseClick(menu.containerId, destMenuSlot, 0, ClickType.PICKUP, player);
//$$         if (!menu.getCarried().isEmpty()) {
//$$             mc.gameMode.handleInventoryMouseClick(menu.containerId, fromSlot, 0, ClickType.PICKUP, player);
//$$         }
//$$         // 用取走后的容器状态回写记忆 + 盒内容索引
//$$         recordRemaining(menu, containerSize);
//$$         BoxIndex.refreshFromMenu(targetKey, targetPos, menu, containerSize);
//$$         player.closeContainer();
//$$         if (scanning) {
//$$             finishScan(true);
//$$         }
//$$         settling = true;
//$$         settleStartedAtMs = System.currentTimeMillis();
//$$         } catch (Exception e) {
//$$             Reference.LOGGER.warn("[ChestTracker] 远程取物处理异常", e);
//$$             fail(me.aleksilassila.litematica.printer.I18n.REMOTE_TAKE_EXCEPTION.getName(e.getClass().getSimpleName()).getString());
//$$         }
//$$     }

//$$     private static boolean matches(ItemStack stack) {
//$$         return exactMatch
//$$                 ? ItemStack.isSameItemSameComponents(stack, requestedStack)
//$$                 : stack.is(requestedItem);
//$$     }

//$$     /** 物品是否装有目标物品（直接匹配或潜影盒内匹配） */
//$$     private static boolean stackMatchesTarget(ItemStack stack) {
//$$         if (stack == null || stack.isEmpty()) {
//$$             return false;
//$$         }
//$$         if (matches(stack)) {
//$$             return true;
//$$         }
//$$         return isShulkerBoxStack(stack) && shulkerContains(stack);
//$$     }

//$$     private static boolean isShulkerBoxStack(ItemStack stack) {
//$$         return stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem
//$$                 && blockItem.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock;
//$$     }

//$$     /** 潜影盒内是否包含目标物品 */
//$$     private static boolean shulkerContains(ItemStack shulkerStack) {
//$$         try {
//$$             net.minecraft.core.NonNullList<ItemStack> stored =
//$$                     fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(shulkerStack, -1);
//$$             for (ItemStack inner : stored) {
//$$                 if (!inner.isEmpty() && matches(inner)) {
//$$                     return true;
//$$                 }
//$$             }
//$$         } catch (Exception ignored) {
//$$         }
//$$         return false;
//$$     }

//$$     private static int getContainerSize(AbstractContainerMenu menu) {
//$$         if (menu.slots.isEmpty()) {
//$$             return 0;
//$$         }
//$$         return Math.min(menu.slots.size(), menu.slots.get(0).container.getContainerSize());
//$$     }

//$$     /**
//$$      * 玩家背包槽位(0-35) → 容器菜单槽位。
//$$      * 标准布局：容器槽 [0,size)，主背包 [size,size+27)（玩家 9-35），热栏 [size+27,size+36)（玩家 0-8）。
//$$      */
//$$     private static int playerSlotToMenuSlot(int playerSlot, int containerSize) {
//$$         return playerSlot < 9 ? containerSize + 27 + playerSlot : containerSize + playerSlot;
//$$     }

//$$     private static int findDestinationPlayerSlot(AbstractContainerMenu menu, int containerSize) {
//$$         // 空热栏
//$$         for (int i = 0; i < 9; i++) {
//$$             if (menu.slots.get(containerSize + 27 + i).getItem().isEmpty()) {
//$$                 return i;
//$$             }
//$$         }
//$$         // 空主背包
//$$         for (int i = 9; i < 36; i++) {
//$$             if (menu.slots.get(containerSize + i).getItem().isEmpty()) {
//$$                 return i;
//$$             }
//$$         }
//$$         // 同物品可合并
//$$         for (int i = 0; i < 36; i++) {
//$$             int menuSlot = playerSlotToMenuSlot(i, containerSize);
//$$             ItemStack stack = menu.slots.get(menuSlot).getItem();
//$$             if (!stack.isEmpty() && matches(stack) && stack.getCount() < stack.getMaxStackSize()) {
//$$                 return i;
//$$             }
//$$         }
//$$         return -1;
//$$     }

//$$     private static void recordRemaining(AbstractContainerMenu menu, int containerSize) {
//$$         MemoryBankImpl bank = exactMatch
//$$                 ? MemoryBankAccessImpl.INSTANCE.getLoadedInternal().orElse(null)
//$$                 : PrinterMemory.get();
//$$         if (bank == null) {
//$$             return;
//$$         }
//$$         List<ItemStack> items = new ArrayList<>();
//$$         for (int i = 0; i < containerSize; i++) {
//$$             ItemStack stack = menu.slots.get(i).getItem();
//$$             if (!stack.isEmpty()) {
//$$                 items.add(stack.copy());
//$$             }
//$$         }
//$$         Memory memory = MemoryBuilder.create(items).build();
//$$         bank.addMemory(targetKey, targetPos, memory);
//$$         if (!exactMatch) {
//$$             PrinterMemory.save();
//$$         }
//$$     }

//$$     public static void tick() {
//$$         if (!awaiting) {
//$$             return;
//$$         }
//$$         if (settling) {
//$$             boolean arrived = nestedTake
//$$                     ? countShulkerBoxes() > initialShulkerCount
//$$                     : countInventoryItem(requestedItem) > initialCount;
//$$             if (arrived) {
//$$                 complete();
//$$             } else if (System.currentTimeMillis() - settleStartedAtMs > settleTimeoutMs()) {
//$$                 fail(me.aleksilassila.litematica.printer.I18n.REMOTE_TAKE_NOT_SYNCED.getName().getString());
//$$             }
//$$             return;
//$$         }
//$$         if (scanning) {
//$$             // 上一个箱子已处理完（内容无目标时已关箱复位），继续下一个
//$$             if (!RemoteChestOpener.isActive()) {
//$$                 if (!openScanNext()) {
//$$                     // 队列扫完
//$$                     finishScan(false);
//$$                     awaiting = false;
//$$                     reset();
//$$                 }
//$$             }
//$$             if (System.currentTimeMillis() - startedAtMs > SCAN_TIMEOUT_MS) {
//$$                 fail(me.aleksilassila.litematica.printer.I18n.REMOTE_TAKE_SCAN_TIMEOUT.getName().getString());
//$$             }
//$$             return;
//$$         }
//$$         if (System.currentTimeMillis() - startedAtMs > TAKE_TIMEOUT_MS) {
//$$             fail(me.aleksilassila.litematica.printer.I18n.REMOTE_TAKE_TIMEOUT.getName().getString());
//$$         }
//$$     }

//$$     private static int countInventoryItem(Item item) {
//$$         Minecraft mc = Minecraft.getInstance();
//$$         if (mc.player == null || item == null) {
//$$             return 0;
//$$         }
//$$         int count = 0;
//$$         var inventory = mc.player.getInventory();
//$$         int size = Math.min(36, inventory.getContainerSize());
//$$         for (int i = 0; i < size; i++) {
//$$             ItemStack stack = inventory.getItem(i);
//$$             if (!stack.isEmpty() && stack.is(item)) {
//$$                 count += stack.getCount();
//$$             }
//$$         }
//$$         return count;
//$$     }

//$$     /** 背包空位数（热栏 + 主背包 36 格） */
//$$     private static int countEmptyInventorySlots() {
//$$         Minecraft mc = Minecraft.getInstance();
//$$         if (mc.player == null) {
//$$             return 0;
//$$         }
//$$         int count = 0;
//$$         var inventory = mc.player.getInventory();
//$$         int size = Math.min(36, inventory.getContainerSize());
//$$         for (int i = 0; i < size; i++) {
//$$             if (inventory.getItem(i).isEmpty()) {
//$$                 count++;
//$$             }
//$$         }
//$$         return count;
//$$     }
//$$
//$$     /** 背包中的潜影盒数量（未开启的整盒，count==1） */
//$$     private static int countShulkerBoxes() {
//$$         Minecraft mc = Minecraft.getInstance();
//$$         if (mc.player == null) {
//$$             return 0;
//$$         }
//$$         int count = 0;
//$$         var inventory = mc.player.getInventory();
//$$         int size = Math.min(36, inventory.getContainerSize());
//$$         for (int i = 0; i < size; i++) {
//$$             ItemStack stack = inventory.getItem(i);
//$$             if (!stack.isEmpty() && stack.getCount() == 1 && isShulkerBoxStack(stack)) {
//$$                 count++;
//$$             }
//$$         }
//$$         return count;
//$$     }

//$$     private static void complete() {
//$$         MessageUtils.setOverlayMessage(me.aleksilassila.litematica.printer.I18n.REMOTE_TAKE_COMPLETE.getName());
//$$         reset();
//$$     }
//$$
//$$     /** 玩家主动交互/手动中止：静默复位，让玩家操作优先 */
//$$     public static void abort() {
//$$         if (!awaiting) {
//$$             return;
//$$         }
//$$         MessageUtils.setOverlayMessage(me.aleksilassila.litematica.printer.I18n.REMOTE_TAKE_CANCELLED.getName());
//$$         RemoteChestOpener.closeAndReset();
//$$         reset();
//$$     }
//$$
//$$     private static void fail(String message) {
//$$         MessageUtils.setOverlayMessage(message);
//$$         RemoteChestOpener.closeAndReset();
//$$         reset();
//$$     }

//$$     private static void reset() {
//$$         requestedItem = null;
//$$         requestedStack = null;
//$$         exactMatch = false;
//$$         targetKey = null;
//$$         targetPos = null;
//$$         awaiting = false;
//$$         settling = false;
//$$         startedAtMs = 0L;
//$$         initialCount = 0;
//$$         settleStartedAtMs = 0L;
//$$         nestedTake = false;
//$$         initialShulkerCount = 0;
//$$         scanning = false;
//$$         scanQueue = new ArrayList<>();
//$$         scanIndex = 0;
//$$     }
//$$ }
//#endif
