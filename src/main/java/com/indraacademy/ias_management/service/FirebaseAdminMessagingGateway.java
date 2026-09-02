package com.indraacademy.ias_management.service;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class FirebaseAdminMessagingGateway implements FirebaseMessagingGateway {
    @Override
    public BatchResponse send(List<String> tokens, String title, String body) throws Exception {
        return send(tokens, title, body, Map.of());
    }

    @Override
    public boolean isAvailable() {
        return !FirebaseApp.getApps().isEmpty();
    }

    @Override
    public BatchResponse send(List<String> tokens, String title, String body, Map<String, String> data) throws Exception {
        MulticastMessage message = MulticastMessage.builder()
                .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                .putAllData(data)
                .addAllTokens(tokens)
                .build();
        return FirebaseMessaging.getInstance().sendEachForMulticast(message);
    }
}
