package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;

import java.time.LocalDate;

/**
 * A platform-level Edunexify product release announcement ("What's New"). Deliberately not
 * modeled as a School {@link Event} — a release is authored once by the platform and read by
 * every tenant, not created per-school by a school admin.
 */
@Entity
@Table(name = "release_notes")
public class ReleaseNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20, unique = true)
    private String version;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 500)
    private String summary;

    /** Bullet points, one per line. Kept as plain text — no rich HTML editing in Phase 1. */
    @Column(columnDefinition = "TEXT")
    private String items;

    /** ALL, ADMIN, TEACHER, PARENT or STUDENT. See {@link com.indraacademy.ias_management.config.Role}. */
    @Column(nullable = false, length = 20)
    private String audience = "ALL";

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "published_at", nullable = false)
    private LocalDate publishedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getItems() { return items; }
    public void setItems(String items) { this.items = items; }

    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDate getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDate publishedAt) { this.publishedAt = publishedAt; }
}
