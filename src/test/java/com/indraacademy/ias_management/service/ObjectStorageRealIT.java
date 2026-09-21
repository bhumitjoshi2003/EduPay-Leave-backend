package com.indraacademy.ias_management.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CORSConfiguration;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetBucketCorsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutBucketCorsRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Neon-Object-Storage proof, mirroring the established *IT convention in this codebase
 * (see NotificationDeliveryRedisIT): talks directly to whatever S3-compatible endpoint the
 * OBJECT_STORAGE_* env vars point at, using the exact same client construction
 * ObjectStorageConfig uses, and skips itself cleanly (never fails) if those vars aren't set —
 * no Spring context, no PostgreSQL/Hikari dependency, so this can run standalone even though
 * this environment has never had a reachable local Postgres.
 *
 * <p>Every object this test creates lives under a per-run "integration-tests/{uuid}/" prefix and
 * is deleted in an @AfterAll sweep regardless of individual test outcomes.
 */
class ObjectStorageRealIT {

    private static boolean configured;
    private static S3Client s3Client;
    private static S3Presigner s3Presigner;
    private static String bucket;
    private static final String RUN_PREFIX = "integration-tests/" + UUID.randomUUID() + "/";
    private static final List<String> createdKeys = new ArrayList<>();

    @BeforeAll
    static void setUpClient() {
        String endpoint = System.getenv("OBJECT_STORAGE_ENDPOINT");
        String region = System.getenv("OBJECT_STORAGE_REGION");
        String accessKey = System.getenv("OBJECT_STORAGE_ACCESS_KEY");
        String secretKey = System.getenv("OBJECT_STORAGE_SECRET_KEY");
        bucket = System.getenv("OBJECT_STORAGE_BUCKET");

        configured = notBlank(endpoint) && notBlank(region) && notBlank(accessKey) && notBlank(secretKey) && notBlank(bucket);
        if (!configured) {
            return;
        }

        boolean pathStyle = !"false".equalsIgnoreCase(System.getenv("OBJECT_STORAGE_PATH_STYLE_ACCESS"));
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));

        s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build())
                .build();

        s3Presigner = S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build())
                .build();
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    @BeforeEach
    void requireConfigured() {
        Assumptions.assumeTrue(configured,
                "OBJECT_STORAGE_* env vars not set in this process — skipping (not failing), mirroring how " +
                        "*PostgresIT/NotificationDeliveryRedisIT skip when their own external dependency is absent.");
    }

    @AfterAll
    static void cleanUpAndCloseClients() {
        if (configured) {
            for (String key : createdKeys) {
                try {
                    s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
                } catch (Exception ignored) {
                    // best-effort — a key that was already deleted by its own test is fine
                }
            }
        }
        if (s3Client != null) s3Client.close();
        if (s3Presigner != null) s3Presigner.close();
    }

    private String testKey(String name) {
        String key = RUN_PREFIX + name;
        createdKeys.add(key);
        return key;
    }

    // ─── Section 2: raw PUT / HEAD / GET / DELETE round trip ─────────────────────

    @Test
    void rawPutHeadGetDeleteRoundTrip() {
        String key = testKey("hello.txt");
        String content = "hello from Edunexify object storage integration test";

        s3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("text/plain").build(),
                RequestBody.fromString(content));

        var head = s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        assertThat(head.contentLength()).isEqualTo((long) content.getBytes().length);

        var getResponse = s3Client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build());
        assertThat(getResponse.asUtf8String()).isEqualTo(content);

        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());

        assertThatThrowsNoSuchKey(key);
    }

    private void assertThatThrowsNoSuchKey(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            throw new AssertionError("Expected object " + key + " to no longer exist after delete.");
        } catch (S3Exception e) {
            // Some S3-compatible providers return a generic 404 S3Exception rather than the
            // typed NoSuchKeyException for HEAD specifically — accept either as "confirmed gone".
            if (e instanceof S3Exception s3e && s3e.statusCode() != 404) {
                throw e;
            }
        }
    }

    // ─── Section 3: presigned PUT without permanent credentials ──────────────────

    @Test
    void presignedPutUploadWorksWithoutPermanentCredentials() throws Exception {
        String key = testKey("presigned-put.txt");
        String content = "uploaded via presigned PUT, no permanent credentials used";

        PutObjectRequest putRequest = PutObjectRequest.builder().bucket(bucket).key(key).contentType("text/plain").build();
        var presigned = s3Presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(5)).putObjectRequest(putRequest).build());

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest putHttp = HttpRequest.newBuilder(presigned.url().toURI())
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString(content))
                .build();
        HttpResponse<String> response = httpClient.send(putHttp, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isBetween(200, 204);

        var head = s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        assertThat(head.contentType()).isEqualTo("text/plain");
    }

    // ─── Section 4: presigned GET without permanent credentials ───────────────────

    @Test
    void presignedGetDownloadWorksWithoutPermanentCredentials() throws Exception {
        String key = testKey("presigned-get.txt");
        String content = "downloaded via presigned GET, no permanent credentials used";
        s3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("text/plain").build(),
                RequestBody.fromString(content));

        var presigned = s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(5))
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .build());

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest getHttp = HttpRequest.newBuilder(presigned.url().toURI()).GET().build();
        HttpResponse<String> response = httpClient.send(getHttp, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(content);
    }

    // ─── Section 5/6: apply the production+local-dev CORS policy, then prove preflight ───
    // works for BOTH origins in one deterministic test (JUnit 5's default method order is not
    // guaranteed to be source order, so PutBucketCors and the preflight checks that depend on it
    // must not be split across separate @Test methods).
    //
    // Neon's PutBucketCors already confirmed working in an earlier session (single-origin rule
    // for https://edunexify.co.in) — this now widens that same one rule to also allow local
    // development from http://localhost:4200, without using a wildcard. PutBucketCors REPLACES
    // the entire ruleset (not additive), so the production origin is included here explicitly
    // rather than assumed to survive from whatever was set before.

    @Test
    void applyProductionAndLocalDevCorsPolicy_thenPreflightSucceedsForBothOrigins() throws Exception {
        try {
            var existing = s3Client.getBucketCors(GetBucketCorsRequest.builder().bucket(bucket).build());
            System.out.println("[ObjectStorageRealIT] Existing bucket CORS rules before update: " + existing.corsRules().size());
        } catch (Exception e) {
            System.out.println("[ObjectStorageRealIT] GetBucketCors failed/unsupported: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        CORSRule rule = CORSRule.builder()
                .allowedOrigins("https://edunexify.co.in", "http://localhost:4200")
                .allowedMethods("PUT", "GET", "HEAD")
                .allowedHeaders("content-type")
                .maxAgeSeconds(3000)
                .build();
        s3Client.putBucketCors(PutBucketCorsRequest.builder()
                .bucket(bucket)
                .corsConfiguration(CORSConfiguration.builder().corsRules(rule).build())
                .build());
        System.out.println("[ObjectStorageRealIT] PutBucketCors applied: production + local-dev origins, no wildcard.");

        var updated = s3Client.getBucketCors(GetBucketCorsRequest.builder().bucket(bucket).build());
        assertThat(updated.corsRules()).hasSize(1);
        assertThat(updated.corsRules().get(0).allowedOrigins())
                .containsExactlyInAnyOrder("https://edunexify.co.in", "http://localhost:4200");
        assertThat(updated.corsRules().get(0).allowedMethods()).containsExactlyInAnyOrder("PUT", "GET", "HEAD");
        assertThat(updated.corsRules().get(0).allowedHeaders()).containsExactly("content-type");

        assertPreflightAllowsOrigin("https://edunexify.co.in");
        assertPreflightAllowsOrigin("http://localhost:4200");
    }

    private void assertPreflightAllowsOrigin(String origin) throws Exception {
        String key = testKey("cors-preflight-" + origin.replaceAll("[^a-zA-Z0-9]", "-") + ".txt");
        PutObjectRequest putRequest = PutObjectRequest.builder().bucket(bucket).key(key).contentType("text/plain").build();
        var presigned = s3Presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(5)).putObjectRequest(putRequest).build());

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest preflight = HttpRequest.newBuilder(presigned.url().toURI())
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "content-type")
                .build();
        HttpResponse<String> response = httpClient.send(preflight, HttpResponse.BodyHandlers.ofString());

        String allowOrigin = response.headers().firstValue("Access-Control-Allow-Origin").orElse("<absent>");
        System.out.println("[ObjectStorageRealIT] Preflight for Origin=" + origin
                + " -> status=" + response.statusCode() + ", Access-Control-Allow-Origin=" + allowOrigin);

        assertThat(response.statusCode()).isBetween(200, 204);
        assertThat(allowOrigin).isEqualTo(origin);
    }
}
