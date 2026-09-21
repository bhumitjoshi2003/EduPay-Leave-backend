package com.indraacademy.ias_management.dto;

import java.util.List;

public record SchoolSetupHealthResponse(
        int completionPercentage,
        int completedRequired,
        int totalRequired,
        Status status,
        List<Item> items) {

    public enum Status { NOT_STARTED, IN_PROGRESS, READY }
    public enum ItemStatus { COMPLETED, INCOMPLETE, NOT_APPLICABLE }
    public enum Importance { REQUIRED, RECOMMENDED, OPTIONAL }

    public record Item(
            String key,
            String title,
            String description,
            ItemStatus status,
            Importance importance) {}
}
