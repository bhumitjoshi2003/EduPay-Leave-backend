package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.HomeworkClassworkDtos.AttachmentRef;
import com.indraacademy.ias_management.dto.HomeworkClassworkDtos.CreateRequest;
import com.indraacademy.ias_management.dto.HomeworkClassworkDtos.UpdateRequest;
import com.indraacademy.ias_management.dto.HomeworkClassworkDtos.WorkView;
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
class HomeworkClassworkServiceTest {
    @Mock HomeworkClassworkRepository works;
    @Mock HomeworkClassworkAttachmentRepository attachmentRepo;
    @Mock TimetableRepository timetable;
    @Mock TeacherSubstitutionRepository substitutions;
    @Mock TeacherRepository teachers;
    @Mock StudentEnrollmentRepository enrollments;
    @Mock TimetableSessionAccessService sessions;
    @Mock UploadIntentRepository uploadIntents;
    @Mock ObjectStorageService objectStorage;
    @Mock SecurityUtil security;
    @Mock ApplicationEventPublisher events;

    HomeworkClassworkService service;

    static final long SCHOOL = 1L;
    static final long SESSION = 11L;
    /** Wednesday 23 Sep 2026. */
    static final LocalDate TODAY = LocalDate.of(2026, 9, 23);
    static final String KEY = "schools/1/homework/new/attachments/abc.pdf";

    AcademicSession session;

