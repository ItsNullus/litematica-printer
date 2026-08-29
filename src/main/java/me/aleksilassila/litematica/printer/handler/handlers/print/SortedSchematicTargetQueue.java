package me.aleksilassila.litematica.printer.handler.handlers.print;

import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按材料分桶的打印目标队列。
 *
 * 旧实现把全部候选一次性排序成单一队列，"手头材料优先"只在构建队列那一刻按当时的主手物品
 * 求值一次——主手切到新材料后队列并不重排，超大项目里新材料的方块可能排在几万位之后，
 * 期间逐个尝试缺料位置，表现为"长时间不执行操作但其实有材料可用"。
 *
 * 分桶后：
 * - 桶按 {@code block.asItem()} 分组，桶内保留自底向上/距离/视线排序；
 * - 每次迭代动态决定桶序：主手材料桶 → 背包有料的桶（按桶首距离）→ 缺料桶（按桶首距离）；
 * - 主手切换后下一 tick 立即换桶，O(1)，无需全量重排。
 *
 * 已放置位置清理（避免"扫描浪费"导致大项目越来越慢/看似卡住）：
 * - 迭代到位置时，若世界方块已等于投影方块（已建成）或正等待服务端确认（刚放置），
 *   直接从桶中移除并跳过——每个已建位置只被扫描一次，而不是每 tick 从桶头重扫；
 * - 缺支撑/缺材料等临时性拒绝的位置保留在桶中，下个迭代自然重试，
 *   支撑建成或材料补足后立即可以放置，不会因出队而丢失；
 * - 桶随消费自然收缩，全部清空后从 ScanCache 重新收集（ScanCache 本身排除已建位置）。
 */
public final class SortedSchematicTargetQueue {
    private final Map<Item, Deque<BlockPos>> buckets = new HashMap<>();
    private List<PrinterBox> boxes = List.of();
    private boolean hasMoreSource;

    /** iterable 每 tick 传入，iterator 构建桶序时使用（主手/背包每 tick 变化） */
    private LocalPlayer lastPlayer;
    /** 用于判断"已建成/等待确认"的世界与投影引用 */
    private ClientLevel lastLevel;
    private WorldSchematic lastSchematic;

    public void clear() {
        this.buckets.clear();
        this.boxes = List.of();
        this.hasMoreSource = false;
    }

    public Iterable<BlockPos> iterable(List<PrinterBox> sourceBoxes, ClientLevel level, WorldSchematic schematic, LocalPlayer player, int scanGuardLimit) {
        return this.iterable(sourceBoxes, level, schematic, player, scanGuardLimit, Configs.Placement.PLACE_BLOCKS_PER_TICK.getIntegerValue());
    }

    /**
     * 带显式预算参数的 iterable（maxBlocksPerTick 仅用于兼容签名；排序队列本身按
     * 动态桶序 + 已放置清理推进，每 tick 的扫描量由迭代循环的时间预算约束）。
     */
    public Iterable<BlockPos> iterable(List<PrinterBox> sourceBoxes, ClientLevel level, WorldSchematic schematic, LocalPlayer player, int scanGuardLimit, int maxBlocksPerTick) {
        if (!this.boxes.equals(sourceBoxes)) {
            this.buckets.clear();
        }
        this.boxes = List.copyOf(sourceBoxes);
        this.lastPlayer = player;
        this.lastLevel = level;
        this.lastSchematic = schematic;
        this.fill(sourceBoxes, level, schematic, player, scanGuardLimit);
        return this::iterator;
    }

    private void fill(List<PrinterBox> sourceBoxes, ClientLevel level, WorldSchematic schematic, LocalPlayer player, int scanGuardLimit) {
        if (!this.buckets.isEmpty()) {
            return;
        }
        int collectLimit = scanGuardLimit > 0 ? scanGuardLimit : Integer.MAX_VALUE;
        List<BlockPos> positions = new ArrayList<>();
        Set<Long> queuedKeys = new HashSet<>();
        this.hasMoreSource = false;
        Iterable<BlockPos> candidates = ScanCache.INSTANCE.iterable(
                "print_sorted",
                sourceBoxes,
                level,
                schematic,
                player,
                scanGuardLimit,
                ScanIntent.PRINT,
                pos -> true
        );
        for (BlockPos candidate : candidates) {
            if (candidate == null || positions.size() >= collectLimit) {
                this.hasMoreSource = true;
                break;
            }
            if (queuedKeys.add(ScanCache.key(candidate))) {
                positions.add(candidate);
            }
        }
        // 按材料分桶；桶内一次性排序（此后桶序动态决定，不再全量重排）
        Map<Item, List<BlockPos>> grouped = new HashMap<>();
        for (BlockPos pos : positions) {
            Item item = schematic.getBlockState(pos).getBlock().asItem();
            grouped.computeIfAbsent(item, k -> new ArrayList<>()).add(pos);
        }
        Comparator<BlockPos> inBucket = createInBucketComparator(player);
        for (Map.Entry<Item, List<BlockPos>> entry : grouped.entrySet()) {
            entry.getValue().sort(inBucket);
            this.buckets.put(entry.getKey(), new ArrayDeque<>(entry.getValue()));
        }
    }

