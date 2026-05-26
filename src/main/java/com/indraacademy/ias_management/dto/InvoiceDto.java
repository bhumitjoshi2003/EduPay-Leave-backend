package com.indraacademy.ias_management.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class InvoiceDto {
    private Long id;
    private String invoiceNumber;
    private String studentId;
    private String studentName;
    private String className;
    private Long academicSessionId;
    private String sessionLabel;
    private int billingMonth;
    private String billingMonthName;
    private LocalDate dueDate;
    private long totalAmount;
    private long discountAmount;
    private long netAmount;
    private long amountPaid;
    private long balanceDue;
    private String status;
    private LocalDateTime issuedAt;
    private LocalDateTime createdAt;
    private List<InvoiceLineItemDto> lineItems;

    public InvoiceDto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public String getStudentName() { return studentName; }
    public void setStudentName(String studentName) { this.studentName = studentName; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public Long getAcademicSessionId() { return academicSessionId; }
    public void setAcademicSessionId(Long academicSessionId) { this.academicSessionId = academicSessionId; }

    public String getSessionLabel() { return sessionLabel; }
    public void setSessionLabel(String sessionLabel) { this.sessionLabel = sessionLabel; }

    public int getBillingMonth() { return billingMonth; }
    public void setBillingMonth(int billingMonth) { this.billingMonth = billingMonth; }

    public String getBillingMonthName() { return billingMonthName; }
    public void setBillingMonthName(String billingMonthName) { this.billingMonthName = billingMonthName; }

    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }

    public long getTotalAmount() { return totalAmount; }
    public void setTotalAmount(long totalAmount) { this.totalAmount = totalAmount; }

    public long getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(long discountAmount) { this.discountAmount = discountAmount; }

    public long getNetAmount() { return netAmount; }
    public void setNetAmount(long netAmount) { this.netAmount = netAmount; }

    public long getAmountPaid() { return amountPaid; }
    public void setAmountPaid(long amountPaid) { this.amountPaid = amountPaid; }

    public long getBalanceDue() { return balanceDue; }
    public void setBalanceDue(long balanceDue) { this.balanceDue = balanceDue; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(LocalDateTime issuedAt) { this.issuedAt = issuedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public List<InvoiceLineItemDto> getLineItems() { return lineItems; }
    public void setLineItems(List<InvoiceLineItemDto> lineItems) { this.lineItems = lineItems; }
}
