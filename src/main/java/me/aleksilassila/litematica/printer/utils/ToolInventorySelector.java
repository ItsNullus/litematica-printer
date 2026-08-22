package me.aleksilassila.litematica.printer.utils;

import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.ToolSelectionUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Selects and equips the best currently safe tool for one block state. */
public final class ToolInventorySelector {
    private ToolInventorySelector() {
    }

    public static boolean switchToBestTool(Minecraft client, LocalPlayer player, BlockState blockState) {
        if (client == null || player == null || blockState == null || blockState.isAir()) {
            return false;
        }
        if (RuntimeAccess.get().inventorySwitchGuard().isWaiting()
                || PlayerUtils.getAbilities(player).instabuild) {
            return false;
        }

        ItemStack currentStack = player.getMainHandItem();
        boolean currentToolAllowed = InteractionUtils.isToolAllowedByDurabilityProtection(currentStack);
        float currentProgress = currentToolAllowed
                ? destroyProgress(player, blockState, currentStack)
                : 0.0F;
        float bestProgress = currentProgress;
        boolean preferSilkTouch = ToolSelectionUtils.prefersSilkTouchForDrops(blockState);
        boolean bestHasSilkTouch = preferSilkTouch
                && currentToolAllowed
                && ToolSelectionUtils.hasSilkTouch(currentStack);
        int bestSlot = -1;
        ItemStack bestStack = ItemStack.EMPTY;

        NonNullList<ItemStack> stacks = InventoryUtils.getMainStacks(player.getInventory());
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);
            if (stack.isEmpty() || !InteractionUtils.isToolAllowedByDurabilityProtection(stack)) {
                continue;
            }
            float progress = destroyProgress(player, blockState, stack);
            boolean stackHasSilkTouch = preferSilkTouch && ToolSelectionUtils.hasSilkTouch(stack);
            if ((stackHasSilkTouch && !bestHasSilkTouch)
                    || stackHasSilkTouch == bestHasSilkTouch && progress > bestProgress) {
                bestProgress = progress;
                bestHasSilkTouch = stackHasSilkTouch;
                bestSlot = slot;
                bestStack = stack;
            }
        }

        if (bestSlot == -1 || bestStack.isEmpty()
                || !InventoryUtils.setPickedItemToHand(bestSlot, bestStack, client)) {
            return false;
        }
        if (!Inventory.isHotbarSlot(bestSlot)) {
            RuntimeAccess.get().inventorySwitchGuard().markSwitchIfNeeded(bestStack.getItem());
        }
        return true;
    }

    private static float destroyProgress(LocalPlayer player, BlockState state, ItemStack stack) {
        float hardness = state.getBlock().defaultDestroyTime();
        if (hardness < 0.0F) return 0.0F;
        if (hardness == 0.0F) return 1.0F;
        int divisor = (!state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state)) ? 30 : 100;
        return PlayerUtils.getBlockBreakingSpeed(player, state, stack) / hardness / (float) divisor;
    }
}
