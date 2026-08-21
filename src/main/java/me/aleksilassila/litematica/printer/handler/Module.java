package me.aleksilassila.litematica.printer.handler;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import lombok.Getter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.*;
import me.aleksilassila.litematica.printer.handler.scan.BoxRegionDiff;
import me.aleksilassila.litematica.printer.handler.scan.DirtyRegionTracker;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.handler.scan.ScanLifecycle;
import me.aleksilassila.litematica.printer.printer.*;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.utils.mods.LitematicaUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

public abstract class Module extends ConfigUtils {
    private static final int ITERATION_BUDGET_CHECK_INTERVAL = 8;

    @Getter
    @Nullable
    public final AtomicReference<PrinterBox> playerInteractionBox;
    @Nullable
    private final AtomicReference<PrinterBox> externalScanBoxRef;
    private final InteractionBoxTracker interactionBoxTracker;
    private final GuiBlockInfoBuffer guiBlockInfoBuffer = new GuiBlockInfoBuffer();
    private final ScanLifecycle scanLifecycle = new ScanLifecycle();
    @Getter
    private final String id;
    @Getter
    @Nullable
    private final PrintModeType printMode;
    @Getter
    @Nullable
    private final ConfigBoolean enableConfig;
    @Getter
    @Nullable
    private final ConfigOptionList selectionType;
    private final AtomicReference<Boolean> skipIteration = new AtomicReference<>(false);
    private boolean iterationConsumedEffectiveExecution = true;
    @Getter
    private int pendingDirtyRegionCount;
    @Nullable
    private PrinterBox lastScanSourceBox;
    private List<PrinterBox> lastScanSourceBoxes = List.of();
    @Nullable
    private BlockPos lastScanCenter;
    private long lastDirtyVersion;
    private final ArrayDeque<PrinterBox> dirtyScanQueue = new ArrayDeque<>();
    @Nullable
    private PrinterBox activeDirtyScanBox;
    private boolean currentIterationDidWork;
    private boolean currentIterationFoundCandidate;
    private boolean currentIterationCompletedPass;
    private boolean inventoryRevisionInitialized;
    private long lastInventoryGainRevision;
    private boolean schematicIdentityInitialized;
    @Nullable
    private Object lastSchematicIdentity;
    @Nullable
    private PrinterBox cachedScanSourceInput;
    private List<PrinterBox> cachedScanSourceBoxes = List.of();

    protected Minecraft mc;
    protected ClientLevel level;
    protected LocalPlayer player;
    protected ClientPacketListener connection;
    protected MultiPlayerGameMode gameMode;
    protected GameType gameType;
    @Nullable
    protected HitResult hitResult;
    @Nullable
    protected BlockHitResult blockHitResult;

    private long lastTickTime = -1L;

    public ScanState getScanState() {
        return this.scanLifecycle.state();
    }

    protected Module(String id, @Nullable PrintModeType printMode, @Nullable ConfigBoolean enableConfig, @Nullable ConfigOptionList selectionType, boolean useBox) {
        this.id = id;
        this.printMode = printMode;
        this.enableConfig = enableConfig;
        this.selectionType = selectionType;
        this.interactionBoxTracker = new InteractionBoxTracker(useBox);
        this.playerInteractionBox = this.interactionBoxTracker.getBoxReference();
        this.externalScanBoxRef = this.playerInteractionBox == null ? null : new AtomicReference<>();
        this.updateVariables();
    }

    @Nullable
    public AtomicReference<PrinterBox> getBoxRef() {
        return this.externalScanBoxRef;
    }

    protected void updateVariables() {
        this.updateVariables(TickContext.capture());
    }

    public final void resetRuntimeState() {
        this.updateVariables();
        this.resetScanRuntime();
        this.resetPlayerTracking();
        this.guiBlockInfoBuffer.resetForTracking(false);
        this.skipIteration.set(false);
        this.iterationConsumedEffectiveExecution = true;
        this.currentIterationDidWork = false;
        this.currentIterationFoundCandidate = false;
        this.currentIterationCompletedPass = false;
        this.scanLifecycle.reset();
        this.inventoryRevisionInitialized = false;
        this.lastInventoryGainRevision = 0L;
        this.schematicIdentityInitialized = false;
        this.lastSchematicIdentity = null;
        this.clearScanSourceCache();
        this.lastTickTime = -1L;
        this.onRuntimeReset();
    }

    public void tick() {
        this.tick(TickContext.capture());
    }

