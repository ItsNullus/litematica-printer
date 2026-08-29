package me.aleksilassila.litematica.printer.printer.zxy.chesttracker;

//#if MC == 12104
//$$ import me.aleksilassila.litematica.printer.config.Configs;
//$$ import me.aleksilassila.litematica.printer.printer.PrinterBox;
//$$ import me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils;
//$$ import me.aleksilassila.litematica.printer.printer.zxy.inventory.SwitchItem;
//$$ import me.aleksilassila.litematica.printer.utils.ContainerGate;
//$$ import me.aleksilassila.litematica.printer.utils.InteractionUtils;
//$$ import me.aleksilassila.litematica.printer.utils.mods.LitematicaUtils;
//$$ import me.aleksilassila.litematica.printer.utils.mods.ModLoadUtils;
//$$ import me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils;
//$$ import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
//$$ import net.minecraft.client.Minecraft;
//$$ import net.minecraft.client.player.LocalPlayer;
//$$ import net.minecraft.core.BlockPos;
//$$ import net.minecraft.core.Direction;
//$$ import net.minecraft.world.InteractionHand;
//$$ import net.minecraft.world.InteractionResult;
//$$ import net.minecraft.world.inventory.AbstractContainerMenu;
//$$ import net.minecraft.world.inventory.ClickType;
//$$ import net.minecraft.world.inventory.Slot;
//$$ import net.minecraft.world.item.BlockItem;
//$$ import net.minecraft.world.item.Item;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import net.minecraft.world.level.block.ShulkerBoxBlock;
//$$ import net.minecraft.world.phys.BlockHitResult;
//$$ import net.minecraft.world.phys.Vec3;
//$$ import org.jetbrains.annotations.Nullable;
//$$ import red.jackf.chesttracker.api.memory.Memory;
//$$ import red.jackf.chesttracker.api.memory.MemoryBank;
//$$ import red.jackf.chesttracker.api.memory.MemoryBankAccess;
//$$ import red.jackf.chesttracker.api.memory.MemoryKey;
//$$ import red.jackf.chesttracker.api.providers.ProviderUtils;
//$$
//$$ import java.util.ArrayList;
//$$ import java.util.Comparator;
//$$ import java.util.HashMap;
//$$ import java.util.HashSet;
//$$ import java.util.LinkedHashSet;
//$$ import java.util.List;
//$$ import java.util.Map;
//$$ import java.util.Set;
//$$
//$$ /**
//$$  * Chest Tracker 远程取物状态机（对齐 master-4 ChestTrackerAdapter）。
//$$  *
//$$  * <p>候选来源：CT MemoryBank ∩ SelectedContainerCache ∩ 已加载区块。
//$$  * 嵌套潜影盒：取整盒 → 本地 TakeItOut/QuickShulker 取料 → 归还原槽。</p>
//$$  */
//$$ public final class ChestTakeController {
//$$     private static final int MAX_SCAN_CANDIDATES = 64;
//$$     private static final long OPEN_TIMEOUT_TICKS = 60L;
//$$     private static final long REQUEST_TIMEOUT_TICKS = 200L;
//$$     private static final long NOT_FOUND_COOLDOWN_TICKS = 400L;
//$$
//$$     private static final Minecraft client = Minecraft.getInstance();
//$$     private static final SelectedContainerCache selectedContainers = new SelectedContainerCache();
//$$     private static final Map<Item, List<Candidate>> index = new HashMap<>();
//$$     private static final Set<BlockPos> invalidCandidates = new HashSet<>();
//$$
//$$     private static boolean active;
//$$     private static List<Candidate> candidates = List.of();
//$$     private static int candidateIndex;
//$$     private static BlockPos targetPos;
//$$     private static Item requestedItem;
//$$     private static List<Item> requestedItems = List.of();
//$$     private static ItemStack requestedStack = ItemStack.EMPTY;
//$$     private static boolean exactMatch;
//$$     private static Phase phase = Phase.IDLE;
//$$     private static long startedTick;
//$$     private static long openDeadline;
//$$     private static long requestDeadline;
//$$     private static long lastFailedTick = Long.MIN_VALUE;
//$$     private static Item lastFailedItem;
//$$     private static BlockPos nestedSourcePos;
//$$     private static int nestedSourceSlot = -1;
//$$     private static ItemStack nestedShulkerSnapshot = ItemStack.EMPTY;
//$$     private static boolean restoringNestedShulker;
//$$     private static boolean suppressContainerScreen;
//$$     private static long restoreSyncDeadline;
//$$     private static int expectedContainerId = -1;
//$$     private static int nestedPlayerInventorySlot = -1;
//$$
//$$     private ChestTakeController() {
//$$     }
//$$
//$$     /** 打印机缺料：按 Item 匹配，扫描允许列表内 CT 记忆容器。 */
//$$     public static boolean requestItem(Item item) {
//$$         if (item == null) return false;
//$$         return requestItems(List.of(item), item, false, ItemStack.EMPTY);
//$$     }
//$$
//$$     /** 打印机缺料：接受多个等效物品。 */
//$$     public static boolean requestItems(List<Item> items) {
//$$         if (items == null || items.isEmpty()) return false;
//$$         Item preferred = null;
//$$         List<Item> accepted = new ArrayList<>();
//$$         for (Item item : items) {
//$$             if (item == null) continue;
//$$             accepted.add(item);
//$$             if (preferred == null) preferred = item;
//$$         }
//$$         if (preferred == null) return false;
//$$         return requestItems(accepted, preferred, false, ItemStack.EMPTY);
//$$     }
//$$
//$$     /** CT 屏幕右键：精确组件匹配。 */
//$$     public static boolean requestFromScreen(ItemStack stack) {
//$$         if (stack == null || stack.isEmpty()) return false;
//$$         return requestItems(List.of(stack.getItem()), stack.getItem(), true, stack.copy());
//$$     }
//$$
//$$     public static boolean isAwaiting() {
//$$         return active;
//$$     }
//$$
//$$     /** 是否正等待本模组预期的容器界面（内容等待阶段），供 MixinContainerScreenGuard 判定 */
//$$     public static boolean isExpectingContainerScreen() {
//$$         return active && (phase == Phase.WAITING_CONTENT || phase == Phase.RESTORE_WAIT_CONTENT);
//$$     }
//$$
//$$     public static boolean shouldSuppressContainerScreen() {
//$$         return suppressContainerScreen;
//$$     }
//$$
//$$     public static void onContainerOpen(int containerId) {
//$$         if (active
//$$                 && (phase == Phase.WAITING_CONTENT || phase == Phase.RESTORE_WAIT_CONTENT)
//$$                 && suppressContainerScreen) {
//$$             expectedContainerId = containerId;
//$$         }
//$$     }
//$$
//$$     public static void onContainerContent(int containerId) {
//$$         if (!active || (phase != Phase.WAITING_CONTENT && phase != Phase.RESTORE_WAIT_CONTENT)) return;
//$$         if (expectedContainerId >= 0 && expectedContainerId != containerId) {
//$$             boolean ownsCurrentMenu = client.player != null
//$$                     && client.player.containerMenu.containerId == expectedContainerId;
//$$             if (restoringNestedShulker) {
//$$                 failNestedRestore("容器包不匹配", ownsCurrentMenu);
//$$             } else {
//$$                 abortRequest("容器包不匹配", ownsCurrentMenu);
//$$             }
//$$             return;
//$$         }
//$$         expectedContainerId = -1;
//$$         LocalPlayer player = client.player;
//$$         if (player == null || player.containerMenu.containerId != containerId) return;
//$$         if (client.gameMode == null) {
//$$             finishUnavailable(requestedItem);
//$$             return;
//$$         }
//$$         AbstractContainerMenu menu = player.containerMenu;
//$$         if (menu == player.inventoryMenu || menu.slots.isEmpty()) {
//$$             failAndContinue();
//$$             return;
//$$         }
//$$         int containerSize = Math.min(menu.slots.size(), menu.slots.get(0).container.getContainerSize());
//$$         if (restoringNestedShulker) {
//$$             restoreNestedShulker(menu, containerSize, player);
//$$             return;
//$$         }
//$$         int sourceSlot = -1;
//$$         boolean nested = false;
//$$         for (int slot = 0; slot < containerSize; slot++) {
//$$             if (matches(menu.slots.get(slot).getItem())) {
//$$                 sourceSlot = slot;
//$$                 break;
//$$             }
//$$         }
//$$         if (sourceSlot < 0) {
//$$             for (int slot = 0; slot < containerSize; slot++) {
//$$                 ItemStack stack = menu.slots.get(slot).getItem();
//$$                 if (isShulker(stack) && shulkerContains(stack)) {
//$$                     sourceSlot = slot;
//$$                     nested = true;
//$$                     break;
//$$                 }
//$$             }
//$$         }
//$$         if (sourceSlot < 0) {
//$$             if (targetPos != null) invalidCandidates.add(targetPos.immutable());
//$$             closeContainer();
//$$             if (!openNextCandidate()) finishUnavailable(requestedItem);
//$$             return;
//$$         }
//$$         ItemStack nestedSnapshot = nested ? menu.slots.get(sourceSlot).getItem().copy() : ItemStack.EMPTY;
//$$         List<ItemStack> inventoryBefore = nested ? inventorySnapshot(player) : List.of();
//$$         quickMove(menu, sourceSlot, player);
//$$         closeContainer();
//$$         if (nested) {
//$$             nestedSourcePos = targetPos == null ? null : targetPos.immutable();
//$$             nestedSourceSlot = sourceSlot;
//$$             nestedShulkerSnapshot = nestedSnapshot;
//$$             nestedPlayerInventorySlot = locateMovedShulker(player, nestedSnapshot, inventoryBefore);
//$$             phase = Phase.WAITING_INVENTORY;
//$$             if (!TakeItOutUtils.tryRequestItem(requestedItem)) {
//$$                 InventoryUtils.requestItemsFromShulker(requestedItem);
//$$             }
//$$         } else {
//$$             phase = Phase.WAITING_INVENTORY;
//$$         }
//$$     }
//$$
//$$     public static void tick() {
//$$         if (!active) return;
//$$         long now = gameTick();
//$$         if (phase == Phase.WAITING_INVENTORY && hasRequestedItem()) {
//$$             if (nestedSourcePos != null && !restoringNestedShulker) {
//$$                 beginNestedRestore();
//$$             } else {
//$$                 finishAvailable();
//$$             }
//$$             return;
//$$         }
//$$         if (phase == Phase.WAITING_RESTORE_SYNC && now >= restoreSyncDeadline) {
//$$             finishAvailable();
//$$             return;
//$$         }
//$$         if (phase == Phase.RESTORE_WAIT_CONTENT && now >= openDeadline) {
//$$             failNestedRestore("归还超时");
//$$             return;
//$$         }
//$$         if (phase == Phase.WAITING_CONTENT && now >= openDeadline) {
//$$             failAndContinue();
//$$         } else if (now >= requestDeadline) {
//$$             finishUnavailable(requestedItem);
//$$         }
//$$     }
//$$
//$$     public static void abort() {
//$$         if (!active) return;
//$$         MessageUtils.setOverlayMessage("Chest Tracker: 取物已取消");
//$$         closeContainer();
//$$         resetState();
//$$     }
//$$
//$$     public static int addSelectionToCache() {
//$$         if (!ModLoadUtils.isChestTrackerLoaded() || client.level == null) return 0;
//$$         List<PrinterBox> boxes = LitematicaUtils.createSelection1Boxes();
//$$         if (boxes.isEmpty()) return 0;
//$$         int added = 0;
//$$         MemoryBank bank = MemoryBankAccess.INSTANCE.getLoaded().orElse(null);
//$$         var key = ProviderUtils.getPlayersCurrentKey().orElse(null);
//$$         MemoryKey memoryKey = bank == null || key == null ? null : bank.getKey(key).orElse(null);
//$$         if (memoryKey != null) {
//$$             String world = SelectedContainerCache.worldId(client);
//$$             String dimension = SelectedContainerCache.dimensionId(client);
//$$             for (Map.Entry<BlockPos, Memory> entry : memoryKey.getMemories().entrySet()) {
//$$                 Memory memory = entry.getValue();
//$$                 BlockPos pos = entry.getKey();
//$$                 if (memory != null && memory.container().isPresent() && insideAny(boxes, pos)) {
//$$                     added += selectedContainers.add(world, dimension, pos);
//$$                 }
//$$             }
//$$         }
//$$         if (added > 0) selectedContainers.save();
//$$         return added;
//$$     }
//$$
//$$     public static int clearSelectionCache() {
//$$         if (client.level == null) return 0;
//$$         int removed = selectedContainers.clear(
//$$                 SelectedContainerCache.worldId(client),
//$$                 SelectedContainerCache.dimensionId(client)
//$$         );
//$$         if (removed > 0) selectedContainers.save();
//$$         index.clear();
//$$         return removed;
//$$     }
//$$
//$$     public static int selectedCacheSize() {
//$$         return client.level == null ? 0 : selectedContainers.count(
//$$                 SelectedContainerCache.worldId(client),
//$$                 SelectedContainerCache.dimensionId(client)
//$$         );
//$$     }
//$$
//$$     public static void reset() {
//$$         closeContainer();
//$$         resetState();
//$$         index.clear();
//$$     }
//$$
//$$     private static boolean requestItems(List<Item> items, Item preferred, boolean exact, ItemStack stack) {
//$$         if (!enabled()) return false;
//$$         if (active) return true;
//$$         LocalPlayer player = client.player;
//$$         if (player == null || client.level == null) return false;
//$$         for (Item item : items) {
//$$             if (me.aleksilassila.litematica.printer.utils.InventoryUtils.playerHasItemInInventory(player, item)) {
//$$                 return false;
//$$             }
//$$         }
//$$         if (failedRecently(preferred)) return false;
//$$         invalidCandidates.clear();
//$$         active = true;
//$$         requestedItem = preferred;
//$$         requestedItems = List.copyOf(items);
//$$         requestedStack = exact ? stack.copy() : new ItemStack(preferred);
//$$         exactMatch = exact;
//$$         rebuildIndex();
//$$         candidates = orderedCandidates(requestedItems);
//$$         candidateIndex = 0;
//$$         startedTick = gameTick();
//$$         requestDeadline = startedTick + REQUEST_TIMEOUT_TICKS;
//$$         phase = Phase.SCANNING;
//$$         if (!openNextCandidate()) {
//$$             finishUnavailable(preferred);
//$$             return false;
//$$         }
//$$         MessageUtils.setOverlayMessage("Chest Tracker: 取物中 " + new ItemStack(preferred).getHoverName().getString());
//$$         return true;
//$$     }
//$$
//$$     private static boolean enabled() {
//$$         return ModLoadUtils.isChestTrackerLoaded()
//$$                 && Configs.Special.REMOTE_TAKE.getBooleanValue();
//$$     }
//$$
//$$     private static boolean openNextCandidate() {
//$$         while (candidateIndex < candidates.size()) {
//$$             Candidate candidate = candidates.get(candidateIndex++);
//$$             if (invalidCandidates.contains(candidate.pos()) || !client.level.isLoaded(candidate.pos())) continue;
//$$             if (open(candidate.pos())) {
//$$                 targetPos = candidate.pos();
//$$                 phase = Phase.WAITING_CONTENT;
//$$                 openDeadline = gameTick() + OPEN_TIMEOUT_TICKS;
//$$                 return true;
//$$             }
//$$             invalidCandidates.add(candidate.pos());
//$$         }
//$$         return false;
//$$     }
//$$
//$$     private static boolean open(BlockPos pos) {
//$$         if (client.player == null || client.level == null) return false;
//$$         if (InventoryUtils.isOpenHandler) return false;
//$$         if (SwitchItem.isWaitingForRestoreContainer()) return false;
//$$         if (client.player.containerMenu != client.player.inventoryMenu) return false;
//$$         // 容器互斥守卫：其他子系统（快捷潜影盒/归还等）占用容器菜单时不开箱
//$$         if (!ContainerGate.tryAcquire(ContainerGate.Owner.CHEST_TRACKER_TAKE)) {
//$$             return false;
//$$         }
//$$         expectedContainerId = -1;
//$$         suppressContainerScreen = true;
//$$         BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
//$$         InteractionResult result = InteractionUtils.INSTANCE.useItemOn(false, InteractionHand.MAIN_HAND, hit);
//$$         if (result == InteractionResult.FAIL) {
//$$             suppressContainerScreen = false;
//$$             ContainerGate.release(ContainerGate.Owner.CHEST_TRACKER_TAKE);
//$$             return false;
//$$         }
//$$         return true;
//$$     }
//$$
//$$     private static void failAndContinue() {
//$$         if (restoringNestedShulker) {
//$$             failNestedRestore("归还阶段中止");
//$$             return;
//$$         }
//$$         if (targetPos != null) invalidCandidates.add(targetPos.immutable());
//$$         closeContainer();
//$$         if (!openNextCandidate()) finishUnavailable(requestedItem);
//$$     }
//$$
//$$     private static void finishAvailable() {
//$$         finishAvailable(true);
//$$     }
//$$
//$$     private static void finishAvailable(boolean closeOwnedMenu) {
//$$         if (closeOwnedMenu) closeContainer();
//$$         else {
//$$             suppressContainerScreen = false;
//$$             expectedContainerId = -1;
//$$         }
//$$         resetState();
//$$     }
//$$
//$$     private static void finishUnavailable(@Nullable Item item) {
//$$         if (item != null) {
//$$             lastFailedItem = item;
//$$             lastFailedTick = gameTick();
//$$         }
//$$         closeContainer();
//$$         resetState();
//$$     }
//$$
//$$     private static void closeContainer() {
//$$         if (client.player != null && client.player.containerMenu != client.player.inventoryMenu) {
//$$             client.player.closeContainer();
//$$         }
//$$         suppressContainerScreen = false;
//$$         expectedContainerId = -1;
//$$         ContainerGate.release(ContainerGate.Owner.CHEST_TRACKER_TAKE);
//$$     }
//$$
//$$     private static void resetState() {
//$$         active = false;
//$$         candidates = List.of();
//$$         candidateIndex = 0;
//$$         targetPos = null;
//$$         requestedItem = null;
//$$         requestedItems = List.of();
//$$         requestedStack = ItemStack.EMPTY;
//$$         nestedSourcePos = null;
//$$         nestedSourceSlot = -1;
//$$         nestedShulkerSnapshot = ItemStack.EMPTY;
//$$         restoringNestedShulker = false;
//$$         nestedPlayerInventorySlot = -1;
//$$         suppressContainerScreen = false;
//$$         restoreSyncDeadline = 0L;
//$$         expectedContainerId = -1;
//$$         exactMatch = false;
//$$         phase = Phase.IDLE;
//$$         invalidCandidates.clear();
//$$         ContainerGate.release(ContainerGate.Owner.CHEST_TRACKER_TAKE);
//$$     }
//$$
//$$     private static boolean failedRecently(Item item) {
//$$         return item != null && item == lastFailedItem && gameTick() - lastFailedTick < NOT_FOUND_COOLDOWN_TICKS;
//$$     }
//$$
//$$     private static long gameTick() {
//$$         return client.level == null ? 0L : client.level.getGameTime();
//$$     }
//$$
//$$     private static boolean matches(ItemStack stack) {
//$$         if (stack == null || stack.isEmpty()) return false;
//$$         return exactMatch
//$$                 ? ItemStack.isSameItemSameComponents(stack, requestedStack)
//$$                 : requestedItems.contains(stack.getItem());
//$$     }
//$$
//$$     private static boolean hasRequestedItem() {
//$$         if (client.player == null || requestedItem == null) return false;
//$$         for (int slot = 0; slot < Math.min(36, client.player.getInventory().getContainerSize()); slot++) {
//$$             ItemStack stack = client.player.getInventory().getItem(slot);
//$$             if (!stack.isEmpty() && requestedItems.contains(stack.getItem())
//$$                     && (!exactMatch || ItemStack.isSameItemSameComponents(stack, requestedStack))) {
//$$                 return true;
//$$             }
//$$         }
//$$         return false;
//$$     }
//$$
//$$     private static boolean shulkerContains(ItemStack stack) {
//$$         try {
//$$             for (ItemStack inner : fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(stack, -1)) {
//$$                 if (matches(inner)) return true;
//$$             }
//$$         } catch (Exception ignored) {
//$$         }
//$$         return false;
//$$     }
//$$
//$$     private static boolean shulkerContains(ItemStack stack, Item requested) {
//$$         try {
//$$             for (ItemStack inner : fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(stack, -1)) {
//$$                 if (!inner.isEmpty() && inner.is(requested)) return true;
//$$             }
//$$         } catch (Exception ignored) {
//$$         }
//$$         return false;
//$$     }
//$$
//$$     private static boolean isShulker(ItemStack stack) {
//$$         return !stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
//$$                 && blockItem.getBlock() instanceof ShulkerBoxBlock;
//$$     }
//$$
//$$     private static void quickMove(AbstractContainerMenu menu, int slot, LocalPlayer player) {
//$$         Minecraft.getInstance().gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.QUICK_MOVE, player);
//$$     }
//$$
//$$     private static void pickup(AbstractContainerMenu menu, int slot, LocalPlayer player) {
//$$         Minecraft.getInstance().gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.PICKUP, player);
//$$     }
//$$
//$$     private static void beginNestedRestore() {
//$$         if (restoringNestedShulker || nestedSourcePos == null
//$$                 || InventoryUtils.isOpenHandler
//$$                 || SwitchItem.hasPendingRestore()
//$$                 || TakeItOutUtils.isAwaitingStack()
//$$                 || client.player == null
//$$                 || client.player.containerMenu != client.player.inventoryMenu) {
//$$             return;
//$$         }
//$$         restoringNestedShulker = true;
//$$         if (!open(nestedSourcePos)) {
//$$             restoringNestedShulker = false;
//$$             MessageUtils.setOverlayMessage("Chest Tracker: 潜影盒未能归还，已保留在背包");
//$$             finishAvailable();
//$$             return;
//$$         }
//$$         targetPos = nestedSourcePos;
//$$         phase = Phase.RESTORE_WAIT_CONTENT;
//$$         openDeadline = gameTick() + OPEN_TIMEOUT_TICKS;
//$$     }
//$$
//$$     private static void restoreNestedShulker(AbstractContainerMenu menu, int containerSize, LocalPlayer player) {
//$$         if (nestedSourceSlot < 0 || nestedSourceSlot >= containerSize) {
//$$             failNestedRestore("源槽位无效");
//$$             return;
//$$         }
//$$         ItemStack source = menu.slots.get(nestedSourceSlot).getItem();
//$$         if (!source.isEmpty()) {
//$$             failNestedRestore("源槽位已被占用");
//$$             return;
//$$         }
//$$         int inventorySlot = nestedPlayerInventorySlot;
//$$         if (inventorySlot < 0 || inventorySlot >= Math.min(36, player.getInventory().getContainerSize())
//$$                 || !isSameShulkerType(player.getInventory().getItem(inventorySlot), nestedShulkerSnapshot)) {
//$$             failNestedRestore("背包中的潜影盒位置已变化");
//$$             return;
//$$         }
//$$         int playerSlot = findPlayerMenuSlot(menu, inventorySlot);
//$$         if (playerSlot < 0) {
//$$             failNestedRestore("背包中找不到原潜影盒");
//$$             return;
//$$         }
//$$         pickup(menu, playerSlot, player);
//$$         pickup(menu, nestedSourceSlot, player);
//$$         if (!menu.getCarried().isEmpty()) {
//$$             pickup(menu, playerSlot, player);
//$$             failNestedRestore("服务器拒绝归还");
//$$             return;
//$$         }
//$$         closeContainer();
//$$         phase = Phase.WAITING_RESTORE_SYNC;
//$$         restoreSyncDeadline = gameTick() + 5L;
//$$     }
//$$
//$$     private static void failNestedRestore(String reason) {
//$$         failNestedRestore(reason, true);
//$$     }
//$$
//$$     private static void failNestedRestore(String reason, boolean closeOwnedMenu) {
//$$         MessageUtils.setOverlayMessage("Chest Tracker: 潜影盒未能归还（" + reason + "），已保留在背包");
//$$         BlockPos mark = nestedSourcePos != null ? nestedSourcePos : targetPos;
//$$         if (mark != null) invalidCandidates.add(mark.immutable());
//$$         restoringNestedShulker = false;
//$$         finishAvailable(closeOwnedMenu);
//$$     }
//$$
//$$     private static void abortRequest(String reason, boolean closeOwnedMenu) {
//$$         MessageUtils.setOverlayMessage("Chest Tracker: 取物已取消（" + reason + "）");
//$$         if (closeOwnedMenu) closeContainer();
//$$         else {
//$$             suppressContainerScreen = false;
//$$             expectedContainerId = -1;
//$$         }
//$$         resetState();
//$$     }
//$$
//$$     private static int findPlayerMenuSlot(AbstractContainerMenu menu, int playerInventorySlot) {
//$$         int ordinal = 0;
//$$         int wanted = playerInventorySlot < 9 ? 27 + playerInventorySlot : playerInventorySlot - 9;
//$$         for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
//$$             Slot slot = menu.slots.get(menuSlot);
//$$             if (slot.container instanceof net.minecraft.world.entity.player.Inventory) {
//$$                 if (ordinal == wanted) return menuSlot;
//$$                 ordinal++;
//$$             }
//$$         }
//$$         return -1;
//$$     }
//$$
//$$     private static int locateMovedShulker(LocalPlayer player, ItemStack snapshot, List<ItemStack> before) {
//$$         int found = -1;
//$$         int size = Math.min(36, player.getInventory().getContainerSize());
//$$         for (int slot = 0; slot < size; slot++) {
//$$             ItemStack candidate = player.getInventory().getItem(slot);
//$$             boolean wasPresent = slot < before.size()
//$$                     && ItemStack.isSameItemSameComponents(before.get(slot), candidate);
//$$             if (!wasPresent && ItemStack.isSameItemSameComponents(candidate, snapshot)) {
//$$                 if (found >= 0) return -1;
//$$                 found = slot;
//$$             }
//$$         }
//$$         return found;
//$$     }
//$$
//$$     private static List<ItemStack> inventorySnapshot(LocalPlayer player) {
//$$         List<ItemStack> snapshot = new ArrayList<>();
//$$         int size = Math.min(36, player.getInventory().getContainerSize());
//$$         for (int slot = 0; slot < size; slot++) {
//$$             snapshot.add(player.getInventory().getItem(slot).copy());
//$$         }
//$$         return snapshot;
//$$     }
//$$
//$$     private static boolean isSameShulkerType(ItemStack candidate, ItemStack snapshot) {
//$$         return candidate != null && !candidate.isEmpty()
//$$                 && candidate.getCount() == 1
//$$                 && snapshot != null && !snapshot.isEmpty()
//$$                 && candidate.getItem() == snapshot.getItem();
//$$     }
//$$
//$$     private static void rebuildIndex() {
//$$         index.clear();
//$$         MemoryBank bank = MemoryBankAccess.INSTANCE.getLoaded().orElse(null);
//$$         var key = ProviderUtils.getPlayersCurrentKey().orElse(null);
//$$         if (bank == null || key == null) return;
//$$         MemoryKey memoryKey = bank.getKey(key).orElse(null);
//$$         if (memoryKey == null) return;
//$$         String world = SelectedContainerCache.worldId(client);
//$$         String dimension = SelectedContainerCache.dimensionId(client);
//$$         int candidateCount = 0;
//$$         for (Map.Entry<BlockPos, Memory> entry : memoryKey.getMemories().entrySet()) {
//$$             if (candidateCount >= MAX_SCAN_CANDIDATES) break;
//$$             BlockPos pos = entry.getKey();
//$$             Memory memory = entry.getValue();
//$$             if (memory == null || memory.container().isEmpty()
//$$                     || !selectedContainers.contains(world, dimension, pos)
//$$                     || client.level == null || !client.level.isLoaded(pos)) continue;
//$$             double distance = client.player == null ? 0.0D : client.player.distanceToSqr(Vec3.atCenterOf(pos));
//$$             Long stamp = memory.inGameTimestamp();
//$$             long timestamp = stamp == null ? Long.MIN_VALUE : stamp;
//$$             for (Item requested : requestedItems) {
//$$                 if (candidateCount >= MAX_SCAN_CANDIDATES) break;
//$$                 boolean direct = false;
//$$                 boolean nested = false;
//$$                 for (ItemStack stack : memory.items()) {
//$$                     if (stack.isEmpty()) continue;
//$$                     if (stack.is(requested)) {
//$$                         direct = true;
//$$                         break;
//$$                     }
//$$                     if (isShulker(stack) && shulkerContains(stack, requested)) {
//$$                         nested = true;
//$$                     }
//$$                 }
//$$                 if (direct || nested) {
//$$                     index.computeIfAbsent(requested, ignored -> new ArrayList<>())
//$$                             .add(new Candidate(pos, !direct, distance, timestamp));
//$$                     candidateCount++;
//$$                 }
//$$             }
//$$         }
//$$     }
//$$
//$$     private static List<Candidate> orderedCandidates(List<Item> items) {
//$$         List<Candidate> values = new ArrayList<>();
//$$         for (Item item : items) values.addAll(index.getOrDefault(item, List.of()));
//$$         values.removeIf(candidate -> invalidCandidates.contains(candidate.pos()));
//$$         values.sort(Comparator.comparing(Candidate::nested)
//$$                 .thenComparingDouble(Candidate::distance)
//$$                 .thenComparing(Comparator.comparingLong(Candidate::timestamp).reversed()));
//$$         if (values.size() > MAX_SCAN_CANDIDATES) return List.copyOf(values.subList(0, MAX_SCAN_CANDIDATES));
//$$         return List.copyOf(values);
//$$     }
//$$
//$$     private static boolean insideAny(List<PrinterBox> boxes, BlockPos pos) {
//$$         for (PrinterBox box : boxes) {
//$$             if (box.contains(pos)) return true;
//$$         }
//$$         return false;
//$$     }
//$$
//$$     private record Candidate(BlockPos pos, boolean nested, double distance, long timestamp) {
//$$     }
//$$
//$$     private enum Phase {
//$$         IDLE,
//$$         SCANNING,
//$$         WAITING_CONTENT,
//$$         RESTORE_WAIT_CONTENT,
//$$         WAITING_INVENTORY,
//$$         WAITING_RESTORE_SYNC
//$$     }
//$$ }
//#else
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** No-op stub when Chest Tracker is not available for this MC version. */
public final class ChestTakeController {
    private ChestTakeController() {
    }

    public static boolean requestItem(Item item) {
        return false;
    }

    public static boolean requestItems(List<Item> items) {
        return false;
    }

    public static boolean requestFromScreen(ItemStack stack) {
        return false;
    }

    public static boolean isAwaiting() {
        return false;
    }

    public static boolean isExpectingContainerScreen() {
        return false;
    }

    public static boolean shouldSuppressContainerScreen() {
        return false;
    }

    public static void onContainerOpen(int containerId) {
    }

    public static void onContainerContent(int containerId) {
    }

    public static void tick() {
    }

    public static void abort() {
    }

    public static int addSelectionToCache() {
        return 0;
    }

    public static int clearSelectionCache() {
        return 0;
    }

    public static int selectedCacheSize() {
        return 0;
    }

    public static void reset() {
    }
}
//#endif
