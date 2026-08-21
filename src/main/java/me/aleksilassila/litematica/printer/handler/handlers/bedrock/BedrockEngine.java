package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashSet;
import java.util.Set;

/** Runtime-owned orchestration for bedrock admission, execution and cleanup. */
public final class BedrockEngine implements RuntimeComponent {
    private final Minecraft client;
    private final BedrockTargetRegistry targets = new BedrockTargetRegistry();
    private final BedrockCleanupCoordinator cleanup;
    private final BedrockRunStats stats = new BedrockRunStats();
    private final BedrockAdmissionController admission;
    private final BedrockTargetExecutor targetExecutor;
    private final BedrockThroughputScheduler throughputScheduler = new BedrockThroughputScheduler();
    private long lastProcessedTick = Long.MIN_VALUE;

    public BedrockEngine(Minecraft client) {
        this.client = client;
        this.cleanup = new BedrockCleanupCoordinator(client);
        this.admission = new BedrockAdmissionController(client, this.targets, this.cleanup, this.stats);
        this.targetExecutor = new BedrockTargetExecutor(
                this.targets,
                this.cleanup,
                this.stats,
                this.admission::setRetryCooldown
        );
    }

    @Override
    public void onEpochChanged(RuntimeEvent.EpochChanged event) {
        this.reset();
    }

    public void reset() {
        this.targets.clear();
        this.cleanup.reset();
        this.admission.reset();
        this.stats.reset();
        BedrockPlacer.clearHorizontalLookState();
        BedrockCriticalExecutor.reset();
        this.throughputScheduler.reset();
        this.lastProcessedTick = Long.MIN_VALUE;
        HudStatsManager.INSTANCE.resetMode(HudStatsManager.Mode.BEDROCK);
    }

    public void clearHorizontalLookState() {
        BedrockPlacer.clearHorizontalLookState();
    }

    public void tick() {
        ClientLevel level = this.client.level;
        if (level == null) {
            this.reset();
            return;
        }
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        if (now == this.lastProcessedTick) {
            return;
        }
        this.lastProcessedTick = now;
        this.stats.beginTick();
        this.cleanup.beginTick();
        BedrockCriticalExecutor.beginTick(now);
        this.admission.purgeTargetsOutsideSelection();
        this.admission.beginTick(level);

        BedrockThroughputScheduler.Allocation allocation = this.throughputScheduler.allocate(
                this.admission.configuredThroughput(),
                this.admission.configuredInterval()
        );
        Set<BedrockTarget> processedTargets = new LinkedHashSet<>();
        BedrockTarget sideLookTarget = this.targets.findSideLookTarget();
        int unusedFastLaneBudget = this.targetExecutor.process(
                level, allocation.critical(), true, processedTargets, sideLookTarget);
        int preparationBudget = allocation.preparation() + unusedFastLaneBudget;
        int unusedBudget = this.targetExecutor.process(
                level, preparationBudget, false, processedTargets, sideLookTarget);
        if (unusedBudget > 0) {
            unusedBudget = this.targetExecutor.process(
                    level, unusedBudget, true, processedTargets, sideLookTarget);
        }
        this.throughputScheduler.consume(allocation, unusedBudget);
        this.cleanup.process(this.targets::isReserved, this.admission.cleanupLimitPerTick());
        this.admission.refreshCleanupPressure(level);
    }

    public boolean hasActiveWork() {
        return !this.targets.isEmpty() || !this.cleanup.isEmpty();
    }

    public boolean hasPendingScanWork() {
        return this.admission.hasPendingScanWork(ClientPlayerTickManager.getCurrentHandlerTime());
    }

    public int getPendingScanWorkCount() {
        return this.admission.pendingScanWorkCount(ClientPlayerTickManager.getCurrentHandlerTime());
    }

    public boolean canScanForTargets() {
        return this.admission.canScanForTargets();
    }

    public boolean canAccept(BlockPos pos) {
        return this.admission.canAccept(pos);
    }

    public boolean isPositionOnRetryCooldown(BlockPos pos) {
        return this.admission.isPositionOnRetryCooldown(pos);
    }

    public int getSchedulingPenalty(BlockPos pos) {
        return this.admission.schedulingPenalty(pos);
    }

    public int getPredictedMachineOverlapPenalty(
            BlockPos bedrockPos,
            BedrockMachineLayout layout,
            BedrockTorchPlacement placement
    ) {
        return this.admission.predictedMachineOverlapPenalty(bedrockPos, layout, placement);
    }

    public boolean shouldSkipSchedulingHotspot(BlockPos pos) {
        return this.admission.shouldSkipSchedulingHotspot(pos);
    }

    public boolean submit(BlockPos pos) {
        return this.admission.submit(pos);
    }

    boolean isPositionReservedByOtherTarget(BlockPos pos, BedrockTarget self) {
        return this.targets.isReservedByOther(pos, self);
    }

    boolean isTorchPlacementReservedByOtherTarget(
            BedrockTorchPlacement placement,
            BedrockTarget self
    ) {
        return this.targets.isTorchPlacementReservedByOther(placement, self);
    }

    public void clearSubmissionPlans() {
        this.admission.clearSubmissionPlans();
    }

    public void primeSubmissionPlan(
            BlockPos bedrockPos,
            BedrockMachineLayout layout,
            BedrockTorchPlacement placement,
            BlockPos slimePos
    ) {
        this.admission.primeSubmissionPlan(bedrockPos, layout, placement, slimePos);
    }

    public HudSnapshot getHudSnapshot() {
        return new HudSnapshot(
                this.targets.size(),
                this.targets.countActive(),
                this.targets.countVerticalActive(),
                this.targets.countSide(),
                this.cleanup.size(),
                this.admission.cleanupPressure(),
                this.admission.blockedCleanupDemand(),
                this.stats.confirmedSuccesses,
                this.stats.submittedTargets,
                this.stats.failedTargets,
                this.stats.stuckTargets,
                this.stats.acceptedThisTick,
                this.stats.rejectedThisTick,
                this.admission.configuredThroughput(),
                this.admission.submitCap(),
                this.admission.activeCap(),
                this.admission.verticalActiveCap(),
                this.admission.sideCap(),
                this.stats.successRate(),
                this.stats.lastReason
        );
    }

    public record HudSnapshot(
            int totalTargets,
            int activeTargets,
            int verticalActiveTargets,
            int sideTargets,
            int cleanupQueueSize,
            int cleanupPressure,
            int blockedCleanupDemand,
            int confirmedSuccesses,
            int submittedTargets,
            int failedTargets,
            int stuckTargets,
            int acceptedThisTick,
            int rejectedThisTick,
            int configuredThroughput,
            int submitCap,
            int activeCap,
            int verticalActiveCap,
            int sideCap,
            double successRate,
            String lastReason
    ) {
    }
}

