package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "fee_structure")
@Data
public class FeeStructure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "academic_year")
    private String academicYear;

    @Column(name = "class_name")
    private String className;

    @Column(name = "tuition_fee")
    private double tuitionFee;

    @Column(name = "admission_fee")
    private double admissionFee;

    @Column(name = "annual_charges")
    private double annualCharges;

    @Column(name = "eca_project")
    private double ecaProject;

    @Column(name = "examination_fee")
    private double examinationFee;

    @Column(name = "lab_charges")
    private double labCharges;

    // Constructors, getters, and setters
    public FeeStructure() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAcademicYear() {
        return academicYear;
    }

    public void setAcademicYear(String academicYear) {
        this.academicYear = academicYear;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public double getTuitionFee() {
        return tuitionFee;
    }

    public void setTuitionFee(double tuitionFee) {
        this.tuitionFee = tuitionFee;
    }

    public double getAdmissionFee() {
        return admissionFee;
    }

    public void setAdmissionFee(double admissionFee) {
        this.admissionFee = admissionFee;
    }

    public double getAnnualCharges() {
        return annualCharges;
    }

    public void setAnnualCharges(double annualCharges) {
        this.annualCharges = annualCharges;
    }

    public double getEcaProject() {
        return ecaProject;
    }

    public void setEcaProject(double ecaProject) {
        this.ecaProject = ecaProject;
    }

    public double getExaminationFee() {
        return examinationFee;
    }

    public void setExaminationFee(double examinationFee) {
        this.examinationFee = examinationFee;
    }

    public double getLabCharges() {
        return labCharges;
    }

    public void setLabCharges(double labCharges) {
        this.labCharges = labCharges;
    }
}