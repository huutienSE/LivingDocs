package com.livingdocs.model;

import com.livingdocs.exception.GitHubAuthorizationException;
import java.time.Duration;

/** Request-local monotonic budget. Infrastructure calls may shorten, never restart it. */
public final class GitHubAuthorizationDeadline {
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();
    private GitHubAuthorizationDeadline() {}
    public static Scope bind(Long deadline) {
        Long previous = CURRENT.get();
        if (deadline == null) CURRENT.remove(); else CURRENT.set(deadline);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    public static void check() {
        Long deadline = CURRENT.get();
        if (deadline != null && (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline))
            throw new GitHubAuthorizationException(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_UNAVAILABLE);
    }
    public static long bound(Duration budget) {
        check(); Long deadline = CURRENT.get();
        return deadline == null ? budget.toNanos() : Math.min(budget.toNanos(), Math.max(0, deadline - System.nanoTime()));
    }
    public interface Scope extends AutoCloseable { @Override void close(); }
}
