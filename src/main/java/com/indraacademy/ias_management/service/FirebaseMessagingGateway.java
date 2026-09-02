package com.indraacademy.ias_management.service;

import com.google.firebase.messaging.BatchResponse;

import java.util.List;
import java.util.Map;

public interface FirebaseMessagingGateway {
    boolean isAvailable();
    BatchResponse send(List<String> tokens, String title, String body) throws Exception;
    default BatchResponse send(List<String> tokens, String title, String body, Map<String, String> data) throws Exception {
        return send(tokens, title, body);
    }
}