    public void tick(TickContext context) {
        this.guiBlockInfoBuffer.tickCache();
        if (this.shouldSkipByTickInterval(context)) {
            return;
        }
        if (!isEnable()) {
            this.resetScanRuntime();
            this.resetPlayerTracking();
            return;
        }
        this.updateVariables(context);
        this.clearScanSourceCache();
        if (!this.hasRequiredClientState()) {
            this.resetScanRuntime();
            this.resetPlayerTracking();
            return;
        }
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        ScanEngine.INSTANCE.beginTick(this.level, schematic, context.gameTime);
        this.wakeForSchematicChange(schematic);
        this.updatePlayerInteractionBox();
        this.preprocess(); // 运行前处理的事情
        this.wakeForInventoryChange();
        if (!this.isConfigAllowExecute()) {
            this.resetScanRuntime();
            this.resetPlayerTracking();
            return;
        }
        boolean interrupt = this.runIterationIfNeeded();
        if (!interrupt) {
            this.resetPlayerTracking();
        }
    }

    protected void updateVariables(TickContext context) {
        this.mc = context.mc;
        this.level = context.level;
        this.player = context.player;
        this.connection = context.connection;
        this.gameMode = context.gameMode;
        this.gameType = context.gameType;
        this.hitResult = context.hitResult;
        this.blockHitResult = context.blockHitResult;
    }

    private boolean shouldSkipByTickInterval(TickContext context) {
        int tickInterval = this.getTickInterval();
        if (tickInterval <= 0) {
            return false;
        }
        long currentTickTime = context.gameTime;
        if (this.lastTickTime != -1L && currentTickTime - this.lastTickTime < tickInterval) {
            return true;
        }
        this.lastTickTime = currentTickTime;
        return false;
    }

    private boolean hasRequiredClientState() {
        return this.mc != null
                && this.level != null
                && this.player != null
                && this.connection != null
                && this.gameMode != null
                && this.gameType != null;
    }

    private void resetPlayerTracking() {
        this.interactionBoxTracker.resetPlayerTracking();
    }

    private void updatePlayerInteractionBox() {
        this.interactionBoxTracker.update(this.player);
    }

    private void wakeForInventoryChange() {
        long revision = InventoryAvailabilityTracker.INSTANCE.gainRevision();
        if (!this.inventoryRevisionInitialized) {
            this.inventoryRevisionInitialized = true;
            this.lastInventoryGainRevision = revision;
            return;
        }
        if (this.lastInventoryGainRevision == revision) {
            return;
        }
        this.lastInventoryGainRevision = revision;
        ScanEngine.INSTANCE.resetOwner(this.id);
        this.requestFullScan();
    }

    private void wakeForSchematicChange(@Nullable WorldSchematic schematic) {
        if (!this.schematicIdentityInitialized) {
            this.schematicIdentityInitialized = true;
            this.lastSchematicIdentity = schematic;
            return;
        }
        if (this.lastSchematicIdentity == schematic) {
            return;
        }
        this.lastSchematicIdentity = schematic;
        if (this.isSchematicBlockHandler()) {
            this.requestFullScan();
        }
    }

    private boolean runIterationIfNeeded() {
        if (this.playerInteractionBox == null || !this.canExecute()) {
            this.updateExternalScanBox(null);
            return false;
        }
        PrinterBox playerInteractionBox = this.playerInteractionBox.get();
        if (playerInteractionBox == null || !this.canIterate()) {
            this.updateExternalScanBox(null);
            return false;
        }
        this.wakeForScanCenterChange();
        List<PrinterBox> scanSourceBoxes = this.getScanSourceBoxes(playerInteractionBox);
        PrinterBox scanSourceBox = enclosingBox(scanSourceBoxes);
        if (scanSourceBox == null) {
            this.updateExternalScanBox(null);
            this.lastScanSourceBox = null;
            this.lastScanSourceBoxes = List.of();
            return false;
        }
        this.updateExternalScanBox(scanSourceBox);
        this.updateScanSource(scanSourceBox, scanSourceBoxes);
        if (!this.isLazyScanEnabled()) {
            this.scanLifecycle.setState(ScanState.FULL);
            this.scanLifecycle.idlePolicy().clearCompletedPassEvidence();
            this.clearDirtyScanQueue();
            return this.runFullIteration(playerInteractionBox);
        }
        return switch (this.scanLifecycle.state()) {
            case FULL -> this.runFullIteration(playerInteractionBox);
            case PARTIAL -> this.runPartialIteration(playerInteractionBox);
            case LAZY -> this.runLazyIteration(playerInteractionBox);
        };
    }

