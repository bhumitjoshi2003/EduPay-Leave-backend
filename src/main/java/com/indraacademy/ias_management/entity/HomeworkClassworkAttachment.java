package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** One attachment (image or PDF) on a Homework/Classwork post (V77). */
@Entity
@Table(name = "homework_classwork_attachment")
@Getter
@Setter
public class HomeworkClassworkAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "homework_classwork_id", nullable = false)
    private HomeworkClasswork homeworkClasswork;

    @Column(name = "object_key", nullable = false, unique = true, length = 500)
    private String objectKey;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
