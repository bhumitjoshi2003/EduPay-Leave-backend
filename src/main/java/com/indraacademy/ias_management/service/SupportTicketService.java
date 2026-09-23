package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.SupportTicketDtos.*;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.specification.SupportTicketSpecification;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * Technical support tickets for PLATFORM issues — deliberately never routed to a school's own
 * ADMIN/SUB_ADMIN (see the Phase 1 report). Only the reporter (their own tickets) and
 * SUPER_ADMIN (every ticket, every school) can ever read a ticket; only SUPER_ADMIN can change
 * status/internalNote.
 */
@Service
public class SupportTicketService {
    private static final Logger log = LoggerFactory.getLogger(SupportTicketService.class);

    private final SupportTicketRepository tickets;
    private final SchoolRepository schools;
    private final TeacherRepository teachers;
    private final StudentRepository students;
    private final AdminRepository admins;
    private final ParentRepository parents;
    private final ObjectStorageService objectStorage;
    private final SecurityUtil security;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final UploadIntentRepository uploadIntents;

    public SupportTicketService(SupportTicketRepository tickets, SchoolRepository schools,
            TeacherRepository teachers, StudentRepository students, AdminRepository admins,
            ParentRepository parents, ObjectStorageService objectStorage, SecurityUtil security,
            AuditService audit, ApplicationEventPublisher events, UploadIntentRepository uploadIntents) {
        this.tickets = tickets;
        this.schools = schools;
        this.teachers = teachers;
        this.students = students;
        this.admins = admins;
        this.parents = parents;
        this.objectStorage = objectStorage;
        this.security = security;
        this.audit = audit;
        this.events = events;
        this.uploadIntents = uploadIntents;
    }

    @Transactional
    public TicketDetail createTicket(CreateRequest request, HttpServletRequest http) {
        Long schoolId = security.getSchoolId();
        if (schoolId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No school context for the current session.");
        }
        String role = security.getRole();
        String userId = security.getUsername();
        String screenshotObjectKey = verifiedScreenshotKey(request.screenshotObjectKey(), schoolId, userId);

        SupportTicket ticket = new SupportTicket();
        ticket.setSchoolId(schoolId);
        ticket.setReporterUserId(userId);
        ticket.setReporterName(Optional.ofNullable(resolveReporterName(role, userId, schoolId)).orElse(userId));
        ticket.setReporterRole(role);
        ticket.setCategory(request.category());
        ticket.setTitle(request.title().trim());
        ticket.setDescription(request.description().trim());
        ticket.setScreenshotObjectKey(screenshotObjectKey);
        ticket.setRoute(request.route());
        ticket.setPlatform(request.platform());
        ticket.setAppVersion(request.appVersion());
        ticket.setStatus(SupportTicketStatus.OPEN);
        ticket = tickets.saveAndFlush(ticket);

        // Ticket number embeds the generated id, so it can only be assigned after the first
        // insert — a second, tiny write, not a race: the id is already ours alone at this point.
        ticket.setTicketNumber("EDX-" + ticket.getId());
        ticket = tickets.saveAndFlush(ticket);

        audit.log(userId, role, "CREATE_SUPPORT_TICKET", "SupportTicket",
                String.valueOf(ticket.getId()), null,
                "ticketNumber=" + ticket.getTicketNumber() + ",category=" + ticket.getCategory(),
                http.getRemoteAddr());

        log.info("Support ticket created: {} by {} ({})", ticket.getTicketNumber(), userId, role);
        return toDetail(ticket, false);
    }

    /** The client-supplied key is only a reference: it must resolve to a COMPLETED upload intent
     *  issued for SUPPORT_TICKET_SCREENSHOT to this same user in this same school. One generic
     *  rejection for every failure so the endpoint can't be used to probe other users' keys. */
    private String verifiedScreenshotKey(String objectKey, Long schoolId, String userId) {
        if (objectKey == null || objectKey.isBlank()) return null;
        boolean valid = uploadIntents.findByObjectKey(objectKey)
                .filter(i -> schoolId.equals(i.getSchoolId()))
                .filter(i -> userId.equals(i.getRequestedByUserId()))
                .filter(i -> UploadPurpose.SUPPORT_TICKET_SCREENSHOT.name().equals(i.getPurpose()))
                .filter(i -> UploadIntent.STATUS_COMPLETED.equals(i.getStatus()))
                .isPresent();
        if (!valid) {
            log.warn("Rejected support ticket screenshot reference {} from {} (school {})", objectKey, userId, schoolId);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid screenshot reference.");
        }
        return objectKey;
    }

    @Transactional(readOnly = true)
    public Page<TicketSummary> myTickets(Pageable pageable) {
        Long schoolId = security.getSchoolId();
        String userId = security.getUsername();
        return tickets.findBySchoolIdAndReporterUserIdOrderByCreatedAtDesc(schoolId, userId, pageable)
                .map(t -> TicketSummary.from(t, schoolName(t.getSchoolId())));
    }

