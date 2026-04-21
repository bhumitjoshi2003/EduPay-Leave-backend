package com.indraacademy.ias_management.dto;

import java.util.List;

public class OverdueStudentDto {

    private String studentId;
    private String studentName;
    private String className;
    private String parentPhone;
    private String parentEmail;
    private List<String> unpaidMonths;
    private double totalDue;
    private String lastPaymentDate;
    private int daysOverdue;

    public OverdueStudentDto() {}

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public String getStudentName() { return studentName; }
    public void setStudentName(String studentName) { this.studentName = studentName; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getParentPhone() { return parentPhone; }
    public void setParentPhone(String parentPhone) { this.parentPhone = parentPhone; }

    public String getParentEmail() { return parentEmail; }
    public void setParentEmail(String parentEmail) { this.parentEmail = parentEmail; }

    public List<String> getUnpaidMonths() { return unpaidMonths; }
    public void setUnpaidMonths(List<String> unpaidMonths) { this.unpaidMonths = unpaidMonths; }

    public double getTotalDue() { return totalDue; }
    public void setTotalDue(double totalDue) { this.totalDue = totalDue; }

    public String getLastPaymentDate() { return lastPaymentDate; }
    public void setLastPaymentDate(String lastPaymentDate) { this.lastPaymentDate = lastPaymentDate; }

    public int getDaysOverdue() { return daysOverdue; }
    public void setDaysOverdue(int daysOverdue) { this.daysOverdue = daysOverdue; }
}
