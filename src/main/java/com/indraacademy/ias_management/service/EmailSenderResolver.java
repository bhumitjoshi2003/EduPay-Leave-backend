package com.indraacademy.ias_management.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
public class EmailSenderResolver {
    private static final Logger log = LoggerFactory.getLogger(EmailSenderResolver.class);
    static final String DEFAULT_HELLO = "hello@edunexify.co.in";
    static final String DEFAULT_NOTIFICATIONS = "notifications@edunexify.co.in";
    static final String DEFAULT_FEES = "fees@edunexify.co.in";
    static final String DEFAULT_NO_REPLY = "noreply@edunexify.co.in";
    static final String DEFAULT_SUPPORT = "support@edunexify.co.in";

    private final Map<EmailPurpose, EmailSenderIdentity> identities;

    public EmailSenderResolver(
            @Value("${app.mail.sender.hello:${EMAIL_SENDER_HELLO:hello@edunexify.co.in}}") String hello,
            @Value("${app.mail.sender.notifications:${EMAIL_SENDER_NOTIFICATIONS:notifications@edunexify.co.in}}") String notifications,
            @Value("${app.mail.sender.fees:${EMAIL_SENDER_FEES:fees@edunexify.co.in}}") String fees,
            @Value("${app.mail.sender.no-reply:${EMAIL_SENDER_NOREPLY:noreply@edunexify.co.in}}") String noReply,
            @Value("${app.mail.sender.support:${EMAIL_SENDER_SUPPORT:support@edunexify.co.in}}") String support) {
        EnumMap<EmailPurpose, EmailSenderIdentity> configured = new EnumMap<>(EmailPurpose.class);
        configured.put(EmailPurpose.ONBOARDING, identity(hello, DEFAULT_HELLO, "Edunexify", EmailPurpose.ONBOARDING));
        configured.put(EmailPurpose.NOTIFICATION, identity(notifications, DEFAULT_NOTIFICATIONS,
                "Edunexify Notifications", EmailPurpose.NOTIFICATION));
        configured.put(EmailPurpose.FEES, identity(fees, DEFAULT_FEES, "Edunexify Fees", EmailPurpose.FEES));
        configured.put(EmailPurpose.SECURITY, identity(noReply, DEFAULT_NO_REPLY,
                "Edunexify Security", EmailPurpose.SECURITY));
        configured.put(EmailPurpose.SUPPORT, identity(support, DEFAULT_SUPPORT,
                "Edunexify Support", EmailPurpose.SUPPORT));
        identities = Map.copyOf(configured);
    }

    public EmailSenderIdentity resolve(EmailPurpose purpose) {
        EmailPurpose safePurpose = purpose == null ? EmailPurpose.SECURITY : purpose;
        if (purpose == null) log.warn("Email purpose was not provided; using SECURITY sender identity");
        return identities.get(safePurpose);
    }

    private EmailSenderIdentity identity(String configured, String fallback, String name, EmailPurpose purpose) {
        String email = configured == null || configured.isBlank() ? fallback : configured.trim();
        if (configured == null || configured.isBlank()) {
            log.warn("Blank sender configuration for {}; using the verified default address", purpose);
        }
        return new EmailSenderIdentity(email, name);
    }
}
