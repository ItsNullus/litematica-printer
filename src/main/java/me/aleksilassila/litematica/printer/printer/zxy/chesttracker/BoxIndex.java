package me.aleksilassila.litematica.printer.printer.zxy.chesttracker;

//#if MC == 12104
//$$ import com.google.gson.Gson;
//$$ import me.aleksilassila.litematica.printer.Reference;
//$$ import net.minecraft.client.Minecraft;
//$$ import net.minecraft.client.player.LocalPlayer;
//$$ import net.minecraft.core.BlockPos;
//$$ import net.minecraft.core.registries.BuiltInRegistries;
//$$ import net.minecraft.resources.ResourceLocation;
//$$ import net.minecraft.world.inventory.AbstractContainerMenu;
//$$ import net.minecraft.world.item.Item;
//$$ import net.minecraft.world.item.ItemStack;

//$$ import java.io.File;
//$$ import java.io.FileReader;
//$$ import java.io.FileWriter;
//$$ import java.nio.charset.StandardCharsets;
//$$ import java.util.ArrayList;
//$$ import java.util.HashMap;
//$$ import java.util.List;
//$$ import java.util.Map;

//$$ /**
//$$  * 盒内容索引：记录"箱子 → 槽位 → 盒内物品"的本地索引。
//$$  *
//$$  * CT 的记忆在保存时会剥掉潜影盒内容组件（记忆里的盒子是空壳），导致无法从记忆判断盒内物品。
//$$  * 这里在每次真正看到箱子内容（添加库存 / 玩家开箱 / 远程取物开箱）时，把"箱子 → 盒内物品"
//$$  * 存到自己的索引文件里，提供 O(1) 的物品定位：item → (箱子, 槽位)，取物时只需开目标箱子。
//$$  *
//$$  * 刷新是被动的（读取当前菜单槽位 → 写索引），不会触发任何开箱，因此不会递归。
//$$  */
//$$ public final class BoxIndex {
//$$     private static final Gson GSON = new Gson();

//$$     /** 定位结果：箱子 + 槽位（isBox=true 表示该槽位是装有目标物品的潜影盒） */
//$$     public record Candidate(ResourceLocation dim, BlockPos pos, int slot, boolean isBox) {
//$$     }

//$$     private static final Map<ResourceLocation, Map<BlockPos, ChestEntry>> index = new HashMap<>();
//$$     private static final Map<String, List<Candidate>> reverse = new HashMap<>();
//$$     private static boolean dirty;
//$$     private static long lastSaveMs;
//$$     private static final long SAVE_INTERVAL_MS = 10000L;

//$$     private static final class ChestEntry {
//$$         final Map<String, Integer> direct = new HashMap<>();             // 非盒物品: itemId -> count
//$$         final Map<Integer, Map<String, Integer>> boxes = new HashMap<>(); // 潜影盒: 槽位 -> (itemId -> count)
//$$     }

//$$     private static final class FileData {
//$$         Map<String, Map<String, ChestEntryData>> dims = new HashMap<>();
//$$     }

//$$     private static final class ChestEntryData {
//$$         Map<String, Integer> direct = new HashMap<>();
//$$         Map<String, Map<String, Integer>> boxes = new HashMap<>();
//$$     }

//$$     private BoxIndex() {
//$$     }

//$$     // ========== 生命周期 ==========

//$$     private static File file() {
//$$         return new File(Minecraft.getInstance().gameDirectory,
//$$                 "chesttracker/" + PrinterMemory.worldId() + "-printer-index.json");
//$$     }

//$$     public static void load() {
//$$         try {
//$$             File f = file();
//$$             index.clear();
//$$             reverse.clear();
//$$             if (!f.exists()) {
//$$                 dirty = false;
//$$                 Reference.LOGGER.info("[ChestTracker] 盒内容索引: 无文件, 空索引");
//$$                 return;
//$$             }
//$$             FileData data;
//$$             try (FileReader reader = new FileReader(f, StandardCharsets.UTF_8)) {
//$$                 data = GSON.fromJson(reader, FileData.class);
//$$             }
//$$             if (data != null && data.dims != null) {
//$$                 for (var de : data.dims.entrySet()) {
//$$                     ResourceLocation dim = ResourceLocation.tryParse(de.getKey());
//$$                     if (dim == null) {
//$$                         continue;
//$$                     }
//$$                     Map<BlockPos, ChestEntry> dimMap = new HashMap<>();
//$$                     for (var ce : de.getValue().entrySet()) {
//$$                         BlockPos pos = parsePos(ce.getKey());
//$$                         if (pos == null) {
//$$                             continue;
//$$                         }
//$$                         ChestEntry entry = new ChestEntry();
//$$                         if (ce.getValue().direct != null) {
//$$                             entry.direct.putAll(ce.getValue().direct);
//$$                         }
//$$                         if (ce.getValue().boxes != null) {
//$$                             for (var be : ce.getValue().boxes.entrySet()) {
//$$                                 try {
//$$                                     int slot = Integer.parseInt(be.getKey());
//$$                                     entry.boxes.put(slot, new HashMap<>(be.getValue()));
//$$                                 } catch (NumberFormatException ignored) {
//$$                                 }
//$$                             }
//$$                         }
//$$                         dimMap.put(pos, entry);
//$$                     }
//$$                     index.put(dim, dimMap);
//$$                 }
//$$             }
//$$             rebuildReverse();
//$$             dirty = false;
//$$             Reference.LOGGER.info("[ChestTracker] 盒内容索引已加载 ({} 个箱子)", countChests());
//$$         } catch (Exception e) {
//$$             Reference.LOGGER.warn("[ChestTracker] 盒内容索引加载失败", e);
//$$         }
//$$     }

