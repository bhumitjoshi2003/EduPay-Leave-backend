package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.ObjectStorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ObjectStorageServiceTest {

    @Mock private S3Client s3Client;

    private ObjectStorageService service;
    private ObjectStorageProperties properties;

    @BeforeEach
    void setUp() {
        service = new ObjectStorageService();
        properties = new ObjectStorageProperties();
        properties.setBucket("test-bucket");
        ReflectionTestUtils.setField(service, "properties", properties);
    }

    // ─── Object key construction (scenario: "generated key ignores unsafe filename") ──────

    @Test
    void buildObjectKey_deterministicStructure_neverUsesClientFilename() {
        String key = service.buildObjectKey(7L, "teachers", "T1", "profile", "jpg");

        assertThat(key).matches("schools/7/teachers/T1/profile/[0-9a-f-]{36}\\.jpg");
    }

    @Test
    void buildObjectKey_twoCallsForSameEntity_produceDistinctKeys() {
        String a = service.buildObjectKey(7L, "teachers", "T1", "profile", "jpg");
        String b = service.buildObjectKey(7L, "teachers", "T1", "profile", "jpg");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void buildObjectKey_rejectsPathTraversalInEntityId() {
        assertThatThrownBy(() -> service.buildObjectKey(7L, "teachers", "../../etc/passwd", "profile", "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildObjectKey_rejectsAnyStructuralCharacterInEntityId() {
        // Rejected outright, never silently stripped — entityId is always a server/DB-originated
        // value in every current caller, so any unsafe character here means something upstream
        // is already wrong.
        assertThatThrownBy(() -> service.buildObjectKey(7L, "teachers", "T1/../../hack", "profile", "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.buildObjectKey(7L, "teachers", "T1 hack", "profile", "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.buildObjectKey(7L, "teachers", "<script>", "profile", "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildObjectKey_rejectsBlankEntityId() {
        assertThatThrownBy(() -> service.buildObjectKey(7L, "teachers", "", "profile", "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.buildObjectKey(7L, "teachers", "   ", "profile", "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── Legacy vs. new-style key discrimination ─────────────────────────────────────────

    @Test
    void isObjectStorageKey_trueOnlyForNewStyleKeys() {
        assertThat(ObjectStorageService.isObjectStorageKey("schools/7/teachers/T1/profile/uuid.jpg")).isTrue();
        assertThat(ObjectStorageService.isObjectStorageKey("/uploads/teacher-photos/T1.jpg")).isFalse();
        assertThat(ObjectStorageService.isObjectStorageKey(null)).isFalse();
        assertThat(ObjectStorageService.isObjectStorageKey("")).isFalse();
    }

    // ─── Regression: an unconfigured endpoint must never even attempt to build a client ────
    // (a real bug hit during this feature's own development: application.properties'
    // established convention of ${OBJECT_STORAGE_ENDPOINT:} always leaves the property PRESENT
    // but blank when the env var is unset — @ConditionalOnProperty alone does not catch that,
    // and Spring context startup crashed trying to build an S3Client with an empty endpoint URI.
    // ObjectStorageConfig now uses @ConditionalOnExpression to check for a genuinely non-blank
    // value; IasManagementApplicationTests' full context-load test is the actual regression
    // guard for the Spring wiring itself — these two just document the contract this class
    // relies on to fail safely if that ever regresses.)

    // ─── Unconfigured object storage fails clearly, never silently or with an NPE ────────

    @Test
    void createPresignedUploadUrl_whenUnconfigured_throwsClearError() {
        assertThatThrownBy(() -> service.createPresignedUploadUrl("schools/1/teachers/T1/profile/x.jpg", "image/jpeg"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void headObject_whenUnconfigured_throwsClearError() {
        assertThatThrownBy(() -> service.headObject("schools/1/teachers/T1/profile/x.jpg"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    // ─── HEAD behavior (with a mocked, "configured" client) ──────────────────────────────

    @Test
    void headObject_returnsMetadataWhenPresent() {
        ReflectionTestUtils.setField(service, "s3Client", s3Client);
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(12345L).contentType("image/jpeg").build());

        Optional<ObjectStorageService.ObjectMetadata> result = service.headObject("schools/1/teachers/T1/profile/x.jpg");

        assertThat(result).isPresent();
        assertThat(result.get().size()).isEqualTo(12345L);
        assertThat(result.get().contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void headObject_returnsEmptyWhenObjectMissing_neverThrows() {
        ReflectionTestUtils.setField(service, "s3Client", s3Client);
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());

        Optional<ObjectStorageService.ObjectMetadata> result = service.headObject("schools/1/teachers/T1/profile/x.jpg");

        assertThat(result).isEmpty();
    }

    @Test
    void deleteObjectQuietly_neverThrowsEvenOnFailure() {
        ReflectionTestUtils.setField(service, "s3Client", s3Client);
        when(s3Client.deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class)))
                .thenThrow(new RuntimeException("storage unavailable"));

        service.deleteObjectQuietly("schools/1/teachers/T1/profile/x.jpg"); // must not throw

        verify(s3Client).deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
    }
}
