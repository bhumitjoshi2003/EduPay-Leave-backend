package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.SupportTicketDtos.*;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupportTicketServiceTest {
    @Mock SupportTicketRepository tickets;
    @Mock SchoolRepository schools;
    @Mock TeacherRepository teachers;
    @Mock StudentRepository students;
    @Mock AdminRepository admins;
    @Mock ParentRepository parents;
    @Mock ObjectStorageService objectStorage;
    @Mock SecurityUtil security;
    @Mock AuditService audit;
    @Mock ApplicationEventPublisher events;
    @Mock UploadIntentRepository uploadIntents;
    @Mock HttpServletRequest http;

    SupportTicketService service;

    static final Long SCHOOL_ID = 1L;

    @BeforeEach
    void setup() {
        service = new SupportTicketService(tickets, schools, teachers, students, admins, parents,
                objectStorage, security, audit, events, uploadIntents);

        lenient().when(security.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(security.getUsername()).thenReturn("T1");
        lenient().when(security.getRole()).thenReturn(Role.TEACHER);
        lenient().when(http.getRemoteAddr()).thenReturn("127.0.0.1");
        lenient().when(schools.findById(anyLong())).thenReturn(Optional.empty());

        Teacher teacher = new Teacher();
        teacher.setTeacherId("T1");
        teacher.setName("Ms Rao");
        lenient().when(teachers.findByTeacherIdAndSchoolId("T1", SCHOOL_ID)).thenReturn(Optional.of(teacher));

        lenient().when(tickets.saveAndFlush(any())).thenAnswer(inv -> {
            SupportTicket t = inv.getArgument(0);
            if (t.getId() == null) t.setId(500L);
            return t;
        });
    }

    private CreateRequest request() {
        return new CreateRequest(SupportTicketCategory.APP_WEBSITE, "App crashes on login",
                "The app crashes every time I try to log in.", null, "/dashboard/teacher-dashboard",
                SupportPlatform.WEB, "1.4.2");
    }

    // ─── Create ────────────────────────────────────────────────────────

    @Test
    void createTicketAssignsATicketNumberAndCapturesReporterIdentity() {
        TicketDetail result = service.createTicket(request(), http);

        assertThat(result.ticketNumber()).isEqualTo("EDX-500");
        assertThat(result.reporterUserId()).isEqualTo("T1");
        assertThat(result.reporterName()).isEqualTo("Ms Rao");
        assertThat(result.reporterRole()).isEqualTo(Role.TEACHER);
        assertThat(result.schoolId()).isEqualTo(SCHOOL_ID);
        assertThat(result.status()).isEqualTo(SupportTicketStatus.OPEN);
        assertThat(result.internalNote()).isNull();
        verify(tickets, times(2)).saveAndFlush(any());
    }

    static final String KEY = "schools/1/support-tickets/new/screenshots/abc.png";

    private CreateRequest withScreenshot(String key) {
        return new CreateRequest(SupportTicketCategory.OTHER, "Broken image",
                "Screenshot attached.", key, null, SupportPlatform.ANDROID, "2.0.0");
    }

    private UploadIntent intent(Long schoolId, String requestedBy, String purpose, String status) {
        UploadIntent i = new UploadIntent();
        i.setSchoolId(schoolId);
        i.setRequestedByUserId(requestedBy);
        i.setPurpose(purpose);
        i.setObjectKey(KEY);
        i.setStatus(status);
        return i;
    }

    private void assertScreenshotRejected() {
        assertThatThrownBy(() -> service.createTicket(withScreenshot(KEY), http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid screenshot reference");
        verify(tickets, never()).saveAndFlush(any());
    }

    @Test
    void createTicketPersistsAScreenshotKeyBackedByTheReportersOwnCompletedUpload() {
        when(uploadIntents.findByObjectKey(KEY)).thenReturn(Optional.of(
                intent(SCHOOL_ID, "T1", "SUPPORT_TICKET_SCREENSHOT", UploadIntent.STATUS_COMPLETED)));
        when(objectStorage.resolveDisplayUrl(KEY)).thenReturn("https://cdn.example.com/signed-url");

        TicketDetail result = service.createTicket(withScreenshot(KEY), http);

        ArgumentCaptor<SupportTicket> saved = ArgumentCaptor.forClass(SupportTicket.class);
        verify(tickets, atLeastOnce()).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getScreenshotObjectKey()).isEqualTo(KEY);
        assertThat(result.screenshotUrl()).isEqualTo("https://cdn.example.com/signed-url");
    }

    @Test
    void createTicketRejectsAnArbitraryKeyWithNoUploadIntent() {
        when(uploadIntents.findByObjectKey(KEY)).thenReturn(Optional.empty());
        assertScreenshotRejected();
    }

    @Test
    void createTicketRejectsAnotherUsersUploadInTheSameSchool() {
        when(uploadIntents.findByObjectKey(KEY)).thenReturn(Optional.of(
                intent(SCHOOL_ID, "T2", "SUPPORT_TICKET_SCREENSHOT", UploadIntent.STATUS_COMPLETED)));
        assertScreenshotRejected();
    }

    @Test
    void createTicketRejectsAnUploadFromAnotherSchool() {
        when(uploadIntents.findByObjectKey(KEY)).thenReturn(Optional.of(
                intent(2L, "T1", "SUPPORT_TICKET_SCREENSHOT", UploadIntent.STATUS_COMPLETED)));
        assertScreenshotRejected();
    }

    @Test
    void createTicketRejectsAnUploadIssuedForADifferentPurpose() {
        when(uploadIntents.findByObjectKey(KEY)).thenReturn(Optional.of(
                intent(SCHOOL_ID, "T1", "TEACHER_PHOTO", UploadIntent.STATUS_COMPLETED)));
        assertScreenshotRejected();
    }

    @Test
    void createTicketRejectsAnUploadThatWasNeverCompleted() {
        when(uploadIntents.findByObjectKey(KEY)).thenReturn(Optional.of(
                intent(SCHOOL_ID, "T1", "SUPPORT_TICKET_SCREENSHOT", UploadIntent.STATUS_PENDING)));
        assertScreenshotRejected();
    }

    @Test
    void createTicketWithoutAScreenshotNeverConsultsUploadIntents() {
        service.createTicket(request(), http);
        verifyNoInteractions(uploadIntents);
    }

    @Test
    void createTicketFallsBackToTheRawUserIdWhenTheNameCannotBeResolved() {
        when(teachers.findByTeacherIdAndSchoolId("T1", SCHOOL_ID)).thenReturn(Optional.empty());
        TicketDetail result = service.createTicket(request(), http);
        assertThat(result.reporterName()).isEqualTo("T1");
    }

    // ─── Own-ticket read ───────────────────────────────────────────────

    @Test
    void reporterCanReadTheirOwnTicket() {
        SupportTicket ticket = ownedTicket();
        when(tickets.findByIdAndSchoolIdAndReporterUserId(500L, SCHOOL_ID, "T1")).thenReturn(Optional.of(ticket));

        TicketDetail result = service.myTicketDetail(500L);

        assertThat(result.ticketNumber()).isEqualTo("EDX-500");
        assertThat(result.internalNote()).isNull(); // never exposed to the reporter
    }

    @Test
    void aTicketBelongingToAnotherUserIsNotFoundForMyTicketDetail() {
        // The repository query itself is schoolId+reporterUserId scoped — a cross-user id simply
        // never matches, which is exactly how tenant/ownership isolation is enforced here.
        when(tickets.findByIdAndSchoolIdAndReporterUserId(999L, SCHOOL_ID, "T1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.myTicketDetail(999L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not found");
    }

    // ─── Super Admin ───────────────────────────────────────────────────

    @Test
    void superAdminSeesAllTicketsAcrossSchools() {
        when(tickets.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(ownedTicket())));

        var page = service.allTickets(null, null, null, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void superAdminDetailIncludesTheInternalNote() {
        SupportTicket ticket = ownedTicket();
        ticket.setInternalNote("Escalated to engineering.");
        when(tickets.findById(500L)).thenReturn(Optional.of(ticket));

        TicketDetail result = service.adminTicketDetail(500L);

        assertThat(result.internalNote()).isEqualTo("Escalated to engineering.");
    }

    // ─── Status update + notification ──────────────────────────────────

    @Test
    void updateStatusToInProgressPublishesExactlyOneNotificationEvent() {
        when(security.getRole()).thenReturn(Role.SUPER_ADMIN);
        when(security.getUsername()).thenReturn("SA1");
        SupportTicket ticket = ownedTicket();
        when(tickets.findById(500L)).thenReturn(Optional.of(ticket));

        service.updateStatus(500L, new StatusUpdateRequest(SupportTicketStatus.IN_PROGRESS, null), http);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, times(1)).publishEvent(captor.capture());
        SupportTicketNotificationEvent event = (SupportTicketNotificationEvent) captor.getValue();
        assertThat(event.eventKind()).isEqualTo("IN_PROGRESS");
        assertThat(event.recipientUserId()).isEqualTo("T1");
        assertThat(event.ticketNumber()).isEqualTo("EDX-500");
    }

    @Test
    void updateStatusToResolvedSetsTheInternalNoteAndNotifies() {
        when(security.getRole()).thenReturn(Role.SUPER_ADMIN);
        SupportTicket ticket = ownedTicket();
        when(tickets.findById(500L)).thenReturn(Optional.of(ticket));

        TicketDetail result = service.updateStatus(500L,
                new StatusUpdateRequest(SupportTicketStatus.RESOLVED, "Fixed in v1.4.3."), http);

        assertThat(result.status()).isEqualTo(SupportTicketStatus.RESOLVED);
        assertThat(result.internalNote()).isEqualTo("Fixed in v1.4.3.");
        verify(events, times(1)).publishEvent(any(SupportTicketNotificationEvent.class));
    }

    @Test
    void reSavingTheSameStatusDoesNotPublishASecondNotification() {
        when(security.getRole()).thenReturn(Role.SUPER_ADMIN);
        SupportTicket ticket = ownedTicket();
        ticket.setStatus(SupportTicketStatus.IN_PROGRESS);
        when(tickets.findById(500L)).thenReturn(Optional.of(ticket));

        service.updateStatus(500L, new StatusUpdateRequest(SupportTicketStatus.IN_PROGRESS, "still looking"), http);

        verify(events, never()).publishEvent(any());
    }

    @Test
    void movingBackToOpenDoesNotNotify() {
        when(security.getRole()).thenReturn(Role.SUPER_ADMIN);
        SupportTicket ticket = ownedTicket();
        ticket.setStatus(SupportTicketStatus.IN_PROGRESS);
        when(tickets.findById(500L)).thenReturn(Optional.of(ticket));

        service.updateStatus(500L, new StatusUpdateRequest(SupportTicketStatus.OPEN, null), http);

        verify(events, never()).publishEvent(any());
    }

    @Test
    void updatingAMissingTicketThrowsNotFound() {
        when(tickets.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(999L, new StatusUpdateRequest(SupportTicketStatus.RESOLVED, null), http))
                .isInstanceOf(ResponseStatusException.class);
    }

    private SupportTicket ownedTicket() {
        SupportTicket t = new SupportTicket();
        t.setId(500L);
        t.setTicketNumber("EDX-500");
        t.setSchoolId(SCHOOL_ID);
        t.setReporterUserId("T1");
        t.setReporterName("Ms Rao");
        t.setReporterRole(Role.TEACHER);
        t.setCategory(SupportTicketCategory.APP_WEBSITE);
        t.setTitle("App crashes on login");
        t.setDescription("The app crashes every time I try to log in.");
        t.setPlatform(SupportPlatform.WEB);
        t.setStatus(SupportTicketStatus.OPEN);
        return t;
    }
}
