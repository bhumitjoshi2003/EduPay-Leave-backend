package com.indraacademy.ias_management.dto;

/**
 * Deliberately narrow: the only fields an admin may edit on an existing StudentFees row
 * through the generic "edit fee record" action. paid/amountPaid/manuallyPaid/
 * manualPaymentReceived/baseAmountDue/busFeeDue/discountAmount/amountRuleSnapshot/
 * snapshotStatus are NOT present here on purpose — those are exclusively derived from the
 * payment allocation ledger (StudentFeesService.markFeesAsPaid / PaymentService.
 * recomputeStudentFeesNetState) or the snapshot generation pipeline, never from a generic
 * admin edit. A field left null here is simply not changed, not cleared.
 */
public class StudentFeesAdminUpdateRequest {
    private Long id;
    private String className;
    private Long classId;
    private Boolean takesBus;
    private Double distance;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public Long getClassId() { return classId; }
    public void setClassId(Long classId) { this.classId = classId; }

    public Boolean getTakesBus() { return takesBus; }
    public void setTakesBus(Boolean takesBus) { this.takesBus = takesBus; }

    public Double getDistance() { return distance; }
    public void setDistance(Double distance) { this.distance = distance; }
}
