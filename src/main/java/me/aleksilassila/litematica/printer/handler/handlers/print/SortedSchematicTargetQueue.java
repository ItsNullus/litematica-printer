package me.aleksilassila.litematica.printer.handler.handlers.print;

import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

public final class SortedSchematicTargetQueue {
    private final ScanEngine scanEngine;
    private final Deque<BlockPos> queue = new ArrayDeque<>();
    private List<PrinterBox> boxes = List.of();
    private boolean hasMoreSource;

    public SortedSchematicTargetQueue(ScanEngine scanEngine) {
        this.scanEngine = scanEngine;
    }

    public void clear() {
        this.queue.clear();
        this.boxes = List.of();
        this.hasMoreSource = false;
    }

    public Iterable<BlockPos> iterable(List<PrinterBox> sourceBoxes, ClientLevel level, WorldSchematic schematic, LocalPlayer player, int scanGuardLimit) {
        if (!this.boxes.equals(sourceBoxes)) {
            this.queue.clear();
        }
        this.boxes = List.copyOf(sourceBoxes);
        this.fill(sourceBoxes, level, schematic, player, scanGuardLimit);
        return this::iterator;
    }

    private void fill(List<PrinterBox> sourceBoxes, ClientLevel level, WorldSchematic schematic, LocalPlayer player, int scanGuardLimit) {
        if (!this.queue.isEmpty()) {
            return;
        }
        int collectLimit = scanGuardLimit > 0 ? scanGuardLimit : Integer.MAX_VALUE;
        Item heldItem = player.getMainHandItem().getItem();
        Vec3 eye = player.getEyePosition();
        Vec3 view = player.getLookAngle().normalize();
        List<TargetScore> targets = new ArrayList<>();
        LongSet queuedKeys = new LongOpenHashSet();
        this.hasMoreSource = false;
        Iterable<BlockPos> candidates = this.scanEngine.iterable(
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
            if (candidate == null || targets.size() >= collectLimit) {
                this.hasMoreSource = true;
                break;
            }
            if (queuedKeys.add(ScanCache.key(candidate))) {
                targets.add(scoreTarget(schematic, heldItem, eye, view, candidate));
            }
        }
        targets.sort(TargetScore.COMPARATOR);
        for (TargetScore target : targets) {
            this.queue.addLast(target.pos());
        }
    }

    private Iterator<BlockPos> iterator() {
        return new Iterator<>() {
            private boolean returnedSentinel;

            @Override
            public boolean hasNext() {
                return !queue.isEmpty() || hasMoreSource && !this.returnedSentinel;
            }

            @Override
            public BlockPos next() {
                if (!queue.isEmpty()) {
                    return queue.removeFirst();
                }
                this.returnedSentinel = true;
                return null;
            }
        };
    }

    private static TargetScore scoreTarget(
            WorldSchematic schematic,
            Item heldItem,
            Vec3 eye,
            Vec3 view,
            BlockPos pos
    ) {
        double dx = pos.getX() + 0.5D - eye.x;
        double dy = pos.getY() + 0.5D - eye.y;
        double dz = pos.getZ() + 0.5D - eye.z;
        double distanceSqr = dx * dx + dy * dy + dz * dz;
        double viewAngleScore = distanceSqr < 1.0E-6D
                ? 0.0D
                : -(view.x * dx + view.y * dy + view.z * dz) / Math.sqrt(distanceSqr);
        return new TargetScore(
                pos,
                !isHoldingRequiredItem(schematic, heldItem, pos),
                distanceSqr,
                viewAngleScore
        );
    }

    private static boolean isHoldingRequiredItem(WorldSchematic schematic, Item heldItem, BlockPos pos) {
        return schematic.getBlockState(pos).getBlock().asItem() == heldItem;
    }

    private record TargetScore(
            BlockPos pos,
            boolean heldItemMismatch,
            double distanceSqr,
            double viewAngleScore
    ) {
        private static final Comparator<TargetScore> COMPARATOR = (left, right) -> {
            int result = Boolean.compare(left.heldItemMismatch, right.heldItemMismatch);
            if (result != 0) {
                return result;
            }
            result = Double.compare(left.distanceSqr, right.distanceSqr);
            return result != 0 ? result : Double.compare(left.viewAngleScore, right.viewAngleScore);
        };
    }
}
