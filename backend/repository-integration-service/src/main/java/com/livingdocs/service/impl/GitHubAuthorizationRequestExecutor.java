package com.livingdocs.service.impl;

import com.livingdocs.exception.GitHubAuthorizationException;
import com.livingdocs.model.GitHubAuthorizationDeadline;
import com.livingdocs.model.GitHubAuthorizationError;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Holds only copied application inputs, never a ServletRequest, response or request stream. */
@Component
public class GitHubAuthorizationRequestExecutor implements AutoCloseable {
    private final Semaphore slots = new Semaphore(16);
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    public <T> T execute(long deadline, Supplier<T> operation) {
        if (deadline <= System.nanoTime() || !slots.tryAcquire()) throw unavailable();
        var task = new FutureTask<T>(() -> {
            try (var scope = GitHubAuthorizationDeadline.bind(deadline)) {
                GitHubAuthorizationDeadline.check();
                T result = operation.get();
                GitHubAuthorizationDeadline.check();
                return result;
            }
        });
        try {
            workers.execute(() -> { try { task.run(); } finally { slots.release(); } });
        } catch (RuntimeException rejected) { slots.release(); throw unavailable(); }
        try { return task.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); task.cancel(true); throw unavailable(); }
        catch (TimeoutException expired) { task.cancel(true); throw unavailable(); }
        catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failed.getCause() instanceof Error error) throw error;
            throw unavailable();
        }
    }
    private static GitHubAuthorizationException unavailable() { return new GitHubAuthorizationException(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_UNAVAILABLE); }
    @Override public void close() { workers.shutdownNow(); }
}
