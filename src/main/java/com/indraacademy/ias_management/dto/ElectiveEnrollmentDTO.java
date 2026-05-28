package com.indraacademy.ias_management.dto;

/** Represents a single student's elective choice for one optional group. */
public class ElectiveEnrollmentDTO {
    private Long id;
    private String studentId;
    private String studentName;
    private String className;
    private String optionalGroup;
    private String subjectName;

    public ElectiveEnrollmentDTO() {}

    public ElectiveEnrollmentDTO(Long id, String studentId, String studentName,
                                  String className, String optionalGroup, String subjectName) {
        this.id = id;
        this.studentId = studentId;
        this.studentName = studentName;
        this.className = className;
        this.optionalGroup = optionalGroup;
        this.subjectName = subjectName;
    }

    public Long getId() { return id; }
    public String getStudentId() { return studentId; }
    public String getStudentName() { return studentName; }
    public String getClassName() { return className; }
    public String getOptionalGroup() { return optionalGroup; }
    public String getSubjectName() { return subjectName; }

    public void setId(Long id) { this.id = id; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public void setStudentName(String studentName) { this.studentName = studentName; }
    public void setClassName(String className) { this.className = className; }
    public void setOptionalGroup(String optionalGroup) { this.optionalGroup = optionalGroup; }
    public void setSubjectName(String subjectName) { this.subjectName = subjectName; }
}
