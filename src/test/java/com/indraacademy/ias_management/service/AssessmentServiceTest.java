package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.AssessmentDtos.*;
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
class AssessmentServiceTest {
    @Mock AssessmentRepository assessments;
    @Mock TimetableRepository timetable;
    @Mock SchoolClassRepository classes;
    @Mock SectionRepository sections;
    @Mock ClassSubjectRepository classSubjects;
    @Mock TeacherRepository teachers;
    @Mock AdminRepository admins;
    @Mock SchoolRepository schools;
    @Mock StudentEnrollmentRepository enrollments;
    @Mock TimetableSessionAccessService sessions;
    @Mock TeacherClassScopeService classScope;
    @Mock UploadIntentRepository uploadIntents;
    @Mock ObjectStorageService objectStorage;
    @Mock SecurityUtil security;
    @Mock ApplicationEventPublisher events;

    AssessmentService service;

    static final long SCHOOL = 1L;
    static final long SESSION = 11L;
    /** 23 Sep 2026, 10:00 IST (school-local). */
    static final LocalDate TODAY = LocalDate.of(2026, 9, 23);
    static final Instant NOW = TODAY.atTime(10, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
    static final String KEY = "schools/1/assessments/new/attachments/a.pdf";

    AcademicSession session;

    @BeforeEach
    void setup() {
        service = new AssessmentService(assessments, timetable, classes, sections, classSubjects, teachers, admins, schools,
                enrollments, sessions, classScope, uploadIntents, objectStorage, security, events, Clock.fixed(NOW, ZoneOffset.UTC));
        session = new AcademicSession();
        session.setId(SESSION);
        session.setStartDate(LocalDate.of(2026, 4, 1));
        session.setEndDate(LocalDate.of(2027, 3, 31));
        School school = new School();
        school.setId(SCHOOL);
        school.setTimezone("Asia/Kolkata");
        lenient().when(schools.findById(SCHOOL)).thenReturn(Optional.of(school));
        lenient().when(security.getSchoolId()).thenReturn(SCHOOL);
        lenient().when(security.getUsername()).thenReturn("T1");
        lenient().when(security.getRole()).thenReturn(Role.TEACHER);
        lenient().when(sessions.requireCurrentSessionForTeacherWrite(SCHOOL)).thenReturn(session);
        lenient().when(sessions.currentSessionOrNull(SCHOOL)).thenReturn(session);
        lenient().when(timetable.findByAcademicSessionIdAndTeacherIdAndSchoolId(SESSION, "T1", SCHOOL)).thenReturn(List.of(
                entry(8L, "8", 3L, "A", "Science"), entry(8L, "8", 3L, "A", "Maths"),
                entry(8L, "8", 4L, "B", "Science"), entry(10L, "10", null, null, "Physics")));
        Teacher t1 = new Teacher();
        t1.setName("Ms Rao");
        lenient().when(teachers.findByTeacherIdAndSchoolId("T1", SCHOOL)).thenReturn(Optional.of(t1));
        lenient().when(assessments.saveAndFlush(any())).thenAnswer(i -> {
            Assessment a = i.getArgument(0);
            if (a.getId() == null) a.setId(500L);
            return a;
        });
        lenient().when(objectStorage.resolveDisplayUrl(anyString())).thenReturn("https://cdn.example/signed");
        lenient().when(classScope.resolveOwnScope(anyString(), eq(SCHOOL)))
                .thenReturn(new TeacherClassScopeService.TeacherScope(null, null, false));
    }

    private CreateRequest create(Long classId, Long sectionId, String subject, LocalDate date) {
        return new CreateRequest(AssessmentType.UNIT_TEST, "Unit Test 2", classId, sectionId, subject, date,
                LocalTime.of(10, 0), LocalTime.of(11, 0), "Chapters 3–4", "Bring a calculator", null);
    }

    private UpdateRequest update(String title, LocalDate date, LocalTime start, LocalTime end) {
        return new UpdateRequest(AssessmentType.UNIT_TEST, title, date, start, end, "Chapters 3–4", null, null);
    }

    private void asAdmin() {
        when(security.getRole()).thenReturn(Role.ADMIN);
        when(security.getUsername()).thenReturn("A1");
    }

    // ─── Authorization ──────────────────────────────────────────────────

    @Test
    void subAdminStudentAndParentCannotManageAssessments() {
        for (String role : List.of(Role.SUB_ADMIN, Role.STUDENT, Role.PARENT)) {
            when(security.getRole()).thenReturn(role);
            assertThatThrownBy(() -> service.create(create(8L, 3L, "Science", TODAY.plusDays(7)))).as(role)
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.contexts()).as(role).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.manage("upcoming")).as(role).isInstanceOf(AccessDeniedException.class);
        }
        verify(assessments, never()).saveAndFlush(any());
    }

    @Test
    void aTeacherSchedulesForAClassSectionSubjectTheyTeachWithServerResolvedContext() {
        AssessmentView view = service.create(create(8L, 3L, "science", TODAY.plusDays(7)));

        ArgumentCaptor<Assessment> saved = ArgumentCaptor.forClass(Assessment.class);
        verify(assessments).saveAndFlush(saved.capture());
        Assessment a = saved.getValue();
        assertThat(a.getSchoolId()).isEqualTo(SCHOOL);
        assertThat(a.getAcademicSessionId()).isEqualTo(SESSION);
        assertThat(a.getClassName()).isEqualTo("8");
        assertThat(a.getSectionName()).isEqualTo("A");
        assertThat(a.getSubjectName()).isEqualTo("Science");
        assertThat(a.getCreatedByUserId()).isEqualTo("T1");
        assertThat(a.getCreatedByRole()).isEqualTo(Role.TEACHER);
        assertThat(a.getCreatedByName()).isEqualTo("Ms Rao");
        assertThat(a.getReminderSentAt()).isNull();
        assertThat(view.canEdit()).isTrue();
        assertThat(view.typeLabel()).isEqualTo("Unit Test");
    }

    @Test
    void aTeacherCannotTargetAClassSectionOrSubjectTheyDoNotTeach() {
        assertThatThrownBy(() -> service.create(create(9L, 3L, "Science", TODAY.plusDays(7)))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.create(create(8L, 4L, "Maths", TODAY.plusDays(7)))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.create(create(8L, null, "Science", TODAY.plusDays(7)))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.create(create(10L, 5L, "Physics", TODAY.plusDays(7)))).isInstanceOf(AccessDeniedException.class);
        verify(assessments, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    @Test
    void creatingPublishesOneScheduledEventForTheClassSection() {
        service.create(create(8L, 3L, "Science", TODAY.plusDays(7)));

        ArgumentCaptor<AssessmentNotificationEvent> event = ArgumentCaptor.forClass(AssessmentNotificationEvent.class);
        verify(events, times(1)).publishEvent(event.capture());
        assertThat(event.getValue().kind()).isEqualTo(AssessmentNotificationEvent.Kind.SCHEDULED);
        assertThat(event.getValue().classId()).isEqualTo(8L);
        assertThat(event.getValue().sectionId()).isEqualTo(3L);
        assertThat(event.getValue().academicSessionId()).isEqualTo(SESSION);
    }

    @Test
    void anAssessmentForTodayOrTomorrowIsCreatedAlreadyReminded() {
        service.create(create(8L, 3L, "Science", TODAY.plusDays(1)));
        ArgumentCaptor<Assessment> saved = ArgumentCaptor.forClass(Assessment.class);
        verify(assessments).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getReminderSentAt()).isEqualTo(NOW);
    }

    @Test
    void anAdminSchedulesForAnyActiveClassSectionWithAConfiguredSubject() {
        asAdmin();
        when(classes.findByIdAndSchoolId(8L, SCHOOL)).thenReturn(Optional.of(schoolClass(8L, "8", true)));
        when(sections.findByIdAndSchoolId(3L, SCHOOL)).thenReturn(Optional.of(section(3L, 8L, "A", true)));
        when(classSubjects.findBySchoolId(SCHOOL)).thenReturn(List.of(subject(8L, "Science"), subject(8L, "English")));
        Admin admin = new Admin();
        admin.setName("Principal");
        when(admins.findByAdminIdAndSchoolId("A1", SCHOOL)).thenReturn(Optional.of(admin));

        service.create(create(8L, 3L, "english", TODAY.plusDays(7)));

        ArgumentCaptor<Assessment> saved = ArgumentCaptor.forClass(Assessment.class);
        verify(assessments).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getSubjectName()).isEqualTo("English");
        assertThat(saved.getValue().getSectionName()).isEqualTo("A");
        assertThat(saved.getValue().getCreatedByRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getValue().getCreatedByName()).isEqualTo("Principal");
    }

    @Test
    void anAdminIsLimitedToTheirSchoolsActiveClassesSectionsOfThatClassAndConfiguredSubjects() {
        asAdmin();
        when(classes.findByIdAndSchoolId(99L, SCHOOL)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(create(99L, null, "Science", TODAY.plusDays(7)))).isInstanceOf(NoSuchElementException.class);

        when(classes.findByIdAndSchoolId(7L, SCHOOL)).thenReturn(Optional.of(schoolClass(7L, "7", false)));
        assertThatThrownBy(() -> service.create(create(7L, null, "Science", TODAY.plusDays(7)))).isInstanceOf(NoSuchElementException.class);

        when(classes.findByIdAndSchoolId(8L, SCHOOL)).thenReturn(Optional.of(schoolClass(8L, "8", true)));
        when(sections.findByIdAndSchoolId(5L, SCHOOL)).thenReturn(Optional.of(section(5L, 9L, "C", true)));
        assertThatThrownBy(() -> service.create(create(8L, 5L, "Science", TODAY.plusDays(7)))).isInstanceOf(NoSuchElementException.class);

        when(classSubjects.findBySchoolId(SCHOOL)).thenReturn(List.of(subject(8L, "Science")));
        assertThatThrownBy(() -> service.create(create(8L, null, "Dance", TODAY.plusDays(7))))
                .hasMessageContaining("not configured");
        verify(assessments, never()).saveAndFlush(any());
    }

    @Test
    void writesRequireACurrentSession() {
        when(sessions.requireCurrentSessionForTeacherWrite(SCHOOL)).thenThrow(new IllegalStateException("No current session"));
        assertThatThrownBy(() -> service.create(create(8L, 3L, "Science", TODAY.plusDays(7)))).isInstanceOf(IllegalStateException.class);
    }

    // ─── Validation ─────────────────────────────────────────────────────

    @Test
    void theDateMustBeInsideTheSessionAndNotInThePast() {
        assertThatThrownBy(() -> service.create(create(8L, 3L, "Science", TODAY.minusDays(1)))).hasMessageContaining("past");
        assertThatThrownBy(() -> service.create(create(8L, 3L, "Science", LocalDate.of(2027, 4, 2)))).hasMessageContaining("session");
        assertThatThrownBy(() -> service.create(create(8L, 3L, "Science", null))).hasMessageContaining("date");
    }

    @Test
    void timesTitleSyllabusAndInstructionsAreValidated() {
        CreateRequest base = create(8L, 3L, "Science", TODAY.plusDays(7));
        assertThatThrownBy(() -> service.create(with(base, LocalTime.of(11, 0), LocalTime.of(10, 0)))).hasMessageContaining("after");
        assertThatThrownBy(() -> service.create(with(base, LocalTime.of(10, 0), LocalTime.of(10, 0)))).hasMessageContaining("after");
        assertThatThrownBy(() -> service.create(with(base, null, LocalTime.of(10, 0)))).hasMessageContaining("start time");
        assertThatThrownBy(() -> service.create(new CreateRequest(null, "T", 8L, 3L, "Science", TODAY.plusDays(7), null, null, "S", null, null)))
                .hasMessageContaining("type");
        assertThatThrownBy(() -> service.create(new CreateRequest(AssessmentType.OTHER, " ", 8L, 3L, "Science", TODAY.plusDays(7), null, null, "S", null, null)))
                .hasMessageContaining("title");
        assertThatThrownBy(() -> service.create(new CreateRequest(AssessmentType.OTHER, "x".repeat(151), 8L, 3L, "Science", TODAY.plusDays(7), null, null, "S", null, null)))
                .hasMessageContaining("150");
        assertThatThrownBy(() -> service.create(new CreateRequest(AssessmentType.OTHER, "T", 8L, 3L, "Science", TODAY.plusDays(7), null, null, "", null, null)))
                .hasMessageContaining("syllabus");
        assertThatThrownBy(() -> service.create(new CreateRequest(AssessmentType.OTHER, "T", 8L, 3L, "Science", TODAY.plusDays(7), null, null, "x".repeat(3001), null, null)))
                .hasMessageContaining("3000");
        assertThatThrownBy(() -> service.create(new CreateRequest(AssessmentType.OTHER, "T", 8L, 3L, "Science", TODAY.plusDays(7), null, null, "S", "x".repeat(2001), null)))
                .hasMessageContaining("2000");
        verify(assessments, never()).saveAndFlush(any());
    }

    private CreateRequest with(CreateRequest r, LocalTime start, LocalTime end) {
        return new CreateRequest(r.assessmentType(), r.title(), r.classId(), r.sectionId(), r.subjectName(),
                r.assessmentDate(), start, end, r.syllabus(), r.instructions(), r.attachment());
    }

    // ─── Attachment ─────────────────────────────────────────────────────

    @Test
    void anAttachmentMustBeTheCallersOwnCompletedAssessmentUploadInTheSchool() {
        completedUpload(KEY, "ASSESSMENT_ATTACHMENT", "T1", SCHOOL);
        AssessmentView view = service.create(withAttachment(KEY));
        assertThat(view.attachment().contentType()).isEqualTo("application/pdf");
        assertThat(view.attachment().fileSize()).isEqualTo(2048L);
        assertThat(view.attachment().fileName()).isEqualTo("paper.pdf");

        for (String[] c : new String[][] {{"k-other", "ASSESSMENT_ATTACHMENT", "T2", "1"},
                {"k-school", "ASSESSMENT_ATTACHMENT", "T1", "2"}, {"k-purpose", "CLASS_UPDATE_ATTACHMENT", "T1", "1"}}) {
            completedUpload(c[0], c[1], c[2], Long.parseLong(c[3]));
            assertThatThrownBy(() -> service.create(withAttachment(c[0]))).as(c[0]).hasMessage("Invalid attachment reference.");
        }
        UploadIntent pending = intent("k-pending", "ASSESSMENT_ATTACHMENT", "T1", SCHOOL);
        pending.setStatus(UploadIntent.STATUS_PENDING);
        when(uploadIntents.findByObjectKey("k-pending")).thenReturn(Optional.of(pending));
        assertThatThrownBy(() -> service.create(withAttachment("k-pending"))).hasMessage("Invalid attachment reference.");

        when(assessments.existsByAttachmentObjectKey("k-used")).thenReturn(true);
        completedUpload("k-used", "ASSESSMENT_ATTACHMENT", "T1", SCHOOL);
        assertThatThrownBy(() -> service.create(withAttachment("k-used"))).hasMessage("Invalid attachment reference.");
    }

    private CreateRequest withAttachment(String key) {
        CreateRequest r = create(8L, 3L, "Science", TODAY.plusDays(7));
        return new CreateRequest(r.assessmentType(), r.title(), r.classId(), r.sectionId(), r.subjectName(), r.assessmentDate(),
                r.startTime(), r.endTime(), r.syllabus(), r.instructions(), new AttachmentRef(key, "../paper.pdf"));
    }

    // ─── Edit / delete ──────────────────────────────────────────────────

    @Test
    void theCreatorAndSchoolAdminsMayEditOrDeleteButNotOtherTeachers() {
        Assessment a = stored("T1", TODAY.plusDays(7));
        when(assessments.findByIdAndSchoolId(500L, SCHOOL)).thenReturn(Optional.of(a));

        // T2 has no relation to class 8 at all: indistinguishable from "not found".
        when(security.getUsername()).thenReturn("T2");
        assertThatThrownBy(() -> service.update(500L, update("New", a.getAssessmentDate(), null, null))).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.delete(500L)).isInstanceOf(NoSuchElementException.class);

        when(security.getUsername()).thenReturn("T1");
        service.update(500L, update("By creator", a.getAssessmentDate(), a.getStartTime(), a.getEndTime()));

        asAdmin();
        service.update(500L, update("New", a.getAssessmentDate(), a.getStartTime(), a.getEndTime()));
        service.delete(500L);
        verify(assessments).delete(a);
    }

    @Test
    void anotherSchoolsAssessmentIsNotFound() {
        when(assessments.findByIdAndSchoolId(500L, SCHOOL)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(500L)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.get(500L)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void onlyADateTimeOrTitleChangeNotifiesStudents() {
        Assessment a = stored("T1", TODAY.plusDays(7));
        when(assessments.findByIdAndSchoolId(500L, SCHOOL)).thenReturn(Optional.of(a));

        // Syllabus/type only → silent.
        service.update(500L, new UpdateRequest(AssessmentType.CLASS_TEST, "Unit Test 2", a.getAssessmentDate(),
                LocalTime.of(10, 0), LocalTime.of(11, 0), "Only chapter 3", "New note", null));
        verifyNoInteractions(events);

        service.update(500L, update("Unit Test 2", a.getAssessmentDate(), LocalTime.of(12, 0), LocalTime.of(13, 0)));
        service.update(500L, update("Renamed", a.getAssessmentDate(), LocalTime.of(12, 0), LocalTime.of(13, 0)));
        service.update(500L, update("Renamed", TODAY.plusDays(9), LocalTime.of(12, 0), LocalTime.of(13, 0)));
        ArgumentCaptor<AssessmentNotificationEvent> event = ArgumentCaptor.forClass(AssessmentNotificationEvent.class);
        verify(events, times(3)).publishEvent(event.capture());
        assertThat(event.getAllValues()).allMatch(e -> e.kind() == AssessmentNotificationEvent.Kind.UPDATED);
    }

    @Test
    void reschedulingReArmsTheReminderUnlessTheNewDateIsTodayOrTomorrow() {
        Assessment a = stored("T1", TODAY.plusDays(7));
        a.setReminderSentAt(NOW.minusSeconds(3600));
        when(assessments.findByIdAndSchoolId(500L, SCHOOL)).thenReturn(Optional.of(a));

        service.update(500L, update("Unit Test 2", TODAY.plusDays(5), null, null));
        assertThat(a.getReminderSentAt()).isNull();

        service.update(500L, update("Unit Test 2", TODAY.plusDays(1), null, null));
        assertThat(a.getReminderSentAt()).isEqualTo(NOW);

        a.setReminderSentAt(null);
        service.update(500L, update("Other title", TODAY.plusDays(1), null, null));
        assertThat(a.getReminderSentAt()).as("unchanged date keeps reminder state").isNull();
    }

    @Test
    void aPastAssessmentsDetailsCanBeCorrectedButNotMovedIntoThePast() {
        Assessment a = stored("T1", TODAY.minusDays(3));
        when(assessments.findByIdAndSchoolId(500L, SCHOOL)).thenReturn(Optional.of(a));
        service.update(500L, update("Typo fixed", TODAY.minusDays(3), null, null));
        assertThat(a.getTitle()).isEqualTo("Typo fixed");
        assertThatThrownBy(() -> service.update(500L, update("x", TODAY.minusDays(2), null, null))).hasMessageContaining("past");
    }

    @Test
    void assessmentsFromAPastSessionAreReadOnly() {
        Assessment a = stored("T1", TODAY.plusDays(7));
        a.setAcademicSessionId(10L);
        when(assessments.findByIdAndSchoolId(500L, SCHOOL)).thenReturn(Optional.of(a));
        assertThatThrownBy(() -> service.update(500L, update("x", a.getAssessmentDate(), null, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    // ─── Lists / contexts ───────────────────────────────────────────────

    @Test
    void teacherContextsGroupTheirTimetableByClassSectionAndSubject() {
        List<ContextClass> contexts = service.contexts();
        assertThat(contexts).extracting(ContextClass::className).containsExactly("8", "10");
        assertThat(contexts.get(0).sections()).extracting(ContextSection::sectionName).containsExactly("A", "B");
        assertThat(contexts.get(0).sections().get(0).subjects()).containsExactly("Maths", "Science");
        assertThat(contexts.get(1).sections()).singleElement().satisfies(s -> {
            assertThat(s.sectionId()).isNull();
            assertThat(s.subjects()).containsExactly("Physics");
        });
    }

    @Test
    void adminContextsOfferEveryActiveClassWholeClassAndEachSectionWithConfiguredSubjects() {
        asAdmin();
        when(classes.findBySchoolIdAndActiveOrderByDisplayOrderAsc(SCHOOL, true)).thenReturn(List.of(schoolClass(8L, "8", true)));
        when(classes.findBySchoolIdOrderByDisplayOrderAsc(SCHOOL)).thenReturn(List.of(schoolClass(8L, "8", true)));
        when(sections.findBySchoolIdAndActiveOrderByDisplayOrderAsc(SCHOOL, true)).thenReturn(List.of(section(3L, 8L, "A", true)));
        ClassSubject legacy = subject(null, "Hindi");
        legacy.setClassName("8");
        when(classSubjects.findBySchoolId(SCHOOL)).thenReturn(List.of(subject(8L, "Science"), legacy));

        List<ContextClass> contexts = service.contexts();
        assertThat(contexts).singleElement().satisfies(c -> {
            assertThat(c.sections()).extracting(ContextSection::sectionId).containsExactly(null, 3L);
            assertThat(c.sections().get(0).subjects()).containsExactly("Hindi", "Science");
        });
    }

    @Test
    void adminManageIsSchoolWideWithFullPermissions() {
        asAdmin();
        Assessment a = stored("T1", TODAY.plusDays(2));
        when(assessments.findBySchoolIdAndAcademicSessionIdAndAssessmentDateGreaterThanEqualOrderByAssessmentDateAscStartTimeAscIdAsc(
                eq(SCHOOL), eq(SESSION), eq(TODAY), any(Pageable.class))).thenReturn(List.of(a));
        assertThat(service.manage("upcoming")).singleElement().satisfies(v -> {
            assertThat(v.canEdit()).isTrue();
            assertThat(v.canDelete()).isTrue();
            assertThat(v.createdByCurrentUser()).isFalse();
        });
        service.manage("past");
        verify(assessments).findBySchoolIdAndAcademicSessionIdAndAssessmentDateBeforeOrderByAssessmentDateDescIdDesc(
                eq(SCHOOL), eq(SESSION), eq(TODAY), any(Pageable.class));
    }

    // ─── Teacher visibility ─────────────────────────────────────────────

    /** Candidates the coarse query would return for T1; the service applies the exact relevance rule. */
    private void candidates(Assessment... rows) {
        when(assessments.findTeacherCandidatesFrom(eq(SCHOOL), eq(SESSION), eq("T1"), anyCollection(), eq(TODAY), any(Pageable.class)))
                .thenReturn(List.of(rows));
    }

    private Assessment byAdmin(long id, long classId, Long sectionId, String subject) {
        Assessment a = stored("A1", TODAY.plusDays(3));
        a.setId(id);
        a.setCreatedByRole(Role.ADMIN);
        a.setCreatedByName("Principal");
        a.setClassId(classId);
        a.setSectionId(sectionId);
        a.setSubjectName(subject);
        return a;
    }

    private void classTeacherOf(String className, Long sectionId, long classId) {
        when(classScope.resolveOwnScope("T1", SCHOOL)).thenReturn(new TeacherClassScopeService.TeacherScope(className, sectionId, false));
        lenient().when(classes.findBySchoolIdAndName(SCHOOL, className)).thenReturn(Optional.of(schoolClass(classId, className, true)));
    }

    private List<Long> visibleIds() {
        return service.manage("upcoming").stream().map(AssessmentView::id).toList();
    }

    @Test
    void theCreatorSeesTheirOwnAssessmentWithFullPermissions() {
        Assessment own = stored("T1", TODAY.plusDays(2));
        candidates(own);
        assertThat(service.manage("upcoming")).singleElement().satisfies(v -> {
            assertThat(v.canEdit()).isTrue();
            assertThat(v.canDelete()).isTrue();
            assertThat(v.createdByCurrentUser()).isTrue();
            assertThat(v.attachment()).isNull();
        });
    }

    @Test
    void aSubjectTeacherSeesARelevantAdminAssessmentReadOnly() {
        candidates(byAdmin(601L, 8L, 3L, "science"));
        assertThat(service.manage("upcoming")).singleElement().satisfies(v -> {
            assertThat(v.id()).isEqualTo(601L);
            assertThat(v.canEdit()).isFalse();
            assertThat(v.canDelete()).isFalse();
            assertThat(v.createdByCurrentUser()).isFalse();
            assertThat(v.createdByRole()).isEqualTo(Role.ADMIN);
            assertThat(v.createdByName()).isEqualTo("Principal");
        });
    }

    @Test
    void sectionOrSubjectMismatchOrAnotherClassIsNotVisible() {
        candidates(byAdmin(602L, 8L, 5L, "Science"),     // section 5: T1 teaches 8-A and 8-B only
                byAdmin(603L, 8L, 4L, "Maths"),           // T1 teaches Maths only in 8-A
                byAdmin(604L, 9L, 3L, "Science"));        // class 9: not taught
        assertThat(visibleIds()).isEmpty();
    }

    @Test
    void aWholeClassAssessmentIsVisibleToTeachersOfThatSubjectButNotOtherSubjects() {
        candidates(byAdmin(605L, 8L, null, "Science"), byAdmin(606L, 8L, null, "English"));
        assertThat(visibleIds()).containsExactly(605L);
    }

    @Test
    void theClassTeacherSeesEveryAssessmentForTheirSectionAndWholeClassButNotOtherSections() {
        classTeacherOf("7", 2L, 7L);
        candidates(byAdmin(607L, 7L, 2L, "History"), byAdmin(608L, 7L, null, "English"), byAdmin(609L, 7L, 6L, "History"));
        List<AssessmentView> views = service.manage("upcoming");
        assertThat(views).extracting(AssessmentView::id).containsExactly(607L, 608L);
        assertThat(views).allSatisfy(v -> assertThat(v.canEdit()).isFalse());
    }

    @Test
    void aClassTeacherWithAnAmbiguousSectionlessAssignmentGetsNoClassTeacherVisibility() {
        when(classScope.resolveOwnScope("T1", SCHOOL)).thenReturn(new TeacherClassScopeService.TeacherScope("7", null, true));
        candidates(byAdmin(610L, 7L, 2L, "History"));
        assertThat(visibleIds()).isEmpty();
        verify(classes, never()).findBySchoolIdAndName(anyLong(), anyString());
    }

    @Test
    void theCandidateQueryIsScopedToTheTeachersOwnAndRelatedClasses() {
        classTeacherOf("7", 2L, 7L);
        candidates();
        service.manage("upcoming");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<Long>> ids = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(assessments).findTeacherCandidatesFrom(eq(SCHOOL), eq(SESSION), eq("T1"), ids.capture(), eq(TODAY), any(Pageable.class));
        assertThat(ids.getValue()).containsExactlyInAnyOrder(8L, 10L, 7L);
    }

    @Test
    void aTeacherQualifyingSeveralWaysSeesEachAssessmentOnce() {
        classTeacherOf("8", 3L, 8L);
        Assessment own = stored("T1", TODAY.plusDays(2));                  // creator + subject + class teacher
        candidates(own, byAdmin(611L, 8L, 3L, "Science"));                 // subject + class teacher
        assertThat(visibleIds()).containsExactly(500L, 611L);
    }

    @Test
    void anotherTeachersAssessmentForARelevantClassIsVisibleReadOnly() {
        Assessment other = stored("T2", TODAY.plusDays(4));
        other.setId(612L);
        other.setCreatedByName("Mr Sharma");
        candidates(other);
        assertThat(service.manage("upcoming")).singleElement().satisfies(v -> {
            assertThat(v.createdByName()).isEqualTo("Mr Sharma");
            assertThat(v.canEdit()).isFalse();
        });
    }

    @Test
    void relevantTeachersAreReadOnlyOnTheWriteAndSingleReadPaths() {
        Assessment relevant = byAdmin(601L, 8L, 3L, "Science");
        when(assessments.findByIdAndSchoolId(601L, SCHOOL)).thenReturn(Optional.of(relevant));

        AssessmentView view = service.get(601L);
        assertThat(view.canEdit()).isFalse();
        assertThat(view.canDelete()).isFalse();
        assertThatThrownBy(() -> service.update(601L, update("x", relevant.getAssessmentDate(), null, null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.delete(601L)).isInstanceOf(AccessDeniedException.class);
        verify(assessments, never()).delete(any());
        verify(assessments, never()).saveAndFlush(any());
    }

    @Test
    void anUnrelatedTeacherCannotReadOrChangeIt() {
        Assessment unrelated = byAdmin(613L, 9L, 5L, "Science");
        when(assessments.findByIdAndSchoolId(613L, SCHOOL)).thenReturn(Optional.of(unrelated));
        assertThatThrownBy(() -> service.get(613L)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.update(613L, update("x", unrelated.getAssessmentDate(), null, null)))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.delete(613L)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void relevanceNeverReachesAPastSessionsAssessment() {
        Assessment old = byAdmin(614L, 8L, 3L, "Science");
        old.setAcademicSessionId(10L);
        when(assessments.findByIdAndSchoolId(614L, SCHOOL)).thenReturn(Optional.of(old));
        assertThatThrownBy(() -> service.get(614L)).isInstanceOf(NoSuchElementException.class);
    }

    // ─── Student ────────────────────────────────────────────────────────

    @Test
    void studentsSeeTheirActiveEnrollmentsClassSectionInTheCurrentSessionWithoutObjectKeys() {
        when(security.getUsername()).thenReturn("S1");
        when(enrollments.findRealizedEffectiveEnrollments(SCHOOL, "S1", SESSION, TODAY)).thenReturn(List.of(enrollment(8L, 3L)));
        Assessment a = stored("T1", TODAY.plusDays(2));
        a.setAttachmentObjectKey(KEY);
        a.setAttachmentFileName("p.pdf");
        a.setAttachmentContentType("application/pdf");
        a.setAttachmentFileSize(10L);
        when(assessments.findForClassFrom(eq(SCHOOL), eq(SESSION), eq(8L), eq(3L), eq(TODAY), any(Pageable.class))).thenReturn(List.of(a));

        List<AssessmentView> views = service.studentUpcoming(3);
        assertThat(views).singleElement().satisfies(v -> {
            assertThat(v.canEdit()).isFalse();
            assertThat(v.attachment().objectKey()).isNull();
        });
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(assessments).findForClassFrom(anyLong(), anyLong(), anyLong(), any(), any(), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(3);

        service.studentMonth(null);
        verify(assessments).findForClassBetween(SCHOOL, SESSION, 8L, 3L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        service.studentPast();
        verify(assessments).findForClassBefore(eq(SCHOOL), eq(SESSION), eq(8L), eq(3L), eq(TODAY), any(Pageable.class));
    }

    @Test
    void aStudentWithoutAnActiveEnrollmentOrCurrentSessionSeesNothing() {
        when(security.getUsername()).thenReturn("S1");
        StudentEnrollment closed = enrollment(8L, 3L);
        closed.setStatus(StudentEnrollmentStatus.CLOSED);
        when(enrollments.findRealizedEffectiveEnrollments(SCHOOL, "S1", SESSION, TODAY)).thenReturn(List.of(closed));
        assertThat(service.studentUpcoming(null)).isEmpty();

        when(sessions.currentSessionOrNull(SCHOOL)).thenReturn(null);
        assertThat(service.studentPast()).isEmpty();
        verify(assessments, never()).findForClassBefore(anyLong(), anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void singleReadIsLimitedToTheCreatorSchoolAdminsAndStudentsOfThatClass() {
        Assessment a = stored("T1", TODAY.plusDays(7));
        when(assessments.findByIdAndSchoolId(500L, SCHOOL)).thenReturn(Optional.of(a));
        assertThat(service.get(500L).canEdit()).isTrue();

        when(security.getUsername()).thenReturn("T2");
        assertThatThrownBy(() -> service.get(500L)).isInstanceOf(NoSuchElementException.class);

        when(security.getRole()).thenReturn(Role.STUDENT);
        when(security.getUsername()).thenReturn("S1");
        when(enrollments.findRealizedEffectiveEnrollments(SCHOOL, "S1", SESSION, TODAY)).thenReturn(List.of(enrollment(8L, 4L)));
        assertThatThrownBy(() -> service.get(500L)).isInstanceOf(NoSuchElementException.class);
        when(enrollments.findRealizedEffectiveEnrollments(SCHOOL, "S1", SESSION, TODAY)).thenReturn(List.of(enrollment(8L, 3L)));
        assertThat(service.get(500L).canEdit()).isFalse();

        when(security.getRole()).thenReturn(Role.SUB_ADMIN);
        assertThatThrownBy(() -> service.get(500L)).isInstanceOf(AccessDeniedException.class);
    }

    // ─── Fixtures ───────────────────────────────────────────────────────

    private TimetableEntry entry(long classId, String className, Long sectionId, String sectionName, String subject) {
        TimetableEntry e = new TimetableEntry();
        e.setSchoolId(SCHOOL);
        e.setAcademicSessionId(SESSION);
        e.setClassId(classId);
        e.setClassName(className);
        e.setSectionId(sectionId);
        e.setSectionName(sectionName);
        e.setSubjectName(subject);
        e.setTeacherId("T1");
        e.setDay(Day.MONDAY);
        e.setPeriodNumber(1);
        return e;
    }

    private Assessment stored(String creator, LocalDate date) {
        Assessment a = new Assessment();
        a.setId(500L);
        a.setSchoolId(SCHOOL);
        a.setAcademicSessionId(SESSION);
        a.setCreatedByUserId(creator);
        a.setCreatedByRole(Role.TEACHER);
        a.setAssessmentType(AssessmentType.UNIT_TEST);
        a.setClassId(8L);
        a.setClassName("8");
        a.setSectionId(3L);
        a.setSectionName("A");
        a.setSubjectName("Science");
        a.setTitle("Unit Test 2");
        a.setAssessmentDate(date);
        a.setStartTime(LocalTime.of(10, 0));
        a.setEndTime(LocalTime.of(11, 0));
        a.setSyllabus("Chapters 3–4");
        return a;
    }

    private SchoolClass schoolClass(long id, String name, boolean active) {
        SchoolClass c = new SchoolClass();
        c.setId(id);
        c.setSchoolId(SCHOOL);
        c.setName(name);
        c.setActive(active);
        return c;
    }

    private Section section(long id, long classId, String name, boolean active) {
        Section s = new Section();
        s.setId(id);
        s.setSchoolId(SCHOOL);
        s.setClassId(classId);
        s.setName(name);
        s.setActive(active);
        return s;
    }

    private ClassSubject subject(Long classId, String name) {
        ClassSubject cs = new ClassSubject();
        cs.setSchoolId(SCHOOL);
        cs.setClassId(classId);
        cs.setClassName("8");
        cs.setSubjectName(name);
        return cs;
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

    private UploadIntent intent(String key, String purpose, String requester, long school) {
        UploadIntent i = new UploadIntent();
        i.setSchoolId(school);
        i.setRequestedByUserId(requester);
        i.setPurpose(purpose);
        i.setObjectKey(key);
        i.setStatus(UploadIntent.STATUS_COMPLETED);
        i.setExpectedContentType("application/pdf");
        i.setExpectedSize(2048L);
        return i;
    }

    private void completedUpload(String key, String purpose, String requester, long school) {
        lenient().when(uploadIntents.findByObjectKey(key)).thenReturn(Optional.of(intent(key, purpose, requester, school)));
    }
}
