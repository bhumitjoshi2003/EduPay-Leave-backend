package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ReportCardDataDTO;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.ReportCardPublicationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Separated from ReportCardPublicationService so that @Async is invoked via
 * the Spring proxy (calling @Async from within the same bean bypasses the proxy).
 */
@Service
public class ReportCardEmailBlastService {

    private static final Logger log = LoggerFactory.getLogger(ReportCardEmailBlastService.class);

    @Autowired private ReportCardPublicationRepository pubRepo;
    @Autowired private ReportCardDataAssembler         assembler;
    @Autowired private ReportCardPdfGenerator          pdfGenerator;
    @Autowired private EmailService                    emailService;

    @Async
    public void execute(Long templateId, String session, String className,
                        Long schoolId, List<Student> students, String schoolName) {
        int sent = 0;
        for (Student student : students) {
            String email = student.getEmail();
            if (email == null || email.isBlank()) continue;
            try {
                ReportCardDataDTO data = assembler.assemble(student.getStudentId(), templateId, session);
                byte[] pdf  = pdfGenerator.generate(data);
                String name = student.getName() != null ? student.getName() : student.getStudentId();
                String sn   = data.getSchoolName() != null ? data.getSchoolName() : schoolName;
                String file = sanitize(name) + "_" + session.replace("/", "-") + "_ReportCard.pdf";
                emailService.sendReportCardEmail(email, name, sn, session, pdf, file);
                sent++;
            } catch (Exception e) {
                log.warn("Email blast: skipping student {} — {}", student.getStudentId(), e.getMessage());
            }
        }

        // Persist stats
        try {
            final int finalSent = sent;
            pubRepo.findBySchoolIdAndTemplateIdAndSessionAndClassName(schoolId, templateId, session, className)
                   .ifPresent(pub -> {
                       pub.setEmailSentAt(LocalDateTime.now());
                       pub.setEmailCount(finalSent);
                       pubRepo.save(pub);
                   });
            log.info("Email blast complete: template={} session={} class={} sent={}", templateId, session, className, sent);
        } catch (Exception e) {
            log.error("Failed to persist email blast stats: {}", e.getMessage(), e);
        }
    }

    private String sanitize(String name) {
        if (name == null) return "Student";
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_").replaceAll("_+", "_");
    }
}
