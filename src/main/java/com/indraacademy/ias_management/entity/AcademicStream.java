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

    @Column(name = "stream_name", unique = true, nullable = false)
    private String streamName;

    public AcademicStream() {}

    public Long getId() { return id; }
    public String getStreamName() { return streamName; }

    public void setId(Long id) { this.id = id; }
    public void setStreamName(String streamName) { this.streamName = streamName; }
}
