package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.printer.PrinterUtils;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.RegistryFilterResolver;
import me.aleksilassila.litematica.printer.utils.minecraft.BlockUtils;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

public class FluidHandler extends FeatureModuleBase {
    public final static String NAME = "fluid";
    private static final Direction[] PLACEMENT_SIDE_ORDER = {
            Direction.DOWN,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.EAST,
            Direction.WEST,
            Direction.UP
    };

    /**
     * How long to cool a cell after a rejected placement attempt. A rejection is usually caused
     * by a falling fill-block entity in the column, which settles within a few ticks. Shorter
     * than the normal placement cooldown so the cell is retried promptly once it becomes
     * placeable again, but long enough that a momentarily-occupied column is not hot-rejected
     * every single tick.
     */
    private static final int REJECT_RETRY_COOLDOWN_TICKS = 4;

    private List<String> fillBlocks = new ArrayList<>();
    private List<Item> fillItems = new ArrayList<>();
    private Item[] fillItemArray = new Item[0];

    private List<String> fluidBlocks = new ArrayList<>();
    private Set<Fluid> fluids = Set.of();
    private int observedScanConfigHash = Integer.MIN_VALUE;

    public FluidHandler() {
        super(PrinterRuntime.get(), NAME, PrintModeType.FLUID, Configs.Core.FLUID, Configs.Fluid.FLUID_SELECTION_TYPE, true);
    }

    public FluidHandler(PrinterRuntime runtime) {
        super(runtime, NAME, PrintModeType.FLUID, Configs.Core.FLUID, Configs.Fluid.FLUID_SELECTION_TYPE, true);
    }

    @Override
    protected int getTickInterval() {
        return Configs.Placement.PLACE_INTERVAL.getIntegerValue();
    }

    @Override
    protected int getMaxEffectiveExecutionsPerTick() {
        return Configs.Placement.PLACE_BLOCKS_PER_TICK.getIntegerValue();
    }

