package me.aleksilassila.litematica.printer.config;

import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.gui.ConfigUi;
import me.aleksilassila.litematica.printer.printer.zxy.chesttracker.ChestTrackerBridge;
import net.minecraft.client.Minecraft;


//监听按键
public class HotkeysCallback {
    private static final Minecraft client = Minecraft.getInstance();

    public static boolean onKeyAction(KeyAction action, IKeybind key) {
        if (client.player == null || client.level == null) {
            return false;
        }
        if (key == Configs.Hotkeys.OPEN_SCREEN.getKeybind()) {
            //#if MC > 260100
            //$$ client.gui.setScreen(new ConfigUi());
            //#else
            client.setScreen(new ConfigUi());
            //#endif
            return true;
        }
        if (key == Configs.Hotkeys.PRINTER_INVENTORY.getKeybind()) {
            try {
                ChestTrackerBridge.startAddPrinterInventory();
            } catch (Exception e) {
                Reference.LOGGER.warn("[ChestTracker] 添加库存快捷键回调异常", e);
            }
            return true;
        }
        if (key == Configs.Hotkeys.REMOVE_PRINT_INVENTORY.getKeybind()) {
            try {
                ChestTrackerBridge.clearPrinterMemory();
            } catch (Exception e) {
                Reference.LOGGER.warn("[ChestTracker] 清空库存快捷键回调异常", e);
            }
            return true;
        }

        return false;
    }
}
