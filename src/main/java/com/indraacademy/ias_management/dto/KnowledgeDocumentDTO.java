package com.indraacademy.ias_management.dto;

import java.time.LocalDateTime;

public class KnowledgeDocumentDTO {

    private Long id;
    private String title;
    private String category;
    private String originalFilename;
    private String status;
    private String errorMessage;
    private LocalDateTime createdAt;

    public KnowledgeDocumentDTO() {}

    public KnowledgeDocumentDTO(Long id, String title, String category, String originalFilename,
                                 String status, String errorMessage, LocalDateTime createdAt) {
        this.id = id;
        this.title = title;
        this.category = category;
        this.originalFilename = originalFilename;
        this.status = status;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
