package me.aleksilassila.litematica.printer.printer.zxy.inventory;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import me.aleksilassila.litematica.printer.utils.mods.ModLoadUtils;
import me.aleksilassila.litematica.printer.utils.mods.ShulkerUtils;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.mixin.printer.litematica.InventoryUtilsAccessor;
import me.aleksilassila.litematica.printer.printer.zxy.utils.ZxyUtils;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.LinkedHashSet;

public class InventoryUtils {
    private static int shulkerCooldown = 0;
    private static int openHandlerTimeout = 0;
    private static final int OPEN_HANDLER_TIMEOUT_TICKS = 40;

    private static final Minecraft client = Minecraft.getInstance();

    public static boolean isInventory(Level world, BlockPos pos) {
        return fi.dy.masa.malilib.util.InventoryUtils.getInventory(world, pos) != null;
    }

    public static boolean canOpenInv(BlockPos pos) {
        if (client.level != null) {
            BlockState blockState = client.level.getBlockState(pos);
            BlockEntity blockEntity = client.level.getBlockEntity(pos);
            boolean isInventory = InventoryUtils.isInventory(client.level, pos);
            try {
                if ((isInventory && blockState.getMenuProvider(client.level, pos) == null) ||
                        (blockEntity instanceof ShulkerBoxBlockEntity entity &&
                                //#if MC > 260100
                                //$$ !client.level.noCollision(Shulker.getProgressDeltaAabb(1.0F, blockState.getValue(BlockStateProperties.FACING), 0.0F, 0.5F, Vec3.atBottomCenterOf(pos)).move(pos).deflate(1.0E-6)) &&
                                //#elseif MC > 12103
                                !client.level.noCollision(Shulker.getProgressDeltaAabb(1.0F, blockState.getValue(BlockStateProperties.FACING), 0.0F, 0.5F, pos.getBottomCenter()).move(pos).deflate(1.0E-6)) &&
                                //#elseif MC <= 12103 && MC > 12004
                                //$$ !client.level.noCollision(Shulker.getProgressDeltaAabb(1.0F, blockState.getValue(BlockStateProperties.FACING), 0.0F, 0.5F).move(pos).deflate(1.0E-6)) &&
                                //#elseif MC <= 12004
                                //$$ !client.level.noCollision(Shulker.getProgressDeltaAabb(blockState.getValue(BlockStateProperties.FACING), 0.0f, 0.5f).move(pos).deflate(1.0E-6)) &&
                                //#endif
                                entity.getAnimationStatus() == ShulkerBoxBlockEntity.AnimationStatus.CLOSED)) {
                    return false;
                } else if (!isInventory) {
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
            return true;
        } else {
            return false;
        }
    }

    public static HashSet<Item> lastNeedItemList = new LinkedHashSet<>();
    public static boolean isOpenHandler = false;

    private static boolean loggedQuickShulkerConfig;

    public static boolean switchItem() {
        // 远程取物/扫描进行中让路（避免抢菜单：远程开箱会关掉快捷潜影盒刚开的盒子）
        if (me.aleksilassila.litematica.printer.printer.zxy.chesttracker.ChestTrackerBridge.isAwaitingStack()) {
            return true;
        }
        if (Configs.Placement.QUICK_SHULKER.getBooleanValue() && !loggedQuickShulkerConfig) {
            loggedQuickShulkerConfig = true;
            Reference.LOGGER.info("[QuickShulker] 配置: QUICK_SHULKER={} MODE={} COOLDOWN={} STORE_ORDERLY={} qsModLoaded={}",
                    Configs.Placement.QUICK_SHULKER.getBooleanValue(),
                    Configs.Placement.QUICK_SHULKER_MODE.getOptionListValue(),
                    Configs.Placement.QUICK_SHULKER_COOLDOWN.getIntegerValue(),
                    Configs.Placement.STORE_ORDERLY.getBooleanValue(),
                    ModLoadUtils.isQuickShulkerLoaded());
        }
        if (Configs.Placement.QUICK_SHULKER.getBooleanValue() && !lastNeedItemList.isEmpty()) {
            Reference.LOGGER.info("[QuickShulker] switchItem: 缺 {} 个物品, isOpenHandler={}", lastNeedItemList.size(), isOpenHandler);
        }
        if (!lastNeedItemList.isEmpty() && !isOpenHandler) {
            LocalPlayer player = client.player;
            if (player == null) {
                clearSwitchRequest();
                return false;
            }
            AbstractContainerMenu sc = player.containerMenu;
            if (!sc.equals(player.inventoryMenu)) {
                Reference.LOGGER.info("[QuickShulker] switchItem: 容器菜单占用, 让路");
                return true;
            }
            if (Configs.Placement.STORE_ORDERLY.getBooleanValue()
                    && Configs.Placement.QUICK_SHULKER.getBooleanValue()
                    && SwitchItem.tryRestoreForInventoryPressure()) {
                Reference.LOGGER.info("[QuickShulker] switchItem: restore 压力接管");
                return true;
            }

            if (Configs.Placement.QUICK_SHULKER.getBooleanValue()) {
                if (shulkerCooldown > 0) {
                    Reference.LOGGER.info("[QuickShulker] switchItem: 冷却中 {}", shulkerCooldown);
                    return true;
                }
                if (openShulker(lastNeedItemList)) {
                    return true;
                }
                Reference.LOGGER.info("[QuickShulker] switchItem: 背包无可用潜影盒, 尝试远程取物");
            }
            // 背包潜影盒找不到所需物品 → 远程取物（箱子兜底；v2 架构：本地盒子优先，箱子其次）
            if (me.aleksilassila.litematica.printer.config.Configs.Hotkeys.REMOTE_TAKE.getBooleanValue()
                    && me.aleksilassila.litematica.printer.printer.zxy.chesttracker.ChestTrackerBridge.requestMissingItem(
                    lastNeedItemList.toArray(new Item[0]))) {
                return true;
            }
            clearSwitchRequest();
        }
        return false;
    }

    public static boolean hasPendingSwitchRequest() {
        return isOpenHandler || !lastNeedItemList.isEmpty() || SwitchItem.hasPendingRestore();
    }

    public static boolean shouldPauseForSwitchRequest() {
        return Configs.Placement.QUICK_SHULKER.getBooleanValue() && hasPendingSwitchRequest();
    }

    public static boolean shouldSuppressContainerScreen() {
        LocalPlayer player = client.player;
        return player != null
                && !player.containerMenu.equals(player.inventoryMenu)
                && (isOpenHandler || SwitchItem.isWaitingForRestoreContainer());
    }

    public static void resetRuntime() {
        clearSwitchRequest();
        shulkerCooldown = 0;
        ModLoadUtils.closeScreen = 0;
    }

    static int shulkerInventoryMenuSlot = -1;

    public static void switchInv() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || client.gameMode == null) {
            clearSwitchRequest();
            return;
        }
        AbstractContainerMenu sc = player.containerMenu;
        if (sc.equals(player.inventoryMenu)) {
            return;
        }
        Reference.LOGGER.info("[QuickShulker] switchInv: 容器内容到达, 缺 {} 个物品", lastNeedItemList.size());
        NonNullList<Slot> slots = sc.slots;
        if (slots.isEmpty()) {
            clearSwitchRequest();
            player.closeContainer();
            return;
        }
        for (Item item : lastNeedItemList) {
            int containerSize = Math.min(slots.size(), slots.get(0).container.getContainerSize());
            for (int y = 0; y < containerSize; y++) {
                if (slots.get(y).getItem().getItem().equals(item)) {
                    String[] str = fi.dy.masa.litematica.config.Configs.Generic.PICK_BLOCKABLE_SLOTS.getStringValue().split(",");
                    if (str.length == 0) return;
                    for (String s : str) {
                        if (s == null) break;
                        try {
                            int c = Integer.parseInt(s) - 1;
                            if (BuiltInRegistries.ITEM.getKey(player.getInventory().getItem(c).getItem()).toString().contains("shulker_box") &&
                                    Configs.Placement.QUICK_SHULKER.getBooleanValue()) {
                                MessageUtils.setOverlayMessage(I18n.INVENTORY_SHULKER_OCCUPIED.getName(), false);
                                continue;
                            }
                            int a = InventoryUtilsAccessor.getEmptyPickBlockableHotbarSlot(player.getInventory()) == -1 ?
                                    InventoryUtilsAccessor.getPickBlockTargetSlot(player) :
                                    InventoryUtilsAccessor.getEmptyPickBlockableHotbarSlot(player.getInventory());
                            c = a == -1 ? c : a;
                            ItemStack retrievedStack = slots.get(y).getItem().copy();
                            ItemStack sourceShulker = player.inventoryMenu.slots
                                    .get(shulkerInventoryMenuSlot).getItem().copy();
                            int movedPlayerSlot = ZxyUtils.switchPlayerInvToHotbarAir(c);
                            SwitchItem.moveTrackedItem(c, movedPlayerSlot);
                            fi.dy.masa.malilib.util.InventoryUtils.swapSlots(sc, y, c);
                            SwitchItem.newItem(
                                    retrievedStack,
                                    sourceShulker,
                                    y,
                                    shulkerInventoryMenuSlot,
                                    c
                            );
                            me.aleksilassila.litematica.printer.utils.InventoryUtils.setSelectedSlot(player.getInventory(), c);
                            me.aleksilassila.litematica.printer.utils.InventoryUtils.syncSelectedHotbarSlot();
                            me.aleksilassila.litematica.printer.utils.InventorySwitchGuard.markSwitchIfNeeded(item);
                            player.closeContainer();
                            Reference.LOGGER.info("[QuickShulker] switchInv: 已取 {} 到热栏槽 {}", BuiltInRegistries.ITEM.getKey(item), c);
                            clearSwitchRequest();
                            return;
                        } catch (Exception e) {
                            Reference.LOGGER.warn("Quick Shulker 物品切换失败", e);
                        }
                    }
                }
            }
        }
        clearSwitchRequest();
        AbstractContainerMenu sc2 = player.containerMenu;
        if (!sc2.equals(player.inventoryMenu)) {
            player.closeContainer();
        }
    }