    private void updateScanSource(PrinterBox scanSourceBox, List<PrinterBox> scanSourceBoxes) {
        boolean boxesChanged = !this.lastScanSourceBoxes.equals(scanSourceBoxes);
        if (scanSourceBox.equals(this.lastScanSourceBox) && !boxesChanged) {
            return;
        }
        this.lastScanSourceBoxes = List.copyOf(scanSourceBoxes);
        if (!boxesChanged
                && this.lastScanSourceBox != null
                && this.lastScanSourceBox.sameSectionWindow(scanSourceBox)) {
            PrinterBox previousScanSourceBox = this.lastScanSourceBox;
            this.lastScanSourceBox = scanSourceBox;
            if (this.scanLifecycle.state() == ScanState.LAZY) {
                if (this.usesDirtyRegionWakeup()) {
                    this.queueNewlyExposedScanRegions(previousScanSourceBox, scanSourceBox);
                } else {
                    this.scanLifecycle.setState(ScanState.FULL);
                    this.scanLifecycle.idlePolicy().recordActivity();
                    this.clearDirtyScanQueue();
                }
            }
            return;
        }
        this.lastScanSourceBox = scanSourceBox;
        this.scanLifecycle.setState(ScanState.FULL);
        this.scanLifecycle.idlePolicy().recordActivity();
        this.lastDirtyVersion = DirtyRegionTracker.INSTANCE.currentVersion();
        this.clearDirtyScanQueue();
    }

    private boolean runFullIteration(PrinterBox playerInteractionBox) {
        boolean interrupt = this.runIterationLoop(playerInteractionBox);
        this.updateFullScanIdleState();
        return interrupt;
    }

    private boolean runLazyIteration(PrinterBox playerInteractionBox) {
        if (this.scanLifecycle.idlePolicy().shouldWakeForPendingWork(this.hasPendingIterationWork())) {
            this.scanLifecycle.setState(ScanState.FULL);
            this.clearDirtyScanQueue();
            return this.runFullIteration(playerInteractionBox);
        }
        if (this.usesDirtyRegionWakeup() && this.scanLifecycle.state() == ScanState.LAZY) {
            this.refreshDirtyScanQueue(playerInteractionBox);
        }
        if (this.scanLifecycle.state() == ScanState.LAZY) {
            int fallbackProbeInterval = Math.max(40, Configs.Core.LAZY_ENTER_TICKS.getIntegerValue() * 10);
            if (!this.scanLifecycle.idlePolicy().shouldRunLazyProbe(fallbackProbeInterval)) {
                return false;
            }
            return this.runLazyProbeIteration(playerInteractionBox);
        }
        if (this.scanLifecycle.state() == ScanState.FULL) {
            return this.runFullIteration(playerInteractionBox);
        }
        return this.runPartialIteration(playerInteractionBox);
    }

    private void updateExternalScanBox(@Nullable PrinterBox scanSourceBox) {
        if (this.externalScanBoxRef != null) {
            this.externalScanBoxRef.set(scanSourceBox);
        }
    }

    private boolean runLazyProbeIteration(PrinterBox playerInteractionBox) {
        this.pendingDirtyRegionCount = 0;
        boolean interrupt = this.runIterationLoop(playerInteractionBox);
        if (this.scanLifecycle.idlePolicy().recordLazyProbe(this.currentIterationDidWork, this.currentIterationFoundCandidate)) {
            this.scanLifecycle.setState(ScanState.FULL);
            return interrupt;
        }
        this.scanLifecycle.setState(ScanState.LAZY);
        return true;
    }

    private boolean runPartialIteration(PrinterBox playerInteractionBox) {
        if (this.activeDirtyScanBox == null) {
            if (this.dirtyScanQueue.isEmpty()) {
                this.refreshDirtyScanQueue(playerInteractionBox);
                if (this.scanLifecycle.state() == ScanState.LAZY) {
                    return false;
                }
                if (this.scanLifecycle.state() == ScanState.FULL) {
                    return this.runFullIteration(playerInteractionBox);
                }
            }
            this.activeDirtyScanBox = this.dirtyScanQueue.pollFirst();
        }

        if (this.activeDirtyScanBox == null) {
            this.scanLifecycle.setState(ScanState.LAZY);
            this.pendingDirtyRegionCount = 0;
            return false;
        }

        PrinterBox boundedDirtyBox = intersect(playerInteractionBox, this.activeDirtyScanBox);
        if (boundedDirtyBox == null || this.getScanSourceBox(boundedDirtyBox) == null) {
            this.activeDirtyScanBox = null;
            this.updatePartialScanState(false);
            return this.hasPendingPartialScan();
        }

        boolean interrupt = this.runIterationLoop(boundedDirtyBox);
        if (!interrupt) {
            this.activeDirtyScanBox = null;
        }
        this.updatePartialScanState(interrupt);
        return interrupt || this.hasPendingPartialScan();
    }

