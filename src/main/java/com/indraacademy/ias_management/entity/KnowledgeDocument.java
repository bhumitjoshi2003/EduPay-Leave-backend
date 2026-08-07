package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A school-uploaded Knowledge Base document (policy, handbook, guideline, etc.)
 * used for RAG retrieval by the AI copilot. The raw file lives on disk under
 * ./uploads/knowledge-base/{schoolId}/{id}.{ext} — this row tracks its metadata
 * and processing status; the actual chunk text + embeddings live in KnowledgeChunk.
 */
@Entity
@Data
@Table(name = "knowledge_document",
    indexes = {
        @Index(name = "idx_knowledge_document_school", columnList = "school_id")
    })
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(nullable = false)
    private String title;

    /** ATTENDANCE, LEAVE, FEES, EXAMS, GENERAL, OTHER */
    @Column(nullable = false, length = 30)
    private String category;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "storage_path", nullable = false)
    private String storagePath;

    /** PROCESSING, READY, FAILED */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "uploaded_by_admin_id")
    private String uploadedByAdminId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
