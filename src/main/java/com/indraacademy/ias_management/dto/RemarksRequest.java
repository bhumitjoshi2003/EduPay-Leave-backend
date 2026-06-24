package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Bulk upsert request for teacher/principal remarks across an entire class. */
public class RemarksRequest {

    @NotNull
    private Long templateId;

    @NotNull
    private String session;

    @NotNull
    private List<StudentRemarkItem> studentRemarks;

    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }

    public String getSession() { return session; }
    public void setSession(String session) { this.session = session; }

    public List<StudentRemarkItem> getStudentRemarks() { return studentRemarks; }
    public void setStudentRemarks(List<StudentRemarkItem> studentRemarks) { this.studentRemarks = studentRemarks; }

    public static class StudentRemarkItem {
        private String studentId;
        private String teacherRemark;
        private String principalRemark;

        public String getStudentId() { return studentId; }
        public void setStudentId(String studentId) { this.studentId = studentId; }

        public String getTeacherRemark() { return teacherRemark; }
        public void setTeacherRemark(String teacherRemark) { this.teacherRemark = teacherRemark; }

        public String getPrincipalRemark() { return principalRemark; }
        public void setPrincipalRemark(String principalRemark) { this.principalRemark = principalRemark; }
    }
}
