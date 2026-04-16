package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "student_stream_selection")
@Data
public class StudentStreamSelection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", unique = true, nullable = false)
    private String studentId;

    @Column(name = "stream_id", nullable = false)
    private Long streamId;

    @Column(name = "optional_subject_id")
    private Long optionalSubjectId;

    public StudentStreamSelection() {}

    public Long getId() { return id; }
    public String getStudentId() { return studentId; }
    public Long getStreamId() { return streamId; }
    public Long getOptionalSubjectId() { return optionalSubjectId; }

    public void setId(Long id) { this.id = id; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public void setStreamId(Long streamId) { this.streamId = streamId; }
    public void setOptionalSubjectId(Long optionalSubjectId) { this.optionalSubjectId = optionalSubjectId; }
}