    private void refreshDirtyScanQueue(PrinterBox playerInteractionBox) {
        DirtyRegionTracker.DirtySnapshot snapshot = DirtyRegionTracker.INSTANCE.snapshotAfter(this.lastDirtyVersion, playerInteractionBox);
        this.lastDirtyVersion = snapshot.version();
        this.dirtyScanQueue.clear();
        this.activeDirtyScanBox = null;

        List<PrinterBox> dirtyBoxes = new ArrayList<>();
        for (PrinterBox dirtyBox : snapshot.boxes()) {
            PrinterBox boundedDirtyBox = intersect(playerInteractionBox, dirtyBox);
            if (boundedDirtyBox != null && this.getScanSourceBox(boundedDirtyBox) != null) {
                dirtyBoxes.add(boundedDirtyBox);
            }
        }
        dirtyBoxes.sort(Comparator.comparingDouble(this::distanceToPlayerSqr));
        this.dirtyScanQueue.addAll(dirtyBoxes);

        this.pendingDirtyRegionCount = this.dirtyScanQueue.size();
        if (this.dirtyScanQueue.isEmpty()) {
            this.scanLifecycle.setState(ScanState.LAZY);
            return;
        }
        this.scanLifecycle.setState(ScanState.PARTIAL);
    }

    private void updateFullScanIdleState() {
        int lazyThreshold = Configs.Core.LAZY_ENTER_TICKS.getIntegerValue();
        if (this.scanLifecycle.idlePolicy().recordFullIteration(
                this.currentIterationDidWork,
                this.currentIterationFoundCandidate,
                this.currentIterationCompletedPass,
                this.hasPendingIterationWork(),
                lazyThreshold
        )) {
            this.scanLifecycle.setState(ScanState.LAZY);
            this.clearDirtyScanQueue();
        }
    }

    private void updatePartialScanState(boolean interrupt) {
        this.pendingDirtyRegionCount = this.dirtyScanQueue.size() + (this.activeDirtyScanBox == null ? 0 : 1);
        if (interrupt) {
            return;
        }
        if (!this.hasPendingPartialScan()) {
            this.scanLifecycle.setState(ScanState.LAZY);
            this.scanLifecycle.idlePolicy().resetIdleAndProbe();
            this.pendingDirtyRegionCount = 0;
        }
    }

    private boolean hasPendingPartialScan() {
        return this.activeDirtyScanBox != null || !this.dirtyScanQueue.isEmpty();
    }

    private boolean isLazyScanEnabled() {
        return Configs.Core.LAZY_ENTER_TICKS.getIntegerValue() > 0;
    }

    private void clearDirtyScanQueue() {
        this.dirtyScanQueue.clear();
        this.activeDirtyScanBox = null;
        this.pendingDirtyRegionCount = 0;
    }

    private void queueNewlyExposedScanRegions(PrinterBox previous, PrinterBox current) {
        BoxRegionDiff.Result diff = BoxRegionDiff.newlyExposed(previous, current);
        if (diff.requiresFullScan()) {
            this.scanLifecycle.setState(ScanState.FULL);
            this.scanLifecycle.idlePolicy().recordActivity();
            this.clearDirtyScanQueue();
            return;
        }
        this.dirtyScanQueue.addAll(diff.boxes());

        if (!this.dirtyScanQueue.isEmpty()) {
            List<PrinterBox> sorted = new ArrayList<>(this.dirtyScanQueue);
            sorted.sort(Comparator.comparingDouble(this::distanceToPlayerSqr));
            this.dirtyScanQueue.clear();
            this.dirtyScanQueue.addAll(sorted);
            this.pendingDirtyRegionCount = this.dirtyScanQueue.size();
            this.scanLifecycle.setState(ScanState.PARTIAL);
            this.scanLifecycle.idlePolicy().resetIdle();
        }
    }

    protected final void requestFullScan() {
        this.scanLifecycle.setState(ScanState.FULL);
        this.scanLifecycle.idlePolicy().reset();
        this.clearDirtyScanQueue();
    }

    private void wakeForScanCenterChange() {
        // Compare at section (16-block) granularity, matching the scan session reuse window.
        // Rebuilding the cursor for every single block of player movement repeatedly rescanned
        // the whole reach shape from scratch; within a section the in-flight cursor is reused
        // and the spherical/octahedral reach shape shifts by at most a few blocks, which the
        // per-tick dirty tracking picks up anyway.
        int sectionX = (int) Math.floor(this.player.getX()) >> 4;
        int sectionY = (int) Math.floor(this.player.getEyeY()) >> 4;
        int sectionZ = (int) Math.floor(this.player.getZ()) >> 4;
        if (this.lastScanCenter == null) {
            this.lastScanCenter = new BlockPos(sectionX, sectionY, sectionZ);
            return;
        }
        if (this.lastScanCenter.getX() == sectionX
                && this.lastScanCenter.getY() == sectionY
                && this.lastScanCenter.getZ() == sectionZ) {
            return;
        }
        this.lastScanCenter = new BlockPos(sectionX, sectionY, sectionZ);
        if (this.scanLifecycle.state() != ScanState.FULL) {
            // The source AABB can remain unchanged while the spherical/octahedral reach shape
            // moves inside it. Wake positions that have just entered the actual reach shape.
            this.requestFullScan();
        }
    }

