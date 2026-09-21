package com.indraacademy.ias_management.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the S3-API-compatible object storage backend (Neon Object Storage, or
 * any equivalent S3-compatible endpoint) used for direct-to-storage presigned uploads/downloads
 * — see {@link com.indraacademy.ias_management.service.ObjectStorageService}.
 *
 * <p><b>Neon Object Storage specifics are not hardcoded or assumed here.</b> No existing
 * reference to Neon Object Storage was found anywhere in this codebase's history, and its exact
 * endpoint URL format, credential model, and path-style-vs-virtual-hosted addressing requirement
 * could not be independently verified. Every field below is sourced from environment variables
 * with no compiled-in defaults for the connection details themselves (only {@link #presignExpirySeconds}/
 * {@link #downloadPresignExpirySeconds} have safe defaults) — the actual endpoint/region/
 * credentials/bucket MUST be supplied via env vars pointing at whatever the real Neon project
 * dashboard provides, and confirmed to be genuinely S3-API-compatible before this is used in
 * production. Never commit real values here or anywhere else.
 */
@Configuration
@ConfigurationProperties(prefix = "object-storage")
public class ObjectStorageProperties {

    /** S3-compatible endpoint URL, e.g. https://<project>.s3.<region>.<provider-host>. */
    private String endpoint;

    /** Region string required by the AWS SDK client even for non-AWS S3-compatible endpoints —
     * many S3-compatible providers accept an arbitrary/placeholder region; confirm the correct
     * value for the actual provider before production use. */
    private String region;

    private String accessKey;
    private String secretKey;

    /** The single private bucket used for all Phase 1 object-storage-backed uploads. */
    private String bucket;

    /** Whether the endpoint requires path-style addressing (https://host/bucket/key) instead of
     * virtual-hosted-style (https://bucket.host/key) — most non-AWS S3-compatible providers
     * require path-style; left configurable rather than assumed. Defaults to true (path-style)
     * as the safer default for an unverified non-AWS endpoint. */
    private boolean pathStyleAccess = true;

    /** Presigned PUT (upload) URL expiry — kept short per the mandated 5-10 minute policy. */
    private long presignExpirySeconds = 600;

    /** Presigned GET (view/download) URL expiry — longer than upload since it's a read-only,
     * lower-risk operation, but still bounded (not cached indefinitely). */
    private long downloadPresignExpirySeconds = 1800;

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }

    public boolean isPathStyleAccess() { return pathStyleAccess; }
    public void setPathStyleAccess(boolean pathStyleAccess) { this.pathStyleAccess = pathStyleAccess; }

    public long getPresignExpirySeconds() { return presignExpirySeconds; }
    public void setPresignExpirySeconds(long presignExpirySeconds) { this.presignExpirySeconds = presignExpirySeconds; }

    public long getDownloadPresignExpirySeconds() { return downloadPresignExpirySeconds; }
    public void setDownloadPresignExpirySeconds(long downloadPresignExpirySeconds) { this.downloadPresignExpirySeconds = downloadPresignExpirySeconds; }
}
