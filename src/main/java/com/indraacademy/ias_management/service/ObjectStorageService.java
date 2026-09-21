package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.ObjectStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The one place business services talk to object storage — nothing outside this class touches
 * an AWS SDK class directly, so a future storage-provider change or Neon-specific quirk stays
 * contained here. Every public method fails clearly (not silently, not with a generic NPE) when
 * {@code object-storage.endpoint} isn't configured, matching {@link ObjectStorageConfig}'s
 * conditional bean creation — a local/dev environment without object storage credentials still
 * boots cleanly; only actually invoking this service surfaces the missing-configuration error.
 */
@Service
public class ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageService.class);

    @Autowired private ObjectStorageProperties properties;
    @Autowired(required = false) private S3Client s3Client;
    @Autowired(required = false) private S3Presigner s3Presigner;

    public record PresignedUpload(String objectKey, String uploadUrl, Instant expiresAt, Map<String, String> requiredHeaders) {}
    public record ObjectMetadata(long size, String contentType) {}

    /**
     * Deterministic, collision-safe, cross-tenant-proof key. Never derived from a client-supplied
     * filename or path — schoolId/entityType/entityId come from trusted server-side context, and
     * the random suffix makes each upload attempt for the same entity land at a distinct key
     * (the caller decides whether/when to treat a new upload as "replacing" the old reference).
     */
    public String buildObjectKey(Long schoolId, String entityType, String entityId, String purposeSegment, String extension) {
        String safeEntityId = sanitizeSegment(entityId);
        return "schools/%d/%s/%s/%s/%s.%s".formatted(
                schoolId, entityType, safeEntityId, purposeSegment, UUID.randomUUID(), extension);
    }

    private static final java.util.regex.Pattern SAFE_SEGMENT = java.util.regex.Pattern.compile("^[A-Za-z0-9_-]+$");

    /**
     * Rejects outright (never silently strips) anything but alphanumerics/hyphen/underscore —
     * entityId is a trusted, server/DB-originated value in every current caller (e.g. a
     * generated teacherId), so encountering a slash, a "..", or any other structural character
     * here means something upstream is already wrong; failing loudly surfaces that immediately
     * instead of silently producing a technically-safe-but-unexpected key that could mask it.
     */
    private String sanitizeSegment(String value) {
        if (value == null || value.isBlank() || !SAFE_SEGMENT.matcher(value).matches()) {
            throw new IllegalArgumentException("Entity id is not a safe storage path segment.");
        }
        return value;
    }

    public PresignedUpload createPresignedUploadUrl(String objectKey, String contentType) {
        S3Presigner presigner = requirePresigner();
        Duration expiry = Duration.ofSeconds(properties.getPresignExpirySeconds());

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(objectKey)
                .contentType(contentType)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .putObjectRequest(putRequest)
                .build();

        PresignedPutObjectRequest presigned = presigner.presignPutObject(presignRequest);
        Instant expiresAt = Instant.now().plus(expiry);
        return new PresignedUpload(objectKey, presigned.url().toString(), expiresAt, Map.of("Content-Type", contentType));
    }

    public URL createPresignedDownloadUrl(String objectKey) {
        S3Presigner presigner = requirePresigner();
        Duration expiry = Duration.ofSeconds(properties.getDownloadPresignExpirySeconds());

        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(objectKey)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .getObjectRequest(getRequest)
                .build();

        return presigner.presignGetObject(presignRequest).url();
    }

    /** Empty when the object doesn't exist — never throws for the "not found" case, only for a
     * genuine transport/auth failure, so callers can treat "missing" as an ordinary outcome. */
    public Optional<ObjectMetadata> headObject(String objectKey) {
        S3Client client = requireClient();
        try {
            HeadObjectResponse response = client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(objectKey)
                    .build());
            return Optional.of(new ObjectMetadata(response.contentLength(), response.contentType()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    /** Idempotent and tolerant of an already-missing object — deletion cleanup (old-photo
     * replacement, orphan sweep) must never turn "it's already gone" into a fatal error. */
    public void deleteObjectQuietly(String objectKey) {
        S3Client client = requireClient();
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(objectKey)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to delete object storage key {} — leaving for later manual/cleanup review: {}",
                    objectKey, e.getMessage());
        }
    }

    /** Legacy local-disk photo paths (/uploads/...) vs. this feature's own keys (schools/...) —
     * the one place that distinction is made, so backward compatibility never needs a migration
     * of existing data. See TeacherService's photo-resolution logic. */
    public static boolean isObjectStorageKey(String storedValue) {
        return storedValue != null && storedValue.startsWith("schools/");
    }

    private S3Client requireClient() {
        if (s3Client == null) {
            throw new IllegalStateException("Object storage is not configured (object-storage.endpoint is unset) — " +
                    "cannot perform this operation.");
        }
        return s3Client;
    }

    private S3Presigner requirePresigner() {
        if (s3Presigner == null) {
            throw new IllegalStateException("Object storage is not configured (object-storage.endpoint is unset) — " +
                    "cannot presign URLs.");
        }
        return s3Presigner;
    }
}
