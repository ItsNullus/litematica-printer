package me.aleksilassila.litematica.printer.handler.scan;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;

/**
 * One bounded daemon worker shared by all scan cursors.
 *
 * <p>The worker only traverses immutable box coordinates. It never receives a
 * world, schematic, player, block state or network object.</p>
 */
final class AsyncPositionCursorScheduler implements AutoCloseable {
    private final ExecutorService executor;
    private volatile boolean closed;

    AsyncPositionCursorScheduler() {
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "litematica-printer-scan");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, throwable) -> throwable.printStackTrace());
            return thread;
        };
        this.executor = Executors.newSingleThreadExecutor(threadFactory);
    }

    boolean execute(Runnable task) {
        if (this.closed) {
            return false;
        }
        try {
            this.executor.execute(task);
            return true;
        } catch (RejectedExecutionException exception) {
            return false;
        }
    }

    @Override
    public void close() {
        this.closed = true;
        this.executor.shutdownNow();
    }
}
