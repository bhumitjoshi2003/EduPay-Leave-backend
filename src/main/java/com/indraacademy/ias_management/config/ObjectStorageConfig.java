package com.indraacademy.ias_management.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * S3-API-compatible client beans, pointed at whatever endpoint {@link ObjectStorageProperties}
 * resolves from environment variables — never a hardcoded AWS endpoint.
 *
 * <p>Both beans are conditional on {@code object-storage.endpoint} being genuinely non-blank,
 * mirroring {@link FirebaseConfig}'s existing pattern for an optional external integration: a
 * local/dev environment with no object-storage credentials configured must still start up
 * cleanly (matching this codebase's established convention throughout, e.g. Firebase/Sentry),
 * rather than crashing on missing config for a feature that may not be exercised. {@link
 * com.indraacademy.ias_management.service.ObjectStorageService} checks for their absence at call
 * time and fails clearly and specifically only when the feature is actually used unconfigured.
 *
 * <p>Deliberately {@code @ConditionalOnExpression} rather than {@code @ConditionalOnProperty}:
 * this codebase's convention (see application.properties) is an explicit
 * {@code ${OBJECT_STORAGE_ENDPOINT:}} placeholder with an empty-string fallback, which always
 * leaves the property genuinely PRESENT (just possibly blank) — {@code @ConditionalOnProperty}
 * only checks presence, so it would still try to create these beans with an empty endpoint and
 * fail at startup with "The URI scheme of endpointOverride must not be null" instead of skipping
 * them.
 */
@Configuration
public class ObjectStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageConfig.class);

    @Autowired private ObjectStorageProperties properties;

    @Bean
    @ConditionalOnExpression("'${object-storage.endpoint:}'.length() > 0")
    public S3Client s3Client() {
        log.info("Configuring S3-compatible object storage client for endpoint: {}", properties.getEndpoint());
        return S3Client.builder()
                .endpointOverride(URI.create(properties.getEndpoint()))
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.isPathStyleAccess())
                        .build())
                .build();
    }

    @Bean
    @ConditionalOnExpression("'${object-storage.endpoint:}'.length() > 0")
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.getEndpoint()))
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.isPathStyleAccess())
                        .build())
                .build();
    }
}
