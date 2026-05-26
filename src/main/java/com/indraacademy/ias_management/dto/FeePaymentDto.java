package com.indraacademy.ias_management.dto;

import java.time.LocalDateTime;
import java.util.List;

public class FeePaymentDto {
    private Long id;
    private String studentId;
    private String studentName;
    private String className;
    private long amount;
    private String paymentMode;
    private String status;
    private String razorpayPaymentId;
    private String razorpayOrderId;
    private String referenceNumber;
    private String notes;
    private String receivedBy;
    private LocalDateTime paymentDate;
    private List<AllocationDto> allocations;

    public FeePaymentDto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public String getStudentName() { return studentName; }
    public void setStudentName(String studentName) { this.studentName = studentName; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public String getPaymentMode() { return paymentMode; }
    public void setPaymentMode(String paymentMode) { this.paymentMode = paymentMode; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRazorpayPaymentId() { return razorpayPaymentId; }
    public void setRazorpayPaymentId(String razorpayPaymentId) { this.razorpayPaymentId = razorpayPaymentId; }

    public String getRazorpayOrderId() { return razorpayOrderId; }
    public void setRazorpayOrderId(String razorpayOrderId) { this.razorpayOrderId = razorpayOrderId; }

    public String getReferenceNumber() { return referenceNumber; }
    public void setReferenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getReceivedBy() { return receivedBy; }
    public void setReceivedBy(String receivedBy) { this.receivedBy = receivedBy; }

    public LocalDateTime getPaymentDate() { return paymentDate; }
    public void setPaymentDate(LocalDateTime paymentDate) { this.paymentDate = paymentDate; }

    public List<AllocationDto> getAllocations() { return allocations; }
    public void setAllocations(List<AllocationDto> allocations) { this.allocations = allocations; }

    public static class AllocationDto {
        private Long invoiceId;
        private String invoiceNumber;
        private int billingMonth;
        private long amountAllocated;

        public Long getInvoiceId() { return invoiceId; }
        public void setInvoiceId(Long invoiceId) { this.invoiceId = invoiceId; }

        public String getInvoiceNumber() { return invoiceNumber; }
        public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }

        public int getBillingMonth() { return billingMonth; }
        public void setBillingMonth(int billingMonth) { this.billingMonth = billingMonth; }

        public long getAmountAllocated() { return amountAllocated; }
        public void setAmountAllocated(long amountAllocated) { this.amountAllocated = amountAllocated; }
    }
}
