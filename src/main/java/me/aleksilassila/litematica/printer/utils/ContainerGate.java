package me.aleksilassila.litematica.printer.utils;

import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;

/**
 * 容器操作互斥守卫。
 *
 * <p>所有会打开/占用容器菜单的后台子系统（Chest Tracker 远程取物、快捷潜影盒、
 * TakeItOut、物品归还）在打开容器前必须先 {@link #tryAcquire}，流程结束/失败时
 * {@link #release}。避免两个子系统同时打开容器、互相 closeContainer 造成的
 * "容器打开或切物品中"长时间阻塞（菜单反复开关、物品取不出来、状态机卡死）。</p>
 *
 * <p>看门狗：持有超过 {@link #maxHoldTicks(Owner)} 即视为卡死，由
 * {@code TickScheduler} 每 tick 检查 {@link #isExpired()} 并强制释放对应子系统。</p>
 */
public final class ContainerGate {
    /**
     * 各所有者允许的最大持有 tick 数（看门狗阈值）。
     * - CHEST_TRACKER_TAKE：其内部请求超时为 200 tick，看门狗阈值必须高于它（260），
     *   只在内部超时也失效时兜底；
     * - QUICK_SHULKER / SWITCH_ITEM_RESTORE：内部超时 40 tick，60 即视为卡死；
     * - TAKE_IT_OUT：正常为同步短暂持有，120 兜底。
     */
    public static long maxHoldTicks(Owner owner) {
        return switch (owner) {
            case CHEST_TRACKER_TAKE -> 260L;
            case QUICK_SHULKER -> 60L;
            case TAKE_IT_OUT -> 120L;
            case SWITCH_ITEM_RESTORE -> 60L;
        };
    }

    public enum Owner {
        /** Chest Tracker 远程取物（涉及网络往返，优先级最高） */
        CHEST_TRACKER_TAKE,
        /** 快捷潜影盒取料 */
        QUICK_SHULKER,
        /** Take It Out 模组取料 */
        TAKE_IT_OUT,
        /** 物品有序存放/归还潜影盒（最低，可延后） */
        SWITCH_ITEM_RESTORE
    }

    private static Owner owner = null;
    private static long acquiredTick = Long.MIN_VALUE;

    private ContainerGate() {
    }

    /**
     * 尝试获得容器锁。锁空闲或已被同所有者持有（幂等）时成功。
     *
     * @return true=可以打开容器；false=其他子系统正在占用容器，本次不要打开
     */
    public static synchronized boolean tryAcquire(Owner owner) {
        if (owner == null) {
            return false;
        }
        if (ContainerGate.owner == null) {
            ContainerGate.owner = owner;
            ContainerGate.acquiredTick = ClientPlayerTickManager.getCurrentHandlerTime();
            return true;
        }
        return ContainerGate.owner == owner;
    }

    /** 释放容器锁（仅当调用者是当前持有者时生效；幂等） */
    public static synchronized void release(Owner owner) {
        if (ContainerGate.owner == owner) {
            ContainerGate.owner = null;
            ContainerGate.acquiredTick = Long.MIN_VALUE;
        }
    }

    /** 无条件释放容器锁（看门狗/玩家意图优先时使用） */
    public static synchronized void forceRelease() {
        ContainerGate.owner = null;
        ContainerGate.acquiredTick = Long.MIN_VALUE;
    }

    public static synchronized Owner getOwner() {
        return ContainerGate.owner;
    }

    public static synchronized boolean isHeld() {
        return ContainerGate.owner != null;
    }

    public static synchronized boolean isHeldBy(Owner owner) {
        return ContainerGate.owner == owner;
    }

    /** 是否已超过该所有者允许的最大持有 tick（卡死判定） */
    public static synchronized boolean isExpired() {
        if (ContainerGate.owner == null || ContainerGate.acquiredTick == Long.MIN_VALUE) {
            return false;
        }
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        return now - ContainerGate.acquiredTick > maxHoldTicks(ContainerGate.owner);
    }

    /** 当前持有时长（tick） */
    public static synchronized long getHoldTicks() {
        if (ContainerGate.owner == null || ContainerGate.acquiredTick == Long.MIN_VALUE) {
            return 0L;
        }
        return Math.max(0L, ClientPlayerTickManager.getCurrentHandlerTime() - ContainerGate.acquiredTick);
    }
}
