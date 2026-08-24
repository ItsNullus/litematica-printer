package me.aleksilassila.litematica.printer.handler.handlers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public final class MineDebugLog {
    private static int lastLoggedCount;
    private static String lastMessage = "";

    private MineDebugLog() {
    }

    /** 调度器暂停原因写入游戏日志（限流：同消息最多每秒一条） */
    public static void write(String message) {
        try {
            if (message == null) {
                return;
            }
            if (message.equals(lastMessage) && ++lastLoggedCount % 20 != 0) {
                return; // 同一条暂停原因每 20 次记一条, 避免刷屏
            }
            if (!message.equals(lastMessage)) {
                lastLoggedCount = 1;
                lastMessage = message;
            }
            me.aleksilassila.litematica.printer.Reference.LOGGER.info("[PrinterDebug] {}", message);
        } catch (Exception ignored) {
        }
    }

    public static void reset() {
        lastLoggedCount = 0;
        lastMessage = "";
    }

    public static String pos(BlockPos pos) {
        if (pos == null) {
            return "null";
        }
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public static String describeState(BlockState state) {
        return state.getBlock() + " " + state;
    }
}
