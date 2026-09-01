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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the CSV parsing/validation/orchestration layer of timetable bulk import.
 * {@link TimetableRepository#save} is stubbed to assign an id and return the entry as-is —
 * this is not a test of JPA/Hibernate, just of this service's own row-by-row logic.
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

    private static final Long SCHOOL_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new TimetableBulkImportService();
        ReflectionTestUtils.setField(service, "timetableRepository", timetableRepository);
        ReflectionTestUtils.setField(service, "teacherRepository", teacherRepository);
        ReflectionTestUtils.setField(service, "sectionRepository", sectionRepository);
        ReflectionTestUtils.setField(service, "schoolClassRepository", schoolClassRepository);
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

        Teacher teacher = new Teacher();
        teacher.setTeacherId("T1");
        teacher.setName("Jane Doe");
        lenient().when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID)).thenReturn(Optional.of(teacher));

        lenient().when(timetableRepository.save(any(TimetableEntry.class))).thenAnswer(inv -> {
            TimetableEntry e = inv.getArgument(0);
            e.setId(100L);
            return e;
        });
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
    }

    @Test
    void unknownClassIsRejectedRatherThanStoredAsFreeText() {
        MockMultipartFile file = csv("11,,Monday,1,09:00,09:40,Mathematics,T1");

        Result result = service.bulkImport(file, request);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).isEqualTo("Class '11' not found");
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
    void slotAlreadyExistingInDbIsRejectedNotOverwritten() {
        when(timetableRepository.existsByClassNameAndSectionIdIsNullAndDayAndPeriodNumberAndSchoolId(
                eq("10"), any(), eq(1), eq(SCHOOL_ID))).thenReturn(true);
        MockMultipartFile file = csv("10,,Monday,1,09:00,09:40,Mathematics,T1");

        Result result = service.bulkImport(file, request);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).contains("already assigned");
        verify(timetableRepository, never()).save(any());
    }

    @Test
    void twoRowsTargetingTheSameSlotInTheSameFile_secondRowIsRejected() {
        // First row: no prior conflict. After it's "saved" (stubbed), the second identical-slot
        // row must be caught — simulated here by flipping the exists-check after the first save.
        when(timetableRepository.existsByClassNameAndSectionIdIsNullAndDayAndPeriodNumberAndSchoolId(
                eq("10"), any(), eq(1), eq(SCHOOL_ID)))
                .thenReturn(false)
                .thenReturn(true);

        MockMultipartFile file = csv(
                "10,,Monday,1,09:00,09:40,Mathematics,T1",
                "10,,Monday,1,10:00,10:40,Science,T1"
        );

        Result result = service.bulkImport(file, request);

        assertThat(result.successful()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).reason()).contains("already assigned");
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
        MockMultipartFile file = csv("10,,Monday,1,09:00,09:40,Mathematics,T1", ",,,,,,,");

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
        verify(timetableRepository).existsByClassNameAndSectionIdAndDayAndPeriodNumberAndSchoolId(
                eq("10"), eq(55L), any(), eq(1), eq(SCHOOL_ID));

        ArgumentCaptor<TimetableEntry> captor = ArgumentCaptor.forClass(TimetableEntry.class);
        verify(timetableRepository).save(captor.capture());
        assertThat(captor.getValue().getSectionId()).isEqualTo(55L);
        assertThat(captor.getValue().getSectionName()).isEqualTo("A");
    }
}
