package me.aleksilassila.litematica.printer.handler.handlers.print;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiPredicate;

/** Tracks in-flight falling placements without blocking unrelated columns. */
public final class FallingPlacementTracker {
    private static final int CONFIRM_TIMEOUT_TICKS = 80;
    private final Map<BlockPos, Pending> pending = new LinkedHashMap<>();

    public void clear() {
        this.pending.clear();
    }

    public void mark(BlockPos pos, BlockState expectedState, long currentTick) {
        this.pending.put(
                pos.immutable(),
                new Pending(pos.immutable(), expectedState, currentTick + CONFIRM_TIMEOUT_TICKS)
        );
    }

    public boolean blocks(
            BlockPos target,
            long currentTick,
            boolean enforceColumnOrder,
            BiPredicate<BlockPos, BlockState> stateMatches
    ) {
        Iterator<Pending> iterator = this.pending.values().iterator();
        while (iterator.hasNext()) {
            Pending entry = iterator.next();
            if (stateMatches.test(entry.pos, entry.expectedState) || currentTick > entry.expireTick) {
                iterator.remove();
            }
        }
        if (this.pending.containsKey(target)) return true;
        if (!enforceColumnOrder) return false;
        for (Pending entry : this.pending.values()) {
            if (entry.pos.getX() == target.getX()
                    && entry.pos.getZ() == target.getZ()
                    && entry.pos.getY() < target.getY()) {
                return true;
            }
        }
        return false;
    }

    private record Pending(BlockPos pos, BlockState expectedState, long expireTick) {
    }
}
