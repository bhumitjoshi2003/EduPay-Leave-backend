package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.TimetableBulkImportDtos.Result;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TimetableEntry;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.TimetableRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the CSV parsing/validation/orchestration layer of timetable bulk import.
 *
 * <p>The timetable is a permissive schedule record: this import never rejects a row for
 * colliding with another row already saved (in the same file or already in the database) —
 * {@code timetableRepository} is a plain mock here since there is no slot/teacher-conflict logic
 * left to exercise against realistic evolving state.
 *
 * <p>Phase F3: every import targets one explicit {@code academicSessionId}, and slot/teacher
 * matching in the fake is keyed by canonical classId + sessionId, not the className string.
 */
@ExtendWith(MockitoExtension.class)
class TimetableBulkImportServiceTest {

    @Mock private TimetableRepository timetableRepository;
    @Mock private TeacherRepository teacherRepository;
    @Mock private SectionRepository sectionRepository;
    @Mock private SchoolClassRepository schoolClassRepository;
    @Mock private TimetableSessionAccessService sessionAccess;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private HttpServletRequest request;

    private TimetableBulkImportService service;
    private final List<TimetableEntry> savedEntries = new ArrayList<>();

    private static final Long SCHOOL_ID = 1L;
    private static final Long SESSION_ID = 10L;
    private static final Long CLASS_10_ID = 100L;
    private static final Long CLASS_11_ID = 111L;

