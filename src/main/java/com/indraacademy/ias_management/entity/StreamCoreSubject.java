package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "stream_core_subject",
        uniqueConstraints = @UniqueConstraint(columnNames = {"stream_id", "subject_name"}))
@Data
public class StreamCoreSubject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stream_id", nullable = false)
    private Long streamId;

    @Column(name = "subject_name", nullable = false)
    private String subjectName;

    public StreamCoreSubject() {}

    public Long getId() { return id; }
    public Long getStreamId() { return streamId; }
    public String getSubjectName() { return subjectName; }

    public void setId(Long id) { this.id = id; }
    public void setStreamId(Long streamId) { this.streamId = streamId; }
    public void setSubjectName(String subjectName) { this.subjectName = subjectName; }
}
