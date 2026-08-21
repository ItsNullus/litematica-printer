package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Asynchronously prefetches the pure player-distance coordinate stream. */
final class AsyncPositionCursor implements PositionCursor {
    private static final int QUEUE_CAPACITY = 2048;
    private static final int REFILL_THRESHOLD = 512;
    private static final int PRODUCE_BATCH = 256;

    private final AsyncPositionCursorScheduler scheduler;
    private final PlayerDistanceCursor delegate;
    private final ArrayBlockingQueue<Coordinate> ready = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean fillScheduled = new AtomicBoolean();
    private volatile boolean producerComplete;
    private volatile boolean failed;
    private volatile boolean closed;

    AsyncPositionCursor(
            AsyncPositionCursorScheduler scheduler,
            List<PrinterBox> boxes,
            int centerX,
            int centerY,
            int centerZ,
            int maxDistanceBand
    ) {
        this.scheduler = scheduler;
        this.delegate = new PlayerDistanceCursor(List.copyOf(boxes), centerX, centerY, centerZ, maxDistanceBand);
        this.requestFill();
    }

    @Override
    public PollResult poll(BlockPos.MutableBlockPos target) {
        Coordinate coordinate = this.ready.poll();
        if (coordinate != null) {
            target.set(coordinate.x, coordinate.y, coordinate.z);
            if (this.ready.size() <= REFILL_THRESHOLD) {
                this.requestFill();
            }
            return PollResult.AVAILABLE;
        }
        if (this.failed) {
            return PollResult.FAILED;
        }
        if (this.producerComplete) {
            return PollResult.COMPLETE;
        }
        this.requestFill();
        return PollResult.PENDING;
    }

    @Override
    public long peekDistanceSqr() {
        Coordinate coordinate = this.ready.peek();
        return coordinate == null ? Long.MAX_VALUE : coordinate.distanceSqr;
    }

    @Override
    public boolean isComplete() {
        return this.producerComplete && this.ready.isEmpty();
    }

    @Override
    public void close() {
        this.closed = true;
        this.ready.clear();
    }

    private void requestFill() {
        if (this.closed || this.failed || this.producerComplete || !this.fillScheduled.compareAndSet(false, true)) {
            return;
        }
        if (!this.scheduler.execute(this::produce)) {
            this.fillScheduled.set(false);
            this.failed = true;
        }
    }

    private void produce() {
        try {
            if (this.closed) {
                return;
            }
            BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
            int produced = 0;
            while (!this.closed && produced < PRODUCE_BATCH && this.ready.remainingCapacity() > 0) {
                long distanceSqr = this.delegate.peekDistanceSqr();
                if (!this.delegate.next(position)) {
                    this.producerComplete = true;
                    break;
                }
                if (!this.ready.offer(new Coordinate(
                        position.getX(),
                        position.getY(),
                        position.getZ(),
                        distanceSqr
                ))) {
                    break;
                }
                produced++;
            }
        } catch (Throwable throwable) {
            this.failed = true;
        } finally {
            this.fillScheduled.set(false);
            if (!this.closed && !this.failed && !this.producerComplete && this.ready.remainingCapacity() > 0) {
                this.requestFill();
            }
        }
    }

    private record Coordinate(int x, int y, int z, long distanceSqr) {
    }
}
