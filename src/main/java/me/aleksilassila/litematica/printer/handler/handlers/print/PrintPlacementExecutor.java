package me.aleksilassila.litematica.printer.handler.handlers.print;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.handlers.PrintHandler;
import me.aleksilassila.litematica.printer.interfaces.Implementation;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.printer.PlayerLook;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.utils.minecraft.DirectionUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import me.aleksilassila.litematica.printer.utils.mods.LitematicaUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;

public final class PrintPlacementExecutor {
    private final ActionBroker actionBroker;
    private static final Item[] EMPTY_HAND_ITEMS = {Items.AIR};
    private static final long RESERVE_NOTICE_COOLDOWN_TICKS = 100L;
    private static long lastReserveNoticeTick = Long.MIN_VALUE;

    public PrintPlacementExecutor(ActionBroker actionBroker) {
        this.actionBroker = actionBroker;
    }

    public PrintPlacementResult execute(SchematicBlockContext context, Action action, @Nullable PrintTaskAction taskAction) {
        BlockPos blockPos = context.blockPos;
        if (Configs.Placement.FALLING_CHECK.getBooleanValue() && context.requiredState.getBlock() instanceof FallingBlock) {
            BlockPos downPos = blockPos.below();
            if (FallingBlock.isFree(context.level.getBlockState(downPos))) {
                HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "下落方块无支撑");
                MessageUtils.setOverlayMessage(I18n.FALLING_BLOCK_NO_SUPPORT.getName(context.requiredBlockName().getString()));
                return PrintPlacementResult.failure(false, shouldStopAfterTaskAction(taskAction));
            }
        }