    private void clearScanSourceCache() {
        this.cachedScanSourceInput = null;
        this.cachedScanSourceBoxes = List.of();
    }

    private void resetScanRuntime() {
        this.scanLifecycle.setState(ScanState.FULL);
        this.scanLifecycle.idlePolicy().reset();
        this.currentIterationCompletedPass = false;
        this.lastScanSourceBox = null;
        this.lastScanSourceBoxes = List.of();
        this.lastScanCenter = null;
        this.updateExternalScanBox(null);
        this.lastDirtyVersion = DirtyRegionTracker.INSTANCE.currentVersion();
        this.clearDirtyScanQueue();
    }

    private double distanceToPlayerSqr(PrinterBox box) {
        if (this.player == null) {
            return 0.0D;
        }
        double dx = axisDistance(this.player.getX(), box.minX, box.maxX);
        double dy = axisDistance(this.player.getEyeY(), box.minY, box.maxY);
        double dz = axisDistance(this.player.getZ(), box.minZ, box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double axisDistance(double value, int min, int max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0D;
    }

    private boolean runIterationLoop(PrinterBox playerInteractionBox) {
        int maxEffectiveExec = this.getMaxEffectiveExecutionsPerTick();
        int scanGuardLimit = this.getScanGuardLimit();
        int totalIterCount = 0;
        int effectiveExecCount = 0;
        int iterationBudgetChecks = 0;
        long iterationStartNanos = System.nanoTime();
        long actionExecutionNanos = 0L;
        long iterationBudgetNanos = Math.max(1L, Configs.Core.SCAN_TIME_BUDGET_MS.getIntegerValue()) * 1_000_000L;
        boolean interrupt = false;
        boolean trackGuiBlockInfo = this.shouldTrackGuiBlockInfo();
        boolean prefilteredReachAndSelection = this.iterationPositionsPrefilterReachAndSelection();
        boolean prefilteredCooldown = this.iterationPositionsPrefilterCooldown();
        boolean exactCandidates = this.iterationPositionsAreExactCandidates();
        this.skipIteration.set(false);
        this.currentIterationDidWork = false;
        this.currentIterationFoundCandidate = false;
        this.currentIterationCompletedPass = false;
        this.guiBlockInfoBuffer.resetForTracking(trackGuiBlockInfo);

        int completedPassesBefore = ScanEngine.INSTANCE.metricsFor(this.id).completedPasses();
        Iterable<BlockPos> iterationPositions = this.getIterationPositions(playerInteractionBox);
        if (this.iterationPositionsArePrefetched()) {
            // Prefetching is already constrained by ScanCache's per-tick budget. Do not charge
            // that same time again to the consumer loop or high action limits collapse to the
            // first budget-check interval.
            iterationStartNanos = System.nanoTime();
        }
        int iterationCount = 0;
        for (BlockPos pos : iterationPositions) {
            iterationCount++;
            if (++iterationBudgetChecks % ITERATION_BUDGET_CHECK_INTERVAL == 0
                    && System.nanoTime() - iterationStartNanos - actionExecutionNanos >= iterationBudgetNanos) {
                interrupt = true;
                break;
            }
            if (scanGuardLimit > 0 && totalIterCount++ >= scanGuardLimit) {
                interrupt = true;
                break;
            }
            if (this.skipIteration.get() || ActionBroker.INSTANCE.isWaitingForLook()) {
                interrupt = true;
                break;
            }
            if (pos == null) {
                interrupt = true;
                break;
            }
            GuiBlockInfo gui = this.createGuiBlockInfo(trackGuiBlockInfo, pos);
            if (prefilteredReachAndSelection || this.canReachIterationPosition(pos)) {
                if (gui != null) {
                    gui.interacted = true;
                }
            } else {
                if (gui != null) {
                    gui.interacted = false;
                }
                this.guiBlockInfoBuffer.add(gui);
                continue;
            }
            if (isSchematicBlockHandler()) {
                if (!LitematicaUtils.isSchematicBlock(pos)) {
                    this.guiBlockInfoBuffer.add(gui);
                    continue;
                }
            }
            if (!prefilteredReachAndSelection && !this.isInSelectionRange(pos)) {
                if (gui != null) {
                    gui.posInSelectionRange = false;
                }
                this.guiBlockInfoBuffer.add(gui);
                continue;
            }
            if (gui != null) {
                gui.posInSelectionRange = true;
            }
            if (!prefilteredCooldown && isBlockPosOnCooldown(pos)) {
                // The scanner did find a valid source position; it is only deferred until its
                // action cooldown expires.  Treating it as an empty pass could enter LAZY and
                // postpone the retry until the fallback probe.
                this.currentIterationFoundCandidate = true;
                this.guiBlockInfoBuffer.add(gui);
                continue;
            }
            if (exactCandidates || this.canIterationBlockPos(pos)) {
                this.currentIterationFoundCandidate = true;
                this.iterationConsumedEffectiveExecution = true;
                long actionStartNanos = System.nanoTime();
                try {
                    this.executeIteration(pos, this.skipIteration);
                } finally {
                    if (this.iterationConsumedEffectiveExecution) {
                        actionExecutionNanos += Math.max(0L, System.nanoTime() - actionStartNanos);
                    }
                }
                if (gui != null) {
                    gui.execute = true;
                }
                boolean consumedEffectiveExecution = this.iterationConsumedEffectiveExecution;
                if (this.skipIteration.get()
                        || maxEffectiveExec > 0 && consumedEffectiveExecution && ++effectiveExecCount >= maxEffectiveExec) {
                    interrupt = true;
                }
                if (consumedEffectiveExecution) {
                    this.currentIterationDidWork = true;
                }
            }
            this.guiBlockInfoBuffer.add(gui);
            if (interrupt) {
                break;
            }
        }
        this.currentIterationCompletedPass = ScanEngine.INSTANCE.metricsFor(this.id).completedPasses()
                > completedPassesBefore;
        stopIteration(interrupt);
        return interrupt;
    }

    protected void stopIteration(boolean interrupt) {
    }

    protected void onRuntimeReset() {
    }

    protected boolean isSchematicBlockHandler() {
        return false;
    }

    protected boolean requiresSelection1ModeRangeCheck() {
        return true;
    }

    protected boolean shouldTrackGuiBlockInfo() {
        return false;
    }

    @Nullable
    private GuiBlockInfo createGuiBlockInfo(boolean enabled, BlockPos pos) {
        if (!enabled) {
            return null;
        }
        if (isSchematicBlockHandler()) {
            WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
            return new GuiBlockInfo(level, schematic, pos);
        }
        return new GuiBlockInfo(level, null, pos);
    }

    @Nullable
    public GuiBlockInfo getCurrentRenderGuiBlockInfo() {
        return this.guiBlockInfoBuffer.current();
    }

    @Nullable
    public GuiBlockInfo getGuiBlockInfo() {
        return this.guiBlockInfoBuffer.latest();
    }

    public void setGuiBlockInfo(@Nullable GuiBlockInfo guiBlockInfo) {
        this.guiBlockInfoBuffer.add(guiBlockInfo);
    }

    public int getGuiBlockInfoQueueSize() {
        return this.guiBlockInfoBuffer.size();
    }

    public int getRenderIndex() {
        return this.guiBlockInfoBuffer.renderIndex();
    }

    private boolean isConfigAllowExecute() {
        // 全局打印机功能未启用，直接禁止所有处理器执行
        if (!ConfigUtils.isEnable()) {
            return false;
        }
        // 处理器绑定了模式和配置，按当前游戏模式校验
        if (this.printMode != null && this.enableConfig != null) {
            WorkingModeType modeType = (WorkingModeType) Configs.Core.WORK_MODE.getOptionListValue();
            return switch (modeType) {
                case SINGLE -> Configs.Core.WORK_MODE_TYPE.getOptionListValue().equals(this.printMode);
                case MULTI -> this.enableConfig.getBooleanValue();
            };
        }
        // 仅绑定了启用配置，直接校验配置是否启用
        if (this.enableConfig != null) {
            return this.enableConfig.getBooleanValue();
        }
        // 无任何配置绑定，默认允许执行（由全局配置控制）
        return true;
    }

    protected int getTickInterval() {
        return -1;
    }

    protected int getMaxEffectiveExecutionsPerTick() {
        return -1;
    }

    protected int getScanGuardLimit() {
        return 0;
    }

    protected boolean iterationPositionsPrefilterReachAndSelection() {
        return false;
    }

    protected boolean iterationPositionsPrefilterCooldown() {
        return false;
    }

    protected boolean iterationPositionsAreExactCandidates() {
        return false;
    }

    protected boolean iterationPositionsArePrefetched() {
        return false;
    }

    protected Iterable<BlockPos> getIterationPositions(PrinterBox playerInteractionBox) {
        return playerInteractionBox;
    }

    protected Iterable<BlockPos> getFilteredIterationPositions(PrinterBox playerInteractionBox, Predicate<BlockPos> candidatePredicate) {
        return ScanEngine.INSTANCE.rawIterable(
                this.id + "_raw",
                playerInteractionBox,
                this.player,
                this.getScanGuardLimit(),
                candidatePredicate
        );
    }

    protected Iterable<BlockPos> getCachedFilteredIterationPositions(PrinterBox playerInteractionBox, ScanIntent intent, Predicate<BlockPos> candidatePredicate) {
        List<PrinterBox> scanSourceBoxes = this.getScanSourceBoxes(playerInteractionBox);
        if (scanSourceBoxes.isEmpty()) {
            return java.util.List.of();
        }
        Predicate<BlockPos> selectionPredicate = this.createSelectionRangePredicate();
        Predicate<BlockPos> reachPredicate = this.createScanReachPredicate();
        return ScanEngine.INSTANCE.iterable(
                this.id,
                scanSourceBoxes,
                this.level,
                SchematicWorldHandler.getSchematicWorld(),
                this.player,
                this.getScanGuardLimit(),
                intent,
                candidatePredicate,
                pos -> reachPredicate.test(pos) && selectionPredicate.test(pos)
        );
    }

    @Nullable
    protected PrinterBox getScanSourceBox(PrinterBox playerInteractionBox) {
        return enclosingBox(this.getScanSourceBoxes(playerInteractionBox));
    }

    protected List<PrinterBox> getScanSourceBoxes(PrinterBox playerInteractionBox) {
        if (playerInteractionBox == null) {
            return List.of();
        }
        if (playerInteractionBox.equals(this.cachedScanSourceInput)) {
            return this.cachedScanSourceBoxes;
        }

        List<PrinterBox> baseBoxes;
        if (isSchematicBlockHandler()) {
            baseBoxes = LitematicaUtils.createSchematicPlacementBoxes();
        } else if (requiresSelection1ModeRangeCheck()) {
            baseBoxes = LitematicaUtils.createSelection1Boxes();
        } else {
            baseBoxes = List.of(playerInteractionBox);
        }

        List<PrinterBox> result = new ArrayList<>(baseBoxes.size());
        for (PrinterBox baseBox : baseBoxes) {
            PrinterBox bounded = intersect(playerInteractionBox, baseBox);
            bounded = this.clampToConfiguredSelection(bounded);
            if (bounded != null) {
                result.add(bounded);
            }
        }
        this.cachedScanSourceInput = playerInteractionBox;
        this.cachedScanSourceBoxes = result.isEmpty() ? List.of() : List.copyOf(result);
        return this.cachedScanSourceBoxes;
    }

    @Nullable
    private PrinterBox clampToConfiguredSelection(@Nullable PrinterBox box) {
        if (box == null || this.selectionType == null) {
            return box;
        }
        if (!(this.selectionType.getOptionListValue() instanceof SelectionType selectionType)) {
            return null;
        }
        return switch (selectionType) {
            case LITEMATICA_SELECTION -> box;
            case LITEMATICA_RENDER_LAYER -> LitematicaUtils.clampToRenderLayer(box);
            case LITEMATICA_SELECTION_BELOW_PLAYER -> this.player == null
                    ? null
                    : clipMaximumY(box, (int) Math.floor(this.player.getY()));
            case LITEMATICA_SELECTION_ABOVE_PLAYER -> this.player == null
                    ? null
                    : clipMinimumY(box, (int) Math.ceil(this.player.getY()));
        };
    }

    @Nullable
    private static PrinterBox clipMaximumY(PrinterBox box, int maxY) {
        int clippedMaxY = Math.min(box.maxY, maxY);
        return clippedMaxY < box.minY
                ? null
                : new PrinterBox(box.minX, box.minY, box.minZ, box.maxX, clippedMaxY, box.maxZ);
    }

    @Nullable
    private static PrinterBox clipMinimumY(PrinterBox box, int minY) {
        int clippedMinY = Math.max(box.minY, minY);
        return clippedMinY > box.maxY
                ? null
                : new PrinterBox(box.minX, clippedMinY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    @Nullable
    private static PrinterBox intersect(PrinterBox first, PrinterBox second) {
        int minX = Math.max(first.minX, second.minX);
        int minY = Math.max(first.minY, second.minY);
        int minZ = Math.max(first.minZ, second.minZ);
        int maxX = Math.min(first.maxX, second.maxX);
        int maxY = Math.min(first.maxY, second.maxY);
        int maxZ = Math.min(first.maxZ, second.maxZ);
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return null;
        }
        return new PrinterBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Nullable
    private static PrinterBox enclosingBox(List<PrinterBox> boxes) {
        PrinterBox result = null;
        for (PrinterBox box : boxes) {
            if (result == null) {
                result = box;
            } else {
                result = new PrinterBox(
                        Math.min(result.minX, box.minX),
                        Math.min(result.minY, box.minY),
                        Math.min(result.minZ, box.minZ),
                        Math.max(result.maxX, box.maxX),
                        Math.max(result.maxY, box.maxY),
                        Math.max(result.maxZ, box.maxZ)
                );
            }
        }
        return result;
    }

    protected void preprocess() {
    }

    protected boolean canExecute() {
        return true;
    }

    protected boolean canIterate() {
        return true;
    }

    protected boolean hasPendingIterationWork() {
        return false;
    }

    public int getPendingIterationWorkCount() {
        return 0;
    }

    protected boolean usesDirtyRegionWakeup() {
        return true;
    }

    protected boolean canReachIterationPosition(BlockPos pos) {
        return ConfigUtils.canInteracted(pos);
    }

    protected Predicate<BlockPos> createScanReachPredicate() {
        return ConfigUtils.createCanInteractPredicate();
    }

    protected boolean isInSelectionRange(BlockPos pos) {
        if (!isSchematicBlockHandler()
                && requiresSelection1ModeRangeCheck()
                && !LitematicaUtils.isWithinSelection1ModeRange(pos)) {
            return false;
        }
        return selectionType == null || ConfigUtils.isPositionInSelectionRange(player, pos, selectionType);
    }

    protected Predicate<BlockPos> createSelectionRangePredicate() {
        Predicate<BlockPos> selection1Predicate = isSchematicBlockHandler() || !requiresSelection1ModeRangeCheck()
                ? pos -> true
                : LitematicaUtils.createSelection1RangePredicate();
        Predicate<BlockPos> configuredSelectionPredicate = this.createConfiguredSelectionRangePredicate();
        return pos -> selection1Predicate.test(pos) && configuredSelectionPredicate.test(pos);
    }

    private Predicate<BlockPos> createConfiguredSelectionRangePredicate() {
        if (this.selectionType == null) {
            return pos -> true;
        }
        if (!(this.selectionType.getOptionListValue() instanceof SelectionType selectionType)) {
            return pos -> false;
        }
        return switch (selectionType) {
            case LITEMATICA_SELECTION -> pos -> true;
            case LITEMATICA_RENDER_LAYER -> LitematicaUtils::isPositionWithinRange;
            case LITEMATICA_SELECTION_BELOW_PLAYER -> {
                if (this.player == null) {
                    yield pos -> false;
                }
                int playerY = (int) Math.floor(this.player.getY());
                yield pos -> pos.getY() <= playerY;
            }
            case LITEMATICA_SELECTION_ABOVE_PLAYER -> {
                if (this.player == null) {
                    yield pos -> false;
                }
                int playerY = (int) Math.ceil(this.player.getY());
                yield pos -> pos.getY() >= playerY;
            }
        };
    }

    public boolean canIterationBlockPos(BlockPos pos) {
        return true;
    }

    protected void executeIteration(BlockPos pos, AtomicReference<Boolean> skipIteration) {
    }

    protected final void setIterationConsumedEffectiveExecution(boolean consumed) {
        this.iterationConsumedEffectiveExecution = consumed;
    }

    public boolean isBlockPosOnCooldown(@Nullable BlockPos pos) {
        if (this.level == null || pos == null) return true;
        return CooldownUtils.INSTANCE.isOnCooldown(this.level, this.getId(), pos);
    }

    public boolean isBlockPosOnCooldown(String name, @Nullable BlockPos pos) {
        if (this.level == null || pos == null) return true;
        return CooldownUtils.INSTANCE.isOnCooldown(this.level, this.getId() + "_" + name, pos);
    }

    public void setBlockPosCooldown(@Nullable BlockPos pos, int cooldownTicks) {
        if (this.level == null || pos == null || cooldownTicks < 1) return;
        CooldownUtils.INSTANCE.setCooldown(this.level, this.getId(), pos, cooldownTicks);
    }

    public void setBlockPosCooldown(String name, @Nullable BlockPos pos, int cooldownTicks) {
        if (this.level == null || pos == null || cooldownTicks < 1) return;
        CooldownUtils.INSTANCE.setCooldown(this.level, this.getId() + "_" + name, pos, cooldownTicks);
    }

    protected Direction[] getPlayerOrderedByNearest() {
        return Direction.orderedByNearest(player);
    }

    protected Direction getPlayerPlacementDirection() {
        return getPlayerOrderedByNearest()[0].getOpposite();
    }
}
