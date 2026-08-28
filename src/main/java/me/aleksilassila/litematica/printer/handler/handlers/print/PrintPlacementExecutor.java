package me.aleksilassila.litematica.printer.handler.handlers.print;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.SupportPlaceModeType;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.handlers.PrintHandler;
import me.aleksilassila.litematica.printer.interfaces.Implementation;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.PlayerLook;
import me.aleksilassila.litematica.printer.printer.PrinterUtils;
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

    /**
     * 目标方块在当前世界位置是否"需要支撑"。
     * 下落类方块（铁砧/沙/砂砾等）不重写 canSurvive（恒为 true, 只会坠落）,
     * 必须用 FallingBlock.isFree(下方) 判断；其余方块用 canSurvive。
     */
    public static boolean requiresSupportAt(net.minecraft.world.level.Level level, BlockState state, BlockPos pos) {
        if (state.getBlock() instanceof FallingBlock) {
            return FallingBlock.isFree(level.getBlockState(pos.below()));
        }
        return !state.canSurvive(level, pos);
    }

    public PrintPlacementResult execute(SchematicBlockContext context, Action action, @Nullable PrintTaskAction taskAction) {
        BlockPos blockPos = context.blockPos;
        // 无支撑支撑方块：目标方块无法存活（缺支撑）且支撑位为空（世界+投影均无）→ 先放支撑方块
        SupportPlaceModeType supportMode =
                (SupportPlaceModeType) Configs.Print.SUPPORT_PLACE_MODE.getOptionListValue();
        if (!supportModeBannerLogged) {
            supportModeBannerLogged = true;
            me.aleksilassila.litematica.printer.Reference.LOGGER.info(
                    "[Printer] 支撑配置: 模式={} 列表={} 解析物品={}",
                    supportMode.getI18n().getSimpleKey(),
                    String.join("|", Configs.Print.SUPPORT_BLOCK_LIST.getStrings()),
                    resolveSupportItem() == null ? "null" : BuiltInRegistries.ITEM.getKey(resolveSupportItem()));
        }
        if (supportMode != SupportPlaceModeType.NONE) {
            boolean survivable = !requiresSupportAt(context.level, context.requiredState, blockPos);
            if (!survivable) {
                me.aleksilassila.litematica.printer.Reference.LOGGER.info(
                        "[Printer] 无支撑支撑: 模式={} 目标={} 位置={} 需要支撑",
                        supportMode.getI18n().getSimpleKey(), context.requiredState.getBlock(), blockPos);
                SupportPlacementPlan supportPlan = tryBuildSupportPlacement(context, action, blockPos);
                if (supportPlan != null) {
                    if (supportPlan.deferred()) {
                        HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "等待支撑");
                        return PrintPlacementResult.cancelled(false);
                    }
                    return sendSupportPlacement(context, action, taskAction, blockPos, supportPlan);
                }
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

    /** 支撑放置方案：null=无需/无法支撑；deferred=true=等待中（防抖/切换物品）；否则为可执行的支撑方案 */
    private record SupportPlacementPlan(
            @Nullable BlockPos supportPos,
            @Nullable Direction supportSide,
            @Nullable Item item,
            boolean useShift,
            boolean deferred,
            boolean airPlace
    ) {
        static SupportPlacementPlan waiting() {
            return new SupportPlacementPlan(null, null, null, false, true, false);
        }

        static SupportPlacementPlan ready(BlockPos supportPos, Direction supportSide, Item item, boolean useShift) {
            return new SupportPlacementPlan(supportPos, supportSide, item, useShift, false, false);
        }

        /** 凭空放置：无可点击相邻面时, 直接把空气位当作点击目标（ItemPlacementContext 对可替换目标直接在目标位放置） */
        static SupportPlacementPlan readyAir(BlockPos supportPos, Item item, boolean useShift) {
            return new SupportPlacementPlan(supportPos, Direction.UP, item, useShift, false, true);
        }
    }

    /**
     * 目标方块无法存活（缺支撑）时，寻找支撑位并准备放置支撑方块。
     * 支撑方向优先取目标真正需要的方向：单侧面 Action（火把/墙牌/梯子/绊线钩等）→ 该侧面（墙侧/下方）；
     * 其余（铁砧/压力板等）→ 正下方。支撑位无可点击相邻面时（悬空/虚空），只要支撑位在世界内就直接
     * 凭空放置（直接点击空气位）；支撑位超出世界则无法放置（无解）。
     * 只负责选位和切换物品；点击由 sendSupportPlacement 排队并发送。
     */
    @Nullable
    private static SupportPlacementPlan tryBuildSupportPlacement(SchematicBlockContext context, Action action, BlockPos blockPos) {
        if (context.client.player == null) {
            return null;
        }
        BlockState target = context.requiredState;
        // 目标本身能存活 → 不需要支撑（下落类方块用 FallingBlock.isFree 判断）
        if (!requiresSupportAt(context.level, target, blockPos)) {
            return null;
        }
        long gameTime = context.level.getGameTime();
        // 刚发过支撑, 等待服务端确认期间不重复排队（防抖 tick 数可配置, 0=关闭）
        long pendingTtlTicks = Configs.Print.SUPPORT_PENDING_TTL.getIntegerValue();
        Long pendingTick = pendingSupportTargets.get(blockPos);
        if (pendingTtlTicks > 0 && pendingTick != null && gameTime - pendingTick < pendingTtlTicks) {
            return SupportPlacementPlan.waiting();
        }
        pendingSupportTargets.remove(blockPos);
        List<Direction> dirs = computeSupportDirections(action);
        Item supportItem = resolveSupportItem();
        if (supportItem == null) {
            me.aleksilassila.litematica.printer.Reference.LOGGER.info("[Printer] 无支撑支撑: 支撑列表无法解析出物品");
            return null;
        }
        me.aleksilassila.litematica.printer.Reference.LOGGER.info(
                "[Printer] 无支撑支撑: 支撑物品={} 候选方向数={} 方向={}",
                BuiltInRegistries.ITEM.getKey(supportItem), dirs.size(), dirs);
        for (Direction d : dirs) {
            BlockPos supportPos = blockPos.relative(d);
            if (supportPos == null) {
                continue;
            }
            me.aleksilassila.litematica.printer.Reference.LOGGER.info(
                    "[Printer] 无支撑支撑: 候选方向 {} 位置 {} 世界空气={} 投影方块={}",
                    d, supportPos, context.level.getBlockState(supportPos).isAir(), context.schematic.getBlockState(supportPos));
            if (!context.level.getBlockState(supportPos).isAir()) {
                continue; // 世界已有方块（含已放支撑）
            }
            if (!context.schematic.getBlockState(supportPos).isAir()) {
                continue; // 投影中有方块 → 交给打印本身放置
            }
            if (!ConfigUtils.canInteracted(supportPos)) {
                continue; // 超出交互范围
            }
            Direction supportSide = findSupportSide(context.level, supportPos);
            if (supportSide != null) {
                if (!PrinterUtils.canBeClicked(context.level, supportPos.relative(supportSide))) {
                    me.aleksilassila.litematica.printer.Reference.LOGGER.info("[Printer] 无支撑支撑: 位置 {} 相邻面 {} 不可点击", supportPos, supportSide);
                    continue;
                }
                boolean useShift = isSupportNeighborInteractive(context, supportPos, supportSide);
                if (InventoryUtils.switchToItems(context.client.player, new Item[]{supportItem})) {
                    me.aleksilassila.litematica.printer.Reference.LOGGER.info(
                            "[Printer] 无支撑支撑: 位置 {} 放置 {} 支撑 {} (方向 {})",
                            supportPos, BuiltInRegistries.ITEM.getKey(supportItem), target.getBlock(), d);
                    return SupportPlacementPlan.ready(supportPos, supportSide, supportItem, useShift);
                } else if (InventorySwitchGuard.isWaiting()) {
                    me.aleksilassila.litematica.printer.Reference.LOGGER.info("[Printer] 无支撑支撑: 正在切换支撑物品");
                    return SupportPlacementPlan.waiting(); // 正在切换支撑物品
                } else {
                    me.aleksilassila.litematica.printer.Reference.LOGGER.info(
                            "[Printer] 无支撑支撑: 背包中没有支撑物品 {}", BuiltInRegistries.ITEM.getKey(supportItem));
                }
            } else if (canAirPlace(context, supportPos)) {
                // 无可点击相邻面, 但支撑位在世界内 → 凭空放置：直接点击空气位（无需相邻面/协议）
                boolean useShift = Configs.Print.PRINT_FORCED_SNEAK.getBooleanValue();
                if (InventoryUtils.switchToItems(context.client.player, new Item[]{supportItem})) {
                    me.aleksilassila.litematica.printer.Reference.LOGGER.info(
                            "[Printer] 无支撑支撑: 位置 {} 凭空放置 {} (方向 {})",
                            supportPos, BuiltInRegistries.ITEM.getKey(supportItem), d);
                    return SupportPlacementPlan.readyAir(supportPos, supportItem, useShift);
                } else if (InventorySwitchGuard.isWaiting()) {
                    me.aleksilassila.litematica.printer.Reference.LOGGER.info("[Printer] 无支撑支撑: 正在切换支撑物品");
                    return SupportPlacementPlan.waiting(); // 正在切换支撑物品
                } else {
                    me.aleksilassila.litematica.printer.Reference.LOGGER.info(
                            "[Printer] 无支撑支撑: 背包中没有支撑物品 {}", BuiltInRegistries.ITEM.getKey(supportItem));
                }
            } else {
                me.aleksilassila.litematica.printer.Reference.LOGGER.info("[Printer] 无支撑支撑: 位置 {} 无可点击相邻面", supportPos);
            }
        }
        return null;
    }

    /**
     * 计算支撑候选方向：单侧面 Action（火把/墙牌/梯子/绊线钩等）→ 该侧面（目标真正需要的方向）；
     * 其余（铁砧/压力板等）→ 正下方。只测试该主方向——非支撑方向必然无效, 不做兜底迭代。
     */
    private static List<Direction> computeSupportDirections(Action action) {
        List<Direction> actionSides = new ArrayList<>(action.getSides().keySet());
        Direction primary;
        if (actionSides.size() == 1) {
            primary = actionSides.get(0); // 火把/墙牌/梯子等：支撑 = 该侧面（墙侧/下方）
        } else {
            primary = Direction.DOWN; // 铁砧/压力板等：支撑 = 正下方
        }
        return List.of(primary);
    }

    /** 是否允许凭空放置支撑：支撑位在世界高度范围内即可（凭空放置 = 直接点击空气位, 服务端对可替换目标在位放置） */
    private static boolean canAirPlace(SchematicBlockContext context, BlockPos supportPos) {
        return supportPos.getY() >= context.level.getMinY();
    }

    /** 交互方块（箱子/按钮等）需要潜行放置, 避免点开 GUI */
    private static boolean isSupportNeighborInteractive(SchematicBlockContext context, BlockPos supportPos, Direction supportSide) {
        return Implementation.isInteractive(
                context.level.getBlockState(supportPos.relative(supportSide)).getBlock())
                || Configs.Print.PRINT_FORCED_SNEAK.getBooleanValue();
    }

    /**
     * 将支撑点击排队并发送（与主流程相同的发送管线），随后返回对应的执行结果。
     * 普通支撑 = 点击支撑位相邻方块的对应面；凭空放置（airPlace）= 直接点击空气位。
     */
    private static PrintPlacementResult sendSupportPlacement(
            SchematicBlockContext context,
            Action action,
            @Nullable PrintTaskAction taskAction,
            BlockPos targetPos,
            SupportPlacementPlan plan
    ) {
        // 点击目标：凭空放置 = 直接点击空气位（ItemPlacementContext 对可替换目标在目标位放置, 与 litematica easy-place 一致）；
        // 普通 = 点击支撑位相邻方块的对应面
        BlockPos clickTarget;
        Direction clickSide;
        if (plan.airPlace()) {
            clickTarget = plan.supportPos;
            clickSide = Direction.UP;
        } else {
            clickTarget = plan.supportPos.relative(plan.supportSide);
            clickSide = plan.supportSide.getOpposite();
        }
        boolean queued = ActionManager.INSTANCE.queueClick(
                clickTarget,
                clickSide,
                Vec3.ZERO,
                plan.useShift,
                1,
                new Item[]{plan.item},
                ActionManager.ActionSource.PRINT
        );
        if (!queued) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "动作队列占用");
            return PrintPlacementResult.cancelled(true);
        }
        // 防抖 TTL：等待服务端确认期间不重复排队（tick 数可配置, 0=关闭）
        pendingSupportTargets.put(targetPos, context.level.getGameTime());
        int cooldownTicks = action.getCooldownTicksOverride() >= 0
                ? action.getCooldownTicksOverride()
                : ConfigUtils.getPlaceCooldown();
        AtomicBoolean deferred = new AtomicBoolean(false);
        ActionManager.INSTANCE.setQueueCompletionListener(sendResult -> {
            if (!deferred.get()) {
                return;
            }
            if (sendResult.isSent()) {
                if (cooldownTicks > 0) {
                    CooldownUtils.INSTANCE.setCooldown(context.level, PrintHandler.NAME, targetPos, cooldownTicks);
                }
                HudStatsManager.INSTANCE.recordRateUnit(HudStatsManager.Mode.PRINT, 1);
                HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.PRINT, "运行中");
                if (taskAction != null) {
                    taskAction.onSuccess(context, action);
                }
            } else {
                HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, describeSendFailure(sendResult));
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
                    true,
                    true,
                    PrintPlacementResult.TaskEvent.QUEUED,
                    -1
            );
        }
        if (!sendResult.isSent()) {
            if (sendResult == ActionManager.SendResult.RESERVE_LIMIT) {
                showReserveNotice(context, context.client.player.getMainHandItem());
            }
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, describeSendFailure(sendResult));
            return PrintPlacementResult.cancelled(true);
        }
        if (cooldownTicks > 0) {
            CooldownUtils.INSTANCE.setCooldown(context.level, PrintHandler.NAME, targetPos, cooldownTicks);
        }
        HudStatsManager.INSTANCE.recordRateUnit(HudStatsManager.Mode.PRINT, 1);
        HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.PRINT, "运行中");
        HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "放置支撑");
        return new PrintPlacementResult(
                true,
                shouldStopAfterTaskAction(taskAction),
                PrintPlacementResult.TaskEvent.SUCCESS,
                cooldownTicks
        );
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
