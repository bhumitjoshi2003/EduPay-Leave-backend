package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public class RecordPaymentRequest {

    @NotBlank
    private String studentId;

    @NotNull
    @Min(1)
    private Long amount;

    @NotNull
    private String paymentMode;

    /** Invoice IDs to allocate payment against */
    @NotNull
    private List<InvoiceAllocation> invoiceAllocations;

    // For manual payments
    private String referenceNumber;
    private String notes;

    // For Razorpay payments
    private String razorpayPaymentId;
    private String razorpayOrderId;
    private String razorpaySignature;

    public RecordPaymentRequest() {}

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }

    public String getPaymentMode() { return paymentMode; }
    public void setPaymentMode(String paymentMode) { this.paymentMode = paymentMode; }

    public List<InvoiceAllocation> getInvoiceAllocations() { return invoiceAllocations; }
    public void setInvoiceAllocations(List<InvoiceAllocation> invoiceAllocations) { this.invoiceAllocations = invoiceAllocations; }

    public String getReferenceNumber() { return referenceNumber; }
    public void setReferenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getRazorpayPaymentId() { return razorpayPaymentId; }
    public void setRazorpayPaymentId(String razorpayPaymentId) { this.razorpayPaymentId = razorpayPaymentId; }

    public String getRazorpayOrderId() { return razorpayOrderId; }
    public void setRazorpayOrderId(String razorpayOrderId) { this.razorpayOrderId = razorpayOrderId; }

    public String getRazorpaySignature() { return razorpaySignature; }
    public void setRazorpaySignature(String razorpaySignature) { this.razorpaySignature = razorpaySignature; }

    public static class InvoiceAllocation {
        @NotNull
        private Long invoiceId;

        @NotNull
        @Min(1)
        private Long amount;

        public Long getInvoiceId() { return invoiceId; }
        public void setInvoiceId(Long invoiceId) { this.invoiceId = invoiceId; }

        public Long getAmount() { return amount; }
        public void setAmount(Long amount) { this.amount = amount; }
    }
}
