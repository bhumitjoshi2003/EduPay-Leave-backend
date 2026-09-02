package com.indraacademy.ias_management.notification;

import com.indraacademy.ias_management.entity.Notification;
import com.indraacademy.ias_management.entity.NotificationDelivery;
import com.indraacademy.ias_management.entity.UserNotification;

import java.util.List;

public record NotificationPublication(
        Notification notification,
        List<UserNotification> recipients,
        List<NotificationDelivery> deliveries,
        boolean duplicate) {
}
