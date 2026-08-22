package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.integration.tweakeroo.TweakerooAdapter;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

final class MineToolSession {
    private static final double FRONTIER_MARGIN = 2.5D;

    private Item sessionToolItem;
    private int remainingInstantBudget;
    private int remainingTweakerooBreaks;
    private int toolSessionRemaining;
    private BlockPos lastSessionPos;

    void reset() {
        this.sessionToolItem = null;
        this.remainingInstantBudget = 0;
        this.remainingTweakerooBreaks = 0;
        this.toolSessionRemaining = 0;
        this.lastSessionPos = null;
    }

    void beginTick(LocalPlayer player, TweakerooAdapter tweakeroo) {
        int configuredBudget = Configs.Break.BREAK_BLOCKS_PER_TICK.getIntegerValue();
        this.remainingInstantBudget = configuredBudget <= 0 ? -1 : configuredBudget;
        this.remainingTweakerooBreaks = player == null
                ? 0
                : tweakeroo.safeBreakBudget(player.getMainHandItem());
    }

    Comparator<MineBreakExecutor.Target> comparator(LocalPlayer player) {
        return Comparator
                .comparingDouble((MineBreakExecutor.Target target) -> distanceScore(player, target))
                .thenComparingInt(target -> target.pos().getY())
                .thenComparingInt(target -> target.pos().getX())
                .thenComparingInt(target -> target.pos().getZ());
    }

    MineBreakExecutor.Target selectTarget(List<MineBreakExecutor.Target> candidates, MineBreakExecutor analyzer, LocalPlayer player) {
        MineBreakExecutor.Target nearest = candidates.get(0);
        if (this.lastSessionPos != null && this.toolSessionRemaining > 0) {
            for (MineBreakExecutor.Target target : candidates) {
                if (target.pos().equals(this.lastSessionPos)) {
                    this.sessionToolItem = target.bestToolItem();
                    return target;
                }
            }
        }
        if (this.sessionToolItem != null && this.toolSessionRemaining > 0) {
            double nearestDistance = distanceScore(player, nearest);
            for (MineBreakExecutor.Target target : candidates) {
                if (!this.isInsideFrontier(player, target, nearestDistance)) {
                    break;
                }
                if (analyzer.hasSameBestTool(target, this.sessionToolItem)) {
                    this.lastSessionPos = target.pos();
                    return target;
                }
            }
        }
        this.sessionToolItem = nearest.bestToolItem();
        this.toolSessionRemaining = this.getToolSessionQuota();
        this.lastSessionPos = nearest.pos();
        return nearest;
    }

    void startSession(MineBreakExecutor.Target firstTarget) {
        this.sessionToolItem = firstTarget.bestToolItem();
        if (this.toolSessionRemaining <= 0) {
            this.toolSessionRemaining = this.getToolSessionQuota();
        }
    }

    boolean matchesSessionTool(MineBreakExecutor analyzer, MineBreakExecutor.Target target) {
        return analyzer.hasSameBestTool(target, this.sessionToolItem);
    }

    boolean shouldStop(BlockBreakResult result, boolean hasActiveMinePos) {
        return result == BlockBreakResult.IN_PROGRESS
                || result == BlockBreakResult.ABORTED
                || hasActiveMinePos
                || !this.hasInstantBudget();
    }

    void consumeAction() {
        if (this.toolSessionRemaining > 0) {
            this.toolSessionRemaining--;
        }
    }

    /**
     * 当一个破坏目标已破掉(或服务端已确认完成)时,释放单目标黏性,允许 selectTarget 选下一个目标。
     * 块破掉后会自动离开候选集,黏性循环本就找不到它而失效;这里显式清除以覆盖「同位置被掉落方块/流体重新占据」等边缘情况。
     */
    void onTargetResolved(BlockBreakResult result, BlockPos pos) {
        if ((result == BlockBreakResult.COMPLETED || result == BlockBreakResult.COMPLETED_WAIT)
                && pos.equals(this.lastSessionPos)) {
            this.lastSessionPos = null;
        }
    }

    void consumeInstantBudget() {
        if (this.remainingInstantBudget > 0) {
            this.remainingInstantBudget--;
        }
        if (this.remainingTweakerooBreaks != Integer.MAX_VALUE && this.remainingTweakerooBreaks > 0) {
            this.remainingTweakerooBreaks--;
        }
    }

    boolean hasInstantBudget() {
        return (this.remainingInstantBudget < 0 || this.remainingInstantBudget > 0)
                && this.remainingTweakerooBreaks != 0;
    }

    boolean isInsideFrontier(LocalPlayer player, MineBreakExecutor.Target target, double nearestDistance) {
        if (this.remainingInstantBudget < 0) {
            return true;
        }
        double nearest = Math.sqrt(nearestDistance);
        double targetDistance = Math.sqrt(distanceScore(player, target));
        return targetDistance <= nearest + FRONTIER_MARGIN;
    }

    private int getToolSessionQuota() {
        return 8;
    }

    static double distanceScore(LocalPlayer player, MineBreakExecutor.Target target) {
        Vec3 eye = player.getEyePosition();
        return Vec3.atCenterOf(target.pos()).distanceToSqr(eye);
    }

}