    @Override
    protected void preprocess() {
        // 填充方块
        List<String> fileBlocks = Configs.Fluid.FLUID_REPLACE_BLOCK_LIST.getStrings();
        if (!fileBlocks.equals(fillBlocks)) {
            fillBlocks = new ArrayList<>(fileBlocks);
            fillItems = new ArrayList<>();
            if (!fileBlocks.isEmpty()) {
                fillItems.addAll(RegistryFilterResolver.resolveItems(fillBlocks));
            }
            fillItemArray = fillItems.toArray(new Item[0]);
        }
        // 流体方块
        List<String> fluidBlocks = Configs.Fluid.FLUID_LIST.getStrings();
        if (!fluidBlocks.equals(this.fluidBlocks)) {
            this.fluidBlocks = new ArrayList<>(fluidBlocks);
            fluids = fluidBlocks.isEmpty() ? Set.of() : RegistryFilterResolver.resolveFluids(this.fluidBlocks);
        }
        if (fillItems.isEmpty()) {
            HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FLUID, "无流体填充方块");
        } else if (this.fluidBlocks.isEmpty()) {
            HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FLUID, "无目标流体配置");
        } else {
            HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FLUID, "运行中");
        }
        int scanConfigHash = this.getScanConfigHash();
        if (this.observedScanConfigHash != Integer.MIN_VALUE
                && this.observedScanConfigHash != scanConfigHash) {
            this.scanEngine.resetOwner(NAME);
            this.requestFullScan();
        }
        this.observedScanConfigHash = scanConfigHash;
    }

    @Override
    protected void onRuntimeReset() {
        this.observedScanConfigHash = Integer.MIN_VALUE;
    }

    @Override
    protected boolean canIterate() {
        return !fillItems.isEmpty() && !fluids.isEmpty();
    }

    @Override
    protected boolean iterationPositionsPrefilterReachAndSelection() {
        return true;
    }

    @Override
    protected boolean iterationPositionsAreExactCandidates() {
        return true;
    }

    @Override
    protected Iterable<BlockPos> getIterationPositions(PrinterBox playerInteractionBox) {
        List<PrinterBox> scanSourceBoxes = this.getScanSourceBoxes(playerInteractionBox);
        if (scanSourceBoxes.isEmpty()) {
            return List.of();
        }
        Predicate<BlockPos> selectionPredicate = this.createSelectionRangePredicate();
        Predicate<BlockPos> reachPredicate = this.createScanReachPredicate();

        // Always run full passes (RESTART), mirroring FillHandler. A full pass that yields nothing
        // still counts as a completed pass (SectionScanSession.finishPass increments
        // completedPasses), which the feature idle policy needs to admit lazy scanning once the water
        // is fully filled. The previous INVALIDATIONS_ONLY state machine stopped scheduling passes
        // after the first completed pass, so empty passes never accumulated and the module could
        // never settle into lazy scanning.
        //
        // Iterate the scan session directly (Beta2.6 behaviour). The session cursor resumes from
        // where the previous tick left off and yields distance-ordered targets for as long as the
        // per-tick scan budget allows. No intermediate FIFO queue: a queue serialised work to one
        // target per tick and let already-placed ("zombie") entries accumulate to ~15k, which read
        // as a slow ring-by-ring expansion even though the scan itself finished in 3 ticks.
        return this.scanEngine.iterable(
                NAME,
                scanSourceBoxes,
                this.level,
                null,
                this.player,
                this.getScanGuardLimit(),
                ScanIntent.FLUID,
                this::isReadyFluidTarget,
                pos -> reachPredicate.test(pos) && selectionPredicate.test(pos),
                ScanEngine.PassPolicy.RESTART
        );
    }

    @Override
    public boolean canIterationBlockPos(BlockPos blockPos) {
        return this.isTargetFluid(blockPos);
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        FluidState fluidState = level.getBlockState(blockPos).getFluidState();
        if (!this.isTargetFluid(fluidState)) {
            setIterationConsumedEffectiveExecution(false);
            return;
        }
        if (!InventoryUtils.switchToItems(player, fillItemArray)) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FLUID, "缺少流体填充方块");
            MissingMaterialTracker.INSTANCE.recordMissing(
                    fillItemArray,
                    null,
                    null,
                    level.getGameTime()
            );
            setIterationConsumedEffectiveExecution(false);
            if (this.actionBroker.isResourceHeld(ResourceLease.INVENTORY)) {
                skipIteration.set(true);
            }
            return;
        }
        MissingMaterialTracker.INSTANCE.resolve(fillItemArray, null);
        BlockPos clickTarget = blockPos;
        Direction clickSide = Direction.DOWN;
        if (!Configs.Print.PLACE_IN_AIR.getBooleanValue()) {
            Direction placementSide = this.findPlacementSide(blockPos);
            if (placementSide == null) {
                HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FLUID, "无有效放置面");
                // This was ready when scanned but its support disappeared before execution.
                // A server block update (or the next full pass after the queue drains) will
                // rediscover it; keeping it hot here would create an endless retry loop.
                setIterationConsumedEffectiveExecution(false);
                return;
            }
            clickTarget = blockPos.relative(placementSide);
            clickSide = placementSide.getOpposite();
        }
        if (!this.actionBroker.queueClick(
                clickTarget,
                clickSide,
                Vec3.ZERO,
                false,
                1,
                fillItemArray,
                ActionManager.ActionSource.FLUID
        )) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FLUID, "动作队列占用");
            setIterationConsumedEffectiveExecution(false);
            skipIteration.set(true);
            return;
        }
        BlockState previousState = level.getBlockState(blockPos);
        ActionManager.SendResult sendResult = this.actionBroker.sendQueue(player);
        if (sendResult.isWaiting()) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FLUID, "等待转头");
            skipIteration.set(true);
            return;
        }
        if (!sendResult.isSent()) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FLUID, "放置动作未发送");
            // A rejected interaction means this cell is momentarily unplaceable. The common cause
            // is a falling fill-block entity (sand) currently occupying the cell: every placement
            // of sand into water spawns a FallingBlockEntity (blocksBuilding=true) that blocks
            // further placement in its column until it lands. Do NOT abort the whole pass: that
            // serialised work to one rejected attempt per tick and read as the slow ring-by-ring
            // expansion. Cool the cell briefly (the falling entity settles within a few ticks)
            // and let the iteration loop move on to other candidates.
            setIterationConsumedEffectiveExecution(false);
            this.setBlockPosCooldown(blockPos, REJECT_RETRY_COOLDOWN_TICKS);
            return;
        }
        HudStatsManager.INSTANCE.trackExpectedBlockChange(HudStatsManager.Mode.FLUID, blockPos, previousState);
        HudStatsManager.INSTANCE.recordRateUnit(HudStatsManager.Mode.FLUID, 1);
        HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FLUID, "运行中");
        this.setBlockPosCooldown(blockPos, ConfigUtils.getPlaceCooldown());
    }

    private Direction findPlacementSide(BlockPos blockPos) {
        for (Direction side : PLACEMENT_SIDE_ORDER) {
            BlockPos neighborPos = blockPos.relative(side);
            if (PrinterUtils.canBeClicked(this.level, neighborPos)
                    && !BlockUtils.isReplaceable(this.level.getBlockState(neighborPos))) {
                return side;
            }
        }
        return null;
    }

    private boolean isTargetFluid(BlockPos blockPos) {
        return this.level != null && this.isTargetFluid(this.level.getBlockState(blockPos).getFluidState());
    }

    private boolean isReadyFluidTarget(BlockPos blockPos) {
        if (this.level == null) {
            return false;
        }
        // Only target cells the fill block can actually replace. Waterlogged non-replaceable
        // blocks (kelp, seagrass, plants) still report a source fluid state, but a solid block
        // cannot be placed into them: BlockPlaceContext.canPlace() fails on replaceClicked, so
        // every scan would emit them and every attempt would be INTERACTION_REJECTED. Excluding
        // them both avoids wasted rejected traffic and lets the scan actually complete so the
        // module can settle into lazy scanning once all real water is filled.
        if (!BlockUtils.isReplaceable(this.level.getBlockState(blockPos))) {
            return false;
        }
        return this.isTargetFluid(blockPos)
                && (Configs.Print.PLACE_IN_AIR.getBooleanValue() || this.findPlacementSide(blockPos) != null);
    }

    private boolean isTargetFluid(FluidState fluidState) {
        return fluids.contains(fluidState.getType())
                && (Configs.Fluid.FILL_FLOWING_FLUID.getBooleanValue() || fluidState.isSource());
    }

    private int getScanConfigHash() {
        int result = this.fillBlocks.hashCode();
        result = 31 * result + this.fluidBlocks.hashCode();
        result = 31 * result + Boolean.hashCode(Configs.Fluid.FILL_FLOWING_FLUID.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Print.PLACE_IN_AIR.getBooleanValue());
        return result;
    }
}
