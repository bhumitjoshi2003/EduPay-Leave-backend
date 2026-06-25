package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ReportCardPublicationDTO;
import com.indraacademy.ias_management.dto.ReportCardTemplateDTO;
import com.indraacademy.ias_management.entity.ReportCardPublication;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.ReportCardPublicationRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class ReportCardPublicationService {

    private static final Logger log = LoggerFactory.getLogger(ReportCardPublicationService.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired private ReportCardPublicationRepository pubRepo;
    @Autowired private ReportCardTemplateService       templateService;
    @Autowired private StudentRepository               studentRepository;
    @Autowired private ReportCardEmailBlastService     blastService;
    @Autowired private SecurityUtil                    securityUtil;

    // ── Status ─────────────────────────────────────────────────────────────

    public ReportCardPublicationDTO getStatus(Long templateId, String session, String className) {
        Long schoolId = securityUtil.getSchoolId();
        return pubRepo
            .findBySchoolIdAndTemplateIdAndSessionAndClassName(schoolId, templateId, session, className)
            .map(p -> toDTO(p, templateId))
            .orElse(ReportCardPublicationDTO.notPublished(templateId, session, className));
    }

    /** Used by the controller to gate STUDENT access — lightweight exists-check. */
    public boolean isPublished(Long templateId, String session, String className) {
        Long schoolId = securityUtil.getSchoolId();
        return pubRepo.existsBySchoolIdAndTemplateIdAndSessionAndClassName(
                schoolId, templateId, session, className);
    }

    // ── Publish ────────────────────────────────────────────────────────────

    @Transactional
    public ReportCardPublicationDTO publish(Long templateId, String session, String className) {
        Long schoolId = securityUtil.getSchoolId();
        String username = securityUtil.getUsername();

        ReportCardPublication pub = pubRepo
            .findBySchoolIdAndTemplateIdAndSessionAndClassName(schoolId, templateId, session, className)
            .orElseGet(() -> {
                ReportCardPublication p = new ReportCardPublication();
                p.setSchoolId(schoolId);
                p.setTemplateId(templateId);
                p.setSession(session);
                p.setClassName(className);
                return p;
            });

        pub.setPublishedAt(LocalDateTime.now());
        pub.setPublishedBy(username);
        pub = pubRepo.save(pub);

        log.info("Report card published: template={} session={} class={} by={}",
                 templateId, session, className, username);
        return toDTO(pub, templateId);
    }

    // ── Unpublish ──────────────────────────────────────────────────────────

    @Transactional
    public void unpublish(Long templateId, String session, String className) {
        Long schoolId = securityUtil.getSchoolId();
        pubRepo.deleteBySchoolIdAndTemplateIdAndSessionAndClassName(schoolId, templateId, session, className);
        log.info("Report card unpublished: template={} session={} class={}", templateId, session, className);
    }

    // ── Email blast ────────────────────────────────────────────────────────

    /**
     * Validates the publication exists, counts eligible students, fires the
     * async blast, and returns the initiated count immediately.
     */
    public int startEmailBlast(Long templateId, String session, String className) {
        Long schoolId = securityUtil.getSchoolId();

        if (!pubRepo.existsBySchoolIdAndTemplateIdAndSessionAndClassName(
                schoolId, templateId, session, className)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Report card must be published before sending emails.");
        }

        List<Student> students = studentRepository
            .findByClassNameAndStatusAndSchoolId(className, StudentStatus.ACTIVE, schoolId);

        int withEmail = (int) students.stream()
            .filter(s -> s.getEmail() != null && !s.getEmail().isBlank())
            .count();

        String schoolName = "";
        try {
            ReportCardTemplateDTO t = templateService.getTemplate(templateId);
            schoolName = t.getName();
        } catch (Exception ignored) {}

        blastService.execute(templateId, session, className, schoolId, students, schoolName);
        return withEmail;
    }

    // ── Helper ─────────────────────────────────────────────────────────────

    private ReportCardPublicationDTO toDTO(ReportCardPublication pub, Long templateId) {
        String tName = null;
        try { tName = templateService.getTemplate(templateId).getName(); } catch (Exception ignored) {}
        return new ReportCardPublicationDTO(
            true,
            pub.getId(),
            pub.getTemplateId(),
            tName,
            pub.getSession(),
            pub.getClassName(),
            pub.getPublishedAt() != null ? pub.getPublishedAt().format(ISO) : null,
            pub.getPublishedBy(),
            pub.getEmailSentAt() != null ? pub.getEmailSentAt().format(ISO) : null,
            pub.getEmailCount()
        );
    }
}
