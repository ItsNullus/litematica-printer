package me.aleksilassila.litematica.printer.handler;

import net.minecraft.core.BlockPos;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/** Reusable one-item look-ahead wrapper for a resumable candidate source. */
public final class PrefetchedCandidateIterable implements Iterable<BlockPos> {
    private final Supplier<Iterator<BlockPos>> sourceFactory;

    public PrefetchedCandidateIterable(Supplier<Iterator<BlockPos>> sourceFactory) {
        this.sourceFactory = sourceFactory;
    }

    @Override
    public Iterator<BlockPos> iterator() {
        return new Iterator<>() {
            private final Iterator<BlockPos> source = PrefetchedCandidateIterable.this.sourceFactory.get();
            private BlockPos next;
            private boolean prepared;

            private void prepare() {
                if (this.prepared) {
                    return;
                }
                this.prepared = true;
                if (this.source.hasNext()) {
                    this.next = this.source.next();
                }
            }

            @Override
            public boolean hasNext() {
                this.prepare();
                return this.next != null;
            }

            @Override
            public BlockPos next() {
                if (!this.hasNext()) {
                    throw new NoSuchElementException();
                }
                BlockPos result = this.next;
                this.next = null;
                this.prepared = false;
                return result;
            }
        };
    }
}
