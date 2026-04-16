package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "student_mark",
        uniqueConstraints = @UniqueConstraint(columnNames = {"student_id", "exam_subject_entry_id"}))
@Data
public class StudentMark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", nullable = false)
    private String studentId;

    @Column(name = "exam_subject_entry_id", nullable = false)
    private Long examSubjectEntryId;

    @Column(name = "marks_obtained")
    private Double marksObtained;

    @Column(name = "entered_by")
    private String enteredBy;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public StudentMark() {}

    public Long getId() { return id; }
    public String getStudentId() { return studentId; }
    public Long getExamSubjectEntryId() { return examSubjectEntryId; }
    public Double getMarksObtained() { return marksObtained; }
    public String getEnteredBy() { return enteredBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setId(Long id) { this.id = id; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public void setExamSubjectEntryId(Long examSubjectEntryId) { this.examSubjectEntryId = examSubjectEntryId; }
    public void setMarksObtained(Double marksObtained) { this.marksObtained = marksObtained; }
    public void setEnteredBy(String enteredBy) { this.enteredBy = enteredBy; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
