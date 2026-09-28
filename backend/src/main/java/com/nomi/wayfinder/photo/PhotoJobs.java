package com.nomi.wayfinder.photo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Background work for user photos:
 * - every few minutes: photos still PENDING after 10 minutes go through the AI check again
 *   (the AI service was down, the queue was full, the backend restarted)
 * - nightly: files of rejected photos are deleted after 7 days, their rows after 30 days,
 *   and files no row points to any more are removed
 */
@Component
public class PhotoJobs {

    private static final Logger log = LoggerFactory.getLogger(PhotoJobs.class);

    static final Duration RETRY_AFTER = Duration.ofMinutes(10);
    static final int RETRY_BATCH = 50;
    static final Duration REJECTED_FILES_KEPT = Duration.ofDays(7);
    static final Duration REJECTED_ROWS_KEPT = Duration.ofDays(30);
    static final Duration ORPHAN_MIN_AGE = Duration.ofDays(1);

    private final UserPhotoRepository repository;
    private final PhotoStorage storage;
    private final PhotoVerificationQueue queue;
    private final PhotoAiClient ai;
    private final Clock clock;

    public PhotoJobs(UserPhotoRepository repository, PhotoStorage storage, PhotoVerificationQueue queue,
                     PhotoAiClient ai, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.queue = queue;
        this.ai = ai;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${nomi.photos.retry-interval:PT5M}",
            initialDelayString = "${nomi.photos.retry-interval:PT5M}")
    public void retryPending() {
        if (!ai.isEnabled()) {
            return;
        }
        List<Long> pending = repository.pendingCreatedBefore(clock.instant().minus(RETRY_AFTER), RETRY_BATCH);
        if (!pending.isEmpty()) {
            log.info("Retrying the AI check of {} pending photo(s)", pending.size());
            pending.forEach(queue::submit);
        }
    }

    @Scheduled(cron = "${nomi.photos.cleanup-cron:0 40 3 * * *}", zone = "${nomi.timezone:Europe/Istanbul}")
    public void cleanup() {
        Instant now = clock.instant();
        int files = 0;
        for (UserPhotoRepository.StoredFile file : repository.rejectedWithFilesReviewedBefore(now.minus(REJECTED_FILES_KEPT))) {
            storage.delete(file.fileKey());
            repository.clearFileKey(file.id());
            files++;
        }
        List<String> leftovers = repository.deleteRejectedReviewedBefore(now.minus(REJECTED_ROWS_KEPT));
        leftovers.forEach(storage::delete);
        int orphans = storage.deleteOrphans(repository.allFileKeys(), now.minus(ORPHAN_MIN_AGE));
        if (files + leftovers.size() + orphans > 0) {
            log.info("Photo cleanup: files of {} rejected photo(s), {} old row(s), {} orphan file(s) removed",
                    files, leftovers.size(), orphans);
        }
    }
}
