package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.core.scan.ScanBatch;
import me.aleksilassila.litematica.printer.core.scan.ScanCoordinate;
import me.aleksilassila.litematica.printer.core.scan.ScanGeneration;
import me.aleksilassila.litematica.printer.core.scan.ScanHandle;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Asynchronously prefetches the pure player-distance coordinate stream. */
final class AsyncPositionCursor implements PositionCursor {
    private static final int QUEUE_CAPACITY = 8;
    private static final int REFILL_THRESHOLD = 2;
    private static final int PRODUCE_BATCH = 256;

    private final AsyncPositionCursorScheduler scheduler;
    private final PlayerDistanceCursor delegate;
    private final ScanHandle handle;
    private final ArrayBlockingQueue<ScanBatch> ready = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean fillScheduled = new AtomicBoolean();
    private ScanBatch currentBatch;
    private int currentBatchIndex;
    private volatile boolean producerExhausted;
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
        this(
                scheduler,
                boxes,
                centerX,
                centerY,
                centerZ,
                maxDistanceBand,
                new ScanHandle(new ScanGeneration(RuntimeEpoch.INITIAL, 0L, 0L, 0L))
        );
    }

    AsyncPositionCursor(
            AsyncPositionCursorScheduler scheduler,
            List<PrinterBox> boxes,
            int centerX,
            int centerY,
            int centerZ,
            int maxDistanceBand,
            ScanHandle handle
    ) {
        this.scheduler = scheduler;
        this.delegate = new PlayerDistanceCursor(List.copyOf(boxes), centerX, centerY, centerZ, maxDistanceBand);
        this.handle = handle;
        this.requestFill();
    }

    @Override
    public PollResult poll(BlockPos.MutableBlockPos target) {
        if (this.closed || this.handle.isCancelled()) {
            return PollResult.COMPLETE;
        }
        ScanCoordinate coordinate = this.pollCoordinate();
        if (coordinate != null) {
            target.set(coordinate.x(), coordinate.y(), coordinate.z());
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
        ScanCoordinate coordinate = this.peekCoordinate();
        return coordinate == null ? Long.MAX_VALUE : coordinate.distanceSqr();
    }

    @Override
    public boolean isComplete() {
        return this.producerComplete && this.currentBatch == null && this.ready.isEmpty();
    }

    @Override
    public void close() {
        this.closed = true;
        this.handle.close();
        this.currentBatch = null;
        this.ready.clear();
    }

    private void requestFill() {
        if (this.closed || this.handle.isCancelled() || this.failed || this.producerExhausted
                || !this.fillScheduled.compareAndSet(false, true)) {
            return;
        }
        if (!this.scheduler.execute(this::produce)) {
            this.fillScheduled.set(false);
            this.failed = true;
        }
    }

    private void produce() {
        try {
            if (this.closed || this.handle.isCancelled()) {
                return;
            }
            BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
            List<ScanCoordinate> batch = new ArrayList<>(PRODUCE_BATCH);
            int produced = 0;
            boolean complete = false;
            while (!this.closed && !this.handle.isCancelled() && produced < PRODUCE_BATCH) {
                long distanceSqr = this.delegate.peekDistanceSqr();
                if (!this.delegate.next(position)) {
                    this.producerExhausted = true;
                    complete = true;
                    break;
                }
                batch.add(new ScanCoordinate(
                        position.getX(),
                        position.getY(),
                        position.getZ(),
                        distanceSqr
                ));
                produced++;
            }
            if ((!batch.isEmpty() || complete) && !this.handle.isCancelled()) {
                this.ready.offer(new ScanBatch(this.handle.generation(), batch, complete));
            }
        } catch (Throwable throwable) {
            this.failed = true;
        } finally {
            this.fillScheduled.set(false);
            if (!this.closed && !this.handle.isCancelled() && !this.failed
                    && !this.producerExhausted && this.ready.remainingCapacity() > 0) {
                this.requestFill();
            }
        }
    }

    private ScanCoordinate pollCoordinate() {
        ScanCoordinate coordinate = this.peekCoordinate();
        if (coordinate != null) {
            this.currentBatchIndex++;
            if (this.currentBatchIndex >= this.currentBatch.coordinates().size()) {
                this.currentBatch = null;
                this.currentBatchIndex = 0;
            }
        }
        return coordinate;
    }

    private ScanCoordinate peekCoordinate() {
        while (this.currentBatch == null) {
            ScanBatch batch = this.ready.poll();
            if (batch == null) {
                return null;
            }
            if (!this.handle.accepts(batch.generation())) {
                continue;
            }
            if (batch.complete()) {
                this.producerComplete = true;
            }
            if (batch.coordinates().isEmpty()) {
                continue;
            }
            this.currentBatch = batch;
            this.currentBatchIndex = 0;
        }
        return this.currentBatch.coordinates().get(this.currentBatchIndex);
    }
}
