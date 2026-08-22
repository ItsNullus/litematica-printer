package me.aleksilassila.litematica.printer.integration.tweakeroo;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/** Optional Tweakeroo tool-switch capability. The printer never reimplements its durability policy. */
public interface TweakerooToolSwitchPort {
    boolean isEffectiveToolSwitchEnabled();

    boolean isNearlyBrokenToolSwapEnabled();

    void switchToEffectiveTool(BlockPos pos);

    void swapNearlyBrokenTool();

    int safeBreakBudget(ItemStack stack);
}
