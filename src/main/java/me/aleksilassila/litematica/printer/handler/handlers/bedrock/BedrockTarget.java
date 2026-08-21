package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

public class BedrockTarget implements BedrockTargetStatusResolver.Host, BedrockTargetActionExecutor.Host {
    public enum Status {
        FAILED,
        UNINITIALIZED,
        UNEXTENDED_WITH_POWER_SOURCE,
        UNEXTENDED_WITHOUT_POWER_SOURCE,
        EXTENDED,
        NEEDS_WAITING,
        RETRACTING,
        RETRACTED,
        STUCK
    }

    private final ClientLevel level;
    private final BedrockMachineLayout layout;
    private final BlockPos bedrockPos;
    private final BlockPos pistonPos;
    private final BlockPos headPos;
    private final boolean conservativeSync;
    private final BedrockTargetResidue residue;
    private final BedrockTargetFootprint footprint;
    private final BedrockTargetStatusResolver statusResolver;
    private final BedrockTargetMachine machine;
    private final BedrockTargetActionExecutor actionExecutor;
    private int tickTimes;
    private boolean hasTried;
    private int stuckTicksCounter;
    private int executeTick = -1;
    private int initializeTick = -1;
    private boolean throughputConsumedThisTick;
    private Status status = Status.UNINITIALIZED;

    public BedrockTarget(BlockPos bedrockPos, ClientLevel level) {
        this(bedrockPos, level, null, null, null);
    }

    public BedrockTarget(BlockPos bedrockPos, ClientLevel level, BedrockMachineLayout precomputedLayout, BedrockTorchPlacement precomputedPlacement, BlockPos precomputedSlimePos) {
        this.bedrockPos = bedrockPos;
        this.level = level;
        this.layout = precomputedLayout != null ? precomputedLayout : BedrockMachineLayout.find(level, bedrockPos);
        if (this.layout == null) {
            this.pistonPos = bedrockPos.above();
            this.headPos = this.pistonPos.above();
            this.residue = new BedrockTargetResidue(level, null, this.pistonPos, this.headPos);
            this.footprint = new BedrockTargetFootprint(level, bedrockPos, this.pistonPos, this.headPos);
            this.machine = new BedrockTargetMachine(
                    level, null, bedrockPos, this.pistonPos, this.headPos,
                    this.footprint, precomputedPlacement, precomputedSlimePos);
            this.statusResolver = new BedrockTargetStatusResolver(this);
            this.actionExecutor = new BedrockTargetActionExecutor(this);
            this.status = Status.FAILED;
            this.conservativeSync = BedrockTargetBlocks.requiresConservativeSync(level.getBlockState(bedrockPos));
            return;
        }
        this.pistonPos = this.layout.getPistonPos();
        this.headPos = this.layout.getHeadPos();
        this.residue = new BedrockTargetResidue(level, this.layout, this.pistonPos, this.headPos);
        this.footprint = new BedrockTargetFootprint(level, bedrockPos, this.pistonPos, this.headPos);
        this.machine = new BedrockTargetMachine(
                level, this.layout, bedrockPos, this.pistonPos, this.headPos,
                this.footprint, precomputedPlacement, precomputedSlimePos);
        this.statusResolver = new BedrockTargetStatusResolver(this);
        this.actionExecutor = new BedrockTargetActionExecutor(this);
        this.conservativeSync = BedrockTargetBlocks.requiresConservativeSync(level.getBlockState(bedrockPos));
        if (!this.machine.isValid()) {
            this.status = Status.FAILED;
        }
    }

    public BlockPos getBedrockPos() {
        return bedrockPos;
    }

    public BlockPos getPistonPos() {
        return pistonPos;
    }

    public BlockPos getHeadPos() {
        return headPos;
    }

    public BlockPos getTorchSupportPos() {
        return this.machine.torchSupportPos();
    }

    @Override
    public BlockPos torchSupportPos() {
        return getTorchSupportPos();
    }

    public BlockPos getTorchPos() {
        return this.machine.torchPos();
    }

    public BlockPos getSlimePos() {
        return this.machine.slimePos();
    }

    public Status getStatus() {
        return status;
    }

    public boolean isHorizontalLayout() {
        return this.layout != null && this.layout.isHorizontal();
    }

    public Status tick() {
        return this.tick(true, true);
    }

