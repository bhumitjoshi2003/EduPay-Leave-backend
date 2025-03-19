package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment")
@Data
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "student_id", nullable = false)
    private String studentId;

    @Column(name = "student_name", nullable = false)
    private String studentName;

    @Column(name = "class_name", nullable = false)
    private String className;

    @Column(name = "session", nullable = false)
    private String session;

    @Column(name = "month", nullable = false)
    private String month;

    @Column(name = "amount", nullable = false)
    private int amount;

    @Column(name = "payment_id", nullable = false)
    private String paymentId;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "payment_date", nullable = false)
    private LocalDateTime paymentDate;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "bus_fee")
    private int busFee;

    @Column(name = "tuition_fee")
    private int tuitionFee;

    @Column(name = "annual_charges")
    private int annualCharges;

    @Column(name = "lab_charges")
    private int labCharges;

    @Column(name = "eca_project")
    private int ecaProject;

    @Column(name = "examination_fee")
    private int examinationFee;

    @Column(name = "razorpay_signature", nullable = false)
    private String razorpaySignature;

    // Constructors, getters, setters
    public Payment() {
        this.paymentDate = LocalDateTime.now();
        this.status = "success";
    }

    public Payment(String studentId, String studentName, String className, String session, String month, int amount, String paymentId, String orderId, LocalDateTime paymentDate, String status, int busFee, int tuitionFee, int annualCharges, int labCharges, int ecaProject, int examinationFee) {
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
    }

    public Long getId() {
        return id;
    }

    public String getStudentId() {
        return studentId;
    }

    public String getStudentName() {
        return studentName;
    }

    public String getClassName() {
        return className;
    }

    public String getSession() {
        return session;
    }

    public String getMonth() {
        return month;
    }

    public int getAmount() {
        return amount;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getOrderId() {
        return orderId;
    }

    public LocalDateTime getPaymentDate() {
        return paymentDate;
    }

    public String getStatus() {
        return status;
    }

    public int getBusFee() {
        return busFee;
    }

    public int getTuitionFee() {
        return tuitionFee;
    }

    public int getAnnualCharges() {
        return annualCharges;
    }

    public int getLabCharges() {
        return labCharges;
    }

    public int getEcaProject() {
        return ecaProject;
    }

    public int getExaminationFee() {
        return examinationFee;
    }

    public String getRazorpaySignature() {
        return razorpaySignature;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
    }

    public void setStudentName(String studentName) {
        this.studentName = studentName;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public void setSession(String session) {
        this.session = session;
    }

    public void setMonth(String month) {
        this.month = month;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public void setExaminationFee(int examinationFee) {
        this.examinationFee = examinationFee;
    }

    public void setPaymentDate(LocalDateTime paymentDate) {
        this.paymentDate = paymentDate;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setBusFee(int busFee) {
        this.busFee = busFee;
    }

    public void setTuitionFee(int tuitionFee) {
        this.tuitionFee = tuitionFee;
    }

    public void setAnnualCharges(int annualCharges) {
        this.annualCharges = annualCharges;
    }

    public void setLabCharges(int labCharges) {
        this.labCharges = labCharges;
    }

    public void setEcaProject(int ecaProject) {
        this.ecaProject = ecaProject;
    }

    public void setRazorpaySignature(String razorpaySignature) {
        this.razorpaySignature = razorpaySignature;
    }
}