    private static Comparator<BlockPos> createInBucketComparator(LocalPlayer player) {
        if (player == null) {
            return Comparator.comparingInt(BlockPos::getY);
        }
        Vec3 eye = player.getEyePosition();
        Vec3 view = player.getLookAngle().normalize();
        Comparator<BlockPos> comparator;
        if (Configs.Print.PRINT_BOTTOM_UP.getBooleanValue()) {
            comparator = Comparator.<BlockPos>comparingInt(BlockPos::getY)
                    .thenComparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(eye));
        } else {
            comparator = Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(eye));
        }
        return comparator.thenComparingDouble(pos -> getViewAngleScore(eye, view, pos));
    }

    private static double getViewAngleScore(Vec3 eye, Vec3 view, BlockPos pos) {
        Vec3 toTarget = Vec3.atCenterOf(pos).subtract(eye);
        if (toTarget.lengthSqr() < 1.0E-6D) {
            return 0.0D;
        }
        return -view.dot(toTarget.normalize());
    }

    /** 每 tick 动态桶序：主手材料 → 背包有料 → 缺料；同级按桶首距离 */
    private List<Deque<BlockPos>> buildBucketOrder(LocalPlayer player) {
        if (player == null) {
            return new ArrayList<>(this.buckets.values());
        }
        Item held = player.getMainHandItem().getItem();
        Set<Item> carriedItems = new HashSet<>();
        Inventory inventory = player.getInventory();
        int size = Math.min(36, inventory.getContainerSize());
        for (int i = 0; i < size; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                carriedItems.add(stack.getItem());
            }
        }
        Vec3 eye = player.getEyePosition();
        Comparator<Deque<BlockPos>> byFirstPos = Comparator.comparingDouble(
                deque -> Vec3.atCenterOf(deque.peekFirst()).distanceToSqr(eye));
        List<Deque<BlockPos>> order = new ArrayList<>(this.buckets.size());
        Deque<BlockPos> heldBucket = this.buckets.get(held);
        if (heldBucket != null && !heldBucket.isEmpty()) {
            order.add(heldBucket);
        }
        List<Deque<BlockPos>> withMaterial = new ArrayList<>();
        List<Deque<BlockPos>> missing = new ArrayList<>();
        for (Map.Entry<Item, Deque<BlockPos>> entry : this.buckets.entrySet()) {
            Deque<BlockPos> deque = entry.getValue();
            if (deque.isEmpty() || entry.getKey() == held) {
                continue;
            }
            (carriedItems.contains(entry.getKey()) ? withMaterial : missing).add(deque);
        }
        withMaterial.sort(byFirstPos);
        missing.sort(byFirstPos);
        order.addAll(withMaterial);
        order.addAll(missing);
        return order;
    }

    /**
     * 位置是否已"解决"（不再需要放置）：
     * - 正在等待服务端确认（刚放置）→ 移除；
     * - 世界方块已等于投影方块（已建成）→ 移除。
     * 缺支撑/缺材料等临时拒绝不在此列，保留重试。
     */
    private boolean isResolved(BlockPos pos) {
        if (HudStatsManager.INSTANCE.isPrintPlacementPending(pos)) {
            return true;
        }
        if (this.lastLevel != null && this.lastSchematic != null) {
            return this.lastLevel.getBlockState(pos).equals(this.lastSchematic.getBlockState(pos));
        }
        return false;
    }

    private Iterator<BlockPos> iterator() {
        Iterator<Deque<BlockPos>> bucketIterator = buildBucketOrder(this.lastPlayer).iterator();
        return new Iterator<>() {
            private Iterator<BlockPos> current = Collections.emptyIterator();
            private BlockPos nextPos = advance();
            private boolean returnedSentinel;

            private BlockPos advance() {
                while (true) {
                    if (this.current.hasNext()) {
                        BlockPos pos = this.current.next();
                        // 已放置/等待确认的位置：从桶中移除（每个已建位置只扫描一次），继续前进
                        if (SortedSchematicTargetQueue.this.isResolved(pos)) {
                            this.current.remove();
                            continue;
                        }
                        return pos;
                    }
                    if (!bucketIterator.hasNext()) {
                        return null;
                    }
                    this.current = bucketIterator.next().iterator();
                }
            }

            @Override
            public boolean hasNext() {
                return this.nextPos != null || SortedSchematicTargetQueue.this.hasMoreSource && !this.returnedSentinel;
            }

            @Override
            public BlockPos next() {
                BlockPos pos = this.nextPos;
                if (pos == null) {
                    this.returnedSentinel = true;
                    return null;
                }
                this.nextPos = advance();
                return pos;
            }
        };
    }
}