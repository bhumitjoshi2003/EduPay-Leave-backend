package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One chunk of a KnowledgeDocument's extracted text. The embedding vector
 * (pgvector `embedding vector(1536)` column) is deliberately NOT mapped here —
 * it's written/read only via native SQL in KnowledgeSearchRepository, since
 * Hibernate has no built-in pgvector column type in this codebase. See the
 * one-time manual migration note next to that repository.
 */
@Entity
@Data
@Table(name = "knowledge_chunk",
    indexes = {
        @Index(name = "idx_knowledge_chunk_document", columnList = "document_id"),
        @Index(name = "idx_knowledge_chunk_school", columnList = "school_id")
    })
public class KnowledgeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    /** Denormalized from the parent document so search queries never need a join to scope by tenant. */
    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "chunk_text", nullable = false, columnDefinition = "TEXT")
    private String chunkText;

    /** 1-indexed PDF page this chunk came from, for citations. Null for DOCX/TXT/MD (no native pagination). */
    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
