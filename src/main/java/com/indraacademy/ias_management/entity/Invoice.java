package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "invoice",
        uniqueConstraints = @UniqueConstraint(name = "uq_invoice_number_school",
                columnNames = {"school_id", "invoice_number"}),
        indexes = {
                @Index(name = "idx_invoice_student_session",
                        columnList = "school_id, student_id, academic_session_id"),
                @Index(name = "idx_invoice_status",
                        columnList = "school_id, status")
        })
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_number", nullable = false, length = 30)
    private String invoiceNumber;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "student_id", nullable = false, length = 50)
    private String studentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academic_session_id", nullable = false)
    private AcademicSession academicSession;

    /** Academic month (1-12) this invoice covers */
    @Column(name = "billing_month", nullable = false)
    private int billingMonth;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    /** All amounts in paise */
    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Column(name = "discount_amount", nullable = false)
    private long discountAmount;

    @Column(name = "net_amount", nullable = false)
    private long netAmount;

    @Column(name = "amount_paid", nullable = false)
    private long amountPaid;

    @Column(name = "balance_due", nullable = false)
    private long balanceDue;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    /** Date the invoice was finalized (moved from DRAFT to ISSUED). Null if still draft. */
    @Column(name = "issued_at")
    private LocalDateTime issuedAt;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<InvoiceLineItem> lineItems = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Invoice() {}

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public AcademicSession getAcademicSession() { return academicSession; }
    public void setAcademicSession(AcademicSession academicSession) { this.academicSession = academicSession; }

    public int getBillingMonth() { return billingMonth; }
    public void setBillingMonth(int billingMonth) { this.billingMonth = billingMonth; }

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

    public InvoiceStatus getStatus() { return status; }
    public void setStatus(InvoiceStatus status) { this.status = status; }

    public LocalDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(LocalDateTime issuedAt) { this.issuedAt = issuedAt; }

    public List<InvoiceLineItem> getLineItems() { return lineItems; }
    public void setLineItems(List<InvoiceLineItem> lineItems) { this.lineItems = lineItems; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public void addLineItem(InvoiceLineItem item) {
        lineItems.add(item);
        item.setInvoice(this);
    }
}
