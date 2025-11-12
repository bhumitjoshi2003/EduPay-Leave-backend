package com.indraacademy.ias_management.dto; // Or your appropriate package

import java.time.LocalDateTime;

public class PaymentResponseDTO {
    private Long id;
    private String studentId;
    private String studentName;
    private String className;
    private String session;
    private String month;
    private int amount;
    private String paymentId;
    private String orderId;
    private LocalDateTime paymentDate;
    private String status;
    private int busFee;
    private int tuitionFee;
    private int annualCharges;
    private int labCharges;
    private int ecaProject;
    private int examinationFee;
    private int amountPaid;
    private int additionalCharges;
    private int lateFees;
    private int platformFee;

    public PaymentResponseDTO() {}

    public PaymentResponseDTO(String studentId, String studentName, String className, String session, String month, int amount, String paymentId, String orderId, LocalDateTime paymentDate, String status, int busFee, int tuitionFee, int annualCharges, int labCharges, int ecaProject, int examinationFee, int amountPaid, int additionalCharges, int lateFees, int platformFee) {
        this.studentId = studentId;
        this.studentName = studentName;
        this.className = className;
        this.session = session;
        this.month = month;
        this.amount = amount;
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.paymentDate = paymentDate;
        this.status = status;
        this.busFee = busFee;
        this.tuitionFee = tuitionFee;
        this.annualCharges = annualCharges;
        this.labCharges = labCharges;
        this.ecaProject = ecaProject;
        this.examinationFee = examinationFee;
        this.amountPaid = amountPaid;
        this.additionalCharges = additionalCharges;
        this.lateFees = lateFees;
        this.platformFee = platformFee;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getSession() {
        return session;
    }

    public void setSession(String session) {
        this.session = session;
    }

    public String getMonth() {
        return month;
    }

    public void setMonth(String month) {
        this.month = month;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
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

    public int getBusFee() {
        return busFee;
    }

    public void setBusFee(int busFee) {
        this.busFee = busFee;
    }

    public int getTuitionFee() {
        return tuitionFee;
    }

    public void setTuitionFee(int tuitionFee) {
        this.tuitionFee = tuitionFee;
    }

    public int getAnnualCharges() {
        return annualCharges;
    }

    public void setAnnualCharges(int annualCharges) {
        this.annualCharges = annualCharges;
    }

    public int getLabCharges() {
        return labCharges;
    }

    public void setLabCharges(int labCharges) {
        this.labCharges = labCharges;
    }

    public int getEcaProject() {
        return ecaProject;
    }

    public void setEcaProject(int ecaProject) {
        this.ecaProject = ecaProject;
    }

    public int getExaminationFee() {
        return examinationFee;
    }

    public void setExaminationFee(int examinationFee) {
        this.examinationFee = examinationFee;
    }

    public int getAmountPaid() { return amountPaid; }

    public void setAmountPaid(int amountPaid) { this.amountPaid = amountPaid; }

    public int getAdditionalCharges() {
        return additionalCharges;
    }

    public void setAdditionalCharges(int additionalCharges) {
        this.additionalCharges = additionalCharges;
    }

    public int getLateFees() {
        return lateFees;
    }

    public void setLateFees(int lateFees) {
        this.lateFees = lateFees;
    }

    public int getPlatformFee() { return platformFee; }

    public void setPlatformFee(int platformFee) { this.platformFee = platformFee; }
}