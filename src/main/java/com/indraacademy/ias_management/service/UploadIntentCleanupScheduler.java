package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.UploadIntent;
import com.indraacademy.ias_management.repository.UploadIntentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The orphan strategy for Phase 1 direct uploads: a client receives a presigned URL, uploads
 * successfully, then never calls /api/files/complete (browser closed, network dropped) — the
 * object now exists in storage with nothing in PostgreSQL referencing it. Rather than a
 * temporary-prefix-plus-promote scheme (real complexity for a low-volume, low-risk feature) or
 * any high-frequency polling, this is a single rare (daily by default) sweep of PENDING
 * {@link UploadIntent} rows past their expiry: each one's object is deleted (harmless no-op if
 * it was never actually uploaded) and the intent is marked EXPIRED. Proportionate to Phase 1's
 * actual volume (one teacher-photo purpose, ADMIN-only) — revisit only if a future phase's
 * volume genuinely demands faster reclaim.
 */
@Service
public class UploadIntentCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(UploadIntentCleanupScheduler.class);

    @Autowired private UploadIntentRepository uploadIntentRepository;
    @Autowired private ObjectStorageService objectStorageService;

    @Value("${object-storage.orphan-cleanup-grace-minutes:60}")
    private long orphanCleanupGraceMinutes;

    @Value("${object-storage.orphan-cleanup-batch-size:100}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${object-storage.orphan-cleanup-interval-ms:86400000}")
    public void sweepOrphanedUploads() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(orphanCleanupGraceMinutes);
        List<UploadIntent> candidates = uploadIntentRepository.findOrphanCandidates(cutoff, PageRequest.of(0, Math.max(1, batchSize)));
        if (candidates.isEmpty()) {
            return;
        }

        log.info("Upload intent cleanup: {} orphaned/expired PENDING intent(s) to reclaim.", candidates.size());
        for (UploadIntent intent : candidates) {
            try {
                objectStorageService.deleteObjectQuietly(intent.getObjectKey());
                intent.setStatus(UploadIntent.STATUS_EXPIRED);
                uploadIntentRepository.save(intent);
            } catch (Exception e) {
                // One bad row must never abort the rest of the sweep.
                log.error("Upload intent cleanup: failed to reclaim intentId={} objectKey={} — will retry on a later sweep.",
                        intent.getId(), intent.getObjectKey(), e);
            }
        }
    }
}
