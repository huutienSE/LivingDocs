package com.livingdocs.service;

import com.livingdocs.config.SourceRequestProperties;
import com.livingdocs.exception.SourceApiException;
import com.livingdocs.service.impl.SourceRequestExecutor;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SourceRequestExecutorTests {
    @Test
    void successiveRequestsReceiveFreshBudgetsAndDoNotBindTheCallersThread() {
        var nanos = new java.util.concurrent.atomic.AtomicLong();
        try (var requests = new SourceRequestExecutor(new SourceRequestProperties(Duration.ofSeconds(1)), 2, nanos::get)) {
            Duration first = requests.execute(() -> {
                nanos.addAndGet(Duration.ofMillis(500).toNanos());
                return com.livingdocs.model.SourceRequestDeadline.limit(Duration.ofSeconds(2));
            });
            assertEquals(Duration.ofMillis(500), first);
            assertEquals(Duration.ofSeconds(1), requests.execute(() ->
                    com.livingdocs.model.SourceRequestDeadline.limit(Duration.ofSeconds(2))));
            assertNull(com.livingdocs.model.SourceRequestDeadline.remainingMillis());
        }
    }

    @Test
    void invalidTimeoutsCannotStartTheApplication() {
        assertThrows(IllegalArgumentException.class, () -> new SourceRequestProperties(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new SourceRequestProperties(Duration.ofSeconds(-1)));
    }

    @Test
    void callerStopsWaitingWhenAnOperationIgnoresInterruption() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        try (var requests = new SourceRequestExecutor(new SourceRequestProperties(Duration.ofMillis(100)), 1)) {
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                        assertThrows(SourceApiException.class, () -> requests.execute(() -> {
                            try {
                                boolean waiting = true;
                                while (waiting) {
                                    try {
                                        release.await();
                                        waiting = false;
                                    } catch (InterruptedException ignored) {
                                        // Mô phỏng I/O chưa dừng dù bên gọi đã hết thời gian.
                                    }
                                }
                                return "late result";
                            } finally {
                                finished.countDown();
                            }
                        })));
                assertThrows(SourceApiException.class, () -> requests.execute(() -> "over capacity"));
            } finally {
                release.countDown();
            }
            assertTrue(finished.await(2, java.util.concurrent.TimeUnit.SECONDS));
        }
    }
}
