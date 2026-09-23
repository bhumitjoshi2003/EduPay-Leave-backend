package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.notification.ExternalDeliveryChannel;
import com.indraacademy.ias_management.notification.NotificationCategory;
import com.indraacademy.ias_management.notification.NotificationEventCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Set;

/** Fires only after the status-change transaction has actually committed — a notification
 *  failure here can never roll back the ticket's status change, since the write already
 *  happened. Reuses the existing BusinessNotificationService.direct(...) pipeline for BOTH
 *  in-app + FCM push AND email in one call (ExternalDeliveryChannel.EMAIL is already wired to
 *  the existing async NotificationDeliveryWorker — see NotificationDeliveryWorker.purposeFor,
 *  which routes SUPPORT_TICKET_* event codes to EmailPurpose.SUPPORT / support@edunexify.co.in).
 *  No separate email call, no new delivery mechanism. */
@Component
public class SupportTicketNotificationListener {
    private static final Logger log = LoggerFactory.getLogger(SupportTicketNotificationListener.class);
    private final BusinessNotificationService notifications;

    public SupportTicketNotificationListener(BusinessNotificationService notifications) {
        this.notifications = notifications;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(SupportTicketNotificationEvent event) {
        try {
            boolean inProgress = "IN_PROGRESS".equals(event.eventKind());
            String title = inProgress ? "Support Request Update" : "Support Request Resolved";
            String message = inProgress
                    ? String.format("Your support request %s is being reviewed.", event.ticketNumber())
                    : String.format("Your support request %s has been resolved.", event.ticketNumber());
            notifications.direct(event.schoolId(), event.recipientUserId(),
                    inProgress ? NotificationEventCode.SUPPORT_TICKET_IN_PROGRESS : NotificationEventCode.SUPPORT_TICKET_RESOLVED,
                    NotificationCategory.SYSTEM_ADMIN, title, message,
                    "SupportTicket", String.valueOf(event.ticketId()),
                    "/dashboard/support-tickets", event.actorUserId(),
                    "support-ticket:" + event.ticketId() + ":" + event.revision() + ":"
                            + event.eventKind() + ":" + event.recipientUserId(),
                    Set.of(ExternalDeliveryChannel.PUSH, ExternalDeliveryChannel.EMAIL));
        } catch (RuntimeException failure) {
            log.error("Ticket {} status changed to {} but notifying {} failed",
                    event.ticketId(), event.eventKind(), event.recipientUserId(), failure);
        }
    }
}
