package com.indraacademy.ias_management.dto;

import java.util.List;

/** Stream with its core subjects — returned by GET /api/streams. */
public class StreamResponseDTO {

    private Long id;
    private String streamName;
    private List<CoreSubjectDTO> coreSubjects;

    public StreamResponseDTO(Long id, String streamName, List<CoreSubjectDTO> coreSubjects) {
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

        public CoreSubjectDTO(Long id, String subjectName) {
            this.id = id;
            this.subjectName = subjectName;
        }

        public Long getId() { return id; }
        public String getSubjectName() { return subjectName; }
    }
}
