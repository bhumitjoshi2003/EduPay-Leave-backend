package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "academic_stream")
@Data
public class AcademicStream {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id")
    private Long schoolId;

    @Column(name = "stream_name", nullable = false)
    private String streamName;

    public AcademicStream() {}

    public Long getId() { return id; }
    public String getStreamName() { return streamName; }

    public void setId(Long id) { this.id = id; }
    public void setStreamName(String streamName) { this.streamName = streamName; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }
}
