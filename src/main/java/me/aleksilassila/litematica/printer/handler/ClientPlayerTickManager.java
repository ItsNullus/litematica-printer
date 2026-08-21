package me.aleksilassila.litematica.printer.handler;

import me.aleksilassila.litematica.printer.handler.handlers.*;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import net.minecraft.client.Minecraft;

@SuppressWarnings("SpellCheckingInspection")
public class ClientPlayerTickManager {
    public static final Minecraft mc = Minecraft.getInstance();

    public static final GuiHandler GUI = PrinterRuntime.get().modules().gui();
    public static final PrintHandler PRINT = PrinterRuntime.get().modules().print();
    public static final FillHandler FILL = PrinterRuntime.get().modules().fill();
    public static final MineHandler MINE = PrinterRuntime.get().modules().mine();
    public static final FluidHandler FLUID = PrinterRuntime.get().modules().fluid();
    public static final BedrockHandler BEDROCK = PrinterRuntime.get().modules().bedrock();

    public static final java.util.List<FeatureModuleBase> VALUES = PrinterRuntime.get().modules().values();

    public static void tick() {
        PrinterRuntime.get().tick(mc);
    }

    public static void tickLegacyRuntime() {
        PrinterRuntime.get().tickModules();
    }

    public static long getCurrentHandlerTime() {
        return TickContext.currentGameTime();
    }

    public static int getPacketTick() {
        return PrinterRuntime.get().modules().packetTick();
    }

    public static void setPacketTick(int packetTick) {
        PrinterRuntime.get().modules().setPacketTick(packetTick);
    }

    public static void recordInboundPacket() {
        PrinterRuntime.get().modules().recordInboundPacket();
    }

    public static void resetRuntime(String reason) {
        PrinterRuntime.get().reset(reason);
    }

    public static String getLastPauseReason() {
        return PrinterRuntime.get().modules().lastPauseReason();
    }
}
