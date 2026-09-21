package com.indraacademy.ias_management.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailSenderResolverTest {
    @Test
    void resolvesEveryPurposeWithVerifiedAddressAndDisplayName() {
        EmailSenderResolver resolver = defaults();

        assertThat(resolver.resolve(EmailPurpose.ONBOARDING))
                .isEqualTo(new EmailSenderIdentity("hello@edunexify.co.in", "Edunexify"));
        assertThat(resolver.resolve(EmailPurpose.NOTIFICATION))
                .isEqualTo(new EmailSenderIdentity("notifications@edunexify.co.in", "Edunexify Notifications"));
        assertThat(resolver.resolve(EmailPurpose.FEES))
                .isEqualTo(new EmailSenderIdentity("fees@edunexify.co.in", "Edunexify Fees"));
        assertThat(resolver.resolve(EmailPurpose.SECURITY))
                .isEqualTo(new EmailSenderIdentity("noreply@edunexify.co.in", "Edunexify Security"));
    }

    @Test
    void nullPurposeFallsBackToSecurity() {
        assertThat(defaults().resolve(null))
                .isEqualTo(new EmailSenderIdentity("noreply@edunexify.co.in", "Edunexify Security"));
    }

    @Test
    void blankOverridesCannotProduceBlankFromAddresses() {
        EmailSenderResolver resolver = new EmailSenderResolver(" ", "", null, "\t");

        assertThat(resolver.resolve(EmailPurpose.ONBOARDING).email()).isEqualTo("hello@edunexify.co.in");
        assertThat(resolver.resolve(EmailPurpose.NOTIFICATION).email()).isEqualTo("notifications@edunexify.co.in");
        assertThat(resolver.resolve(EmailPurpose.FEES).email()).isEqualTo("fees@edunexify.co.in");
        assertThat(resolver.resolve(EmailPurpose.SECURITY).email()).isEqualTo("noreply@edunexify.co.in");
    }

    private EmailSenderResolver defaults() {
        return new EmailSenderResolver("hello@edunexify.co.in", "notifications@edunexify.co.in",
                "fees@edunexify.co.in", "noreply@edunexify.co.in");
    }
}
