package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.FeePaymentDto;
import com.indraacademy.ias_management.dto.RecordPaymentRequest;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class FeePaymentService {

    private static final Logger log = LoggerFactory.getLogger(FeePaymentService.class);

    @Autowired
    private FeePaymentRepository paymentRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private PaymentAllocationRepository allocationRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private AuditService auditService;

    @Autowired
    private SecurityUtil securityUtil;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Record a payment and allocate it against invoices.
     * This handles both online (Razorpay) and manual (cash/cheque) payments.
     */
    @Transactional
    public FeePaymentDto recordPayment(RecordPaymentRequest request, HttpServletRequest httpRequest) {
        Long schoolId = securityUtil.getSchoolId();

        // Validate student belongs to this school
        Student student = studentRepository.findByStudentIdAndSchoolId(request.getStudentId(), schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Student not found."));

        // Prevent duplicate Razorpay payments
        if (request.getRazorpayPaymentId() != null
                && paymentRepository.existsByRazorpayPaymentId(request.getRazorpayPaymentId())) {
            throw new IllegalArgumentException("Payment already recorded.");
        }

        PaymentMode mode = PaymentMode.valueOf(request.getPaymentMode());

        FeePayment payment = new FeePayment();
        payment.setSchoolId(schoolId);
        payment.setStudentId(student.getStudentId());
        payment.setAmount(request.getAmount());
        payment.setPaymentMode(mode);
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setPaymentDate(LocalDateTime.now());
        payment.setRazorpayPaymentId(request.getRazorpayPaymentId());
        payment.setRazorpayOrderId(request.getRazorpayOrderId());
        payment.setRazorpaySignature(request.getRazorpaySignature());
        payment.setReferenceNumber(request.getReferenceNumber());
        payment.setNotes(request.getNotes());

        if (mode != PaymentMode.RAZORPAY) {
            payment.setReceivedBy(securityUtil.getUsername());
        }

        FeePayment savedPayment = paymentRepository.save(payment);

        // Allocate payment against invoices
        long totalAllocated = 0;
        for (RecordPaymentRequest.InvoiceAllocation allocation : request.getInvoiceAllocations()) {
            Invoice invoice = invoiceRepository.findByIdAndSchoolId(allocation.getInvoiceId(), schoolId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Invoice not found: " + allocation.getInvoiceId()));

            // Validate invoice can receive payments
            if (invoice.getStatus() == InvoiceStatus.DRAFT) {
                throw new IllegalArgumentException(
                        "Cannot pay draft invoice " + invoice.getInvoiceNumber() + ". Issue it first.");
            }
            if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
                throw new IllegalArgumentException(
                        "Cannot pay cancelled invoice " + invoice.getInvoiceNumber());
            }

            long allocAmount = allocation.getAmount();
            if (allocAmount > invoice.getBalanceDue()) {
                throw new IllegalArgumentException(
                        "Allocation amount " + allocAmount + " exceeds balance due "
                                + invoice.getBalanceDue() + " on invoice " + invoice.getInvoiceNumber());
            }

            PaymentAllocation pa = new PaymentAllocation();
            pa.setFeePayment(savedPayment);
            pa.setInvoice(invoice);
            pa.setAmountAllocated(allocAmount);
            allocationRepository.save(pa);

            // Update invoice
            invoice.setAmountPaid(invoice.getAmountPaid() + allocAmount);
            invoice.setBalanceDue(invoice.getBalanceDue() - allocAmount);

            if (invoice.getBalanceDue() == 0) {
                invoice.setStatus(InvoiceStatus.PAID);
            } else if (invoice.getAmountPaid() > 0) {
                invoice.setStatus(InvoiceStatus.PARTIALLY_PAID);
            }

            invoiceRepository.save(invoice);
            totalAllocated += allocAmount;
        }

        if (totalAllocated != request.getAmount()) {
            log.warn("Payment {} amount {} but only {} allocated to invoices",
                    savedPayment.getId(), request.getAmount(), totalAllocated);
        }

        // Audit log
        try {
            auditService.log(
                    securityUtil.getUsername(), securityUtil.getRole(),
                    "RECORD_PAYMENT", "FeePayment", String.valueOf(savedPayment.getId()),
                    null, objectMapper.writeValueAsString(toDto(savedPayment, student)),
                    httpRequest.getRemoteAddr());
        } catch (JsonProcessingException ignored) {}

        return toDto(savedPayment, student);
    }

    @Transactional(readOnly = true)
    public FeePaymentDto getPayment(Long paymentId) {
        Long schoolId = securityUtil.getSchoolId();
        FeePayment payment = paymentRepository.findByIdAndSchoolId(paymentId, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found."));

        Student student = studentRepository.findByStudentIdAndSchoolId(payment.getStudentId(), schoolId).orElse(null);
        return toDto(payment, student);
    }

    @Transactional(readOnly = true)
    public Page<FeePaymentDto> getPaymentHistory(
            String studentId, PaymentStatus status, LocalDateTime since, Pageable pageable) {
        Long schoolId = securityUtil.getSchoolId();
        Page<FeePayment> page = paymentRepository.findFiltered(schoolId, studentId, status, since, pageable);

        // Batch-load all students referenced in this page to avoid N+1 queries
        List<String> studentIds = page.getContent().stream()
                .map(FeePayment::getStudentId).distinct().collect(Collectors.toList());
        Map<String, Student> studentMap = studentRepository.findByStudentIdInAndSchoolId(studentIds, schoolId)
                .stream().collect(Collectors.toMap(Student::getStudentId, Function.identity()));

        return page.map(p -> toDto(p, studentMap.get(p.getStudentId())));
    }

    @Transactional(readOnly = true)
    public Page<FeePaymentDto> getStudentPaymentHistory(String studentId, Pageable pageable) {
        Long schoolId = securityUtil.getSchoolId();
        // Load the student once instead of per payment row
        Student student = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId).orElse(null);
        return paymentRepository.findBySchoolIdAndStudentId(schoolId, studentId, pageable)
                .map(p -> toDto(p, student));
    }

    private FeePaymentDto toDto(FeePayment payment, Student student) {
        FeePaymentDto dto = new FeePaymentDto();
        dto.setId(payment.getId());
        dto.setStudentId(payment.getStudentId());
        dto.setStudentName(student != null ? student.getName() : null);
        dto.setClassName(student != null ? student.getClassName() : null);
        dto.setAmount(payment.getAmount());
        dto.setPaymentMode(payment.getPaymentMode().name());
        dto.setStatus(payment.getStatus().name());
        dto.setRazorpayPaymentId(payment.getRazorpayPaymentId());
        dto.setRazorpayOrderId(payment.getRazorpayOrderId());
        dto.setReferenceNumber(payment.getReferenceNumber());
        dto.setNotes(payment.getNotes());
        dto.setReceivedBy(payment.getReceivedBy());
        dto.setPaymentDate(payment.getPaymentDate());

        if (payment.getAllocations() != null && !payment.getAllocations().isEmpty()) {
            dto.setAllocations(payment.getAllocations().stream()
                    .map(a -> {
                        FeePaymentDto.AllocationDto ad = new FeePaymentDto.AllocationDto();
                        ad.setInvoiceId(a.getInvoice().getId());
                        ad.setInvoiceNumber(a.getInvoice().getInvoiceNumber());
                        ad.setBillingMonth(a.getInvoice().getBillingMonth());
                        ad.setAmountAllocated(a.getAmountAllocated());
                        return ad;
                    })
                    .collect(Collectors.toList()));
        }

        return dto;
    }
}
