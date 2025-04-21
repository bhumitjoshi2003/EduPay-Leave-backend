package com.indraacademy.ias_management.dto;

import java.time.LocalDateTime;

public class PaymentHistoryDTO {
    private String studentId;
    private String studentName;
    private String paymentId;
    private int amountPaid;
    private LocalDateTime paymentDate;
    private String status;
    private String className;

    public PaymentHistoryDTO() { }

    public PaymentHistoryDTO(String studentId, String studentName, String paymentId, int amountPaid, LocalDateTime paymentDate, String status, String className) {
        this.studentId = studentId;
        this.studentName = studentName;
        this.paymentId = paymentId;
        this.amountPaid = amountPaid;
        this.paymentDate = paymentDate;
        this.status = status;
        this.className = className;
    }

    public String getStudentId() {
        return studentId;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
    }

    public String getStudentName() {
        return studentName;
    }

    public void setStudentName(String studentName) {
        this.studentName = studentName;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
    }

    public int getAmountPaid() {
        return amountPaid;
    }

    public void setAmountPaid(int amountPaid) {
        this.amountPaid = amountPaid;
    }

    public LocalDateTime getPaymentDate() {
        return paymentDate;
    }

    public void setPaymentDate(LocalDateTime paymentDate) {
        this.paymentDate = paymentDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }
}
