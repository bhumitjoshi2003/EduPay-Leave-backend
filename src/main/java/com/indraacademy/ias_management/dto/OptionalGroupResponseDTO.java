package com.indraacademy.ias_management.dto;

import java.util.List;

/** Optional subject group with its subject options — returned by GET /api/optional-groups. */
public class OptionalGroupResponseDTO {

    private Long id;
    private String groupName;
    private List<OptionalSubjectDTO> subjects;

    public OptionalGroupResponseDTO(Long id, String groupName, List<OptionalSubjectDTO> subjects) {
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

        public OptionalSubjectDTO(Long id, String subjectName) {
            this.id = id;
            this.subjectName = subjectName;
        }

        public Long getId() { return id; }
        public String getSubjectName() { return subjectName; }
    }
}
