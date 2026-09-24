package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.ClassUpdateDtos.AttachmentRef;
import com.indraacademy.ias_management.dto.ClassUpdateDtos.CreateRequest;
import com.indraacademy.ias_management.dto.ClassUpdateDtos.TeachingContext;
import com.indraacademy.ias_management.dto.ClassUpdateDtos.UpdateRequest;
import com.indraacademy.ias_management.dto.ClassUpdateDtos.UpdateView;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.time.*;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClassUpdateServiceTest {
    @Mock ClassUpdateRepository updates;
    @Mock TimetableRepository timetable;
    @Mock TeacherRepository teachers;
    @Mock StudentEnrollmentRepository enrollments;
    @Mock TimetableSessionAccessService sessions;
    @Mock UploadIntentRepository uploadIntents;
    @Mock ObjectStorageService objectStorage;
    @Mock SecurityUtil security;
    @Mock ApplicationEventPublisher events;

    ClassUpdateService service;

    static final long SCHOOL = 1L;
    static final long SESSION = 11L;
    static final LocalDate TODAY = LocalDate.of(2026, 9, 23);
    static final Instant NOW = TODAY.atTime(10, 0).toInstant(ZoneOffset.UTC);
    static final String KEY = "schools/1/class-updates/new/attachments/abc.pdf";

    AcademicSession session;

    @BeforeEach
    void setup() {
        service = new ClassUpdateService(updates, timetable, teachers, enrollments, sessions, uploadIntents,
                objectStorage, security, events, Clock.fixed(NOW, ZoneOffset.UTC));
        session = new AcademicSession();
        session.setId(SESSION);
        lenient().when(security.getSchoolId()).thenReturn(SCHOOL);
        lenient().when(security.getUsername()).thenReturn("T1");
        lenient().when(security.getRole()).thenReturn(Role.TEACHER);
        lenient().when(sessions.requireCurrentSessionForTeacherWrite(SCHOOL)).thenReturn(session);
        lenient().when(sessions.currentSessionOrNull(SCHOOL)).thenReturn(session);
        lenient().when(timetable.findByAcademicSessionIdAndTeacherIdAndSchoolId(SESSION, "T1", SCHOOL)).thenReturn(List.of(
                entry(8L, "8", 3L, "A", "Science", Day.MONDAY),
                entry(8L, "8", 3L, "A", "Science", Day.TUESDAY),
                entry(8L, "8", 3L, "A", "Maths", Day.MONDAY),
                entry(10L, "10", null, null, "Physics", Day.MONDAY),
                entry(2L, "2", 1L, "B", "EVS", Day.FRIDAY)));
        Teacher t1 = new Teacher();
        t1.setName("Ms Rao");
        lenient().when(teachers.findByTeacherIdAndSchoolId("T1", SCHOOL)).thenReturn(Optional.of(t1));
        lenient().when(updates.saveAndFlush(any())).thenAnswer(i -> {
            ClassUpdate u = i.getArgument(0);
            if (u.getId() == null) u.setId(500L);
            return u;
        });
        lenient().when(objectStorage.resolveDisplayUrl(anyString())).thenReturn("https://cdn.example/signed");
    }

    private CreateRequest request(Long classId, Long sectionId, String subject) {
        return new CreateRequest(classId, sectionId, subject, "Test on Friday", "Chapters 3 and 4.", null, null);
    }

    private void completedUpload(String key, String purpose, String requester, long school) {
        UploadIntent i = new UploadIntent();
        i.setSchoolId(school);
        i.setRequestedByUserId(requester);
        i.setPurpose(purpose);
        i.setObjectKey(key);
        i.setStatus(UploadIntent.STATUS_COMPLETED);
        i.setExpectedContentType("application/pdf");
        i.setExpectedSize(2048L);
        lenient().when(uploadIntents.findByObjectKey(key)).thenReturn(Optional.of(i));
    }

    // ─── Contexts ───────────────────────────────────────────────────────

    @Test
    void contextsAreTheTeachersDistinctClassSectionSubjectsSortedByClass() {
        List<TeachingContext> contexts = service.myContexts();

        assertThat(contexts).extracting(c -> c.className() + "/" + c.sectionName() + "/" + c.subjectName())
                .containsExactly("2/B/EVS", "8/A/Maths", "8/A/Science", "10/null/Physics");
    }

    @Test
    void noCurrentSessionMeansNoContexts() {
        when(sessions.currentSessionOrNull(SCHOOL)).thenReturn(null);
        assertThat(service.myContexts()).isEmpty();
    }

    // ─── Create ─────────────────────────────────────────────────────────

    @Test
    void teacherCreatesForTheirOwnClassWithNamesTakenFromTheTimetable() {
        UpdateView view = service.create(new CreateRequest(8L, 3L, "science", "  Test on Friday ", " Chapters 3 and 4. ",
                null, NOW.plus(Duration.ofDays(3))));

        ArgumentCaptor<ClassUpdate> saved = ArgumentCaptor.forClass(ClassUpdate.class);
        verify(updates).saveAndFlush(saved.capture());
        ClassUpdate u = saved.getValue();
        assertThat(u.getSchoolId()).isEqualTo(SCHOOL);
        assertThat(u.getAcademicSessionId()).isEqualTo(SESSION);
        assertThat(u.getClassId()).isEqualTo(8L);
        assertThat(u.getClassName()).isEqualTo("8");
        assertThat(u.getSectionId()).isEqualTo(3L);
        assertThat(u.getSectionName()).isEqualTo("A");
        assertThat(u.getSubjectName()).isEqualTo("Science");
        assertThat(u.getTeacherId()).isEqualTo("T1");
        assertThat(u.getTeacherName()).isEqualTo("Ms Rao");
        assertThat(u.getTitle()).isEqualTo("Test on Friday");
        assertThat(u.getMessage()).isEqualTo("Chapters 3 and 4.");
        assertThat(u.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(3)));
        assertThat(view.canEdit()).isTrue();
        assertThat(view.expired()).isFalse();
    }

    @Test
    void creatingPublishesOneNotificationEventForTheClassSection() {
        service.create(request(8L, 3L, "Science"));

        ArgumentCaptor<ClassUpdatePostedEvent> event = ArgumentCaptor.forClass(ClassUpdatePostedEvent.class);
        verify(events, times(1)).publishEvent(event.capture());
        assertThat(event.getValue().updateId()).isEqualTo(500L);
        assertThat(event.getValue().academicSessionId()).isEqualTo(SESSION);
        assertThat(event.getValue().classId()).isEqualTo(8L);
        assertThat(event.getValue().sectionId()).isEqualTo(3L);
        assertThat(event.getValue().subjectName()).isEqualTo("Science");
    }

    @Test
    void aClassWideUpdateWithoutSubjectIsAllowedForAClassTheTeacherTeaches() {
        service.create(request(8L, 3L, null));

        ArgumentCaptor<ClassUpdate> saved = ArgumentCaptor.forClass(ClassUpdate.class);
        verify(updates).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getSubjectName()).isNull();
    }

    @Test
    void aSectionlessClassMatchesOnlyWithANullSection() {
        service.create(request(10L, null, "Physics"));
        verify(updates).saveAndFlush(any());

        assertThatThrownBy(() -> service.create(request(10L, 7L, "Physics"))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void cannotPostToAClassSectionOrSubjectTheTeacherDoesNotTeach() {
        assertThatThrownBy(() -> service.create(request(9L, 3L, "Science"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.create(request(8L, 4L, "Science"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.create(request(8L, 3L, "History"))).isInstanceOf(AccessDeniedException.class);
        verify(updates, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    @Test
    void anotherSchoolsTimetableIsNeverConsulted() {
        when(security.getSchoolId()).thenReturn(2L);
        when(sessions.requireCurrentSessionForTeacherWrite(2L)).thenReturn(session);

        assertThatThrownBy(() -> service.create(request(8L, 3L, "Science"))).isInstanceOf(AccessDeniedException.class);
        verify(timetable).findByAcademicSessionIdAndTeacherIdAndSchoolId(SESSION, "T1", 2L);
    }

    @Test
    void writesRequireACurrentWritableSession() {
        when(sessions.requireCurrentSessionForTeacherWrite(SCHOOL)).thenThrow(new IllegalStateException("No current session"));
        assertThatThrownBy(() -> service.create(request(8L, 3L, "Science"))).isInstanceOf(IllegalStateException.class);
        verify(updates, never()).saveAndFlush(any());
    }

    @Test
    void titleAndMessageAreRequiredAndBounded() {
        assertThatThrownBy(() -> service.create(new CreateRequest(8L, 3L, "Science", " ", "m", null, null)))
                .hasMessageContaining("title");
        assertThatThrownBy(() -> service.create(new CreateRequest(8L, 3L, "Science", "t", null, null, null)))
                .hasMessageContaining("message");
        assertThatThrownBy(() -> service.create(new CreateRequest(8L, 3L, "Science", "x".repeat(151), "m", null, null)))
                .hasMessageContaining("150");
        assertThatThrownBy(() -> service.create(new CreateRequest(8L, 3L, "Science", "t", "x".repeat(5001), null, null)))
                .hasMessageContaining("5000");
        assertThatThrownBy(() -> service.create(new CreateRequest(null, 3L, "Science", "t", "m", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expiryMustBeInTheFutureAndWithinAYear() {
        assertThatThrownBy(() -> service.create(new CreateRequest(8L, 3L, "Science", "t", "m", null, NOW)))
                .hasMessageContaining("future");
        assertThatThrownBy(() -> service.create(new CreateRequest(8L, 3L, "Science", "t", "m", null, NOW.plus(Duration.ofDays(400)))))
                .hasMessageContaining("one year");
    }

    // ─── Attachment ─────────────────────────────────────────────────────

    @Test
    void anAttachmentMustBeTheTeachersOwnCompletedClassUpdateUploadAndTakesTypeAndSizeFromIt() {
        completedUpload(KEY, "CLASS_UPDATE_ATTACHMENT", "T1", SCHOOL);

        UpdateView view = service.create(new CreateRequest(8L, 3L, "Science", "t", "m",
                new AttachmentRef(KEY, "../../worksheet.pdf"), null));

        assertThat(view.attachment().fileName()).isEqualTo("worksheet.pdf");
        assertThat(view.attachment().contentType()).isEqualTo("application/pdf");
        assertThat(view.attachment().fileSize()).isEqualTo(2048L);
        assertThat(view.attachment().type()).isEqualTo("PDF");
        assertThat(view.attachment().url()).isEqualTo("https://cdn.example/signed");
        assertThat(view.attachment().objectKey()).isEqualTo(KEY);
    }

    @Test
    void attachmentsFromAnotherTeacherSchoolPurposeOrIncompleteUploadAreRejected() {
        String[][] cases = {
                {"k-other-teacher", "CLASS_UPDATE_ATTACHMENT", "T2", "1"},
                {"k-other-school", "CLASS_UPDATE_ATTACHMENT", "T1", "2"},
                {"k-homework", "HOMEWORK_ATTACHMENT", "T1", "1"},
        };
        for (String[] c : cases) {
            completedUpload(c[0], c[1], c[2], Long.parseLong(c[3]));
            assertThatThrownBy(() -> service.create(new CreateRequest(8L, 3L, "Science", "t", "m", new AttachmentRef(c[0], "f"), null)))
                    .as(c[0]).hasMessage("Invalid attachment reference.");
        }
        UploadIntent pending = new UploadIntent();
        pending.setSchoolId(SCHOOL);
        pending.setRequestedByUserId("T1");
        pending.setPurpose("CLASS_UPDATE_ATTACHMENT");
        pending.setStatus(UploadIntent.STATUS_PENDING);
        when(uploadIntents.findByObjectKey("k-pending")).thenReturn(Optional.of(pending));
        assertThatThrownBy(() -> service.create(new CreateRequest(8L, 3L, "Science", "t", "m", new AttachmentRef("k-pending", "f"), null)))
                .hasMessage("Invalid attachment reference.");
        assertThatThrownBy(() -> service.create(new CreateRequest(8L, 3L, "Science", "t", "m", new AttachmentRef("unknown", "f"), null)))
                .hasMessage("Invalid attachment reference.");
        verify(updates, never()).saveAndFlush(any());
    }

    @Test
    void anUploadAlreadyUsedByAnotherUpdateIsRejected() {
        completedUpload(KEY, "CLASS_UPDATE_ATTACHMENT", "T1", SCHOOL);
        when(updates.existsByAttachmentObjectKey(KEY)).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateRequest(8L, 3L, "Science", "t", "m", new AttachmentRef(KEY, "f"), null)))
                .hasMessage("Invalid attachment reference.");
    }

    @Test
    void aConcurrentDuplicateAttachmentBecomesAValidationError() {
        completedUpload(KEY, "CLASS_UPDATE_ATTACHMENT", "T1", SCHOOL);
        doThrow(new DataIntegrityViolationException("uq")).when(updates).saveAndFlush(any());

        assertThatThrownBy(() -> service.create(new CreateRequest(8L, 3L, "Science", "t", "m", new AttachmentRef(KEY, "f"), null)))
                .hasMessage("Invalid attachment reference.");
        verifyNoInteractions(events);
    }

    // ─── Edit / delete ──────────────────────────────────────────────────

    @Test
    void theOwnerCanEditContentKeepingTheExistingAttachmentWithoutNotifying() {
        ClassUpdate stored = stored("T1", KEY);
        when(updates.findByIdAndSchoolId(500L, SCHOOL)).thenReturn(Optional.of(stored));

        UpdateView view = service.update(500L, new UpdateRequest("New title", "New message", new AttachmentRef(KEY, "ignored"), null));

        assertThat(view.title()).isEqualTo("New title");
        assertThat(view.message()).isEqualTo("New message");
        assertThat(stored.getAttachmentFileName()).isEqualTo("abc.pdf");
        verifyNoInteractions(uploadIntents);
        verifyNoInteractions(events);
    }

    @Test
    void editingCanRemoveOrReplaceTheAttachment() {
        ClassUpdate stored = stored("T1", KEY);
        when(updates.findByIdAndSchoolId(500L, SCHOOL)).thenReturn(Optional.of(stored));
        service.update(500L, new UpdateRequest("t", "m", null, null));
        assertThat(stored.getAttachmentObjectKey()).isNull();
        assertThat(stored.getAttachmentFileSize()).isNull();

        completedUpload("k2", "CLASS_UPDATE_ATTACHMENT", "T1", SCHOOL);
        service.update(500L, new UpdateRequest("t", "m", new AttachmentRef("k2", "photo.png"), null));
        assertThat(stored.getAttachmentObjectKey()).isEqualTo("k2");
        assertThat(stored.getAttachmentFileName()).isEqualTo("photo.png");
    }

    @Test
    void anUnchangedPastExpiryIsKeptOnEditButANewOneMustBeInTheFuture() {
        ClassUpdate stored = stored("T1", null);
        Instant past = NOW.minus(Duration.ofDays(1));
        stored.setExpiresAt(past);
        when(updates.findByIdAndSchoolId(500L, SCHOOL)).thenReturn(Optional.of(stored));

        UpdateView view = service.update(500L, new UpdateRequest("t", "m", null, past));
        assertThat(view.expired()).isTrue();

        assertThatThrownBy(() -> service.update(500L, new UpdateRequest("t", "m", null, past.minusSeconds(60))))
                .hasMessageContaining("future");
        service.update(500L, new UpdateRequest("t", "m", null, null));
        assertThat(stored.getExpiresAt()).isNull();
    }

    @Test
    void onlyTheOwnerCanEditOrDelete() {
        when(updates.findByIdAndSchoolId(500L, SCHOOL)).thenReturn(Optional.of(stored("T2", null)));

        assertThatThrownBy(() -> service.update(500L, new UpdateRequest("t", "m", null, null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.delete(500L)).isInstanceOf(AccessDeniedException.class);
        verify(updates, never()).saveAndFlush(any());
        verify(updates, never()).delete(any());
    }

    @Test
    void anotherSchoolsUpdateIsNotFound() {
        when(updates.findByIdAndSchoolId(500L, SCHOOL)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(500L)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void theOwnerCanDeleteWithoutNotifying() {
        ClassUpdate stored = stored("T1", null);
        when(updates.findByIdAndSchoolId(500L, SCHOOL)).thenReturn(Optional.of(stored));

        service.delete(500L);

        verify(updates).delete(stored);
        verifyNoInteractions(events);
    }

    @Test
    void myRecentIsScopedToTheTeacherAndSchoolAndFlagsExpired() {
        ClassUpdate expired = stored("T1", null);
        expired.setExpiresAt(NOW.minusSeconds(1));
        when(updates.findBySchoolIdAndTeacherIdOrderByCreatedAtDescIdDesc(eq(SCHOOL), eq("T1"), any(Pageable.class)))
                .thenReturn(List.of(expired));

        assertThat(service.myRecent()).singleElement().satisfies(v -> {
            assertThat(v.expired()).isTrue();
            assertThat(v.canEdit()).isTrue();
        });
    }

    // ─── Student ────────────────────────────────────────────────────────

    @Test
    void studentsSeeActiveUpdatesForTheirActiveEnrollmentWithoutObjectKeys() {
        when(security.getUsername()).thenReturn("S1");
        when(enrollments.findRealizedEffectiveEnrollments(SCHOOL, "S1", SESSION, TODAY)).thenReturn(List.of(enrollment(8L, 3L)));
        when(updates.findActiveForClass(eq(SCHOOL), eq(SESSION), eq(8L), eq(3L), eq(NOW), any(Pageable.class)))
                .thenReturn(List.of(stored("T1", KEY)));

        List<UpdateView> views = service.studentActive(3);

        assertThat(views).singleElement().satisfies(v -> {
            assertThat(v.canEdit()).isFalse();
            assertThat(v.attachment().objectKey()).isNull();
            assertThat(v.attachment().url()).isEqualTo("https://cdn.example/signed");
        });
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(updates).findActiveForClass(anyLong(), anyLong(), anyLong(), any(), any(), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(3);
    }

    @Test
    void theStudentLimitIsCapped() {
        when(security.getUsername()).thenReturn("S1");
        when(enrollments.findRealizedEffectiveEnrollments(SCHOOL, "S1", SESSION, TODAY)).thenReturn(List.of(enrollment(8L, 3L)));
        service.studentActive(1000);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(updates).findActiveForClass(anyLong(), anyLong(), anyLong(), any(), any(), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void aStudentWithoutAnActiveEnrollmentSeesNothing() {
        when(security.getUsername()).thenReturn("S1");
        StudentEnrollment closed = enrollment(8L, 3L);
        closed.setStatus(StudentEnrollmentStatus.CLOSED);
        when(enrollments.findRealizedEffectiveEnrollments(SCHOOL, "S1", SESSION, TODAY)).thenReturn(List.of(closed));

        assertThat(service.studentActive(null)).isEmpty();
        verify(updates, never()).findActiveForClass(anyLong(), anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void singleReadIsLimitedToOwnerStudentsOfThatClassWhileActiveAndSchoolAdmins() {
        ClassUpdate stored = stored("T1", KEY);
        when(updates.findByIdAndSchoolId(500L, SCHOOL)).thenReturn(Optional.of(stored));

        assertThat(service.get(500L).canEdit()).isTrue();

        when(security.getRole()).thenReturn(Role.TEACHER);
        when(security.getUsername()).thenReturn("T2");
        assertThatThrownBy(() -> service.get(500L)).isInstanceOf(NoSuchElementException.class);

        when(security.getRole()).thenReturn(Role.STUDENT);
        when(security.getUsername()).thenReturn("S1");
        when(enrollments.findRealizedEffectiveEnrollments(SCHOOL, "S1", SESSION, TODAY)).thenReturn(List.of(enrollment(8L, 3L)));
        assertThat(service.get(500L).canEdit()).isFalse();
        stored.setExpiresAt(NOW.minusSeconds(1));
        assertThatThrownBy(() -> service.get(500L)).isInstanceOf(NoSuchElementException.class);
        stored.setExpiresAt(null);
        when(enrollments.findRealizedEffectiveEnrollments(SCHOOL, "S1", SESSION, TODAY)).thenReturn(List.of(enrollment(8L, 4L)));
        assertThatThrownBy(() -> service.get(500L)).isInstanceOf(NoSuchElementException.class);

        when(security.getRole()).thenReturn(Role.SUB_ADMIN);
        assertThat(service.get(500L).attachment().objectKey()).isNull();

        when(security.getRole()).thenReturn("PARENT");
        assertThatThrownBy(() -> service.get(500L)).isInstanceOf(AccessDeniedException.class);
    }

    // ─── Fixtures ───────────────────────────────────────────────────────

    private TimetableEntry entry(long classId, String className, Long sectionId, String sectionName, String subject, Day day) {
        TimetableEntry e = new TimetableEntry();
        e.setSchoolId(SCHOOL);
        e.setAcademicSessionId(SESSION);
        e.setClassId(classId);
        e.setClassName(className);
        e.setSectionId(sectionId);
        e.setSectionName(sectionName);
        e.setSubjectName(subject);
        e.setTeacherId("T1");
        e.setDay(day);
        e.setPeriodNumber(1);
        return e;
    }

    private ClassUpdate stored(String teacherId, String key) {
        ClassUpdate u = new ClassUpdate();
        u.setId(500L);
        u.setSchoolId(SCHOOL);
        u.setAcademicSessionId(SESSION);
        u.setTeacherId(teacherId);
        u.setClassId(8L);
        u.setClassName("8");
        u.setSectionId(3L);
        u.setSectionName("A");
        u.setSubjectName("Science");
        u.setTitle("Title");
        u.setMessage("Message");
        if (key != null) {
            u.setAttachmentObjectKey(key);
            u.setAttachmentFileName("abc.pdf");
            u.setAttachmentContentType("application/pdf");
            u.setAttachmentFileSize(1234L);
        }
        return u;
    }

    private StudentEnrollment enrollment(long classId, Long sectionId) {
        StudentEnrollment e = new StudentEnrollment();
        e.setId(1L);
        e.setClassId(classId);
        e.setSectionId(sectionId);
        e.setStatus(StudentEnrollmentStatus.ACTIVE);
        e.setEffectiveFrom(LocalDate.of(2026, 4, 1));
        return e;
    }
}