//$$     public static void save() {
//$$         try {
//$$             FileData data = new FileData();
//$$             for (var de : index.entrySet()) {
//$$                 Map<String, ChestEntryData> dimMap = new HashMap<>();
//$$                 for (var ce : de.getValue().entrySet()) {
//$$                     ChestEntryData ed = new ChestEntryData();
//$$                     ed.direct = new HashMap<>(ce.getValue().direct);
//$$                     for (var be : ce.getValue().boxes.entrySet()) {
//$$                         ed.boxes.put(String.valueOf(be.getKey()), new HashMap<>(be.getValue()));
//$$                     }
//$$                     dimMap.put(posKey(ce.getKey()), ed);
//$$                 }
//$$                 data.dims.put(de.getKey().toString(), dimMap);
//$$             }
//$$             File f = file();
//$$             f.getParentFile().mkdirs();
//$$             try (FileWriter writer = new FileWriter(f, StandardCharsets.UTF_8)) {
//$$                 GSON.toJson(data, writer);
//$$             }
//$$             dirty = false;
//$$             lastSaveMs = System.currentTimeMillis();
//$$             Reference.LOGGER.info("[ChestTracker] 盒内容索引已保存 ({} 个箱子)", countChests());
//$$         } catch (Exception e) {
//$$             Reference.LOGGER.warn("[ChestTracker] 盒内容索引保存失败", e);
//$$         }
//$$     }

//$$     public static void unload() {
//$$         save();
//$$         index.clear();
//$$         reverse.clear();
//$$         dirty = false;
//$$     }

//$$     /** 节流保存（tick 调用） */
//$$     public static void tick() {
//$$         if (dirty && System.currentTimeMillis() - lastSaveMs > SAVE_INTERVAL_MS) {
//$$             save();
//$$         }
//$$     }

//$$     public static void clear() {
//$$         index.clear();
//$$         reverse.clear();
//$$         dirty = true;
//$$         save();
//$$     }

//$$     // ========== 查询（O(1)） ==========

//$$     /** 在当前维度找最近的装有目标物品的候选（直接物品或潜影盒） */
//$$     public static Candidate findItem(Item item, ResourceLocation currentDim) {
//$$         if (item == null) {
//$$             return null;
//$$         }
//$$         String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
//$$         List<Candidate> candidates = reverse.get(itemId);
//$$         if (candidates == null || candidates.isEmpty()) {
//$$             return null;
//$$         }
//$$         Minecraft mc = Minecraft.getInstance();
//$$         LocalPlayer player = mc.player;
//$$         double bestDist = Double.MAX_VALUE;
//$$         Candidate best = null;
//$$         for (Candidate c : candidates) {
//$$             if (currentDim != null && !c.dim().equals(currentDim)) {
//$$                 continue;
//$$             }
//$$             double d = player == null ? 0.0 : player.distanceToSqr(c.pos().getCenter());
//$$             if (d < bestDist) {
//$$                 bestDist = d;
//$$                 best = c;
//$$             }
//$$         }
//$$         return best;
//$$     }

//$$     /** 索引中某维度的全部箱子（用于批量扫描候选） */
//$$     public static List<BlockPos> getChestsInDimension(ResourceLocation dim) {
//$$         Map<BlockPos, ChestEntry> dimMap = index.get(dim);
//$$         if (dimMap == null) {
//$$             return List.of();
//$$         }
//$$         return new ArrayList<>(dimMap.keySet());
//$$     }
//$$
//$$     // ========== 刷新（被动，不触发开箱，无递归） ==========

