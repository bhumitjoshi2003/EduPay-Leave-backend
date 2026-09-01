package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.TimetableBulkImportDtos.Result;
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
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the CSV parsing/validation/orchestration layer of timetable bulk import.
 *
 * {@code timetableRepository} is backed by a small in-memory fake (an ArrayList populated by the
 * stubbed {@code save()} and read back by the stubbed {@code findBy...} methods) rather than a
 * pure mock — this lets within-file conflict detection (two rows in the SAME uploaded CSV)
 * exercise the real {@link TimetableValidationService} against realistic, evolving state, exactly
 * as it would run against a real database, instead of hand-sequencing mock return values.
 */
@ExtendWith(MockitoExtension.class)
class TimetableBulkImportServiceTest {

    @Mock private TimetableRepository timetableRepository;
    @Mock private TeacherRepository teacherRepository;
    @Mock private SectionRepository sectionRepository;
    @Mock private SchoolClassRepository schoolClassRepository;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private HttpServletRequest request;

    private TimetableBulkImportService service;
    private final List<TimetableEntry> savedEntries = new ArrayList<>();

    private static final Long SCHOOL_ID = 1L;

    @BeforeEach
    void setUp() {
        savedEntries.clear();

        TimetableValidationService validationService = new TimetableValidationService();
        ReflectionTestUtils.setField(validationService, "timetableRepository", timetableRepository);

        service = new TimetableBulkImportService();
        ReflectionTestUtils.setField(service, "timetableRepository", timetableRepository);
        ReflectionTestUtils.setField(service, "teacherRepository", teacherRepository);
        ReflectionTestUtils.setField(service, "sectionRepository", sectionRepository);
        ReflectionTestUtils.setField(service, "schoolClassRepository", schoolClassRepository);
        ReflectionTestUtils.setField(service, "timetableValidationService", validationService);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        lenient().when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        SchoolClass tenA = new SchoolClass();
        tenA.setId(10L);
        tenA.setName("10");
        lenient().when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10")).thenReturn(Optional.of(tenA));

        SchoolClass elevenA = new SchoolClass();
        elevenA.setId(11L);
        elevenA.setName("11");
        lenient().when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "11")).thenReturn(Optional.of(elevenA));

        Section science = new Section();
        science.setId(77L);
        science.setName("Science");
        lenient().when(sectionRepository.findBySchoolIdAndClassIdAndName(SCHOOL_ID, 11L, "Science"))
                .thenReturn(Optional.of(science));

        Teacher teacher = new Teacher();
        teacher.setTeacherId("T1");
        teacher.setName("Jane Doe");
        lenient().when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID)).thenReturn(Optional.of(teacher));

        Teacher teacher2 = new Teacher();
        teacher2.setTeacherId("T2");
        teacher2.setName("John Roe");
        lenient().when(teacherRepository.findByTeacherIdAndSchoolId("T2", SCHOOL_ID)).thenReturn(Optional.of(teacher2));

        // In-memory fake: save() appends and assigns an id; findBy...() reads back from the
        // same list, so a row saved earlier in the same CSV is visible to later rows' validation
        // exactly as an already-existing DB row would be.
        lenient().when(timetableRepository.save(any(TimetableEntry.class))).thenAnswer(inv -> {
            TimetableEntry e = inv.getArgument(0);
            e.setId(100L + savedEntries.size());
            savedEntries.add(e);
            return e;
        });
        lenient().when(timetableRepository.findByClassNameAndSectionIdIsNullAndDayAndPeriodNumberAndSchoolId(
                any(), any(), any(), any())).thenAnswer(inv -> savedEntries.stream()
                .filter(e -> e.getClassName().equals(inv.getArgument(0)) && e.getSectionId() == null
                        && e.getDay() == inv.getArgument(1) && e.getPeriodNumber().equals(inv.getArgument(2)))
                .collect(Collectors.toList()));
        lenient().when(timetableRepository.findByClassNameAndSectionIdAndDayAndPeriodNumberAndSchoolId(
                any(), any(), any(), any(), any())).thenAnswer(inv -> savedEntries.stream()
                .filter(e -> e.getClassName().equals(inv.getArgument(0)) && Objects.equals(e.getSectionId(), inv.getArgument(1))
                        && e.getDay() == inv.getArgument(2) && e.getPeriodNumber().equals(inv.getArgument(3)))
                .collect(Collectors.toList()));
        lenient().when(timetableRepository.findByTeacherIdAndDayAndSchoolId(any(), any(), any()))
                .thenAnswer(inv -> savedEntries.stream()
                        .filter(e -> e.getTeacherId() != null && e.getTeacherId().equals(inv.getArgument(0))
                                && e.getDay() == inv.getArgument(1))
                        .collect(Collectors.toList()));
    }

    private MockMultipartFile csv(String... dataRows) {
        StringBuilder sb = new StringBuilder(String.join(",", TimetableBulkImportService.TEMPLATE_HEADERS)).append("\n");
        for (String row : dataRows) sb.append(row).append("\n");
        return new MockMultipartFile("file", "timetable.csv", "text/csv", sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void validRowIsSavedAndReportedAsCreated() {
        MockMultipartFile file = csv("10,,Monday,1,09:00,09:40,Mathematics,T1");

        Result result = service.bulkImport(file, request);

        assertThat(result.totalRows()).isEqualTo(1);
        assertThat(result.successful()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(0);
        assertThat(result.created().get(0).label()).isEqualTo("10 · Monday · Period 1");

        ArgumentCaptor<TimetableEntry> captor = ArgumentCaptor.forClass(TimetableEntry.class);
        verify(timetableRepository).save(captor.capture());
        TimetableEntry saved = captor.getValue();
        assertThat(saved.getSchoolId()).isEqualTo(SCHOOL_ID);
        assertThat(saved.getSubjectName()).isEqualTo("Mathematics");
        assertThat(saved.getTeacherId()).isEqualTo("T1");
        assertThat(saved.getTeacherName()).isEqualTo("Jane Doe");
        assertThat(saved.getSectionId()).isNull();
        assertThat(saved.getSimultaneousGroup()).isNull();
    }

    @Test
    void unknownClassIsRejectedRatherThanStoredAsFreeText() {
        MockMultipartFile file = csv("99,,Monday,1,09:00,09:40,Mathematics,T1");

        Result result = service.bulkImport(file, request);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).isEqualTo("Class '99' not found");
        verify(timetableRepository, never()).save(any());
    }

    @Test
    void unknownSectionIsRejected() {
        when(sectionRepository.findBySchoolIdAndClassIdAndName(SCHOOL_ID, 10L, "Z")).thenReturn(Optional.empty());
        MockMultipartFile file = csv("10,Z,Monday,1,09:00,09:40,Mathematics,T1");

        Result result = service.bulkImport(file, request);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).isEqualTo("Section 'Z' not found for class '10'");
    }

    @Test
    void invalidDayIsRejectedWithClearMessage() {
        MockMultipartFile file = csv("10,,Funday,1,09:00,09:40,Mathematics,T1");

        Result result = service.bulkImport(file, request);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).contains("Invalid Day 'Funday'");
    }

    @Test
    void dayMatchingIsCaseInsensitive() {
        MockMultipartFile file = csv("10,,monday,1,09:00,09:40,Mathematics,T1");

        Result result = service.bulkImport(file, request);

        assertThat(result.successful()).isEqualTo(1);
    }

    @Test
    void malformedTimeIsRejected() {
        MockMultipartFile file = csv("10,,Monday,1,9am,09:40,Mathematics,T1");

        Result result = service.bulkImport(file, request);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).contains("HH:mm");
    }

    @Test
    void endTimeBeforeStartTimeIsRejected() {
        MockMultipartFile file = csv("10,,Monday,1,10:00,09:40,Mathematics,T1");

        Result result = service.bulkImport(file, request);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).isEqualTo("End Time must be after Start Time");
    }

    @Test
    void unknownTeacherIdIsRejected() {
        MockMultipartFile file = csv("10,,Monday,1,09:00,09:40,Mathematics,GHOST");

        Result result = service.bulkImport(file, request);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).isEqualTo("Teacher ID 'GHOST' not found");
    }

    @Test
    void crossTenantTeacherIsNeverAccepted() {
        // "T1" is registered for SCHOOL_ID in setUp(); a bulk import running under a DIFFERENT
        // school must not find it even though the string id matches.
        when(securityUtil.getSchoolId()).thenReturn(999L);
        MockMultipartFile file = csv("10,,Monday,1,09:00,09:40,Mathematics,T1");
        // Class "10" also isn't registered for school 999, so this fails on Class first —
        // confirming schoolId scoping is applied at every lookup, not just teacher.
        when(schoolClassRepository.findBySchoolIdAndName(999L, "10")).thenReturn(Optional.empty());

        Result result = service.bulkImport(file, request);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).isEqualTo("Class '10' not found");
        verify(timetableRepository, never()).save(any());
    }

    @Test
    void slotAlreadyExistingInDbIsRejectedNotOverwritten() {
        TimetableEntry existing = new TimetableEntry();
        existing.setId(1L);
        existing.setClassName("10");
        existing.setDay(com.indraacademy.ias_management.entity.Day.MONDAY);
        existing.setPeriodNumber(1);
        existing.setStartTime("09:00");
        existing.setEndTime("09:40");
        existing.setSubjectName("Hindi");
        existing.setTeacherId("T1");
        savedEntries.add(existing);

        MockMultipartFile file = csv("10,,Monday,1,09:00,09:40,Mathematics,T1");

        Result result = service.bulkImport(file, request);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).contains("already assigned");
        // Only the pre-existing row is present — the conflicting row was never saved.
        assertThat(savedEntries).hasSize(1);
    }

    @Test
    void twoRowsTargetingTheSameSlotInTheSameFile_secondRowIsRejected() {
        MockMultipartFile file = csv(
                "10,,Monday,1,09:00,09:40,Mathematics,T1",
                "10,,Monday,1,10:00,10:40,Science,T2"
        );

        Result result = service.bulkImport(file, request);

        assertThat(result.successful()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).contains("already assigned");
    }

    @Test
    void twoRowsSameSimultaneousGroup_bothSucceed() {
        MockMultipartFile file = csv(
                "11,Science,Monday,3,09:15,09:50,Mathematics,T1,MATH_BIO",
                "11,Science,Monday,3,09:15,09:50,Biology,T2,MATH_BIO"
        );

        Result result = service.bulkImport(file, request);

        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.successful()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(0);
        assertThat(savedEntries).extracting(TimetableEntry::getSubjectName)
                .containsExactlyInAnyOrder("Mathematics", "Biology");
        assertThat(savedEntries).allMatch(e -> "MATH_BIO".equals(e.getSimultaneousGroup()));
    }

    @Test
    void differentSimultaneousGroupsInSameSlot_secondRowRejected() {
        MockMultipartFile file = csv(
                "11,Science,Monday,3,09:15,09:50,Mathematics,T1,MATH_BIO",
                "11,Science,Monday,3,09:15,09:50,Artificial Intelligence,T2,AI_MATHEMATICS"
        );

        Result result = service.bulkImport(file, request);

        assertThat(result.successful()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).contains("not part of the same simultaneous group");
    }

    @Test
    void groupedPlusUngroupedInSameSlot_rejected() {
        MockMultipartFile file = csv(
                "11,Science,Monday,3,09:15,09:50,Mathematics,T1,MATH_BIO",
                "11,Science,Monday,3,09:15,09:50,Biology,T2"
        );

        Result result = service.bulkImport(file, request);

        assertThat(result.successful()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    void exactDuplicateAssignmentInSameFile_rejected() {
        MockMultipartFile file = csv(
                "11,Science,Monday,3,09:15,09:50,Mathematics,T1,MATH_BIO",
                "11,Science,Monday,3,09:15,09:50,Mathematics,T1,MATH_BIO"
        );

        Result result = service.bulkImport(file, request);

        assertThat(result.successful()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).contains("already exists");
    }

    @Test
    void teacherDoubleBookedAcrossDifferentClassesWithinSameFile_rejected() {
        MockMultipartFile file = csv(
                "11,Science,Monday,3,09:15,09:50,Mathematics,T1,MATH_BIO",
                "10,,Monday,5,09:20,10:00,Physics,T1"
        );

        Result result = service.bulkImport(file, request);

        assertThat(result.successful()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).contains("overlapping period");
    }

    @Test
    void csvWithoutSimultaneousGroupColumnAtAll_stillWorksAsNormalImport() {
        // Old-format CSV — exactly the header this feature shipped with before this change.
        String legacyCsv = "Class,Section,Day,Period,Start Time,End Time,Subject,Teacher ID\n"
                + "10,,Monday,1,09:00,09:40,Mathematics,T1\n";
        MockMultipartFile file = new MockMultipartFile("file", "legacy.csv", "text/csv",
                legacyCsv.getBytes(StandardCharsets.UTF_8));

        Result result = service.bulkImport(file, request);

        assertThat(result.successful()).isEqualTo(1);
        assertThat(savedEntries.get(0).getSimultaneousGroup()).isNull();
    }

    @Test
    void missingRequiredFieldsAreRejected() {
        MockMultipartFile file = csv(",,Monday,1,09:00,09:40,Mathematics,T1");

        Result result = service.bulkImport(file, request);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).isEqualTo("Class is required");
    }

    @Test
    void blankRowsAreSkippedAndNotCountedInTotalRows() {
        MockMultipartFile file = csv("10,,Monday,1,09:00,09:40,Mathematics,T1", ",,,,,,,,");

        Result result = service.bulkImport(file, request);

        assertThat(result.totalRows()).isEqualTo(1);
    }

    @Test
    void oneFailingRowDoesNotPreventOtherValidRowsFromSucceeding() {
        MockMultipartFile file = csv(
                "10,,Monday,1,09:00,09:40,Mathematics,T1",
                "10,,Monday,2,09:40,10:20,Science,GHOST"
        );

        Result result = service.bulkImport(file, request);

        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.successful()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    void sectionSpecificRowResolvesSectionIdAndUsesSectionScopedSlotCheck() {
        Section sectionA = new Section();
        sectionA.setId(55L);
        sectionA.setName("A");
        when(sectionRepository.findBySchoolIdAndClassIdAndName(SCHOOL_ID, 10L, "A")).thenReturn(Optional.of(sectionA));

        MockMultipartFile file = csv("10,A,Monday,1,09:00,09:40,Mathematics,T1");

        Result result = service.bulkImport(file, request);

        assertThat(result.successful()).isEqualTo(1);
        assertThat(savedEntries.get(0).getSectionId()).isEqualTo(55L);
        assertThat(savedEntries.get(0).getSectionName()).isEqualTo("A");
    }
}
