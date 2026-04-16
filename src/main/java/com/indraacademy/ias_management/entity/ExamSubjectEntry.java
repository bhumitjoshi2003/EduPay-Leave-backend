package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;

@Entity
@Table(name = "exam_subject_entry",
        uniqueConstraints = @UniqueConstraint(columnNames = {"exam_config_id", "subject_name"}))
@Data
public class ExamSubjectEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exam_config_id", nullable = false)
    private Long examConfigId;

    @Column(name = "subject_name", nullable = false)
    private String subjectName;

    @Column(name = "max_marks", nullable = false)
    private Integer maxMarks;

    @Column(name = "exam_date")
    private LocalDate examDate;

    public ExamSubjectEntry() {}

    public Long getId() { return id; }
    public Long getExamConfigId() { return examConfigId; }
    public String getSubjectName() { return subjectName; }
    public Integer getMaxMarks() { return maxMarks; }
    public LocalDate getExamDate() { return examDate; }

    public void setId(Long id) { this.id = id; }
    public void setExamConfigId(Long examConfigId) { this.examConfigId = examConfigId; }
    public void setSubjectName(String subjectName) { this.subjectName = subjectName; }
    public void setMaxMarks(Integer maxMarks) { this.maxMarks = maxMarks; }
    public void setExamDate(LocalDate examDate) { this.examDate = examDate; }
}
