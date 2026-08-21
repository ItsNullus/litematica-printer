package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import net.minecraft.core.BlockPos;

final class MineResultReporter {
    private MineResultReporter() {
    }

    static void record(BlockPos blockPos, BlockBreakResult result) {
        switch (result) {
            case COMPLETED -> {
                InteractionUtils.getRuntime().markRecentlyBroken(blockPos);
                HudStatsManager.getRuntime().trackExpectedMineClear(HudStatsManager.Mode.MINE, blockPos);
                HudStatsManager.getRuntime().recordRateUnit(HudStatsManager.Mode.MINE, 1);
                HudStatsManager.getRuntime().recordStatus(HudStatsManager.Mode.MINE, "运行中");
            }
            case COMPLETED_WAIT -> {
                InteractionUtils.getRuntime().markRecentlyBroken(blockPos);
                InteractionUtils.getRuntime().markPendingBroken(blockPos, ConfigUtils.getBreakCooldown());
                HudStatsManager.getRuntime().trackExpectedMineClear(HudStatsManager.Mode.MINE, blockPos);
                HudStatsManager.getRuntime().recordDeferred(HudStatsManager.Mode.MINE, "等待服务端确认");
            }
            case IN_PROGRESS -> {
                HudStatsManager.getRuntime().trackExpectedMineClear(HudStatsManager.Mode.MINE, blockPos);
                HudStatsManager.getRuntime().recordStatus(HudStatsManager.Mode.MINE, "破坏中");
            }
            case ABORTED -> {
                HudStatsManager.getRuntime().trackExpectedMineClear(HudStatsManager.Mode.MINE, blockPos);
                HudStatsManager.getRuntime().recordDeferred(HudStatsManager.Mode.MINE, "挖掘中断");
            }
            case FAILED -> HudStatsManager.getRuntime().recordFailure(HudStatsManager.Mode.MINE, "破坏失败");
        }
    }
}
