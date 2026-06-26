package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.*;
import com.indraacademy.ias_management.dto.VerifyRcDTO;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.service.ReportCardDataAssembler;
import com.indraacademy.ias_management.service.ReportCardPdfGenerator;
import com.indraacademy.ias_management.dto.ClassOverviewDTO;
import com.indraacademy.ias_management.service.ClassOverviewService;
import com.indraacademy.ias_management.service.ReportCardPublicationService;
import com.indraacademy.ias_management.service.ReportCardTemplateService;
import com.indraacademy.ias_management.service.RemarksService;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api")
public class ReportCardController {

    @Autowired private ReportCardTemplateService     templateService;
    @Autowired private ReportCardDataAssembler       assembler;
    @Autowired private RemarksService                remarksService;
    @Autowired private ReportCardPdfGenerator        pdfGenerator;
    @Autowired private ReportCardPublicationService  publicationService;
    @Autowired private ClassOverviewService          classOverviewService;
    @Autowired private StudentRepository             studentRepository;
    @Autowired private SecurityUtil                  securityUtil;

    // ── Template CRUD ─────────────────────────────────────────────────────

    /** List all active templates for the school. */
    @GetMapping("/report-card-templates")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<List<ReportCardTemplateDTO>> listTemplates() {
        return ResponseEntity.ok(templateService.listTemplates());
    }

    /** Get a single template with its sections. */
    @GetMapping("/report-card-templates/{id}")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<ReportCardTemplateDTO> getTemplate(@PathVariable Long id) {
        return ResponseEntity.ok(templateService.getTemplate(id));
    }

    /** Create a new template. Auto-creates all 10 default sections (all enabled). */
    @PostMapping("/report-card-templates")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<ReportCardTemplateDTO> createTemplate(
            @Valid @RequestBody ReportCardTemplateRequest req) {
        return ResponseEntity.ok(templateService.createTemplate(req));
    }

    /** Update template metadata. Does not touch sections. */
    @PutMapping("/report-card-templates/{id}")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<ReportCardTemplateDTO> updateTemplate(
            @PathVariable Long id,
            @Valid @RequestBody ReportCardTemplateRequest req) {
        return ResponseEntity.ok(templateService.updateTemplate(id, req));
    }

    /** Soft-delete a template (sets isActive = false). Cannot delete the default template. */
    @DeleteMapping("/report-card-templates/{id}")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<Void> deleteTemplate(@PathVariable Long id) {
        templateService.deleteTemplate(id);
        return ResponseEntity.noContent().build();
    }

    /** Bulk-replace all sections for a template in one call. */
    @PutMapping("/report-card-templates/{id}/sections")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<ReportCardTemplateDTO> updateSections(
            @PathVariable Long id,
            @Valid @RequestBody ReportCardSectionUpdateRequest req) {
        return ResponseEntity.ok(templateService.updateSections(id, req));
    }

    // ── Report Card Data ──────────────────────────────────────────────────