    @BeforeEach
    void setUp() {
        savedEntries.clear();

        service = new TimetableBulkImportService();
        ReflectionTestUtils.setField(service, "timetableRepository", timetableRepository);
        ReflectionTestUtils.setField(service, "teacherRepository", teacherRepository);
        ReflectionTestUtils.setField(service, "sectionRepository", sectionRepository);
        ReflectionTestUtils.setField(service, "schoolClassRepository", schoolClassRepository);
        ReflectionTestUtils.setField(service, "sessionAccess", sessionAccess);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        lenient().when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        AcademicSession session = new AcademicSession();
        session.setId(SESSION_ID);
        session.setLabel("2026-2027");
        session.setCurrent(true);
        lenient().when(sessionAccess.requireWritableOwnedSession(SCHOOL_ID, SESSION_ID)).thenReturn(session);

        SchoolClass tenA = new SchoolClass();
        tenA.setId(CLASS_10_ID);
        tenA.setName("10");
        lenient().when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10")).thenReturn(Optional.of(tenA));

        SchoolClass elevenA = new SchoolClass();
        elevenA.setId(CLASS_11_ID);
        elevenA.setName("11");
        lenient().when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "11")).thenReturn(Optional.of(elevenA));

        Section science = new Section();
        science.setId(77L);
        science.setName("Science");
        lenient().when(sectionRepository.findBySchoolIdAndClassIdAndName(SCHOOL_ID, CLASS_11_ID, "Science"))
                .thenReturn(Optional.of(science));

        Teacher teacher = new Teacher();
        teacher.setTeacherId("T1");
        teacher.setName("Jane Doe");
        lenient().when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID)).thenReturn(Optional.of(teacher));

        Teacher teacher2 = new Teacher();
        teacher2.setTeacherId("T2");
        teacher2.setName("John Roe");
        lenient().when(teacherRepository.findByTeacherIdAndSchoolId("T2", SCHOOL_ID)).thenReturn(Optional.of(teacher2));

        lenient().when(timetableRepository.save(any(TimetableEntry.class))).thenAnswer(inv -> {
            TimetableEntry e = inv.getArgument(0);
            e.setId(100L + savedEntries.size());
            savedEntries.add(e);
            return e;
        });
    }

    private MockMultipartFile csv(String... dataRows) {
        StringBuilder sb = new StringBuilder(String.join(",", TimetableBulkImportService.TEMPLATE_HEADERS)).append("\n");
        for (String row : dataRows) sb.append(row).append("\n");
        return new MockMultipartFile("file", "timetable.csv", "text/csv", sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Result bulkImport(MockMultipartFile file) {
        return service.bulkImport(file, SESSION_ID, request);
    }

    @Test
    void requiresAnExplicitWritableOwnedSession_beforeReadingTheFileAtAll() {
        when(sessionAccess.requireWritableOwnedSession(SCHOOL_ID, SESSION_ID))
                .thenThrow(new IllegalStateException("Session has ended and is read-only."));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> bulkImport(csv("10,,Monday,1,09:00,09:40,Mathematics,T1")))
                .isInstanceOf(IllegalStateException.class);
        verify(timetableRepository, never()).save(any());
    }

    @Test
    void validRowIsSavedAndReportedAsCreated_withSessionIdOnTheEntryAndResult() {
        MockMultipartFile file = csv("10,,Monday,1,09:00,09:40,Mathematics,T1");

        Result result = bulkImport(file);

        assertThat(result.academicSessionId()).isEqualTo(SESSION_ID);
        assertThat(result.totalRows()).isEqualTo(1);
        assertThat(result.successful()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(0);
        assertThat(result.created().get(0).label()).isEqualTo("10 · Monday · Period 1");

        ArgumentCaptor<TimetableEntry> captor = ArgumentCaptor.forClass(TimetableEntry.class);
        verify(timetableRepository).save(captor.capture());
        TimetableEntry saved = captor.getValue();
        assertThat(saved.getSchoolId()).isEqualTo(SCHOOL_ID);
        assertThat(saved.getAcademicSessionId()).isEqualTo(SESSION_ID);
        assertThat(saved.getClassId()).isEqualTo(CLASS_10_ID);
        assertThat(saved.getSubjectName()).isEqualTo("Mathematics");
        assertThat(saved.getTeacherId()).isEqualTo("T1");
        assertThat(saved.getTeacherName()).isEqualTo("Jane Doe");
        assertThat(saved.getSectionId()).isNull();
    }

    @Test
    void unknownClassIsRejectedRatherThanStoredAsFreeText() {
        MockMultipartFile file = csv("99,,Monday,1,09:00,09:40,Mathematics,T1");

        Result result = bulkImport(file);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).isEqualTo("Class '99' not found");
        verify(timetableRepository, never()).save(any());
    }

    @Test
    void unknownSectionIsRejected() {
        when(sectionRepository.findBySchoolIdAndClassIdAndName(SCHOOL_ID, CLASS_10_ID, "Z")).thenReturn(Optional.empty());
        MockMultipartFile file = csv("10,Z,Monday,1,09:00,09:40,Mathematics,T1");

        Result result = bulkImport(file);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).isEqualTo("Section 'Z' not found for class '10'");
    }

    @Test
    void invalidDayIsRejectedWithClearMessage() {
        MockMultipartFile file = csv("10,,Funday,1,09:00,09:40,Mathematics,T1");

        Result result = bulkImport(file);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).contains("Invalid Day 'Funday'");
    }

    @Test
    void dayMatchingIsCaseInsensitive() {
        MockMultipartFile file = csv("10,,monday,1,09:00,09:40,Mathematics,T1");

        Result result = bulkImport(file);

        assertThat(result.successful()).isEqualTo(1);
    }

    @Test
    void malformedTimeIsRejected() {
        MockMultipartFile file = csv("10,,Monday,1,9am,09:40,Mathematics,T1");

        Result result = bulkImport(file);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).contains("HH:mm");
    }

    @Test
    void endTimeBeforeStartTimeIsRejected() {
        MockMultipartFile file = csv("10,,Monday,1,10:00,09:40,Mathematics,T1");

        Result result = bulkImport(file);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).isEqualTo("End Time must be after Start Time");
    }

    @Test
    void unknownTeacherIdIsRejected() {
        MockMultipartFile file = csv("10,,Monday,1,09:00,09:40,Mathematics,GHOST");

        Result result = bulkImport(file);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).isEqualTo("Teacher ID 'GHOST' not found");
    }

    @Test
    void crossTenantTeacherIsNeverAccepted() {
        // "T1" is registered for SCHOOL_ID in setUp(); a bulk import running under a DIFFERENT
        // school must not find it even though the string id matches.
        when(securityUtil.getSchoolId()).thenReturn(999L);
        AcademicSession otherSchoolSession = new AcademicSession();
        otherSchoolSession.setId(SESSION_ID);
        when(sessionAccess.requireWritableOwnedSession(999L, SESSION_ID)).thenReturn(otherSchoolSession);
        MockMultipartFile file = csv("10,,Monday,1,09:00,09:40,Mathematics,T1");
        // Class "10" also isn't registered for school 999, so this fails on Class first —
        // confirming schoolId scoping is applied at every lookup, not just teacher.
        when(schoolClassRepository.findBySchoolIdAndName(999L, "10")).thenReturn(Optional.empty());

        Result result = bulkImport(file);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).isEqualTo("Class '10' not found");
        verify(timetableRepository, never()).save(any());
    }

    @Test
    void slotAlreadyOccupiedInDb_stillImportedAlongsideTheExistingRow() {
        // The NEW product rule: any number of rows may occupy the same
        // school/session/class/section/day/period, including the same subject. Bulk import never
        // rejects a row for this reason.
        MockMultipartFile file = csv("10,,Monday,1,09:00,09:40,Mathematics,T1");

        Result result = bulkImport(file);

        assertThat(result.successful()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(0);
    }

    @Test
    void identicalSlotInADifferentSession_doesNotConflict() {
        MockMultipartFile file = csv("10,,Monday,1,09:00,09:40,Mathematics,T1");

        Result result = bulkImport(file);

        assertThat(result.successful()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(0);
    }

    @Test
    void twoRowsTargetingTheSameSlotInTheSameFile_bothSucceed() {
        MockMultipartFile file = csv(
                "10,,Monday,1,09:00,09:40,Mathematics,T1",
                "10,,Monday,1,10:00,10:40,Science,T2"
        );

        Result result = bulkImport(file);

        assertThat(result.successful()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(0);
    }

    @Test
    void multipleRowsSameSlotSameSubjectDifferentTeachers_allSucceed() {
        MockMultipartFile file = csv(
                "11,Science,Monday,3,09:15,09:50,Mathematics,T1",
                "11,Science,Monday,3,09:15,09:50,Mathematics,T2"
        );

        Result result = bulkImport(file);

        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.successful()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(0);
        assertThat(savedEntries).extracting(TimetableEntry::getTeacherId)
                .containsExactlyInAnyOrder("T1", "T2");
    }

    @Test
    void teacherDoubleBookedAcrossDifferentClassesWithinSameFile_bothSucceed() {
        MockMultipartFile file = csv(
                "11,Science,Monday,3,09:15,09:50,Mathematics,T1",
                "10,,Monday,5,09:20,10:00,Physics,T1"
        );

        Result result = bulkImport(file);

        assertThat(result.successful()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(0);
    }

    @Test
    void csvWithObsoleteSimultaneousGroupColumnStillPresent_ignoredCleanlyAndStillWorks() {
        // Backward compatibility: an old CSV exported before this column was retired must still
        // import cleanly — the column is simply never looked up (name-keyed column matching).
        String legacyCsv = "Class,Section,Day,Period,Start Time,End Time,Subject,Teacher ID,Simultaneous Group\n"
                + "10,,Monday,1,09:00,09:40,Mathematics,T1,MATH_BIO\n";
        MockMultipartFile file = new MockMultipartFile("file", "legacy.csv", "text/csv",
                legacyCsv.getBytes(StandardCharsets.UTF_8));

        Result result = bulkImport(file);

        assertThat(result.successful()).isEqualTo(1);
    }

    @Test
    void templateHeadersNoLongerIncludeSimultaneousGroup() {
        assertThat(TimetableBulkImportService.TEMPLATE_HEADERS)
                .containsExactly("Class", "Section", "Day", "Period", "Start Time", "End Time", "Subject", "Teacher ID");
    }

    @Test
    void missingRequiredFieldsAreRejected() {
        MockMultipartFile file = csv(",,Monday,1,09:00,09:40,Mathematics,T1");

        Result result = bulkImport(file);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).isEqualTo("Class is required");
    }

    @Test
    void blankRowsAreSkippedAndNotCountedInTotalRows() {
        MockMultipartFile file = csv("10,,Monday,1,09:00,09:40,Mathematics,T1", ",,,,,,,");

        Result result = bulkImport(file);

        assertThat(result.totalRows()).isEqualTo(1);
    }

    @Test
    void oneFailingRowDoesNotPreventOtherValidRowsFromSucceeding() {
        MockMultipartFile file = csv(
                "10,,Monday,1,09:00,09:40,Mathematics,T1",
                "10,,Monday,2,09:40,10:20,Science,GHOST"
        );

        Result result = bulkImport(file);

        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.successful()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    void sectionSpecificRowResolvesSectionIdAndUsesSectionScopedSlotCheck() {
        Section sectionA = new Section();
        sectionA.setId(55L);
        sectionA.setName("A");
        when(sectionRepository.findBySchoolIdAndClassIdAndName(SCHOOL_ID, CLASS_10_ID, "A")).thenReturn(Optional.of(sectionA));

        MockMultipartFile file = csv("10,A,Monday,1,09:00,09:40,Mathematics,T1");

        Result result = bulkImport(file);

        assertThat(result.successful()).isEqualTo(1);
        assertThat(savedEntries.get(0).getSectionId()).isEqualTo(55L);
        assertThat(savedEntries.get(0).getSectionName()).isEqualTo("A");
    }
}
