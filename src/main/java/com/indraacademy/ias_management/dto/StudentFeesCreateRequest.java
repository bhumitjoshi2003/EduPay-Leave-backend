package com.indraacademy.ias_management.dto;

/**
 * Ad-hoc single-row creation (e.g. an admin backfilling one missing month) — deliberately
 * carries only identity/non-financial fields. The row's financial snapshot (baseAmountDue/
 * busFeeDue/discountAmount/amountRuleSnapshot/snapshotStatus) is always computed
 * server-side via FeeCalculationService, exactly like bulk generation does; it is never
 * accepted from the client. The row always starts unpaid — there is no such thing as a
 * "pre-paid" row created out of thin air.
 */
public class StudentFeesCreateRequest {
    private String studentId;
    private String className;
    private String year;
    private Integer month;
    private Boolean takesBus;
    private Double distance;

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getYear() { return year; }
    public void setYear(String year) { this.year = year; }

    public Integer getMonth() { return month; }
    public void setMonth(Integer month) { this.month = month; }

    public Boolean getTakesBus() { return takesBus; }
    public void setTakesBus(Boolean takesBus) { this.takesBus = takesBus; }

    public Double getDistance() { return distance; }
    public void setDistance(Double distance) { this.distance = distance; }
}
