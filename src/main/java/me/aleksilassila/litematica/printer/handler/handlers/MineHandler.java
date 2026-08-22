package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ExcavateListMode;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import me.aleksilassila.litematica.printer.handler.TickContext;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.integration.tweakeroo.TweakerooAdapter;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;


public class MineHandler extends FeatureModuleBase {
    public static final String NAME = "mine";
    private final MineBreakExecutor analyzer;
    private final TweakerooAdapter tweakeroo;
    private final MineToolSession toolSession = new MineToolSession();
    private final MineCandidateQueue candidates = new MineCandidateQueue();
    @Nullable
    private BlockPos activeMinePos;

    public MineHandler(PrinterRuntime runtime) {
        super(runtime, NAME, PrintModeType.MINE, Configs.Core.MINE, Configs.Mine.MINE_SELECTION_TYPE, true);
        this.tweakeroo = runtime.tweakeroo();
        this.analyzer = new MineBreakExecutor(runtime.client(), this.tweakeroo);
    }

    @Override
    public void tick(TickContext context) {
        if (!ConfigUtils.isEnable() || !ConfigUtils.isMineMode()) {
            this.analyzer.reset();
            this.activeMinePos = null;
            this.toolSession.reset();
        }
        super.tick(context);
    }

    public int getRetryQueueSize() {
        return this.candidates.size() + (this.activeMinePos == null ? 0 : 1);
    }

    private boolean mineRestriction(BlockState blockState) {
        if (!InteractionUtils.breakRestriction(blockState)) {
            return false;
        }
        if (Configs.Mine.EXCAVATE_LIMITER.getOptionListValue().equals(ExcavateListMode.TWEAKEROO)) {
            return this.tweakeroo.allowsBreak(blockState);
        }
        return this.tweakeroo.allowsConfiguredBreak(blockState);
    }

    @Override
    protected int getTickInterval() {
        return Configs.Break.BREAK_INTERVAL.getIntegerValue();
    }

    @Override
    protected int getMaxEffectiveExecutionsPerTick() {
        return 0;
    }

    @Override
    protected int getScanGuardLimit() {
        return 0;
    }

    @Override
    protected Iterable<BlockPos> getIterationPositions(PrinterBox playerInteractionBox) {
        List<PrinterBox> scanSourceBoxes = this.getScanSourceBoxes(playerInteractionBox);
        if (scanSourceBoxes.isEmpty()) {
            return List.of();
        }
        Predicate<BlockPos> selectionPredicate = this.createSelectionRangePredicate();
        Predicate<BlockPos> reachPredicate = this.createScanReachPredicate();
        
        return this.scanEngine.iterable(
                NAME,
                scanSourceBoxes,
                this.level,
                this.litematica.schematicWorld(),
                this.player,
                this.getScanGuardLimit(),
                ScanIntent.MINE,
                this::isMineScanCandidate,
                pos -> reachPredicate.test(pos) && selectionPredicate.test(pos)
        );
    }

    @Override
    protected void preprocess() {
        this.pruneCandidates();
        this.analyzer.beginTick();
        this.toolSession.beginTick();
        this.continueActiveMineTarget();
    }

    @Override
    protected void onRuntimeReset() {
        this.candidates.clear();
        this.activeMinePos = null;
        this.analyzer.reset();
        this.toolSession.reset();
    }

    @Override
    protected boolean canIterate() {
        return this.activeMinePos == null && !InteractionUtils.getRuntime().hasActiveDestroyTarget();
    }

    @Override
    protected boolean iterationPositionsPrefilterReachAndSelection() {
        return true;
    }

    @Override
    protected boolean iterationPositionsPrefilterCooldown() {
        return false;
    }

    @Override
    protected boolean iterationPositionsAreExactCandidates() {
        return true;
    }

    @Override
    public boolean canIterationBlockPos(BlockPos pos) {
        return this.isMineScanCandidate(pos);
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        MineBreakExecutor.Target target = this.analyzer.analyze(blockPos);
        if (target == null) {
            this.setIterationConsumedEffectiveExecution(false);
            return;
        }
        this.candidates.add(target);
        this.setIterationConsumedEffectiveExecution(false);
    }

    @Override
    protected void stopIteration(boolean interrupt) {
        if (this.actionBroker.isWaitingForLook() || this.activeMinePos != null || this.candidates.isEmpty()) {
            return;
        }
        List<MineBreakExecutor.Target> orderedCandidates = this.candidates.ordered(this.toolSession.comparator(this.player));
        MineBreakExecutor.Target nearest = orderedCandidates.get(0);
        MineBreakExecutor.Target selected = this.toolSession.selectTarget(orderedCandidates, this.analyzer, this.player);
        BlockPos selectedPos = selected.pos();
        selected = this.analyzer.analyze(selectedPos);
        if (selected == null) {
            this.removeCandidate(selectedPos);
            return;
        }
        this.executeToolSession(selected, MineToolSession.distanceScore(this.player, nearest), orderedCandidates);
    }

