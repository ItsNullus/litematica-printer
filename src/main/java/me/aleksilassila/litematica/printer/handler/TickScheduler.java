package me.aleksilassila.litematica.printer.handler;

import com.google.common.collect.ImmutableList;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.handler.handlers.GuiHandler;
import me.aleksilassila.litematica.printer.handler.handlers.MineDebugLog;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge;
import me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils;
import net.minecraft.client.Minecraft;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;


final class TickScheduler implements RuntimeComponent {
    private static final Minecraft MC = Minecraft.getInstance();

    private final ImmutableList<FeatureModuleBase> modules;
    private int packetTick;
    private String lastPauseReason;
    private boolean runtimeActive;
    private int executionScopeHash = Integer.MIN_VALUE;
    private int roundRobinOffset;

    TickScheduler(ImmutableList<FeatureModuleBase> modules) {
        this.modules = modules;
        PrinterRuntime.get().register(this);
    }

    void tick() {
        InventoryAvailabilityTracker.INSTANCE.tick(MC.player);
        HudStatsManager.INSTANCE.tick();
        MissingMaterialTracker.INSTANCE.tick(
                MC.player,
                MC.level != null ? MC.level.getGameTime() : 0L
        );
        if (!Configs.Core.WORK_SWITCH.getBooleanValue()) {
            if (this.runtimeActive) {
                ClientPlayerTickManager.resetRuntime("work_switch_disabled");
            }
            this.runtimeActive = false;
            HudStatsManager.INSTANCE.resetAll();
            this.lastPauseReason = null;
            return;
        }
        int currentScopeHash = this.currentExecutionScopeHash();
        if (!this.runtimeActive) {
            this.runtimeActive = true;
            this.executionScopeHash = currentScopeHash;
        } else if (this.executionScopeHash != currentScopeHash) {
            ClientPlayerTickManager.resetRuntime("execution_scope_changed");
            this.runtimeActive = true;
            this.executionScopeHash = currentScopeHash;
            return;
        }
        boolean inventoryBusy = this.pauseForInventoryState("shared_precheck");
        if (this.pauseForPendingLookQueue()) {
            ActionBroker.INSTANCE.sendQueue(MC.player);
            return;
        }
        if (this.pauseForLagCheck()) {
            return;
        }
        TickContext context = TickContext.capture();
        if (!inventoryBusy) {
            this.resume();
        }
        for (FeatureModuleBase handler : this.modules) {
            if (handler instanceof GuiHandler) {
                handler.tick(context);
            }
        }
        int actionableCount = Math.max(0, this.modules.size() - 1);
        if (actionableCount == 0) {
            return;
        }
        int startIndex = this.roundRobinOffset % actionableCount;
        this.roundRobinOffset = (this.roundRobinOffset + 1) % actionableCount;
        for (int offset = 0; offset < actionableCount; offset++) {
            FeatureModuleBase handler = this.modules.get(1 + (startIndex + offset) % actionableCount);
            if (this.pauseForHandlerPrecheck(handler)) {
                return;
            }
            handler.tick(context);
        }
    }

    int getPacketTick() {
        return this.packetTick;
    }

    void setPacketTick(int packetTick) {
        this.packetTick = packetTick;
    }

    void recordInboundPacket() {
        this.packetTick = 0;
    }

    void resetRuntime() {
        this.packetTick = 0;
        this.lastPauseReason = null;
        this.runtimeActive = false;
        this.executionScopeHash = Integer.MIN_VALUE;
        this.roundRobinOffset = 0;
    }

    @Override
    public void onEpochChanged(RuntimeEvent.EpochChanged event) {
        this.resetRuntime();
    }

    String getLastPauseReason() {
        return this.lastPauseReason;
    }

    private void pause(String reason) {
        if (!reason.equals(this.lastPauseReason)) {
            MineDebugLog.write("scheduler pause reason=" + reason + " packetTick=" + this.packetTick);
            this.lastPauseReason = reason;
        }
    }

    private void resume() {
        if (this.lastPauseReason != null) {
            MineDebugLog.write("scheduler resume after=" + this.lastPauseReason + " packetTick=" + this.packetTick);
            this.lastPauseReason = null;
        }
    }

    private boolean pauseForInventoryState(String reasonPrefix) {
        boolean switchingItem = QuickShulkerBridge.switchItem();
        boolean pendingSwitch = QuickShulkerBridge.hasPendingRequest();
        boolean takeItOutPending = TakeItOutUtils.isAwaitingStack();
        boolean inventorySwitchPending = InventorySwitchGuard.isWaiting();
        boolean openHandler = QuickShulkerBridge.isOpenHandler();
        if (pendingSwitch || switchingItem || takeItOutPending || inventorySwitchPending) {
            ActionBroker.INSTANCE.cancelQueue();
            this.pause(reasonPrefix + " openHandler=" + openHandler + " pendingSwitch=" + pendingSwitch + " switchingItem=" + switchingItem + " takeItOutPending=" + takeItOutPending + " inventorySwitchPending=" + inventorySwitchPending);
            return true;
        }
        return false;
    }

    private boolean pauseForPendingLookQueue() {
        if (!ActionBroker.INSTANCE.isWaitingForLook()) {
            return false;
        }
        this.pause("send_queue_wait_modify_look");
        return true;
    }

    private boolean pauseForLagCheck() {
        if (!Configs.Core.LAG_CHECK.getBooleanValue()) {
            return false;
        }
        if (this.packetTick > Configs.Core.LAG_CHECK_MAX.getIntegerValue()) {
            this.pause("lag_check packetTick=" + this.packetTick + " max=" + Configs.Core.LAG_CHECK_MAX.getIntegerValue());
            return true;
        }
        this.packetTick++;
        return false;
    }

    private boolean pauseForHandlerPrecheck(FeatureModuleBase handler) {
        if (ActionBroker.INSTANCE.isWaitingForLook()) {
            this.pause("action_wait_modify_look handler=" + handler.getId());
            return true;
        }
        return false;
    }

    private int currentExecutionScopeHash() {
        int result = Configs.Core.WORK_MODE.getOptionListValue().hashCode();
        result = 31 * result + Configs.Core.WORK_MODE_TYPE.getOptionListValue().hashCode();
        result = 31 * result + Boolean.hashCode(Configs.Core.PRINT.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Core.MINE.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Core.FILL.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Core.FLUID.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Hotkeys.BEDROCK.getBooleanValue());
        return result;
    }
}
