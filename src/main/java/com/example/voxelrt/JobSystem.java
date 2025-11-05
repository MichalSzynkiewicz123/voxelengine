package com.example.voxelrt;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight job system backed by a fixed-size worker pool.
 * <p>
 * The scheduler exposes a simple task queue API that accepts {@link Callable} jobs and returns
 * {@link CompletableFuture} handles which can be used to observe completion on the main thread.
 * Tasks are executed on a dedicated set of daemon workers so that long running or blocking work can
 * be moved off the render thread.
 */
public final class JobSystem implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final BlockingQueue<Runnable> queue;

    public JobSystem(String threadNamePrefix, int workerCount) {
        if (workerCount <= 0) {
            throw new IllegalArgumentException("workerCount must be positive");
        }
        Objects.requireNonNull(threadNamePrefix, "threadNamePrefix");
        this.queue = new LinkedBlockingQueue<>();
        AtomicInteger ctr = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, threadNamePrefix + ctr.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.executor = new ThreadPoolExecutor(workerCount, workerCount,
                0L, TimeUnit.MILLISECONDS, queue, factory, new ThreadPoolExecutor.AbortPolicy());
        this.executor.prestartAllCoreThreads();
    }

    public <T> CompletableFuture<T> submit(Callable<T> job) {
        Objects.requireNonNull(job, "job");
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(job.call());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException ex) {
            future.completeExceptionally(ex);
        }
        return future;
    }

    public CompletableFuture<Void> submit(Runnable job) {
        Objects.requireNonNull(job, "job");
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    job.run();
                    future.complete(null);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException ex) {
            future.completeExceptionally(ex);
        }
        return future;
    }

    public int queuedTaskCount() {
        return queue.size();
    }

    public int workerCount() {
        return executor.getCorePoolSize();
    }

    public boolean awaitQuiescence(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        executor.shutdown();
        return executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
