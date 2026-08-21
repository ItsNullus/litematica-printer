package me.aleksilassila.litematica.printer.handler;

import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import net.minecraft.client.Minecraft;

@SuppressWarnings("SpellCheckingInspection")
public class ClientPlayerTickManager {
    public static final Minecraft mc = Minecraft.getInstance();

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
