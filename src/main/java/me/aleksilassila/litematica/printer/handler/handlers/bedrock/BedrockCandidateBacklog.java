package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * Bounded retention for modeled bedrock candidates that have not been submitted yet.
 *
 * <p>The scan cursor may model substantially more positions than the controller can admit in one
 * tick. Keeping those candidates here prevents a completed scan pass from becoming an implicit
 * queue that has to circle the entire interaction range before a nearby target is reconsidered.</p>
 */
final class BedrockCandidateBacklog<T> {
    private final int capacity;
    private final LinkedHashMap<BlockPos, T> entries = new LinkedHashMap<>();

    BedrockCandidateBacklog(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    boolean offer(BlockPos pos, T candidate) {
        if (pos == null || candidate == null) {
            return false;
        }
        BlockPos stablePos = pos.immutable();
        if (this.entries.containsKey(stablePos)) {
            this.entries.put(stablePos, candidate);
            return false;
        }
        if (this.entries.size() >= this.capacity) {
            return false;
        }
        this.entries.put(stablePos, candidate);
        return true;
    }

    boolean contains(BlockPos pos) {
        return pos != null && this.entries.containsKey(pos);
    }

    void remove(BlockPos pos) {
        if (pos != null) {
            this.entries.remove(pos);
        }
    }

    void removeIf(BiPredicate<BlockPos, T> predicate) {
        this.entries.entrySet().removeIf(entry -> predicate.test(entry.getKey(), entry.getValue()));
    }

    List<T> snapshot() {
        return List.copyOf(this.entries.values());
    }

    int remainingCapacity() {
        return Math.max(0, this.capacity - this.entries.size());
    }

    int size() {
        return this.entries.size();
    }

    boolean isEmpty() {
        return this.entries.isEmpty();
    }

    void clear() {
        this.entries.clear();
    }
}