    @Transactional(readOnly = true)
    public TicketDetail myTicketDetail(Long id) {
        Long schoolId = security.getSchoolId();
        String userId = security.getUsername();
        SupportTicket ticket = tickets.findByIdAndSchoolIdAndReporterUserId(id, schoolId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Support ticket not found."));
        return toDetail(ticket, false);
    }

    /** SUPER_ADMIN only — enforced at the controller with hasRole('SUPER_ADMIN'); schoolId here
     *  is an optional filter, never a required scope (unlike every other cross-school method in
     *  this codebase, this one genuinely spans every school when schoolId is omitted). */
    @Transactional(readOnly = true)
    public Page<TicketSummary> allTickets(SupportTicketStatus status, SupportTicketCategory category,
            Long schoolId, Pageable pageable) {
        return tickets.findAll(SupportTicketSpecification.filter(status, category, schoolId), pageable)
                .map(t -> TicketSummary.from(t, schoolName(t.getSchoolId())));
    }

    @Transactional(readOnly = true)
    public TicketDetail adminTicketDetail(Long id) {
        SupportTicket ticket = tickets.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Support ticket not found."));
        return toDetail(ticket, true);
    }

    @Transactional
    public TicketDetail updateStatus(Long id, StatusUpdateRequest request, HttpServletRequest http) {
        SupportTicket ticket = tickets.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Support ticket not found."));
        SupportTicketStatus oldStatus = ticket.getStatus();
        String oldNote = ticket.getInternalNote();

        ticket.setStatus(request.status());
        ticket.setInternalNote(request.internalNote());
        ticket = tickets.saveAndFlush(ticket);

        audit.logUpdate(security.getUsername(), security.getRole(), "UPDATE_SUPPORT_TICKET_STATUS",
                "SupportTicket", String.valueOf(ticket.getId()),
                "{\"status\":\"" + oldStatus + "\",\"internalNote\":" + jsonString(oldNote) + "}",
                "{\"status\":\"" + ticket.getStatus() + "\",\"internalNote\":" + jsonString(ticket.getInternalNote()) + "}",
                http.getRemoteAddr());

        if (ticket.getStatus() != oldStatus
                && (ticket.getStatus() == SupportTicketStatus.IN_PROGRESS || ticket.getStatus() == SupportTicketStatus.RESOLVED)) {
            events.publishEvent(new SupportTicketNotificationEvent(
                    ticket.getSchoolId(), ticket.getId(), ticket.getRevision(), ticket.getTicketNumber(),
                    ticket.getReporterUserId(), ticket.getStatus().name(), security.getUsername()));
        }

        return toDetail(ticket, true);
    }

    private TicketDetail toDetail(SupportTicket t, boolean includeInternalNote) {
        String screenshotUrl = t.getScreenshotObjectKey() == null ? null
                : objectStorage.resolveDisplayUrl(t.getScreenshotObjectKey());
        return new TicketDetail(t.getId(), t.getTicketNumber(), t.getSchoolId(), schoolName(t.getSchoolId()),
                t.getReporterUserId(), t.getReporterName(), t.getReporterRole(),
                t.getCategory(), t.getTitle(), t.getDescription(), screenshotUrl, t.getRoute(),
                t.getPlatform(), t.getAppVersion(), t.getStatus(),
                includeInternalNote ? t.getInternalNote() : null,
                t.getCreatedAt(), t.getUpdatedAt());
    }

    private String schoolName(Long schoolId) {
        return schools.findById(schoolId).map(School::getName).orElse(null);
    }

    /** Mirrors AuthController.resolveName exactly — a person's display name lives on their own
     *  role-specific entity, never on User itself. Best-effort: a null result just falls back to
     *  the raw userId at the call site, it never blocks ticket creation. */
    private String resolveReporterName(String role, String userId, Long schoolId) {
        try {
            if (Role.STUDENT.equals(role)) {
                return students.findByStudentIdAndSchoolId(userId, schoolId).map(Student::getName).orElse(null);
            } else if (Role.TEACHER.equals(role)) {
                return teachers.findByTeacherIdAndSchoolId(userId, schoolId).map(Teacher::getName).orElse(null);
            } else if (Role.PARENT.equals(role)) {
                return parents.findByParentIdAndSchoolId(userId, schoolId).map(Parent::getName).orElse(null);
            } else if (Role.SUPER_ADMIN.equals(role)) {
                return admins.findById(userId).map(Admin::getName).orElse(null);
            } else {
                return admins.findByAdminIdAndSchoolId(userId, schoolId).map(Admin::getName).orElse(null);
            }
        } catch (Exception e) {
            log.warn("Could not resolve reporter name for userId={} role={}: {}", userId, role, e.getMessage());
            return null;
        }
    }

    private String jsonString(String value) {
        return value == null ? "null" : "\"" + value.replace("\"", "\\\"") + "\"";
    }
}