        Direction side = action.getValidSide(context.level, blockPos);
        if (side == null) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "无有效放置面");
            return PrintPlacementResult.failure(false, shouldStopAfterTaskAction(taskAction));
        }

        Item[] requiredItems = normalizeRequiredItems(action.getRequiredItems(context.requiredState.getBlock()));
        Predicate<ItemStack> requiredStackPredicate = action.getRequiredStackPredicate();
        boolean reserveItems = Configs.Print.PRINT_RESERVE_ITEMS.getBooleanValue();
        int reserveCount = Configs.Print.PRINT_RESERVE_ITEM_COUNT.getIntegerValue();
        boolean itemReady;
        if (requiredStackPredicate == null) {
            itemReady = reserveItems
                    ? InventoryUtils.switchToItemsWithReserve(context.client.player, requiredItems, reserveCount)
                    : InventoryUtils.switchToItems(context.client.player, requiredItems);
        } else {
            itemReady = reserveItems
                    ? InventoryUtils.switchToMatchingStackWithReserve(
                            context.client.player,
                            requiredStackPredicate,
                            action.getRequiredCreativeStack(),
                            reserveCount
                    )
                    : InventoryUtils.switchToMatchingStack(
                            context.client.player,
                            requiredStackPredicate,
                            action.getRequiredCreativeStack()
                    );
        }
        if (!itemReady) {
            boolean retrievalPending =
                    InventorySwitchGuard.isWaiting()
                            || this.actionBroker.isResourceHeld(ResourceLease.INVENTORY);
            ItemStack reserveBlockedStack = reserveItems
                    ? InventoryUtils.findReserveBlockedStack(
                            context.client.player,
                            requiredItems,
                            requiredStackPredicate,
                            reserveCount
                    )
                    : ItemStack.EMPTY;
            if (retrievalPending) {
                HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "等待取货");
                MissingMaterialTracker.getRuntime().resolve(requiredItems, requiredStackPredicate);
            } else if (reserveBlockedStack.isEmpty()) {
                HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "缺少材料");
                MissingMaterialTracker.getRuntime().recordMissing(
                        requiredItems,
                        requiredStackPredicate,
                        action.getRequiredCreativeStack(),
                        context.level.getGameTime()
                );
            } else {
                HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "达到保留数量");
                showReserveNotice(context, reserveBlockedStack);
            }
            if (retrievalPending) {
                // 换槽或外部取货只是暂时未就绪。多阶段任务必须保留当前阶段，
                // 否则破冰放水会在材料到达前被当成永久失败并丢失目标。
                return PrintPlacementResult.deferred(true);
            }
            // 真正缺少材料属于无效放置，不应消耗每 tick 的有效放置预算。
            return PrintPlacementResult.failure(false, shouldStopAfterTaskAction(taskAction));
        }
        MissingMaterialTracker.getRuntime().resolve(requiredItems, requiredStackPredicate);
        if (!InventoryUtils.isHoldingAnyItem(context.client.player, requiredItems)
                || requiredStackPredicate != null
                && !requiredStackPredicate.test(context.client.player.getMainHandItem())) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "等待物品同步");
            return PrintPlacementResult.deferred(true);
        }

        boolean useShift = getUseShift(context, action, side);
        if (!action.queueAction(this.actionBroker, blockPos, side, useShift, context.client.player, requiredItems)) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "动作队列占用");
            return PrintPlacementResult.cancelled(true);
        }
        this.actionBroker.setExpectedStackPredicate(requiredStackPredicate);
        Vec3 hitModifier = LitematicaUtils.usePrecisionPlacement(blockPos, context.requiredState);
        if (hitModifier != null) {
            this.actionBroker.useProtocolHitModifier(hitModifier);
        }
        this.actionBroker.setLook(adjustHorizontalLook(action.getPlayerLook(), context));
        this.actionBroker.setNeedWaitModifyLookFromAction(action.isNeedWaitModifyLook());
        boolean consumedEffectiveExecution = action.isConsumeEffectiveExecution();
        int cooldownTicks = action.getCooldownTicksOverride() >= 0
                ? action.getCooldownTicksOverride()
                : ConfigUtils.getPlaceCooldown();
        AtomicBoolean deferred = new AtomicBoolean(false);
        boolean signPlacement = context.requiredState.getBlock() instanceof SignBlock;
        if (signPlacement) {
            this.actionBroker.armPrintSignEdit(blockPos);
        }
        this.actionBroker.setQueueCompletionListener(sendResult -> {
            if (!deferred.get()) {
                return;
            }
            if (sendResult.isSent()) {
                this.recordPlacementSent(context);
                if (cooldownTicks > 0) {
                    PrinterRuntime.get().cooldownUtils().setCooldown(
                            context.level,
                            PrintHandler.NAME,
                            blockPos,
                            cooldownTicks
                    );
                }
                if (taskAction != null) {
                    taskAction.onSuccess(context, action);
                }
            } else {
                if (signPlacement) {
                    this.actionBroker.cancelPrintSignEdit(blockPos);
                }
                if (sendResult == ActionBroker.SendResult.RESERVE_LIMIT) {
                    showReserveNotice(context, context.client.player.getMainHandItem());
                }
                HudStatsManager.INSTANCE.recordDeferred(
                        HudStatsManager.Mode.PRINT,
                        describeSendFailure(sendResult)
                );
                if (taskAction != null) {
                    taskAction.onCancelled(context, action);
                }
            }
        });

        ActionBroker.SendResult sendResult = this.actionBroker.sendQueue(context.client.player);
        if (sendResult.isWaiting()) {
            deferred.set(true);
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "等待转头");
            return new PrintPlacementResult(
                    consumedEffectiveExecution,
                    true,
                    PrintPlacementResult.TaskEvent.QUEUED,
                    -1
            );
        }
        if (!sendResult.isSent()) {
            if (signPlacement) {
                this.actionBroker.cancelPrintSignEdit(blockPos);
            }
            if (sendResult == ActionBroker.SendResult.RESERVE_LIMIT) {
                showReserveNotice(context, context.client.player.getMainHandItem());
            }
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, describeSendFailure(sendResult));
            return PrintPlacementResult.cancelled(true);
        }

        this.recordPlacementSent(context);

        return new PrintPlacementResult(
                consumedEffectiveExecution,
                shouldStopAfterTaskAction(taskAction),
                PrintPlacementResult.TaskEvent.SUCCESS,
                cooldownTicks
        );
    }

    private void recordPlacementSent(SchematicBlockContext context) {
        if (context.requiredState.getBlock() instanceof SignBlock) {
            this.actionBroker.confirmPrintSignEditSent(context.blockPos);
        }
        HudStatsManager.INSTANCE.trackExpectedBlockState(
                HudStatsManager.Mode.PRINT,
                context.blockPos,
                context.requiredState
        );
        HudStatsManager.INSTANCE.recordRateUnit(HudStatsManager.Mode.PRINT, 1);
        HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.PRINT, "运行中");
    }

    private static String describeSendFailure(ActionBroker.SendResult result) {
        return switch (result) {
            case STALE_POSITION -> "移动后动作失效";
            case HELD_ITEM_CHANGED -> "手持物品已变化";
            case RESERVE_LIMIT -> "达到保留数量";
            case NO_PLAYER, NO_GAME_MODE -> "客户端状态未就绪";
            case INTERACTION_REJECTED -> "交互被拒绝";
            case NO_QUEUED_ACTION -> "动作未入队";
            default -> "动作未发送";
        };
    }

    private static boolean getUseShift(SchematicBlockContext context, Action action, Direction side) {
        if (action.getShift() != null) {
            return action.getShift();
        }
        return (Implementation.isInteractive(context.level.getBlockState(context.blockPos.relative(side)).getBlock())
                && !(action instanceof ClickAction))
                || Configs.Print.PRINT_FORCED_SNEAK.getBooleanValue();
    }

    @Nullable
    private PlayerLook adjustHorizontalLook(@Nullable PlayerLook playerLook, SchematicBlockContext context) {
        if (playerLook == null) {
            return null;
        }
        Direction primaryLookDirection = DirectionUtils.orderedByNearest(playerLook.getYaw(), playerLook.getPitch())[0];
        if (primaryLookDirection.getAxis().isHorizontal()) {
            float currentPitch = context.client.player.getXRot();
            currentPitch = Math.max(-40.0F, Math.min(40.0F, currentPitch));
            this.actionBroker.setWaitForHorizontalLook(false);
            return new PlayerLook(playerLook.getYaw(), currentPitch);
        }
        return playerLook;
    }

    private static Item[] normalizeRequiredItems(@Nullable Item[] requiredItems) {
        return requiredItems == null || requiredItems.length == 0 ? EMPTY_HAND_ITEMS : requiredItems;
    }

    private static boolean shouldStopAfterTaskAction(@Nullable PrintTaskAction taskAction) {
        return taskAction != null && taskAction.stopIterationAfterAction();
    }

    private static void showReserveNotice(SchematicBlockContext context, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        long currentTick = context.level.getGameTime();
        if (lastReserveNoticeTick != Long.MIN_VALUE
                && currentTick >= lastReserveNoticeTick
                && currentTick - lastReserveNoticeTick < RESERVE_NOTICE_COOLDOWN_TICKS) {
            return;
        }
        lastReserveNoticeTick = currentTick;
        MessageUtils.setOverlayMessage(I18n.RESERVE_ITEM_SKIP.getName(
                stack.getHoverName(),
                Configs.Print.PRINT_RESERVE_ITEM_COUNT.getIntegerValue()
        ));
    }
}
