package com.livingdocs.model;

import com.livingdocs.exception.SourceApiException;
import com.livingdocs.exception.SourceApiException.UnavailableReason;
import java.time.Duration;
import java.util.function.LongSupplier;

/** Ngữ cảnh đồng bộ của một lượt đọc nguồn, tách khỏi đồng hồ UTC của bằng chứng. */
public final class SourceRequestDeadline implements AutoCloseable {
    private static final ThreadLocal<SourceRequestDeadline> CURRENT = new ThreadLocal<>();
    private final long started;
    private final long budgetNanos;
    private final LongSupplier ticker;

    public SourceRequestDeadline(Duration timeout, LongSupplier ticker) {
        this.ticker = ticker;
        this.started = ticker.getAsLong();
        this.budgetNanos = timeout.toNanos();
    }

    public void bind() {
        CURRENT.set(this);
    }

    public long remainingNanos() {
        long remaining = budgetNanos - (ticker.getAsLong() - started);
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
            throw expired();
        }
        return remaining;
    }

    public static void check() {
        SourceRequestDeadline deadline = CURRENT.get();
        if (deadline != null) {
            deadline.remainingNanos();
        }
    }

    public static Duration limit(Duration maximum) {
        SourceRequestDeadline deadline = CURRENT.get();
        if (deadline == null) {
            return maximum;
        }
        return Duration.ofNanos(Math.min(maximum.toNanos(), deadline.remainingNanos()));
    }

    public static Long remainingMillis() {
        SourceRequestDeadline deadline = CURRENT.get();
        if (deadline == null) {
            return null;
        }
        return Math.ceilDiv(deadline.remainingNanos(), 1_000_000);
    }

    private static SourceApiException expired() {
        return SourceApiException.unavailable(UnavailableReason.REQUEST_DEADLINE_EXCEEDED);
    }

    @Override
    public void close() {
        CURRENT.remove();
    }
}
