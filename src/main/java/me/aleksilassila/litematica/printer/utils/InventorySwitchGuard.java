package me.aleksilassila.litematica.printer.utils;

import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

public final class InventorySwitchGuard {
    private static final Minecraft client = Minecraft.getInstance();
    private static final int MAX_WAIT_TICKS = 4;
    private static final double AIRBORNE_RISK_BLOCKS_PER_TICK = 0.20D;
    private static final double GROUND_RISK_BLOCKS_PER_TICK = 0.35D;
    private static final double AIRBORNE_RISK_SQR = AIRBORNE_RISK_BLOCKS_PER_TICK * AIRBORNE_RISK_BLOCKS_PER_TICK;
    private static final double GROUND_RISK_SQR = GROUND_RISK_BLOCKS_PER_TICK * GROUND_RISK_BLOCKS_PER_TICK;

    private static Item pendingItem;
    private static long pendingStartedTick;

    private InventorySwitchGuard() {
    }

    public static boolean markSwitchIfNeeded(Item item) {
        if (item == null || !isHighSpeedMovement()) {
            return false;
        }
        pendingItem = item;
        pendingStartedTick = ClientPlayerTickManager.getCurrentHandlerTime();
        ActionManager.INSTANCE.clearQueue();
        return true;
    }

    public static boolean isWaiting() {
        if (pendingItem == null) {
            return false;
        }
        ActionManager.INSTANCE.clearQueue();
        long age = ClientPlayerTickManager.getCurrentHandlerTime() - pendingStartedTick;
        if (age > MAX_WAIT_TICKS) {
            clear();
            return false;
        }
        if (age <= 0) {
            return true;
        }
        if (isMainHandReady(pendingItem)) {
            clear();
            return false;
        }
        return true;
    }

    private static void clear() {
        pendingItem = null;
        pendingStartedTick = 0L;
    }

    private static boolean isHighSpeedMovement() {
        if (client.player == null) {
            return false;
        }
        Vec3 movement = client.player.getDeltaMovement();
        double riskThresholdSqr = client.player.onGround() ? GROUND_RISK_SQR : AIRBORNE_RISK_SQR;
        return movement.lengthSqr() >= riskThresholdSqr;
    }

    private static boolean isMainHandReady(Item item) {
        return client.player != null && client.player.getMainHandItem().is(item);
    }
}
