package com.indraacademy.ias_management.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Stream with its core subjects — returned by GET /api/streams and POST /api/streams. */
public class StreamResponseDTO {

    private Long id;
    private String streamName;
    private List<CoreSubjectDTO> coreSubjects;

    @JsonCreator
    public StreamResponseDTO(
            @JsonProperty("id") Long id,
            @JsonProperty("streamName") String streamName,
            @JsonProperty("coreSubjects") List<CoreSubjectDTO> coreSubjects) {
        this.id = id;
        this.streamName = streamName;
        this.coreSubjects = coreSubjects;
    }

    public Long getId() { return id; }
    public String getStreamName() { return streamName; }
    public List<CoreSubjectDTO> getCoreSubjects() { return coreSubjects; }

    public static class CoreSubjectDTO {
        private Long id;
        private String subjectName;

        @JsonCreator
        public CoreSubjectDTO(
                @JsonProperty("id") Long id,
                @JsonProperty("subjectName") String subjectName) {
            this.id = id;
            this.subjectName = subjectName;
        }

        public Long getId() { return id; }
        public String getSubjectName() { return subjectName; }
    }
}
