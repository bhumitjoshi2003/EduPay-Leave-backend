package com.indraacademy.ias_management.dto;

import java.time.Instant;
import java.util.Map;

/** Never includes an access key, secret key, or any other permanent object-storage credential —
 * only a short-lived, single-object presigned URL the client can PUT bytes to directly. */
public record UploadRequestResponse(
        String objectKey,
        String uploadUrl,
        Instant expiresAt,
        Map<String, String> requiredHeaders
) {}
