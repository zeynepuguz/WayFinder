package com.nomi.wayfinder.photo;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small dedicated pool for the AI checks, so uploads answer at once and a slow AI service never ties up
 * web threads. Deliberately not a Spring Executor bean (that would replace Boot's default task executor).
 * A full queue drops the task: the photo stays PENDING and PhotoJobs picks it up again.
 */
@Component
public class PhotoVerificationQueue {

    private static final Logger log = LoggerFactory.getLogger(PhotoVerificationQueue.class);

    private final PhotoVerifier verifier;
    private final ThreadPoolExecutor executor;

    public PhotoVerificationQueue(PhotoVerifier verifier) {
        this.verifier = verifier;
        AtomicInteger number = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(200),
                runnable -> {
                    Thread thread = new Thread(runnable, "photo-verify-" + number.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                (runnable, pool) -> log.warn("Photo verification queue is full; the photo is retried later"));
    }

    public void submit(long photoId) {
        executor.execute(() -> {
            try {
                verifier.verify(photoId);
            } catch (Exception e) {
                log.warn("Photo {} verification failed, retried later: {}", photoId, e.getMessage());
            }
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