    private static boolean openShulker(HashSet<Item> items) {
        if (shulkerCooldown > 0) {
            return false;
        }
        for (Item item : items) {
            AbstractContainerMenu sc = Minecraft.getInstance().player.inventoryMenu;
            for (int i = 9; i < sc.slots.size(); i++) {
                ItemStack stack = sc.slots.get(i).getItem();
                String itemid = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (itemid.contains("shulker_box") && stack.getCount() == 1) {
                    NonNullList<ItemStack> items1 = fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(stack, -1);
                    if (items1.stream().anyMatch(s1 -> s1.getItem().equals(item))) {
                        try {
                            shulkerInventoryMenuSlot = i;
                            if (!ShulkerUtils.openShulker(stack, shulkerInventoryMenuSlot)) {
                                Reference.LOGGER.info("[QuickShulker] openShulker: 槽位 {} 的盒子打开失败(含目标 {})", i, BuiltInRegistries.ITEM.getKey(item));
                                shulkerInventoryMenuSlot = -1;
                                continue;
                            }
                            Reference.LOGGER.info("[QuickShulker] openShulker: 打开槽位 {} 的盒子 (目标 {})", i, BuiltInRegistries.ITEM.getKey(item));
                            ModLoadUtils.closeScreen++;
                            isOpenHandler = true;
                            openHandlerTimeout = OPEN_HANDLER_TIMEOUT_TICKS;
                            shulkerCooldown = Configs.Placement.QUICK_SHULKER_COOLDOWN.getIntegerValue();
                            return true;
                        } catch (Exception e) {
                            Reference.LOGGER.warn("[QuickShulker] openShulker 异常", e);
                        }
                    }
                }
            }
        }
        Reference.LOGGER.info("[QuickShulker] openShulker: 背包中无含所需物品的潜影盒");
        return false;
    }

    public static void tick() {
        SwitchItem.tick();
        if (ModLoadUtils.closeScreen > 0) {
            ModLoadUtils.closeScreen--;
        }
        if (isOpenHandler && openHandlerTimeout > 0 && --openHandlerTimeout <= 0) {
            clearSwitchRequest();
        }
        if (shulkerCooldown > 0) {
            shulkerCooldown--;
        }
        if (Configs.Placement.STORE_ORDERLY.getBooleanValue()
                && Configs.Placement.QUICK_SHULKER.getBooleanValue()
                && !isOpenHandler
                && !InventorySwitchGuard.isWaiting()
                && !TakeItOutUtils.isAwaitingStack()) {
            SwitchItem.maintainOrderlyStorage();
        }
    }

    private static void clearSwitchRequest() {
        shulkerInventoryMenuSlot = -1;
        lastNeedItemList = new LinkedHashSet<>();
        isOpenHandler = false;
        openHandlerTimeout = 0;
    }
}
