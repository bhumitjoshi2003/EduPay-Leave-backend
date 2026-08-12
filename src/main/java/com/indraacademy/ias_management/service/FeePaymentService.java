package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.FeePaymentDto;
import com.indraacademy.ias_management.dto.RecordPaymentRequest;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.exception.SystemBFrozenException;
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

    @Autowired
    private RazorpayService razorpayService;

    /**
     * Record a payment and allocate it against invoices.
     * This handles both online (Razorpay) and manual (cash/cheque) payments.
     *
     * FROZEN (architecture decision — StudentFees/Payment/PaymentStudentFeesAllocation/
     * Refund is now the sole canonical financial system): the guard below rejects every
     * call before any read or write happens. The rest of the method — unreachable but
     * kept exactly as it was — is what existing FeePayment/PaymentAllocation rows were
     * created by; nothing here was deleted, and un-freezing later (if ever) is a one-line
     * revert of the guard. See SystemBFrozenException.
     */
    @Transactional
    public FeePaymentDto recordPayment(RecordPaymentRequest request, HttpServletRequest httpRequest) {
        throw new SystemBFrozenException("recording a fee payment");
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
