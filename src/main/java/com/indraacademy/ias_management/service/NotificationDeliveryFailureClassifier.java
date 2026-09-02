package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.notification.ExternalDeliveryOutcome;
import jakarta.mail.internet.AddressException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Locale;

@Component
public class NotificationDeliveryFailureClassifier {
    public ExternalDeliveryOutcome classify(Throwable failure) {
        Throwable cause = rootCause(failure);
        if (cause instanceof AddressException || failure instanceof MailParseException
                || failure instanceof MailAuthenticationException) {
            return ExternalDeliveryOutcome.PERMANENT_FAILURE;
        }
        if (cause instanceof SocketTimeoutException || cause instanceof ConnectException) {
            return ExternalDeliveryOutcome.RETRYABLE_FAILURE;
        }
        String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
        if (message.contains("invalid address") || message.contains("unknown user")
                || message.contains("recipient rejected") || message.matches(".*\\b5\\d\\d\\b.*")) {
            return ExternalDeliveryOutcome.PERMANENT_FAILURE;
        }
        return ExternalDeliveryOutcome.RETRYABLE_FAILURE;
    }

    private Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }
}
