package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.InvoiceDto;
import com.indraacademy.ias_management.dto.InvoiceLineItemDto;
import com.indraacademy.ias_management.dto.StudentFeeOverviewDto;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.InvoiceRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Month;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class InvoiceService {

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private AcademicSessionService sessionService;

    @Autowired
    private SecurityUtil securityUtil;

    @Transactional(readOnly = true)
    public InvoiceDto getInvoice(Long invoiceId) {
        Long schoolId = securityUtil.getSchoolId();
        Invoice invoice = invoiceRepository.findByIdAndSchoolId(invoiceId, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found."));
        Student student = studentRepository.findByStudentIdAndSchoolId(invoice.getStudentId(), schoolId).orElse(null);
        return toDto(invoice, student);
    }

    @Transactional(readOnly = true)
    public StudentFeeOverviewDto getStudentFeeOverview(String studentId, Long sessionId) {
        Long schoolId = securityUtil.getSchoolId();

        Student student = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Student not found."));

        AcademicSession session;
        if (sessionId != null) {
            session = sessionService.getCurrentSessionEntity();
            // If specific session requested, verify it
        } else {
            session = sessionService.getCurrentSessionEntity();
        }

        List<Invoice> invoices = invoiceRepository.findBySchoolIdAndStudentIdAndAcademicSessionId(
                schoolId, studentId, session.getId());

        long totalFee = invoices.stream().mapToLong(Invoice::getNetAmount).sum();
        long totalPaid = invoices.stream().mapToLong(Invoice::getAmountPaid).sum();

        StudentFeeOverviewDto overview = new StudentFeeOverviewDto();
        overview.setStudentId(student.getStudentId());
        overview.setStudentName(student.getName());
        overview.setClassName(student.getClassName());
        overview.setSessionLabel(session.getLabel());
        overview.setTotalFeeForYear(totalFee);
        overview.setTotalPaid(totalPaid);
        overview.setTotalOutstanding(totalFee - totalPaid);
        // Reuse already-loaded student instead of re-querying per invoice
        overview.setInvoices(invoices.stream().map(i -> toDto(i, student)).collect(Collectors.toList()));

        return overview;
    }

    @Transactional(readOnly = true)
    public List<InvoiceDto> getOutstandingInvoices(String studentId) {
        Long schoolId = securityUtil.getSchoolId();
        // Load student once instead of per invoice
        Student student = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId).orElse(null);
        return invoiceRepository.findOutstandingByStudent(schoolId, studentId)
                .stream()
                .map(i -> toDto(i, student))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<InvoiceDto> getFilteredInvoices(
            String studentId, Long sessionId, InvoiceStatus status, Pageable pageable) {
        Long schoolId = securityUtil.getSchoolId();
        Page<Invoice> page = invoiceRepository.findFiltered(schoolId, studentId, sessionId, status, pageable);

        // Batch-load all students referenced in this page
        List<String> studentIds = page.getContent().stream()
                .map(Invoice::getStudentId).distinct().collect(Collectors.toList());
        Map<String, Student> studentMap = studentRepository.findByStudentIdInAndSchoolId(studentIds, schoolId)
                .stream().collect(Collectors.toMap(Student::getStudentId, Function.identity()));

        return page.map(i -> toDto(i, studentMap.get(i.getStudentId())));
    }

    private InvoiceDto toDto(Invoice invoice, Student student) {
        InvoiceDto dto = new InvoiceDto();
        dto.setId(invoice.getId());
        dto.setInvoiceNumber(invoice.getInvoiceNumber());
        dto.setStudentId(invoice.getStudentId());

        if (student != null) {
            dto.setStudentName(student.getName());
            dto.setClassName(student.getClassName());
        }

        dto.setAcademicSessionId(invoice.getAcademicSession().getId());
        dto.setSessionLabel(invoice.getAcademicSession().getLabel());
        dto.setBillingMonth(invoice.getBillingMonth());
        dto.setBillingMonthName(getBillingMonthName(invoice));
        dto.setDueDate(invoice.getDueDate());
        dto.setTotalAmount(invoice.getTotalAmount());
        dto.setDiscountAmount(invoice.getDiscountAmount());
        dto.setNetAmount(invoice.getNetAmount());
        dto.setAmountPaid(invoice.getAmountPaid());
        dto.setBalanceDue(invoice.getBalanceDue());
        dto.setStatus(invoice.getStatus().name());
        dto.setIssuedAt(invoice.getIssuedAt());
        dto.setCreatedAt(invoice.getCreatedAt());

        if (invoice.getLineItems() != null) {
            dto.setLineItems(invoice.getLineItems().stream()
                    .map(this::toLineItemDto)
                    .collect(Collectors.toList()));
        }

        return dto;
    }

    private InvoiceLineItemDto toLineItemDto(InvoiceLineItem item) {
        InvoiceLineItemDto dto = new InvoiceLineItemDto();
        dto.setId(item.getId());
        dto.setFeeHeadId(item.getFeeHead().getId());
        dto.setFeeHeadCode(item.getFeeHeadCode());
        dto.setDescription(item.getDescription());
        dto.setBaseAmount(item.getBaseAmount());
        dto.setDiscountAmount(item.getDiscountAmount());
        dto.setNetAmount(item.getNetAmount());
        return dto;
    }

    /**
     * Convert billing month (academic month 1-12) to calendar month name.
     */
    private String getBillingMonthName(Invoice invoice) {
        AcademicSession session = invoice.getAcademicSession();
        int startMonth = session.getStartDate().getMonthValue();
        int calendarMonth = ((startMonth - 1 + invoice.getBillingMonth() - 1) % 12) + 1;
        return Month.of(calendarMonth).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }
}
