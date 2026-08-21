package me.aleksilassila.litematica.printer.handler;

import com.google.common.collect.ImmutableList;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockController;
import me.aleksilassila.litematica.printer.handler.handlers.*;
import me.aleksilassila.litematica.printer.handler.runtime.RuntimeLifecycle;
import me.aleksilassila.litematica.printer.mixin_extension.MultiPlayerGameModeExtension;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils;
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

    public static final ImmutableList<FeatureModuleBase> VALUES = Modules.VALUES;
    private static final TickScheduler SCHEDULER = new TickScheduler(VALUES);
    private static final RuntimeLifecycle RUNTIME = createRuntimeLifecycle();

    public static void tick() {
        PrinterRuntime.get().tick(mc);
    }

    public static void tickLegacyRuntime() {
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
        PrinterRuntime.get().reset(reason);
    }

    public static void resetComponentsForEpoch(String reason) {
        RUNTIME.reset(reason);
    }

    private static RuntimeLifecycle createRuntimeLifecycle() {
        RuntimeLifecycle lifecycle = new RuntimeLifecycle();
        lifecycle.register("game_mode", reason -> {
            if (mc.gameMode instanceof MultiPlayerGameModeExtension extension) {
                extension.litematica_printer$resetRuntime();
            }
        });
        lifecycle.register("network_look", reason -> NetworkUtils.clearScopedLookOverride());
        lifecycle.register("inventory_switch_guard", reason -> InventorySwitchGuard.reset());
        lifecycle.register("take_it_out", reason -> TakeItOutUtils.resetPending());
        lifecycle.register("bedrock", reason -> BedrockController.reset());
        lifecycle.seal();
        return lifecycle;
    }

    public static String getLastPauseReason() {
        return SCHEDULER.getLastPauseReason();
    }
}
