package com.indraacademy.ias_management.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Optional subject group with its subjects — returned by GET /api/optional-groups and POST /api/optional-groups. */
public class OptionalGroupResponseDTO {

    private Long id;
    private String groupName;
    private List<OptionalSubjectDTO> subjects;

    @JsonCreator
    public OptionalGroupResponseDTO(
            @JsonProperty("id") Long id,
            @JsonProperty("groupName") String groupName,
            @JsonProperty("subjects") List<OptionalSubjectDTO> subjects) {
        this.id = id;
        this.groupName = groupName;
        this.subjects = subjects;
    }

    public Long getId() { return id; }
    public String getGroupName() { return groupName; }
    public List<OptionalSubjectDTO> getSubjects() { return subjects; }

    public static class OptionalSubjectDTO {
        private Long id;
        private String subjectName;

        @JsonCreator
        public OptionalSubjectDTO(
                @JsonProperty("id") Long id,
                @JsonProperty("subjectName") String subjectName) {
            this.id = id;
            this.subjectName = subjectName;
        }

        public Long getId() { return id; }
        public String getSubjectName() { return subjectName; }
    }
}