    /**
     * Assemble and return the full report card data for a student.
     *
     * Students can only fetch their own report card.
     * Teachers and admins can fetch any student's report card.
     *
     * GET /api/report-cards?studentId=&templateId=&session=
     */
    @GetMapping("/report-cards")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "', '" + Role.STUDENT + "')")
    public ResponseEntity<ReportCardDataDTO> getReportCard(
            @RequestParam String studentId,
            @RequestParam Long templateId,
            @RequestParam String session) {

        // Students can only view published report cards
        if (Role.STUDENT.equals(securityUtil.getRole())) {
            Long schoolId = securityUtil.getSchoolId();
            Student student = studentRepository
                .findByStudentIdAndSchoolId(studentId, schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found"));
            if (!publicationService.isPublished(templateId, session, student.getClassName())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Report card has not been published yet.");
            }
        }

        return ResponseEntity.ok(assembler.assemble(studentId, templateId, session));
    }

    // ── Remarks ───────────────────────────────────────────────────────────

    /**
     * Load all existing remarks + co-scholastic entries for a class in one call.
     * GET /api/report-cards/remarks?templateId=&session=&className=
     */
    @GetMapping("/report-cards/remarks")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<ClassRemarksDTO> getClassRemarks(
            @RequestParam Long templateId,
            @RequestParam String session,
            @RequestParam String className) {
        return ResponseEntity.ok(remarksService.getClassRemarks(templateId, session, className));
    }

    /**
     * Bulk upsert teacher/principal remarks for a class.
     * PUT /api/report-cards/remarks
     */
    @PutMapping("/report-cards/remarks")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<Void> saveRemarks(@Valid @RequestBody RemarksRequest req) {
        remarksService.saveRemarks(req);
        return ResponseEntity.noContent().build();
    }

    /**
     * Bulk upsert co-scholastic grades for a class.
     * PUT /api/report-cards/co-scholastic
     */
    @PutMapping("/report-cards/co-scholastic")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<Void> saveCoScholastic(@Valid @RequestBody CoScholasticRequest req) {
        remarksService.saveCoScholastic(req);
        return ResponseEntity.noContent().build();
    }

    // ── PDF Download ──────────────────────────────────────────────────────

    /**
     * Public QR verification endpoint — no authentication required.
     * GET /api/public/verify-rc?token={uuid}
     */
    @GetMapping("/public/verify-rc")
    public ResponseEntity<VerifyRcDTO> verifyReportCard(@RequestParam String token) {
        return ResponseEntity.ok(publicationService.verifyByToken(token));
    }

    /**
     * Generate and download a single student's report card as PDF.
     * GET /api/report-cards/pdf?studentId=&templateId=&session=
     */
    @GetMapping("/report-cards/pdf")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "', '" + Role.STUDENT + "')")
    public ResponseEntity<byte[]> downloadPdf(
            @RequestParam String studentId,
            @RequestParam Long templateId,
            @RequestParam String session) {

        ReportCardDataDTO data = assembler.assemble(studentId, templateId, session);

        // Embed verification token if the report card is published
        publicationService.getVerificationToken(templateId, session, data.getClassName())
            .ifPresent(data::setVerificationToken);

        byte[] pdf = pdfGenerator.generate(data);

        String filename = sanitizeFilename(data.getStudentName()) + "_" + session + "_ReportCard.pdf";

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(pdf);
    }

    /**
     * Generate and download all students' report cards for a class as a ZIP of PDFs.
     * GET /api/report-cards/pdf/bulk?templateId=&session=&className=
     */
    @GetMapping("/report-cards/pdf/bulk")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<byte[]> downloadBulkPdf(
            @RequestParam Long templateId,
            @RequestParam String session,
            @RequestParam String className) {

        Long schoolId = securityUtil.getSchoolId();
        List<Student> students = studentRepository.findByClassNameAndStatusAndSchoolId(
                className, StudentStatus.ACTIVE, schoolId);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            for (Student student : students) {
                try {
                    ReportCardDataDTO data = assembler.assemble(student.getStudentId(), templateId, session);
                    byte[] pdf = pdfGenerator.generate(data);
                    String entryName = sanitizeFilename(student.getName()) + "_" + student.getStudentId() + ".pdf";
                    zip.putNextEntry(new ZipEntry(entryName));
                    zip.write(pdf);
                    zip.closeEntry();
                } catch (Exception e) {
                    // Skip students with incomplete data; continue with rest
                    org.slf4j.LoggerFactory.getLogger(ReportCardController.class)
                        .warn("Skipping report card for student {}: {}", student.getStudentId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate bulk PDF ZIP", e);
        }

        String zipFilename = sanitizeFilename(className) + "_" + session + "_ReportCards.zip";
        byte[] zipBytes = baos.toByteArray();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + zipFilename + "\"")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(zipBytes.length))
                .body(zipBytes);
    }

    // ── Class Performance Overview ────────────────────────────────────────

    /**
     * Phase 7: Returns pre-computed weighted scores for all students in a class.
     * Lightweight — reads AssessmentGroupResult cache, no full assembly.
     * GET /api/report-cards/class-overview?templateId=&session=&className=
     */
    @GetMapping("/report-cards/class-overview")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<ClassOverviewDTO> getClassOverview(
            @RequestParam Long templateId,
            @RequestParam String session,
            @RequestParam String className) {
        return ResponseEntity.ok(classOverviewService.getClassOverview(templateId, session, className));
    }

    // ── Publishing ────────────────────────────────────────────────────────

    /** GET /api/report-cards/publish?templateId=&session=&className= */
    @GetMapping("/report-cards/publish")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<ReportCardPublicationDTO> getPublishStatus(
            @RequestParam Long templateId,
            @RequestParam String session,
            @RequestParam String className) {
        return ResponseEntity.ok(publicationService.getStatus(templateId, session, className));
    }

    /** POST /api/report-cards/publish — publishes a class's report cards */
    @PostMapping("/report-cards/publish")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<ReportCardPublicationDTO> publish(
            @Valid @RequestBody PublishRequest req) {
        return ResponseEntity.ok(
            publicationService.publish(req.getTemplateId(), req.getSession(), req.getClassName()));
    }

    /** DELETE /api/report-cards/publish?templateId=&session=&className= */
    @DeleteMapping("/report-cards/publish")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<Void> unpublish(
            @RequestParam Long templateId,
            @RequestParam String session,
            @RequestParam String className) {
        publicationService.unpublish(templateId, session, className);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/report-cards/email-blast
     * Validates publication exists, fires async blast, returns { initiated, message }.
     */
    @PostMapping("/report-cards/email-blast")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<Map<String, Object>> emailBlast(
            @Valid @RequestBody PublishRequest req) {
        int initiated = publicationService.startEmailBlast(
            req.getTemplateId(), req.getSession(), req.getClassName());
        return ResponseEntity.accepted().body(Map.of(
            "initiated", initiated,
            "message", "Email blast started for " + initiated + " student(s). Emails will arrive shortly."
        ));
    }

    private String sanitizeFilename(String name) {
        if (name == null) return "Unknown";
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_").replaceAll("_+", "_");
    }
}
