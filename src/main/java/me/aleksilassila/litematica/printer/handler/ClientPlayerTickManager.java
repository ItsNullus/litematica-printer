package me.aleksilassila.litematica.printer.handler;

import com.google.common.collect.ImmutableList;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockController;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.handler.handlers.*;
import me.aleksilassila.litematica.printer.handler.runtime.RuntimeLifecycle;
import me.aleksilassila.litematica.printer.mixin_extension.MultiPlayerGameModeExtension;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.printer.RttReplayController;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils;
import me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge;
import net.minecraft.client.Minecraft;

@SuppressWarnings("SpellCheckingInspection")
public class ClientPlayerTickManager {
    public static final Minecraft mc = Minecraft.getInstance();

    public static final GuiHandler GUI = Modules.GUI;
    public static final PrintHandler PRINT = Modules.PRINT;
    public static final FillHandler FILL = Modules.FILL;
    public static final MineHandler MINE = Modules.MINE;
    public static final FluidHandler FLUID = Modules.FLUID;
    public static final BedrockHandler BEDROCK = Modules.BEDROCK;

    public static final ImmutableList<Module> VALUES = Modules.VALUES;
    private static final TickScheduler SCHEDULER = new TickScheduler(VALUES);
    private static final RuntimeLifecycle RUNTIME = createRuntimeLifecycle();

    public static void tick() {
        SCHEDULER.tick();
    }

    public static long getCurrentHandlerTime() {
        return TickContext.currentGameTime();
    }

    public static int getPacketTick() {
        return SCHEDULER.getPacketTick();
    }

    public static void setPacketTick(int packetTick) {
        SCHEDULER.setPacketTick(packetTick);
    }

    public static void recordInboundPacket() {
        SCHEDULER.recordInboundPacket();
    }

    public static void resetRuntime(String reason) {
        RUNTIME.reset(reason);
    }

    private static RuntimeLifecycle createRuntimeLifecycle() {
        RuntimeLifecycle lifecycle = new RuntimeLifecycle();
        lifecycle.register("action_manager", reason -> ActionManager.INSTANCE.resetRuntime());
        lifecycle.register("game_mode", reason -> {
            if (mc.gameMode instanceof MultiPlayerGameModeExtension extension) {
                extension.litematica_printer$resetRuntime();
            }
        });
        lifecycle.register("network_look", reason -> NetworkUtils.clearScopedLookOverride());
        lifecycle.register("rtt", reason -> RttReplayController.INSTANCE.reset());
        lifecycle.register("scan_engine", reason -> ScanEngine.INSTANCE.clear());
        lifecycle.register("cooldowns", reason -> CooldownUtils.INSTANCE.clearAllCooldowns());
        lifecycle.register("interaction", reason -> InteractionUtils.INSTANCE.resetRuntime());
        lifecycle.register("inventory_switch_guard", reason -> InventorySwitchGuard.reset());
        lifecycle.register("take_it_out", reason -> TakeItOutUtils.resetPending());
        lifecycle.register("quick_shulker", reason -> QuickShulkerBridge.resetRuntime());
        lifecycle.register("bedrock", reason -> BedrockController.reset());
        lifecycle.register("hud", reason -> HudStatsManager.INSTANCE.resetAll());
        lifecycle.register("missing_materials", reason -> MissingMaterialTracker.INSTANCE.clear());
        lifecycle.register("inventory_availability", reason -> InventoryAvailabilityTracker.INSTANCE.reset());
        lifecycle.register("scheduler", reason -> SCHEDULER.resetRuntime());
        for (Module module : VALUES) {
            lifecycle.register("module:" + module.getId(), reason -> module.resetRuntimeState());
        }
        lifecycle.seal();
        return lifecycle;
    }

    public static String getLastPauseReason() {
        return SCHEDULER.getLastPauseReason();
    }
}
