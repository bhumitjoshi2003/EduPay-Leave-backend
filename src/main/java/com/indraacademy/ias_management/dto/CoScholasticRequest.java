package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Bulk upsert request for co-scholastic grades across an entire class. */
public class CoScholasticRequest {

    @NotNull
    private Long templateId;

    @NotNull
    private String session;

    @NotNull
    private List<StudentCoScholasticItem> studentEntries;

    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }

    public String getSession() { return session; }
    public void setSession(String session) { this.session = session; }

    public List<StudentCoScholasticItem> getStudentEntries() { return studentEntries; }
    public void setStudentEntries(List<StudentCoScholasticItem> studentEntries) { this.studentEntries = studentEntries; }

    public static class StudentCoScholasticItem {
        private String studentId;
        private List<ActivityGrade> entries;

        public String getStudentId() { return studentId; }
        public void setStudentId(String studentId) { this.studentId = studentId; }

        public List<ActivityGrade> getEntries() { return entries; }
        public void setEntries(List<ActivityGrade> entries) { this.entries = entries; }
    }

    public static class ActivityGrade {
        private String activity;
        private String grade;

        public String getActivity() { return activity; }
        public void setActivity(String activity) { this.activity = activity; }

        public String getGrade() { return grade; }
        public void setGrade(String grade) { this.grade = grade; }
    }
}