    private void continueActiveMineTarget() {
        BlockPos pos = this.activeMinePos;
        if (pos == null) {
            return;
        }
        if (!this.canContinueActiveMineTarget(pos)) {
            this.activeMinePos = null;
            return;
        }
        if (!this.toolSession.hasInstantBudget()) {
            this.activeMinePos = null;
            return;
        }
        BlockBreakResult result = InteractionUtils.getRuntime().continueDestroyBlockForMine(pos, Direction.DOWN, true);
        MineResultReporter.record(pos, result);
        if (result == BlockBreakResult.IN_PROGRESS
                || result == BlockBreakResult.COMPLETED
                || result == BlockBreakResult.COMPLETED_WAIT) {
            this.toolSession.consumeAction();
        }
        if (result == BlockBreakResult.COMPLETED || result == BlockBreakResult.COMPLETED_WAIT) {
            this.toolSession.consumeInstantBudget();
        }
        this.toolSession.onTargetResolved(result, pos);
        if (result != BlockBreakResult.IN_PROGRESS) {
            this.activeMinePos = null;
            this.setBlockPosCooldown(pos, ConfigUtils.getBreakCooldown());
        }
    }

    private boolean canContinueActiveMineTarget(BlockPos pos) {
        return pos != null
                && this.canReachIterationPosition(pos)
                && InteractionUtils.canBreakBlock(pos)
                && mineRestriction(this.level.getBlockState(pos));
    }

    private boolean isMineScanCandidate(BlockPos pos) {
        if (pos == null || this.level == null || this.player == null || this.gameMode == null) {
            return false;
        }

        if (InteractionUtils.getRuntime().isRecentlyBroken(pos)
                || InteractionUtils.getRuntime().isPendingDelayedDestroy(pos)) {
            return false;
        }

        BlockState state = this.level.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
            return false;
        }

        if (Configs.Break.BREAK_CHECK_HARDNESS.getBooleanValue() && state.getBlock().defaultDestroyTime() < 0) {
            return false;
        }

        return this.canReachIterationPosition(pos)
                && !this.player.blockActionRestricted(this.level, pos, this.gameMode.getPlayerMode())
                && mineRestriction(state);
    }

    private void executeToolSession(MineBreakExecutor.Target firstTarget, double nearestDistance,
                                    List<MineBreakExecutor.Target> orderedCandidates) {
        this.toolSession.startSession(firstTarget);
        BlockBreakResult result = this.executeSessionTarget(firstTarget, !this.analyzer.isCurrentToolEffective(firstTarget));
        if (this.toolSession.shouldStop(result, this.activeMinePos != null)) {
            return;
        }
        for (MineBreakExecutor.Target queuedTarget : orderedCandidates) {
            if (queuedTarget.pos().equals(firstTarget.pos())) {
                continue;
            }
            // Re-analyze only when a target reaches the action frontier. Rebuilding the whole
            // queue on every durability change made large excavations stall while scanning.
            MineBreakExecutor.Target target = this.analyzer.analyze(queuedTarget.pos());
            if (target == null) {
                this.removeCandidate(queuedTarget.pos());
                continue;
            }
            if (!this.toolSession.hasInstantBudget()) {
                break;
            }
            if (!this.toolSession.matchesSessionTool(this.analyzer, target)) {
                continue;
            }
            if (!this.toolSession.isInsideFrontier(this.player, target, nearestDistance)) {
                break;
            }
            result = this.executeSessionTarget(target, false);
            if (this.toolSession.shouldStop(result, this.activeMinePos != null)) {
                break;
            }
        }
    }

    private BlockBreakResult executeSessionTarget(MineBreakExecutor.Target target, boolean allowToolSwitch) {
        boolean switchForRecovery = this.player != null
                && target.shouldSwitchToRecoveryTool(this.player.getMainHandItem());
        BlockBreakResult result = this.executeMineTarget(target, allowToolSwitch || switchForRecovery);
        if (result != BlockBreakResult.FAILED) {
            this.setBlockPosCooldown(target.pos(), ConfigUtils.getBreakCooldown());
        }
        if (result == BlockBreakResult.COMPLETED || result == BlockBreakResult.COMPLETED_WAIT) {
            this.toolSession.consumeInstantBudget();
        }
        if (result == BlockBreakResult.IN_PROGRESS
                || result == BlockBreakResult.COMPLETED
                || result == BlockBreakResult.COMPLETED_WAIT) {
            this.toolSession.consumeAction();
        }
        this.toolSession.onTargetResolved(result, target.pos());
        MineResultReporter.record(target.pos(), result);
        if (result != BlockBreakResult.IN_PROGRESS) {
            this.removeCandidate(target.pos());
        }
        return result;
    }

    private BlockBreakResult executeMineTarget(MineBreakExecutor.Target target, boolean allowToolSwitch) {
        BlockBreakResult result = InteractionUtils.getRuntime().continueDestroyBlockForMine(target.pos(), Direction.DOWN, allowToolSwitch);
        if (result == BlockBreakResult.IN_PROGRESS) {
            this.activeMinePos = target.pos();
        }
        return result;
    }

    private void pruneCandidates() {
        this.candidates.removeIf(target -> target == null
                || !this.isMineScanCandidate(target.pos())
                || this.isBlockPosOnCooldown(target.pos()));
    }

    private void removeCandidate(BlockPos pos) {
        if (pos == null) {
            return;
        }
        this.candidates.remove(pos);
    }

}
