package me.aleksilassila.litematica.printer.printer.zxy.chesttracker;

//#if MC == 12104
//$$ import me.aleksilassila.litematica.printer.Reference;
//$$ import net.minecraft.client.Minecraft;
//$$ import red.jackf.chesttracker.impl.memory.MemoryBankImpl;
//$$ import red.jackf.chesttracker.impl.memory.metadata.Metadata;
//$$ import red.jackf.chesttracker.impl.storage.Storage;

//$$ import java.util.HashMap;

//$$ /**
//$$  * 打印机库存：一个独立的 ChestTracker 记忆库（每世界一个），用于记录"添加库存"扫到的容器内容，
//$$  * 供打印缺料时远程取物使用。与 ChestTracker 的普通库存互不干扰。
//$$  */
//$$ public final class PrinterMemory {
//$$     private static MemoryBankImpl bank;

//$$     private PrinterMemory() {
//$$     }

//$$     public static MemoryBankImpl get() {
//$$         return bank;
//$$     }

//$$     public static boolean isReady() {
//$$         return bank != null;
//$$     }

//$$     /** 世界标识：多人=服务器地址，单人=存档名（每世界独立库存；改名后需重新添加库存） */
//$$     public static String worldId() {
//$$         Minecraft mc = Minecraft.getInstance();
//$$         try {
//$$             if (mc.getCurrentServer() != null) {
//$$                 String address = mc.getCurrentServer().ip;
//$$                 if (address != null && !address.isBlank()) {
//$$                     return sanitize(address);
//$$                 }
//$$             }
//$$             if (mc.getSingleplayerServer() != null) {
//$$                 String levelName = mc.getSingleplayerServer().getWorldData().getLevelName();
//$$                 if (levelName != null && !levelName.isBlank()) {
//$$                     return sanitize(levelName);
//$$                 }
//$$             }
//$$         } catch (Exception e) {
//$$             Reference.LOGGER.warn("无法获取世界标识，使用 fallback", e);
//$$         }
//$$         return "unknown";
//$$     }

//$$     private static String sanitize(String s) {
//$$         return s.replaceAll("[^a-zA-Z0-9_.-]", "_");
//$$     }

//$$     public static void createOrLoad() {
//$$         try {
//$$             String id = worldId() + "-printer";
//$$             unload();
//$$             bank = Storage.load(id).orElseGet(() -> {
//$$                 var newBank = new MemoryBankImpl(Metadata.blankWithName(worldId() + "-printer"), new HashMap<>());
//$$                 newBank.setId(id);
//$$                 return newBank;
//$$             });
//$$             save();
//$$             Reference.LOGGER.info("[ChestTracker] 打印机库存已加载 id={}", id);
//$$         } catch (Exception e) {
//$$             Reference.LOGGER.warn("[ChestTracker] 打印机库存加载失败", e);
//$$             bank = null;
//$$         }
//$$     }

//$$     public static void save() {
//$$         if (bank != null) {
//$$             try {
//$$                 Storage.save(bank);
//$$             } catch (Exception e) {
//$$                 Reference.LOGGER.warn("打印机库存保存失败", e);
//$$             }
//$$         }
//$$     }

//$$     public static void unload() {
//$$         if (bank != null) {
//$$             save();
//$$             bank = null;
//$$         }
//$$     }

//$$     /** 清空打印机库存：删除存储文件并重建空库存 */
//$$     public static void clear() {
//$$         if (bank != null) {
//$$             String id = bank.getId();
//$$             unload();
//$$             try {
//$$                 Storage.delete(id);
//$$             } catch (Exception e) {
//$$                 Reference.LOGGER.warn("打印机库存删除失败", e);
//$$             }
//$$         }
//$$         createOrLoad();
//$$     }
//$$ }
//#endif
