package com.livingdocs.service.impl;

import com.livingdocs.config.SourceRequestProperties;
import com.livingdocs.exception.SourceApiException;
import com.livingdocs.exception.SourceApiException.UnavailableReason;
import com.livingdocs.model.SourceRequestDeadline;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** Giới hạn thời gian bên gọi và số I/O chưa dừng sau timeout. */
@Component
@EnableConfigurationProperties(SourceRequestProperties.class)
public class SourceRequestExecutor implements AutoCloseable {
    private final SourceRequestProperties properties;
    private final Semaphore slots;
    private final LongSupplier ticker;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public SourceRequestExecutor(SourceRequestProperties properties,
                                 @Value("${spring.datasource.hikari.maximum-pool-size:10}") int maxConcurrent) {
        this(properties, maxConcurrent, System::nanoTime);
    }

    public SourceRequestExecutor(SourceRequestProperties properties, int maxConcurrent, LongSupplier ticker) {
        if (maxConcurrent <= 0) {
            throw new IllegalArgumentException("Source request concurrency limit must be positive");
        }
        this.properties = properties;
        this.slots = new Semaphore(maxConcurrent);
        this.ticker = ticker;
    }

    public <T> T execute(Supplier<T> operation) {
        if (!slots.tryAcquire()) {
            throw unavailable();
        }
        var deadline = new SourceRequestDeadline(properties.getRequestTimeout(), ticker);
        var task = new FutureTask<T>(() -> {
            try (deadline) {
                deadline.bind();
                SourceRequestDeadline.check();
                T result = operation.get();
                SourceRequestDeadline.check();
                return result;
            }
        });
        try {
            workers.execute(() -> {
                try {
                    task.run();
                } finally {
                    slots.release();
                }
            });
        } catch (RuntimeException rejected) {
            slots.release();
            throw unavailable();
        }
        try {
            return task.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            throw unavailable();
        } catch (TimeoutException | SourceApiException expired) {
            task.cancel(true);
            throw unavailable();
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failed.getCause() instanceof Error error) {
                throw error;
            }
            throw unavailable();
        }
    }

    private static SourceApiException unavailable() {
        return SourceApiException.unavailable(UnavailableReason.REQUEST_BUDGET_UNAVAILABLE);
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }
}