//$$     /** 用当前菜单槽位刷新一个箱子的索引条目 */
//$$     public static void refreshFromMenu(ResourceLocation dim, BlockPos pos, AbstractContainerMenu menu, int containerSize) {
//$$         if (dim == null || pos == null || menu == null) {
//$$             return;
//$$         }
//$$         try {
//$$             ChestEntry entry = new ChestEntry();
//$$             for (int i = 0; i < containerSize; i++) {
//$$                 ItemStack stack = menu.slots.get(i).getItem();
//$$                 if (stack == null || stack.isEmpty()) {
//$$                     continue;
//$$                 }
//$$                 if (isShulkerBox(stack)) {
//$$                     Map<String, Integer> inner = new HashMap<>();
//$$                     try {
//$$                         var stored = fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(stack, -1);
//$$                         for (ItemStack s : stored) {
//$$                             if (!s.isEmpty()) {
//$$                                 String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
//$$                                 inner.merge(id, s.getCount(), Integer::sum);
//$$                             }
//$$                         }
//$$                     } catch (Exception ignored) {
//$$                     }
//$$                     if (!inner.isEmpty()) {
//$$                         entry.boxes.put(i, inner);
//$$                     }
//$$                 } else {
//$$                     String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
//$$                     entry.direct.merge(id, stack.getCount(), Integer::sum);
//$$                 }
//$$             }
//$$             index.computeIfAbsent(dim, k -> new HashMap<>()).put(pos.immutable(), entry);
//$$             rebuildReverse();
//$$             dirty = true;
//$$         } catch (Exception e) {
//$$             Reference.LOGGER.warn("[ChestTracker] 盒内容索引刷新失败 pos={}", pos, e);
//$$         }
//$$     }

//$$     /** 玩家手动打开箱子时刷新（通过 CT 交互追踪定位）。远程操作时由取物/添加流程自己刷新。 */
//$$     public static void refreshCurrentOpen() {
//$$         Minecraft mc = Minecraft.getInstance();
//$$         if (mc.player == null || mc.level == null) {
//$$             return;
//$$         }
//$$         if (mc.player.containerMenu.equals(mc.player.inventoryMenu)) {
//$$             return;
//$$         }
//$$         AbstractContainerMenu menu = mc.player.containerMenu;
//$$         if (menu.slots.isEmpty()) {
//$$             return;
//$$         }
//$$         int containerSize = Math.min(menu.slots.size(), menu.slots.get(0).container.getContainerSize());
//$$         // 只处理箱子/桶类存储（27/54 槽），排除玩家背包、合成台、潜影盒菜单等
//$$         if (containerSize < 27 || containerSize > 54) {
//$$             return;
//$$         }
//$$         var source = red.jackf.chesttracker.impl.providers.InteractionTrackerImpl.INSTANCE.getLastBlockSource().orElse(null);
//$$         if (source == null || source.pos() == null) {
//$$             return;
//$$         }
//$$         BlockPos pos = source.pos();
//$$         var block = mc.level.getBlockState(pos).getBlock();
//$$         if (!(block instanceof net.minecraft.world.level.block.ChestBlock
//$$                 || block instanceof net.minecraft.world.level.block.BarrelBlock
//$$                 || block instanceof net.minecraft.world.level.block.EnderChestBlock)) {
//$$             return;
//$$         }
//$$         // 防串数据：菜单的容器必须就是该位置的方块实体（否则是潜影盒菜单/其他，跳过）
//$$         var blockEntity = mc.level.getBlockEntity(pos);
//$$         if (!(blockEntity instanceof net.minecraft.world.Container beContainer)
//$$                 || menu.slots.get(0).container != beContainer) {
//$$             return;
//$$         }
//$$         Reference.LOGGER.info("[ChestTracker] 玩家开箱刷新索引 {}", pos);
//$$         refreshFromMenu(mc.level.dimension().location(), pos, menu, containerSize);
//$$     }

//$$     // ========== 工具 ==========

//$$     private static boolean isShulkerBox(ItemStack stack) {
//$$         return stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem
//$$                 && blockItem.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock;
//$$     }

//$$     private static void rebuildReverse() {
//$$         reverse.clear();
//$$         for (var de : index.entrySet()) {
//$$             for (var ce : de.getValue().entrySet()) {
//$$                 for (String itemId : ce.getValue().direct.keySet()) {
//$$                     reverse.computeIfAbsent(itemId, k -> new ArrayList<>())
//$$                             .add(new Candidate(de.getKey(), ce.getKey(), -1, false));
//$$                 }
//$$                 for (var be : ce.getValue().boxes.entrySet()) {
//$$                     for (String itemId : be.getValue().keySet()) {
//$$                         reverse.computeIfAbsent(itemId, k -> new ArrayList<>())
//$$                                 .add(new Candidate(de.getKey(), ce.getKey(), be.getKey(), true));
//$$                     }
//$$                 }
//$$             }
//$$         }
//$$     }

//$$     private static int countChests() {
//$$         int n = 0;
//$$         for (var de : index.values()) {
//$$             n += de.size();
//$$         }
//$$         return n;
//$$     }

//$$     private static String posKey(BlockPos pos) {
//$$         return pos.getX() + "," + pos.getY() + "," + pos.getZ();
//$$     }

//$$     private static BlockPos parsePos(String key) {
//$$         try {
//$$             String[] parts = key.split(",");
//$$             if (parts.length != 3) {
//$$                 return null;
//$$             }
//$$             return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
//$$         } catch (Exception e) {
//$$             return null;
//$$         }
//$$     }
//$$ }
//#endif
