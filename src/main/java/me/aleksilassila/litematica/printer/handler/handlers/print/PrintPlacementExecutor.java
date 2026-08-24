package me.aleksilassila.litematica.printer.handler.handlers.print;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.handlers.PrintHandler;
import me.aleksilassila.litematica.printer.interfaces.Implementation;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.PlayerLook;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.utils.FilterUtils;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.DirectionUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import me.aleksilassila.litematica.printer.utils.mods.LitematicaUtils;
import me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;

public final class PrintPlacementExecutor {
    private static final Item[] EMPTY_HAND_ITEMS = {Items.AIR};
    private static final long RESERVE_NOTICE_COOLDOWN_TICKS = 100L;
    private static long lastReserveNoticeTick = Long.MIN_VALUE;
    private static boolean supportModeBannerLogged;

    public PrintPlacementResult execute(SchematicBlockContext context, Action action, @Nullable PrintTaskAction taskAction) {
        BlockPos blockPos = context.blockPos;
        // 无支撑支撑方块：目标方块无法存活（缺支撑）且支撑位为空（世界+投影均无）→ 先放支撑方块
        me.aleksilassila.litematica.printer.enums.SupportPlaceModeType supportMode =
                (me.aleksilassila.litematica.printer.enums.SupportPlaceModeType) Configs.Print.SUPPORT_PLACE_MODE.getOptionListValue();
        if (!supportModeBannerLogged) {
            supportModeBannerLogged = true;
            me.aleksilassila.litematica.printer.Reference.LOGGER.info(
                    "[Printer] 支撑配置: 模式={} 列表={} 解析物品={}",
                    supportMode.getI18n().getSimpleKey(),
                    String.join("|", Configs.Print.SUPPORT_BLOCK_LIST.getStrings()),
                    resolveSupportItem() == null ? "null" : BuiltInRegistries.ITEM.getKey(resolveSupportItem()));
        }
        if (supportMode != me.aleksilassila.litematica.printer.enums.SupportPlaceModeType.NONE) {
            boolean survivable = context.requiredState.canSurvive(context.level, blockPos);
            if (!survivable) {
                me.aleksilassila.litematica.printer.Reference.LOGGER.info(
                        "[Printer] 无支撑支撑: 模式={} 目标={} 位置={} canSurvive=false",
                        supportMode.getI18n().getSimpleKey(), context.requiredState.getBlock(), blockPos);
            }
            if (!survivable && tryQueueSupportPlacement(context, action, blockPos)) {
                HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "放置支撑");
                return PrintPlacementResult.cancelled(false);
            }
        }
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
            ItemStack reserveBlockedStack = reserveItems
                    ? InventoryUtils.findReserveBlockedStack(
                            context.client.player,
                            requiredItems,
                            requiredStackPredicate,
                            reserveCount
                    )
                    : ItemStack.EMPTY;
            boolean awaitingTakeout = TakeItOutUtils.isAwaitingStack();
            boolean awaitingChestTake = me.aleksilassila.litematica.printer.printer.zxy.chesttracker.ChestTrackerBridge.isAwaitingStack();
            if (reserveBlockedStack.isEmpty()) {
                HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "缺少材料");
                // 远程取物由快捷潜影盒流程驱动（switchItem：背包盒子找不到后才取箱子），
                // 避免与潜影盒抢菜单/重复触发。
                if (awaitingChestTake) {
                    HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "远程取物中");
                }
            } else {
                HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "达到保留数量");
                showReserveNotice(context, reserveBlockedStack);
            }
            // 缺少材料属于无效放置，不应消耗每 tick 的有效放置预算（与重构前行为一致）。
            // 注意不能因缺料就停止迭代（shouldPauseForSwitchRequest 的 lastNeedItemList 缺料时恒非空），
            // 否则一个缺料的方块会卡住整个打印——手中有材料的部分也放不了。只有真正占用菜单时才停。
            return PrintPlacementResult.failure(false,
                    shouldStopAfterTaskAction(taskAction)
                            || me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.isOpenHandler
                            || me.aleksilassila.litematica.printer.printer.zxy.inventory.SwitchItem.hasPendingRestore()
                            || awaitingTakeout
                            || awaitingChestTake);
        }
        if (!InventoryUtils.isHoldingAnyItem(context.client.player, requiredItems)
                || requiredStackPredicate != null
                && !requiredStackPredicate.test(context.client.player.getMainHandItem())) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "等待物品同步");
            return PrintPlacementResult.failure(false, true);
        }

        boolean useShift = getUseShift(context, action, side);
        if (!action.queueAction(blockPos, side, useShift, context.client.player, requiredItems)) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "动作队列占用");
            return PrintPlacementResult.cancelled(true);
        }
        ActionManager.INSTANCE.setExpectedStackPredicate(requiredStackPredicate);
        Vec3 hitModifier = LitematicaUtils.usePrecisionPlacement(blockPos, context.requiredState);
        if (hitModifier != null) {
            ActionManager.INSTANCE.useProtocolHitModifier(hitModifier);
        }
        ActionManager.INSTANCE.setLook(adjustHorizontalLook(action.getPlayerLook(), context));
        ActionManager.INSTANCE.setNeedWaitModifyLookFromAction(action.isNeedWaitModifyLook());
        boolean consumedEffectiveExecution = action.isConsumeEffectiveExecution();
        int cooldownTicks = action.getCooldownTicksOverride() >= 0
                ? action.getCooldownTicksOverride()
                : ConfigUtils.getPlaceCooldown();
        AtomicBoolean deferred = new AtomicBoolean(false);
        boolean signPlacement = context.requiredState.getBlock() instanceof SignBlock;
        if (signPlacement) {
            ActionManager.INSTANCE.armPrintSignEdit(blockPos);
        }
        ActionManager.INSTANCE.setQueueCompletionListener(sendResult -> {
            if (!deferred.get()) {
                return;
            }
            if (sendResult.isSent()) {
                recordPlacementSent(context);
                if (cooldownTicks > 0) {
                    CooldownUtils.INSTANCE.setCooldown(
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
                    ActionManager.INSTANCE.cancelPrintSignEdit(blockPos);
                }
                if (sendResult == ActionManager.SendResult.RESERVE_LIMIT) {
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

        ActionManager.SendResult sendResult = ActionManager.INSTANCE.sendQueue(context.client.player);
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
                ActionManager.INSTANCE.cancelPrintSignEdit(blockPos);
            }
            if (sendResult == ActionManager.SendResult.RESERVE_LIMIT) {
                showReserveNotice(context, context.client.player.getMainHandItem());
            }
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, describeSendFailure(sendResult));
            return PrintPlacementResult.cancelled(true);
        }

        recordPlacementSent(context);

        return new PrintPlacementResult(
                consumedEffectiveExecution,
                shouldStopAfterTaskAction(taskAction),
                PrintPlacementResult.TaskEvent.SUCCESS,
                cooldownTicks
        );
    }

    private static void recordPlacementSent(SchematicBlockContext context) {
        if (context.requiredState.getBlock() instanceof SignBlock) {
            ActionManager.INSTANCE.confirmPrintSignEditSent(context.blockPos);
        }
        HudStatsManager.INSTANCE.trackExpectedBlockState(
                HudStatsManager.Mode.PRINT,
                context.blockPos,
                context.requiredState
        );
        HudStatsManager.INSTANCE.recordRateUnit(HudStatsManager.Mode.PRINT, 1);
        HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.PRINT, "运行中");
    }

    private static String describeSendFailure(ActionManager.SendResult result) {
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
    private static PlayerLook adjustHorizontalLook(@Nullable PlayerLook playerLook, SchematicBlockContext context) {
        if (playerLook == null) {
            return null;
        }
        Direction primaryLookDirection = DirectionUtils.orderedByNearest(playerLook.getYaw(), playerLook.getPitch())[0];
        if (primaryLookDirection.getAxis().isHorizontal()) {
            float currentPitch = context.client.player.getXRot();
            currentPitch = Math.max(-40.0F, Math.min(40.0F, currentPitch));
            ActionManager.INSTANCE.setWaitForHorizontalLook(false);
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

    // ========== 无支撑支撑方块 ==========
    private static final Map<BlockPos, Long> pendingSupportTargets = new HashMap<>();
    private static Item cachedSupportItem;
    private static String cachedSupportListKey;

    /**
     * 目标方块无法存活（缺支撑）时，在支撑位（世界+投影均为空气）放置支撑方块。
     * 返回 true = 已排队支撑/等待支撑确认/正在切换支撑物品（本体本次不放置）。
     */
    private static boolean tryQueueSupportPlacement(SchematicBlockContext context, Action action, BlockPos blockPos) {
        if (context.client.player == null) {
            return false;
        }
        BlockState target = context.requiredState;
        // 目标本身能存活 → 不需要支撑
        if (target.canSurvive(context.level, blockPos)) {
            return false;
        }
        long gameTime = context.level.getGameTime();
        // 刚排过支撑, 等待服务端确认期间不重复排队（防抖 tick 数可配置, 0=关闭）
        long pendingTtlTicks = Configs.Print.SUPPORT_PENDING_TTL.getIntegerValue();
        Long pendingTick = pendingSupportTargets.get(blockPos);
        if (pendingTtlTicks > 0 && pendingTick != null && gameTime - pendingTick < pendingTtlTicks) {
            return true;
        }
        pendingSupportTargets.remove(blockPos);
        me.aleksilassila.litematica.printer.enums.SupportPlaceModeType mode =
                (me.aleksilassila.litematica.printer.enums.SupportPlaceModeType) Configs.Print.SUPPORT_PLACE_MODE.getOptionListValue();
        List<Direction> dirs = new ArrayList<>();
        if (mode == me.aleksilassila.litematica.printer.enums.SupportPlaceModeType.DOWN) {
            dirs.add(Direction.DOWN);
        } else {
            for (Direction d : Direction.values()) {
                dirs.add(d);
            }
        }
        Item supportItem = resolveSupportItem();
        if (supportItem == null) {
            me.aleksilassila.litematica.printer.Reference.LOGGER.info("[Printer] 无支撑支撑: 支撑列表无法解析出物品");
            return false;
        }
        me.aleksilassila.litematica.printer.Reference.LOGGER.info(
                "[Printer] 无支撑支撑: 支撑物品={} 候选方向数={}", BuiltInRegistries.ITEM.getKey(supportItem), dirs.size());
        for (Direction d : dirs) {
            BlockPos supportPos = blockPos.relative(d);
            if (supportPos == null) {
                continue;
            }
            me.aleksilassila.litematica.printer.Reference.LOGGER.info(
                    "[Printer] 无支撑支撑: 候选方向 {} 位置 {} 世界空气={} 投影方块={}",
                    d, supportPos, context.level.getBlockState(supportPos).isAir(), LitematicaUtils.isSchematicBlock(supportPos));
            if (!context.level.getBlockState(supportPos).isAir()) {
                continue; // 世界已有方块（含已放支撑）
            }
            if (LitematicaUtils.isSchematicBlock(supportPos)) {
                continue; // 投影中有方块 → 交给打印本身放置
            }
            if (InventoryUtils.switchToItems(context.client.player, new Item[]{supportItem})) {
                Direction supportSide = findSupportSide(context.level, supportPos);
                if (supportSide != null) {
                    boolean queued = action.queueAction(supportPos, supportSide, false, context.client.player, new Item[]{supportItem});
                    me.aleksilassila.litematica.printer.Reference.LOGGER.info(
                            "[Printer] 无支撑支撑: {} 放置 {} 支撑 {} (方向 {}) 排队={}",
                            supportPos, BuiltInRegistries.ITEM.getKey(supportItem), target.getBlock(), d, queued);
                    if (queued) {
                        pendingSupportTargets.put(blockPos, gameTime);
                        return true;
                    }
                } else {
                    me.aleksilassila.litematica.printer.Reference.LOGGER.info("[Printer] 无支撑支撑: 位置 {} 无可点击相邻面", supportPos);
                }
            } else if (InventorySwitchGuard.isWaiting()) {
                me.aleksilassila.litematica.printer.Reference.LOGGER.info("[Printer] 无支撑支撑: 正在切换支撑物品");
                return true; // 正在切换支撑物品
            } else {
                me.aleksilassila.litematica.printer.Reference.LOGGER.info(
                        "[Printer] 无支撑支撑: 背包中没有支撑物品 {}", BuiltInRegistries.ITEM.getKey(supportItem));
            }
        }
        return false;
    }

    /** 为支撑位置找一个可点击的相邻面（优先下方） */
    private static Direction findSupportSide(net.minecraft.world.level.Level level, BlockPos supportPos) {
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP};
        for (Direction d : order) {
            if (!level.getBlockState(supportPos.relative(d)).isAir()) {
                return d;
            }
        }
        return null;
    }

    /** 解析支撑方块列表（缓存；支持注册表 id 与显示名/拼音） */
    private static Item resolveSupportItem() {
        List<String> list = Configs.Print.SUPPORT_BLOCK_LIST.getStrings();
        String key = String.join("|", list);
        if (key.equals(cachedSupportListKey)) {
            return cachedSupportItem;
        }
        cachedSupportListKey = key;
        cachedSupportItem = null;
        for (String entry : list) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String id = entry.trim();
            if (id.contains(":")) {
                // 注册表 id 精确匹配（版本无关：不直接用 Registry.get/ResourceLocation 类型）
                for (Item item : BuiltInRegistries.ITEM) {
                    if (id.equals(BuiltInRegistries.ITEM.getKey(item).toString())) {
                        cachedSupportItem = item;
                        break;
                    }
                }
            }
            if (cachedSupportItem == null) {
                for (Item item : BuiltInRegistries.ITEM) {
                    if (FilterUtils.matchItemName(entry, new ItemStack(item))) {
                        cachedSupportItem = item;
                        break;
                    }
                }
            }
            if (cachedSupportItem != null) {
                break;
            }
        }
        return cachedSupportItem;
    }
}
