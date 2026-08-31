package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;

/**
 * Implements Persistable&lt;String&gt; for the same reason Student/Teacher/Admin do (see
 * Student.java's Javadoc for the full rationale): parentId is an assigned, non-generated
 * @Id, so without this, Spring Data's default isNew() ("is the id null?") is always false
 * and save() always calls merge() — which would silently upsert into a DIFFERENT school's
 * row on an ID collision instead of failing. Not a live bug today given parentId is
 * currently admin-typed and collisions are rare, but this closes the same latent gap
 * proactively, matching the pattern already established for the other three entities.
 */
@Entity
@Table(name = "parent_account", indexes = {
        @Index(name = "idx_parent_account_school", columnList = "school_id"),
        @Index(name = "idx_parent_account_phone", columnList = "school_id, phone_number")
})
@Data
public class Parent implements Persistable<String> {
    @Id
    @Column(name = "parent_id", length = 50)
    private String parentId;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 255)
    private String email;

    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Defaults true: a freshly-constructed instance is new until proven otherwise.
    // @PostLoad flips it false for anything JPA actually loaded from the DB. Excluded
    // from equals/hashCode/toString — lifecycle bookkeeping, not entity identity/data.
    @Transient
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private boolean isNew = true;

    @Override
    public String getId() {
        return parentId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
