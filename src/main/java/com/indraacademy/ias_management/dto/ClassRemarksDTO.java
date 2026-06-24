package com.indraacademy.ias_management.dto;

import java.util.List;

/** Response DTO for loading all remarks + co-scholastic data for a class in one call. */
public class ClassRemarksDTO {

    private List<StudentRemarksData> students;

    public ClassRemarksDTO(List<StudentRemarksData> students) {
        this.students = students;
    }

    public List<StudentRemarksData> getStudents() { return students; }

    public static class StudentRemarksData {
        private String studentId;
        private String studentName;
        private String teacherRemark;
        private String principalRemark;
        private List<ActivityGrade> coScholasticEntries;

        public StudentRemarksData(String studentId, String studentName,
                                   String teacherRemark, String principalRemark,
                                   List<ActivityGrade> coScholasticEntries) {
            this.studentId = studentId;
            this.studentName = studentName;
            this.teacherRemark = teacherRemark;
            this.principalRemark = principalRemark;
            this.coScholasticEntries = coScholasticEntries;
        }

        public String getStudentId() { return studentId; }
        public String getStudentName() { return studentName; }
        public String getTeacherRemark() { return teacherRemark; }
        public String getPrincipalRemark() { return principalRemark; }
        public List<ActivityGrade> getCoScholasticEntries() { return coScholasticEntries; }
    }

    public static class ActivityGrade {
        private String activity;
        private String grade;

        public ActivityGrade(String activity, String grade) {
            this.activity = activity;
            this.grade = grade;
        }

        public String getActivity() { return activity; }
        public String getGrade() { return grade; }
    }
}