    public Status tick(boolean allowExecute) {
        return this.tick(allowExecute, true);
    }

    public Status tick(boolean allowExecute, boolean allowInitialize) {
        this.throughputConsumedThisTick = false;

        if (this.status != Status.UNINITIALIZED && this.status != Status.EXTENDED) {
            this.tickTimes++;
        } else if (this.status == Status.EXTENDED && allowExecute) {
            this.tickTimes++;
        }

        updateStatus();
        this.actionExecutor.execute(allowExecute, allowInitialize);
        return this.status;
    }

    public Status refreshStatusOnly() {
        this.status = observeStatus();
        return this.status;
    }

    public Status refreshStatusOnlyAndAdvance() {
        this.tickTimes++;
        updateStatus();
        return this.status;
    }

    public boolean consumedThroughputThisTick() {
        return this.throughputConsumedThisTick;
    }

    public Set<BlockPos> getCleanupPositions() {
        return this.footprint.cleanupPositions(
                this.residue, getTorchSupportPos(), getTorchPos(), getSlimePos());
    }

    public Set<BlockPos> getStructuralPositions() {
        return this.footprint.structuralPositions(
                getTorchSupportPos(), getTorchPos(), getSlimePos());
    }

    public Set<BlockPos> getPowerReservationPositions() {
        return this.footprint.powerReservationPositions(
                getTorchSupportPos(), getTorchPos(), getSlimePos());
    }

    public Set<BlockPos> getReservedPositions() {
        return this.footprint.reservedPositions(
                getTorchSupportPos(), getTorchPos(), getSlimePos());
    }

    public boolean sharesTorchPlacementWith(BedrockTarget other) {
        return other != null && matchesTorchPlacement(other.machine.torchPlacement());
    }

    public boolean matchesTorchPlacement(BedrockTorchPlacement placement) {
        return this.machine.matchesTorchPlacement(placement);
    }

    public boolean isTorchPoweredBy(BlockPos torchPos) {
        return torchPos != null && BedrockEnvironment.getTorchInfluencePositions(this.pistonPos).contains(torchPos);
    }

    public boolean canReusePowerReservation(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        return this.machine.canReusePowerReservation(pos, state);
    }

    public boolean canReusePendingCleanupPosition(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        if (pos == null || state == null || state.isAir()) {
            return false;
        }
        if (pos.equals(this.pistonPos)) {
            return this.machine.isReusablePistonState(state);
        }
        return canReusePowerReservation(pos, state);
    }

    public Set<BlockPos> getStaticMachinePositions() {
        return this.footprint.staticMachinePositions(
                getTorchSupportPos(), getTorchPos(), getSlimePos());
    }

    public Set<BlockPos> getMachineFootprint() {
        return this.footprint.machineFootprint(
                getTorchSupportPos(), getTorchPos(), getSlimePos());
    }

    public Set<BlockPos> getOwnedTorchPositions() {
        return this.footprint.ownedTorchPositions(getTorchPos());
    }

    @Override
    public void recordTemporary(BlockPos pos) {
        this.footprint.recordTemporary(pos);
    }

    @Override
    public boolean canBuildInitialMachine() {
        return this.machine.canBuildInitialMachine();
    }

    @Override
    public boolean hasOwnedTorchPowerSource() {
        return this.machine.hasOwnedTorchPowerSource();
    }

    @Override
    public boolean tryRepowerTorch() {
        return this.machine.tryRepowerTorch(this.tickTimes, this::markThroughputAction);
    }

    private void updateStatus() {
        if (isTargetCompleted()) {
            this.status = Status.RETRACTED;
            return;
        }

        if (!this.ensureTorchPlacement()) {
            this.status = Status.FAILED;
            BedrockMessages.actionBar("bedrockminer.fail.place.redstonetorch");
            return;
        }

        this.status = this.statusResolver.resolve(true);
    }

    private boolean ensureTorchPlacement() {
        return this.machine.ensureTorchPlacement(
                this::isPlacementReservedByOtherTarget,
                this::markThroughputAction
        );
    }

    private Status observeStatus() {
        return this.statusResolver.resolve(false);
    }
    private boolean isPlacementReservedByOtherTarget(BedrockTorchPlacement placement) {
        if (placement == null) {
            return false;
        }
        return BedrockController.isTorchPlacementReservedByOtherTarget(placement, this);
    }

