package com.indraacademy.ias_management.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppUpdateConfigTest {

    @Test
    void defaultsMatchTheCurrentlyShippedAndroidVersion_soForcedUpdateNeverActivatesAccidentally() {
        AppUpdateConfig config = new AppUpdateConfig(
                "1.2.0", 1, 1,
                "A new version of Edunexify is available.",
                "https://play.google.com/store/apps/details?id=in.edunexify.app");

        var response = config.toResponse();

        assertThat(response.latestVersionName()).isEqualTo("1.2.0");
        assertThat(response.latestVersionCode()).isEqualTo(1);
        assertThat(response.minimumSupportedVersionCode()).isEqualTo(1);
        // Same shipped version as "latest" and "minimum supported" — an app on the current
        // release sees updateAvailable-style logic evaluate to false on the client side.
        assertThat(response.minimumSupportedVersionCode()).isLessThanOrEqualTo(response.latestVersionCode());
    }

    @Test
    void exposesWhateverConfigurationIsInjected() {
        AppUpdateConfig config = new AppUpdateConfig(
                "1.5.0", 15, 10, "Please update.", "https://play.google.com/store/apps/details?id=in.edunexify.app");

        var response = config.toResponse();

        assertThat(response.latestVersionName()).isEqualTo("1.5.0");
        assertThat(response.latestVersionCode()).isEqualTo(15);
        assertThat(response.minimumSupportedVersionCode()).isEqualTo(10);
        assertThat(response.updateMessage()).isEqualTo("Please update.");
        assertThat(response.playStoreUrl()).contains("in.edunexify.app");
    }
}
