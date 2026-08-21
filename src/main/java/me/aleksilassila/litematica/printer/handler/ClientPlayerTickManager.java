package me.aleksilassila.litematica.printer.handler;

import com.google.common.collect.ImmutableList;
import me.aleksilassila.litematica.printer.handler.handlers.*;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
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

    public static String getLastPauseReason() {
        return SCHEDULER.getLastPauseReason();
    }
}
