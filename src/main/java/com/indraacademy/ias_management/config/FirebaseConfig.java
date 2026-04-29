package com.indraacademy.ias_management.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Value("${app.firebase.service-account-path:}")
    private String serviceAccountPath;

    @PostConstruct
    public void initialize() {
        if (serviceAccountPath == null || serviceAccountPath.isBlank()) {
            log.warn("Firebase service account path not configured (app.firebase.service-account-path). " +
                    "Push notifications will be disabled.");
            return;
        }

        if (!FirebaseApp.getApps().isEmpty()) {
            log.info("FirebaseApp already initialized — skipping.");
            return;
        }

        try (InputStream serviceAccount = new FileInputStream(serviceAccountPath)) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .build();

            FirebaseApp.initializeApp(options);
            log.info("Firebase initialized successfully from: {}", serviceAccountPath);
        } catch (IOException e) {
            log.error("Failed to initialize Firebase from path '{}': {}", serviceAccountPath, e.getMessage());
        }
    }
}
