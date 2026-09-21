package com.indraacademy.ias_management.dto;

/**
 * Static, server-published update thresholds. The client (Android) compares these against its
 * own locally-known versionCode and decides what to show — the server never receives or trusts
 * a client-supplied version, so there is no client input to validate here.
 */
public record AppUpdateInfoResponse(
        String latestVersionName,
        int latestVersionCode,
        int minimumSupportedVersionCode,
        String updateMessage,
        String playStoreUrl
) {}
