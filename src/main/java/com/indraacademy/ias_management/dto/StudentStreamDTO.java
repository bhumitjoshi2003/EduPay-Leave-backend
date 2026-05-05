package com.indraacademy.ias_management.dto;

/**
 * Student with their stream + optional subject selection.
 * Returned by GET /api/student-stream/class/{className}.
 */
public class StudentStreamDTO {

    private String studentId;
    private String studentName;
    private String className;
    private Long streamId;
    private String streamName;
    private Long optionalSubjectId;
    private String optionalSubjectName;

    public StudentStreamDTO(String studentId, String studentName, String className,
                            Long streamId, String streamName,
                            Long optionalSubjectId, String optionalSubjectName) {
        this.studentId = studentId;
        this.studentName = studentName;
        this.className = className;
        this.streamId = streamId;
        this.streamName = streamName;
        this.optionalSubjectId = optionalSubjectId;
        this.optionalSubjectName = optionalSubjectName;
    }

    public String getStudentId() { return studentId; }
    public String getStudentName() { return studentName; }
    public String getClassName() { return className; }
    public Long getStreamId() { return streamId; }
    public String getStreamName() { return streamName; }
    public Long getOptionalSubjectId() { return optionalSubjectId; }
    public String getOptionalSubjectName() { return optionalSubjectName; }
}