    @Override
    public void resetPostExecuteAttempt(Status recoveryStatus) {
        clearPostExecuteAttemptState();
    }

    private void clearPostExecuteAttemptState() {
        this.tickTimes = 0;
        this.hasTried = false;
        this.stuckTicksCounter = 0;
        this.machine.resetAttempt();
        this.executeTick = -1;
        this.initializeTick = -1;
        this.residue.resetAttempt();
    }

    @Override
    public boolean hasMachineCleanupResidue() {
        return this.residue.hasMachineCleanupResidue(getSlimePos(), getTorchPos());
    }

    @Override
    public boolean hasPostExecuteSyncResidue() {
        return this.residue.hasPostExecuteSyncResidue();
    }

    private boolean isPostExecuteCollapsed() {
        return this.residue.isPostExecuteCollapsed(this.hasTried);
    }

    @Override
    public boolean hasTransientMachineResidue() {
        return this.residue.hasTransientMachineResidue();
    }

    @Override
    public boolean hasAnyTransientMachineResidue() {
        return this.residue.hasAnyTransientMachineResidue(getTorchSupportPos(), getSlimePos());
    }

    @Override
    public boolean hasStablePostExecuteResidue() {
        return this.residue.hasStablePostExecuteResidue();
    }

    @Override
    public void cleanupStablePostExecuteResidue() {
        if (this.residue.cleanupStablePostExecuteResidue(this.tickTimes)) {
            markThroughputAction();
        }
    }

    private boolean hasCleanupResidue(BlockPos pos) {
        return this.residue.hasCleanupResidue(pos);
    }

    @Override
    public boolean hasPollutedMachineState() {
        return this.residue.hasPollutedMachineState(
                this.hasTried,
                this.executeTick,
                this.tickTimes,
                this.machine.torchPlacement(),
                getTorchSupportPos(),
                getSlimePos()
        );
    }

    @Override
    public void cleanupPollutedMachineState() {
        if (this.residue.cleanupPollutedMachineState(
                this.tickTimes,
                this.hasTried,
                this.executeTick,
                this.machine.torchPlacement(),
                getTorchSupportPos(),
                getSlimePos()
        )) {
            markThroughputAction();
        }
    }

    private boolean isTargetCompleted() {
        return !BedrockTargetBlocks.isTargetBlock(this.level.getBlockState(this.bedrockPos));
    }

    @Override
    public ClientLevel level() {
        return this.level;
    }

    @Override
    public BedrockMachineLayout layout() {
        return this.layout;
    }

    @Override
    public BlockPos bedrockPos() {
        return this.bedrockPos;
    }

    @Override
    public BlockPos pistonPos() {
        return this.pistonPos;
    }

    @Override
    public BlockPos headPos() {
        return this.headPos;
    }

    @Override
    public boolean hasTried() {
        return this.hasTried;
    }

    @Override
    public int executeTick() {
        return this.executeTick;
    }

    @Override
    public int initializeTick() {
        return this.initializeTick;
    }

    @Override
    public int tickTimes() {
        return this.tickTimes;
    }

    @Override
    public int stuckTicks() {
        return this.stuckTicksCounter;
    }

    @Override
    public Status currentStatus() {
        return this.status;
    }

    @Override
    public void setStatus(Status status) {
        this.status = status;
    }

    @Override
    public boolean placeInitialTorch() {
        return this.machine.placeTorch();
    }

    @Override
    public void setHasTried(boolean value) {
        this.hasTried = value;
    }

    @Override
    public void setInitializeTick(int value) {
        this.initializeTick = value;
    }

    @Override
    public void setExecuteTick(int value) {
        this.executeTick = value;
    }

    @Override
    public Set<BlockPos> ownedTorchPositions() {
        return this.machine.ownedTorchPositions();
    }

    @Override
    public boolean conservativeSync() {
        return this.conservativeSync;
    }

    @Override
    public boolean canRepowerNow() {
        return this.machine.canRepowerNow(this.tickTimes);
    }

    @Override
    public boolean rebuildLimitReached() {
        return this.machine.rebuildLimitReached();
    }

    @Override
    public void recordRebuild() {
        this.machine.recordRebuild(this.tickTimes);
    }

    @Override
    public void incrementStuckTicks() {
        this.stuckTicksCounter++;
    }

    @Override
    public void markThroughputAction() {
        this.throughputConsumedThisTick = true;
    }

}
