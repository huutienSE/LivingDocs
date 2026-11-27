package com.livingdocs.client;

import com.livingdocs.exception.GitHubAuthorizationException;
import com.livingdocs.model.GitHubAuthorizationError;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** No queue; timed-out work retains its slot until the underlying operation stops. */
@Component
public class GitHubAuthorizationIo implements AutoCloseable {
    private final Semaphore slots = new Semaphore(16);
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public Permit acquire(GitHubAuthorizationError unavailable) {
        if (!slots.tryAcquire()) throw new GitHubAuthorizationException(unavailable);
        return new Permit();
    }

    public final class Permit implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();
        private Permit() {}
        @Override public void close() { if (released.compareAndSet(false, true)) slots.release(); }
    }

    public <T> T call(Duration budget, GitHubAuthorizationError unavailable, Supplier<T> operation) {
        long deadline = System.nanoTime() + com.livingdocs.model.GitHubAuthorizationDeadline.bound(budget);
        Permit permit = acquire(unavailable);
        var task = new FutureTask<>(operation::get);
        try {
            workers.execute(() -> {
                try { task.run(); }
                finally { permit.close(); }
            });
        } catch (RuntimeException rejected) {
            permit.close();
            throw new GitHubAuthorizationException(unavailable);
        }
        try {
            return task.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            throw new GitHubAuthorizationException(unavailable);
        } catch (TimeoutException expired) {
            task.cancel(true);
            throw new GitHubAuthorizationException(unavailable);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failed.getCause() instanceof Error error) throw error;
            throw new GitHubAuthorizationException(unavailable);
        }
    }

    @Override
    public void close() { workers.shutdownNow(); }
}
