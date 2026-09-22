package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.AppUpdateInfoResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Android update thresholds as simple, env-overridable config — mirrors EmailSenderResolver's
 * pattern. These change rarely (once per app release) and are scalar values, so a DB table/
 * migration/admin UI would be overengineering for Phase 1; bumping a release means editing an
 * env var (or these defaults) and redeploying, the same as any other config change.
 *
 * minimumSupportedVersionCode defaults to the current shipped versionCode (1), so forced-update
 * messaging never activates by accident — it only starts mattering once this is deliberately
 * raised above a version real users are still on.
 */
@Component
public class AppUpdateConfig {

    private final String latestVersionName;
    private final int latestVersionCode;
    private final int minimumSupportedVersionCode;
    private final String updateMessage;
    private final String playStoreUrl;

    public AppUpdateConfig(
            @Value("${app.android.latest-version-name:1.2.0}") String latestVersionName,
            @Value("${app.android.latest-version-code:1}") int latestVersionCode,
            @Value("${app.android.minimum-supported-version-code:1}") int minimumSupportedVersionCode,
            @Value("${app.android.update-message:A new version of Edunexify is available.}") String updateMessage,
            @Value("${app.android.play-store-url:https://play.google.com/store/apps/details?id=in.edunexify.app}") String playStoreUrl) {
        this.latestVersionName = latestVersionName;
        this.latestVersionCode = latestVersionCode;
        this.minimumSupportedVersionCode = minimumSupportedVersionCode;
        this.updateMessage = updateMessage;
        this.playStoreUrl = playStoreUrl;
    }

    public AppUpdateInfoResponse toResponse() {
        return new AppUpdateInfoResponse(
                latestVersionName, latestVersionCode, minimumSupportedVersionCode, updateMessage, playStoreUrl);
    }

    public int getLatestVersionCode() { return latestVersionCode; }
    public int getMinimumSupportedVersionCode() { return minimumSupportedVersionCode; }
}