    @BeforeEach
    void setup() {
        Clock clock = Clock.fixed(TODAY.atTime(10, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        service = new HomeworkClassworkService(works, attachmentRepo, timetable, substitutions, teachers, enrollments, sessions,
                uploadIntents, objectStorage, security, events, clock);
        session = new AcademicSession();
        session.setId(SESSION);
        session.setStartDate(LocalDate.of(2026, 4, 1));
        session.setEndDate(LocalDate.of(2027, 3, 31));
        lenient().when(security.getSchoolId()).thenReturn(SCHOOL);
        lenient().when(security.getUsername()).thenReturn("T1");
        lenient().when(security.getRole()).thenReturn(Role.TEACHER);
        lenient().when(sessions.requireCurrentSessionForTeacherWrite(SCHOOL)).thenReturn(session);
        lenient().when(sessions.currentSessionOrNull(SCHOOL)).thenReturn(session);
        lenient().when(timetable.findByIdAndSchoolId(100L, SCHOOL)).thenReturn(Optional.of(entry(100L, "T1", Day.WEDNESDAY)));
        Teacher t1 = new Teacher();
        t1.setName("Ms Rao");
        lenient().when(teachers.findByTeacherIdAndSchoolId("T1", SCHOOL)).thenReturn(Optional.of(t1));
        lenient().when(works.saveAndFlush(any())).thenAnswer(i -> {
            HomeworkClasswork w = i.getArgument(0);
            if (w.getId() == null) w.setId(500L);
            return w;
        });
        lenient().when(objectStorage.resolveDisplayUrl(anyString())).thenReturn("https://cdn.example/signed");
    }

    private CreateRequest request(String classwork, String homework, LocalDate due, String key) {
        return new CreateRequest(100L, null, classwork, homework, due,
                key == null ? List.of() : List.of(new AttachmentRef(key, "sheet.pdf")));
    }

    private CreateRequest requestWith(List<AttachmentRef> files) {
        return new CreateRequest(100L, null, "x", null, null, files);
    }

    private void completedUpload(String key, String contentType, long size) {
        UploadIntent i = intent("T1", "HOMEWORK_ATTACHMENT", UploadIntent.STATUS_COMPLETED, SCHOOL);
        i.setObjectKey(key);
        i.setExpectedContentType(contentType);
        i.setExpectedSize(size);
        lenient().when(uploadIntents.findByObjectKey(key)).thenReturn(Optional.of(i));
    }

    // ─── Create ─────────────────────────────────────────────────────────

    @Test
    void teacherCreatesForTheirOwnPeriodWithClassSectionSubjectTakenFromTheTimetable() {
        WorkView view = service.create(request("Read chapter 4", "Exercise 4.1", TODAY.plusDays(1), null));

        ArgumentCaptor<HomeworkClasswork> saved = ArgumentCaptor.forClass(HomeworkClasswork.class);
        verify(works).saveAndFlush(saved.capture());
        HomeworkClasswork w = saved.getValue();
        assertThat(w.getSchoolId()).isEqualTo(SCHOOL);
        assertThat(w.getAcademicSessionId()).isEqualTo(SESSION);
        assertThat(w.getClassId()).isEqualTo(8L);
        assertThat(w.getSectionId()).isEqualTo(3L);
        assertThat(w.getSubjectName()).isEqualTo("Science");
        assertThat(w.getTeacherId()).isEqualTo("T1");
        assertThat(w.getTeacherName()).isEqualTo("Ms Rao");
        assertThat(w.getWorkDate()).isEqualTo(TODAY);
        assertThat(view.canEdit()).isTrue();
    }

    @Test
    void creatingPublishesOneNotificationEventForTheClassSection() {
        service.create(request(null, "Exercise 4.1", TODAY.plusDays(1), null));

        ArgumentCaptor<HomeworkClassworkPostedEvent> event = ArgumentCaptor.forClass(HomeworkClassworkPostedEvent.class);
        verify(events, times(1)).publishEvent(event.capture());
        assertThat(event.getValue().academicSessionId()).isEqualTo(SESSION);
        assertThat(event.getValue().classId()).isEqualTo(8L);
        assertThat(event.getValue().sectionId()).isEqualTo(3L);
        assertThat(event.getValue().hasHomework()).isTrue();
        assertThat(event.getValue().hasClasswork()).isFalse();
    }

    @Test
    void aSubstituteTeacherCanPostForAPeriodTheyCoverThatDay() {
        when(timetable.findByIdAndSchoolId(100L, SCHOOL)).thenReturn(Optional.of(entry(100L, "T9", Day.WEDNESDAY)));
        TeacherSubstitution cover = new TeacherSubstitution();
        cover.setSubstituteTeacherId("T1");
        when(substitutions.findBySchoolIdAndDateAndTimetableEntryIdAndStatus(SCHOOL, TODAY, 100L, TeacherSubstitutionStatus.ACTIVE))
                .thenReturn(Optional.of(cover));

        service.create(request("Worksheet", null, null, null));

        verify(works).saveAndFlush(any());
    }

    @Test
    void teacherCannotPostForAnotherTeachersPeriod() {
        when(timetable.findByIdAndSchoolId(100L, SCHOOL)).thenReturn(Optional.of(entry(100L, "T9", Day.WEDNESDAY)));
        when(substitutions.findBySchoolIdAndDateAndTimetableEntryIdAndStatus(any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request("x", null, null, null))).isInstanceOf(AccessDeniedException.class);
        verify(works, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    @Test
    void aPeriodFromAnotherSchoolIsNotFound() {
        when(timetable.findByIdAndSchoolId(100L, SCHOOL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request("x", null, null, null))).isInstanceOf(NoSuchElementException.class);
        verify(works, never()).saveAndFlush(any());
    }

    @Test
    void rejectsPeriodsOutsideTheCurrentSessionOrOnTheWrongWeekday() {
        TimetableEntry oldSession = entry(100L, "T1", Day.WEDNESDAY);
        oldSession.setAcademicSessionId(10L);
        when(timetable.findByIdAndSchoolId(100L, SCHOOL)).thenReturn(Optional.of(oldSession));
        assertThatThrownBy(() -> service.create(request("x", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("current academic session");

        when(timetable.findByIdAndSchoolId(100L, SCHOOL)).thenReturn(Optional.of(entry(100L, "T1", Day.MONDAY)));
        assertThatThrownBy(() -> service.create(request("x", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not scheduled");
        verify(works, never()).saveAndFlush(any());
    }

    @Test
    void requiresClassworkOrHomeworkAndAValidDueDate() {
        assertThatThrownBy(() -> service.create(request("  ", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("classwork, homework");
        assertThatThrownBy(() -> service.create(request(null, "hw", TODAY.minusDays(1), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("due date");
        assertThatThrownBy(() -> service.create(request("x".repeat(5001), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most");
        verify(works, never()).saveAndFlush(any());
    }

    @Test
    void aDueDateWithoutHomeworkIsDropped() {
        service.create(request("Classwork only", null, TODAY.plusDays(3), null));
        ArgumentCaptor<HomeworkClasswork> saved = ArgumentCaptor.forClass(HomeworkClasswork.class);
        verify(works).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getDueDate()).isNull();
    }

    @Test
    void aSecondPostForTheSamePeriodAndDateIsRejected() {
        when(works.existsBySchoolIdAndTimetableEntryIdAndWorkDate(SCHOOL, 100L, TODAY)).thenReturn(true);
        assertThatThrownBy(() -> service.create(request("x", null, null, null))).isInstanceOf(IllegalStateException.class);

        when(works.existsBySchoolIdAndTimetableEntryIdAndWorkDate(SCHOOL, 100L, TODAY)).thenReturn(false);
        doThrow(new DataIntegrityViolationException("uq")).when(works).saveAndFlush(any());
        assertThatThrownBy(() -> service.create(request("x", null, null, null))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(events);
    }

    // ─── Attachment ─────────────────────────────────────────────────────

    @Test
    void createsWithSeveralAttachmentsInOrderUsingTheVerifiedUploadsTypeAndSize() {
        completedUpload("k/1.png", "image/png", 2048);
        completedUpload("k/2.pdf", "application/pdf", 4096);

        WorkView view = service.create(requestWith(List.of(new AttachmentRef("k/1.png", "diagram.png"),
                new AttachmentRef("k/2.pdf", "../../etc/worksheet.pdf"))));

        assertThat(view.attachments()).extracting(a -> a.fileName()).containsExactly("diagram.png", "worksheet.pdf");
        assertThat(view.attachments()).extracting(a -> a.type()).containsExactly("IMAGE", "PDF");
        assertThat(view.attachments()).extracting(a -> a.fileSize()).containsExactly(2048L, 4096L);
        assertThat(view.attachments().get(0).url()).isEqualTo("https://cdn.example/signed");
        assertThat(view.attachments().get(0).objectKey()).isEqualTo("k/1.png");
        ArgumentCaptor<HomeworkClasswork> saved = ArgumentCaptor.forClass(HomeworkClasswork.class);
        verify(works).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getAttachments()).extracting(HomeworkClassworkAttachment::getSortOrder).containsExactly(0, 1);
        assertThat(saved.getValue().getAttachments()).allSatisfy(a -> assertThat(a.getHomeworkClasswork()).isSameAs(saved.getValue()));
    }

    @Test
    void rejectsMoreThanFiveAttachmentsDuplicatesAndKeysAlreadyUsedByAnotherPost() {
        List<AttachmentRef> six = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) six.add(new AttachmentRef("k/" + i + ".png", "f.png"));
        assertThatThrownBy(() -> service.create(requestWith(six)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most 5");

        completedUpload("k/1.png", "image/png", 10);
        assertThatThrownBy(() -> service.create(requestWith(List.of(new AttachmentRef("k/1.png", "a"), new AttachmentRef("k/1.png", "b")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twice");

        when(attachmentRepo.existsByObjectKey("k/1.png")).thenReturn(true);
        assertThatThrownBy(() -> service.create(requestWith(List.of(new AttachmentRef("k/1.png", "a")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("attachment");
        verify(works, never()).saveAndFlush(any());
    }

    @Test
    void rejectsAttachmentsThatAreNotTheTeachersOwnCompletedHomeworkUpload() {
        for (UploadIntent bad : List.of(
                intent("T2", "HOMEWORK_ATTACHMENT", UploadIntent.STATUS_COMPLETED, SCHOOL),
                intent("T1", "SUPPORT_TICKET_SCREENSHOT", UploadIntent.STATUS_COMPLETED, SCHOOL),
                intent("T1", "HOMEWORK_ATTACHMENT", UploadIntent.STATUS_PENDING, SCHOOL),
                intent("T1", "HOMEWORK_ATTACHMENT", UploadIntent.STATUS_COMPLETED, 2L))) {
            when(uploadIntents.findByObjectKey(KEY)).thenReturn(Optional.of(bad));
            assertThatThrownBy(() -> service.create(request("x", null, null, KEY)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("attachment");
        }
        when(uploadIntents.findByObjectKey(KEY)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(request("x", null, null, KEY))).isInstanceOf(IllegalArgumentException.class);
        verify(works, never()).saveAndFlush(any());
    }

    // ─── Edit / delete ──────────────────────────────────────────────────

    @Test
    void teacherEditsTheirOwnPostWithoutNotifyingStudents() {
        when(works.findByIdAndSchoolId(500L, SCHOOL)).thenReturn(Optional.of(stored("T1", KEY)));

        WorkView view = service.update(500L, new UpdateRequest("Updated", "New hw", TODAY.plusDays(2),
                List.of(new AttachmentRef(KEY, "renamed-by-client.pdf"))));

        assertThat(view.classwork()).isEqualTo("Updated");
        assertThat(view.dueDate()).isEqualTo(TODAY.plusDays(2));
        assertThat(view.attachments()).extracting(a -> a.fileName()).containsExactly("abc.pdf");
        verifyNoInteractions(uploadIntents);
        verifyNoInteractions(events);
    }

    @Test
    void editingCanAddRemoveAndReorderAttachments() {
        HomeworkClasswork post = stored("T1", KEY);
        when(works.findByIdAndSchoolId(500L, SCHOOL)).thenReturn(Optional.of(post));
        completedUpload("k/new.png", "image/png", 99);

        WorkView view = service.update(500L, new UpdateRequest("x", null, null,
                List.of(new AttachmentRef("k/new.png", "new.png"), new AttachmentRef(KEY, "abc.pdf"))));

        assertThat(view.attachments()).extracting(a -> a.fileName()).containsExactly("new.png", "abc.pdf");
        assertThat(post.getAttachments()).extracting(HomeworkClassworkAttachment::getSortOrder).containsExactly(0, 1);

        service.update(500L, new UpdateRequest("x", null, null, List.of()));
        assertThat(post.getAttachments()).isEmpty();
    }

    @Test
    void editingCannotAttachSomeoneElsesUpload() {
        when(works.findByIdAndSchoolId(500L, SCHOOL)).thenReturn(Optional.of(stored("T1", null)));
        UploadIntent others = intent("T2", "HOMEWORK_ATTACHMENT", UploadIntent.STATUS_COMPLETED, SCHOOL);
        when(uploadIntents.findByObjectKey(KEY)).thenReturn(Optional.of(others));

        assertThatThrownBy(() -> service.update(500L, new UpdateRequest("x", null, null, List.of(new AttachmentRef(KEY, "a.pdf")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("attachment");
        verify(works, never()).saveAndFlush(any());
    }

    @Test
    void fileNamesAreDisplayOnlyAndSanitised() {
        assertThat(HomeworkClassworkService.cleanFileName("..\\evil\\a.pdf", "k/x.pdf")).isEqualTo("a.pdf");
        assertThat(HomeworkClassworkService.cleanFileName("   ", "k/x.pdf")).isEqualTo("x.pdf");
        assertThat(HomeworkClassworkService.cleanFileName("a\u0000b.png", "k/x.png")).isEqualTo("ab.png");
        assertThat(HomeworkClassworkService.cleanFileName("n".repeat(300), "k/x.png")).hasSize(255);
    }

    @Test
    void teacherCannotEditOrDeleteAnotherTeachersPost() {
        when(works.findByIdAndSchoolId(500L, SCHOOL)).thenReturn(Optional.of(stored("T9", null)));

        assertThatThrownBy(() -> service.update(500L, new UpdateRequest("x", null, null, List.of()))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.delete(500L)).isInstanceOf(AccessDeniedException.class);
        verify(works, never()).saveAndFlush(any());
        verify(works, never()).delete(any());
    }

    @Test
    void teacherDeletesTheirOwnPostWithoutNotifying() {
        HomeworkClasswork mine = stored("T1", null);
        when(works.findByIdAndSchoolId(500L, SCHOOL)).thenReturn(Optional.of(mine));

        service.delete(500L);

        verify(works).delete(mine);
        verifyNoInteractions(events);
    }

    @Test
    void aPostFromAnotherSchoolCannotBeChanged() {
        when(works.findByIdAndSchoolId(500L, SCHOOL)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(500L)).isInstanceOf(NoSuchElementException.class);
    }

    // ─── Student ────────────────────────────────────────────────────────

    @Test
    void studentSeesOnlyTheirOwnClassAndSectionFromTheirActiveEnrollment() {
        when(security.getUsername()).thenReturn("S1");
        when(enrollments.findRealizedEffectiveEnrollments(SCHOOL, "S1", SESSION, TODAY)).thenReturn(List.of(enrollment(8L, 3L)));
        when(works.findForClassOnDate(SCHOOL, SESSION, 8L, 3L, TODAY)).thenReturn(List.of(stored("T1", KEY)));

        List<WorkView> today = service.studentOn(null);

        assertThat(today).hasSize(1);
        assertThat(today.get(0).canEdit()).isFalse();
        assertThat(today.get(0).attachments()).singleElement().satisfies(a -> {
            assertThat(a.url()).isEqualTo("https://cdn.example/signed");
            assertThat(a.objectKey()).as("students never receive object keys").isNull();
        });
    }

    @Test
    void aStudentWithoutAnActiveEnrollmentSeesNothing() {
        when(security.getUsername()).thenReturn("S1");
        when(enrollments.findRealizedEffectiveEnrollments(SCHOOL, "S1", SESSION, TODAY)).thenReturn(List.of());

        assertThat(service.studentOn(null)).isEmpty();
        assertThat(service.studentUpcoming()).isEmpty();
        assertThat(service.studentRecent()).isEmpty();
        verify(works, never()).findForClassOnDate(any(), any(), any(), any(), any());
    }

    @Test
    void aStudentCannotOpenAPostFromAnotherClassOrSection() {
        when(security.getUsername()).thenReturn("S1");
        when(security.getRole()).thenReturn(Role.STUDENT);
        when(enrollments.findRealizedEffectiveEnrollments(SCHOOL, "S1", SESSION, TODAY)).thenReturn(List.of(enrollment(8L, 4L)));
        when(works.findByIdAndSchoolId(500L, SCHOOL)).thenReturn(Optional.of(stored("T1", null)));

        assertThatThrownBy(() -> service.get(500L)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void schoolAdminsCanReadButTeachersCannotOpenOthersPosts() {
        when(works.findByIdAndSchoolId(500L, SCHOOL)).thenReturn(Optional.of(stored("T9", null)));
        when(security.getRole()).thenReturn(Role.ADMIN);
        assertThat(service.get(500L).canEdit()).isFalse();

        when(security.getRole()).thenReturn(Role.TEACHER);
        assertThatThrownBy(() -> service.get(500L)).isInstanceOf(NoSuchElementException.class);
    }

    // ─── Fixtures ───────────────────────────────────────────────────────

    private TimetableEntry entry(long id, String teacherId, Day day) {
        TimetableEntry e = new TimetableEntry();
        e.setId(id);
        e.setSchoolId(SCHOOL);
        e.setAcademicSessionId(SESSION);
        e.setClassId(8L);
        e.setClassName("8");
        e.setSectionId(3L);
        e.setSectionName("A");
        e.setSubjectName("Science");
        e.setTeacherId(teacherId);
        e.setDay(day);
        e.setPeriodNumber(2);
        return e;
    }

    private HomeworkClasswork stored(String teacherId, String key) {
        HomeworkClasswork w = new HomeworkClasswork();
        w.setId(500L);
        w.setSchoolId(SCHOOL);
        w.setAcademicSessionId(SESSION);
        w.setTeacherId(teacherId);
        w.setClassId(8L);
        w.setClassName("8");
        w.setSectionId(3L);
        w.setSectionName("A");
        w.setSubjectName("Science");
        w.setWorkDate(TODAY);
        w.setClasswork("Read");
        if (key != null) {
            HomeworkClassworkAttachment a = new HomeworkClassworkAttachment();
            a.setId(900L);
            a.setHomeworkClasswork(w);
            a.setObjectKey(key);
            a.setFileName("abc.pdf");
            a.setContentType("application/pdf");
            a.setFileSize(1234);
            w.getAttachments().add(a);
        }
        return w;
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

    private UploadIntent intent(String requester, String purpose, String status, long schoolId) {
        UploadIntent i = new UploadIntent();
        i.setSchoolId(schoolId);
        i.setRequestedByUserId(requester);
        i.setPurpose(purpose);
        i.setObjectKey(KEY);
        i.setStatus(status);
        return i;
    }
}
