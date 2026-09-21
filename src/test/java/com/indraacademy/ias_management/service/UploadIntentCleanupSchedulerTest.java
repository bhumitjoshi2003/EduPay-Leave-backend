package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.UploadIntent;
import com.indraacademy.ias_management.repository.UploadIntentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UploadIntentCleanupSchedulerTest {

    @Mock private UploadIntentRepository uploadIntentRepository;
    @Mock private ObjectStorageService objectStorageService;

    private UploadIntentCleanupScheduler scheduler;

    private UploadIntent pending(Long id, String objectKey) {
        UploadIntent intent = new UploadIntent();
        intent.setId(id);
        intent.setSchoolId(1L);
        intent.setObjectKey(objectKey);
        intent.setStatus(UploadIntent.STATUS_PENDING);
        intent.setExpiresAt(LocalDateTime.now().minusHours(2));
        return intent;
    }

    @BeforeEach
    void setUp() {
        scheduler = new UploadIntentCleanupScheduler();
        ReflectionTestUtils.setField(scheduler, "uploadIntentRepository", uploadIntentRepository);
        ReflectionTestUtils.setField(scheduler, "objectStorageService", objectStorageService);
        ReflectionTestUtils.setField(scheduler, "orphanCleanupGraceMinutes", 60L);
        ReflectionTestUtils.setField(scheduler, "batchSize", 100);
    }

    @Test
    void noCandidates_doesNothing() {
        when(uploadIntentRepository.findOrphanCandidates(any(), any(Pageable.class))).thenReturn(List.of());

        scheduler.sweepOrphanedUploads();

        verify(objectStorageService, never()).deleteObjectQuietly(anyString());
        verify(uploadIntentRepository, never()).save(any());
    }

    @Test
    void orphanedIntent_deletedFromStorageAndMarkedExpired() {
        UploadIntent intent = pending(1L, "schools/1/teachers/T1/profile/x.jpg");
        when(uploadIntentRepository.findOrphanCandidates(any(), any(Pageable.class))).thenReturn(List.of(intent));

        scheduler.sweepOrphanedUploads();

        verify(objectStorageService).deleteObjectQuietly("schools/1/teachers/T1/profile/x.jpg");
        ArgumentCaptor<UploadIntent> captor = ArgumentCaptor.forClass(UploadIntent.class);
        verify(uploadIntentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(UploadIntent.STATUS_EXPIRED);
    }

    @Test
    void oneRowFailure_doesNotAbortTheRestOfTheSweep() {
        UploadIntent bad = pending(1L, "schools/1/teachers/T1/profile/bad.jpg");
        UploadIntent good = pending(2L, "schools/1/teachers/T2/profile/good.jpg");
        when(uploadIntentRepository.findOrphanCandidates(any(), any(Pageable.class))).thenReturn(List.of(bad, good));
        doThrow(new RuntimeException("storage down")).when(objectStorageService)
                .deleteObjectQuietly("schools/1/teachers/T1/profile/bad.jpg");

        scheduler.sweepOrphanedUploads();

        verify(objectStorageService).deleteObjectQuietly("schools/1/teachers/T2/profile/good.jpg");
        verify(uploadIntentRepository, times(1)).save(good); // bad row's save never reached
    }

    @Test
    void usesConfiguredGracePeriodForTheCutoff() {
        ReflectionTestUtils.setField(scheduler, "orphanCleanupGraceMinutes", 120L);
        when(uploadIntentRepository.findOrphanCandidates(any(), any(Pageable.class))).thenReturn(List.of());

        scheduler.sweepOrphanedUploads();

        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(uploadIntentRepository).findOrphanCandidates(cutoffCaptor.capture(), any(Pageable.class));
        assertThat(cutoffCaptor.getValue()).isBefore(LocalDateTime.now().minusMinutes(119));
    }
}
