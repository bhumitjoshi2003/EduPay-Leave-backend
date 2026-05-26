package com.indraacademy.ias_management.dto;

import java.util.List;

/**
 * Overview of a student's fee status for a session — used by student dashboard and fee page.
 */
public class StudentFeeOverviewDto {
    private String studentId;
    private String studentName;
    private String className;
    private String sessionLabel;
    private long totalFeeForYear;
    private long totalPaid;
    private long totalOutstanding;
    private List<InvoiceDto> invoices;

    public StudentFeeOverviewDto() {}

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public String getStudentName() { return studentName; }
    public void setStudentName(String studentName) { this.studentName = studentName; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getSessionLabel() { return sessionLabel; }
    public void setSessionLabel(String sessionLabel) { this.sessionLabel = sessionLabel; }

    public long getTotalFeeForYear() { return totalFeeForYear; }
    public void setTotalFeeForYear(long totalFeeForYear) { this.totalFeeForYear = totalFeeForYear; }

    public long getTotalPaid() { return totalPaid; }
    public void setTotalPaid(long totalPaid) { this.totalPaid = totalPaid; }

    public long getTotalOutstanding() { return totalOutstanding; }
    public void setTotalOutstanding(long totalOutstanding) { this.totalOutstanding = totalOutstanding; }

    public List<InvoiceDto> getInvoices() { return invoices; }
    public void setInvoices(List<InvoiceDto> invoices) { this.invoices = invoices; }
}
