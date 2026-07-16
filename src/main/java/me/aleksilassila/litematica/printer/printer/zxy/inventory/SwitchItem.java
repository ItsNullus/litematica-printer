package me.aleksilassila.litematica.printer.printer.zxy.inventory;

import fi.dy.masa.malilib.util.InventoryUtils;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import me.aleksilassila.litematica.printer.utils.mods.ModLoadUtils;
import me.aleksilassila.litematica.printer.utils.mods.ShulkerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class SwitchItem {
    @NotNull
    static Minecraft client = Minecraft.getInstance();
    public static ItemStack reSwitchItem = null;
    public static Map<ItemStack, ItemStatistics> itemStacks = new HashMap<>();

    public static void removeItem(ItemStack itemStack) {
        ItemStack key = findRecordedStack(itemStack);
        if (key != null) {
            itemStacks.remove(key);
        }
    }

    public static void syncUseTime(ItemStack itemStack) {
        ItemStatistics itemStatistics = findStatistics(itemStack);
        if (itemStatistics != null) itemStatistics.syncUseTime();
    }

    public static void newItem(ItemStack itemStack, int slot, int shulkerBox) {
        if (shulkerBox != -1) itemStacks.put(itemStack.copy(), new ItemStatistics(slot, shulkerBox));
    }

    public static void openInv(ItemStack itemStack) {
        if (client.player == null
                || reSwitchItem == null
                || !client.player.containerMenu.equals(client.player.inventoryMenu)
                || ModLoadUtils.closeScreen > 0) {
            return;
        }
        AbstractContainerMenu sc = client.player.containerMenu;
        if (sc.slots.stream().skip(9).limit(sc.slots.size() - 10)
                .noneMatch(slot -> InventoryUtils.areStacksEqual(slot.getItem(), reSwitchItem))) {
            removeItem(reSwitchItem);
            reSwitchItem = null;
            return;
        }
        ItemStatistics itemStatistics = findStatistics(itemStack);
        if (itemStatistics != null) {
            if (itemStatistics.shulkerBoxSlot >= 0
                    && itemStatistics.shulkerBoxSlot < sc.slots.size()
                    && ShulkerUtils.openShulker(sc.slots.get(itemStatistics.shulkerBoxSlot).getItem(), itemStatistics.shulkerBoxSlot)) {
                ModLoadUtils.closeScreen++;
            } else {
                removeItem(reSwitchItem);
                reSwitchItem = null;
            }
        } else {
            removeItem(reSwitchItem);
            reSwitchItem = null;
        }
    }

    /**
     * 检查所有已记录的物品，找到最近一次使用的物品（useTime最小），
     * 并尝试自动打开该物品的背包界面进行操作。
     * 如果没有可用物品，则在游戏界面显示“背包已满，请先清理”的提示。
     */
    public static void checkItems() {
        final long[] min = {Long.MAX_VALUE};
        AtomicReference<ItemStack> key = new AtomicReference<>();
        itemStacks.forEach((k, statistics) -> {
            long useTime = statistics.useTime;
            if (useTime < min[0]) {
                min[0] = useTime;
                key.set(k);
            }
        });
        ItemStack itemStack = key.get();
        if (itemStack != null) {
            reSwitchItem = itemStack;
            openInv(itemStack);
        } else MessageUtils.setOverlayMessage(I18n.INVENTORY_FULL.getName(), false);
    }

    public static void reSwitchItem() {
        if (client.player == null || client.gameMode == null || reSwitchItem == null) return;
        LocalPlayer player = client.player;
        AbstractContainerMenu sc = player.containerMenu;
        if (sc.equals(player.inventoryMenu)) return;
        ItemStatistics statistics = findStatistics(reSwitchItem);
        if (statistics == null || statistics.slot < 0 || statistics.slot >= sc.slots.size()) {
            removeItem(reSwitchItem);
            reSwitchItem = null;
            player.closeContainer();
            return;
        }

        List<Integer> sameItem = new ArrayList<>();
        for (int i = 0; i < sc.slots.size(); i++) {
            Slot slot = sc.slots.get(i);
            if (!(slot.container instanceof Inventory) &&
                    InventoryUtils.areStacksEqual(reSwitchItem, slot.getItem()) &&
                    slot.getItem().getCount() < slot.getItem().getMaxStackSize()
            ) sameItem.add(i);
            if (slot.container instanceof Inventory && InventoryUtils.areStacksEqual(slot.getItem(), reSwitchItem)) {
                int slot1 = statistics.slot;
                boolean reInv = false;
                //检查记录的槽位是否有物品
                if (sc.slots.get(slot1).getItem().isEmpty()) {
                    client.gameMode.handleContainerInput(sc.containerId, i, 0, ContainerInput.PICKUP, client.player);
                    client.gameMode.handleContainerInput(sc.containerId, slot1, 0, ContainerInput.PICKUP, client.player);
                    reInv = true;
                } else {
                    int count = slot.getItem().getCount();
                    client.gameMode.handleContainerInput(sc.containerId, i, 0, ContainerInput.PICKUP, client.player);
                    for (Integer integer : sameItem) {
                        int count1 = sc.slots.get(integer).getItem().getCount();
                        int maxCount = sc.slots.get(integer).getItem().getMaxStackSize();
                        int i1 = maxCount - count1;
                        count -= i1;
                        client.gameMode.handleContainerInput(sc.containerId, integer, 0, ContainerInput.PICKUP, client.player);
                        if (count <= 0 || sc.getCarried().isEmpty()) {
                            reInv = true;
                            break;
                        }
                    }
                }
                removeItem(reSwitchItem);
                reSwitchItem = null;
                if (!reInv) {
                    MessageUtils.setOverlayMessage(I18n.INVENTORY_RESTORE_FAILED.getName(), false);
                }
                client.gameMode.handleContainerInput(sc.containerId, i, 0, ContainerInput.PICKUP, client.player);
                player.closeContainer();
                return;
            }
        }
        removeItem(reSwitchItem);
        reSwitchItem = null;
        MessageUtils.setOverlayMessage(I18n.INVENTORY_RESTORE_FAILED.getName(), false);
        player.closeContainer();
    }

    public static void reSet() {
        reSwitchItem = null;
        itemStacks = new HashMap<>();
    }

    private static ItemStatistics findStatistics(ItemStack stack) {
        ItemStack key = findRecordedStack(stack);
        return key == null ? null : itemStacks.get(key);
    }

    private static ItemStack findRecordedStack(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        if (itemStacks.containsKey(stack)) {
            return stack;
        }
        for (ItemStack recorded : itemStacks.keySet()) {
            if (InventoryUtils.areStacksEqual(recorded, stack)) {
                return recorded;
            }
        }
        return null;
    }

    public static class ItemStatistics {
        public int slot;
        public int shulkerBoxSlot;
        public long useTime = System.currentTimeMillis();

        public ItemStatistics(int slot, int shulkerBox) {
            this.slot = slot;
            this.shulkerBoxSlot = shulkerBox;
        }

        public void syncUseTime() {
            this.useTime = System.currentTimeMillis();
        }
    }
}
