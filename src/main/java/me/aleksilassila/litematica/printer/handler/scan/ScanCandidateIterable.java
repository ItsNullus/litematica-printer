package me.aleksilassila.litematica.printer.handler.scan;

import net.minecraft.core.BlockPos;

/**
 * Candidate stream with an explicit lifecycle status.
 *
 * <p>The old implementation encoded a paused asynchronous scan as a {@code null}
 * element. That made every consumer treat worker back-pressure as a feature
 * interruption. Consumers can now finish the currently available batch and inspect
 * {@link #availability()} without confusing waiting with completion.</p>
 */
public interface ScanCandidateIterable extends Iterable<BlockPos> {
    ScanAvailability availability();
}
