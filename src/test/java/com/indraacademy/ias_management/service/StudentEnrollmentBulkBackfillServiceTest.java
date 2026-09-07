package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.StudentEnrollmentBackfillDetail;
import com.indraacademy.ias_management.dto.StudentEnrollmentBackfillReport;
import com.indraacademy.ias_management.dto.StudentEnrollmentBulkBackfillReport;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.repository.SchoolRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentEnrollmentBulkBackfillServiceTest {

    @Mock SchoolRepository schoolRepository;
    @Mock StudentEnrollmentBackfillService backfillService;

    private StudentEnrollmentBulkBackfillService bulkService;
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 7);

    @BeforeEach
    void setUp() {
        bulkService = new StudentEnrollmentBulkBackfillService(schoolRepository, backfillService);
    }

    @Test
    void inactiveSchoolsAreExcludedByDefault() {
        School active = school(1L, true);
        School inactive = school(2L, false);
        when(schoolRepository.findAll()).thenReturn(List.of(active, inactive));
        when(backfillService.backfillForSchool(1L, AS_OF, true)).thenReturn(report(1L, 5, 5, 0, 0));

        StudentEnrollmentBulkBackfillReport result = bulkService.backfillActiveSchools(AS_OF, true, null);

        assertThat(result.schoolsRequested()).isEqualTo(1);
        assertThat(result.schoolReports()).extracting(StudentEnrollmentBackfillReport::schoolId).containsExactly(1L);
        verify(backfillService, never()).backfillForSchool(eq(2L), any(), anyBoolean());
    }

    @Test
    void schoolIdsFilterRestrictsProcessingToRequestedActiveSchools() {
        School s1 = school(1L, true);
        School s2 = school(2L, true);
        School s3 = school(3L, true);
        when(schoolRepository.findAll()).thenReturn(List.of(s1, s2, s3));
        when(backfillService.backfillForSchool(2L, AS_OF, true)).thenReturn(report(2L, 3, 3, 0, 0));

        StudentEnrollmentBulkBackfillReport result =
                bulkService.backfillActiveSchools(AS_OF, true, List.of(2L));

        assertThat(result.schoolsRequested()).isEqualTo(1);
        assertThat(result.schoolReports()).extracting(StudentEnrollmentBackfillReport::schoolId).containsExactly(2L);
        verify(backfillService, never()).backfillForSchool(eq(1L), any(), anyBoolean());
        verify(backfillService, never()).backfillForSchool(eq(3L), any(), anyBoolean());
    }

    @Test
    void schoolIdsFilterStillExcludesInactiveSchoolsEvenIfRequested() {
        School inactive = school(9L, false);
        when(schoolRepository.findAll()).thenReturn(List.of(inactive));

        StudentEnrollmentBulkBackfillReport result =
                bulkService.backfillActiveSchools(AS_OF, true, List.of(9L));

        assertThat(result.schoolsRequested()).isZero();
        assertThat(result.schoolReports()).isEmpty();
    }

    @Test
    void dryRunFlagIsPropagatedUnchangedToEachSchool() {
        School s1 = school(1L, true);
        when(schoolRepository.findAll()).thenReturn(List.of(s1));
        when(backfillService.backfillForSchool(1L, AS_OF, false)).thenReturn(report(1L, 2, 1, 1, 0));

        bulkService.backfillActiveSchools(AS_OF, false, null);

        verify(backfillService).backfillForSchool(1L, AS_OF, false);
    }

    @Test
    void oneSchoolFailureDoesNotPreventOthersFromBeingProcessed() {
        School bad = school(1L, true);
        School good = school(2L, true);
        when(schoolRepository.findAll()).thenReturn(List.of(bad, good));
        when(backfillService.backfillForSchool(1L, AS_OF, false))
                .thenThrow(new IllegalStateException("tenant DB row corrupt"));
        when(backfillService.backfillForSchool(2L, AS_OF, false)).thenReturn(report(2L, 4, 4, 0, 0));

        StudentEnrollmentBulkBackfillReport result = bulkService.backfillActiveSchools(AS_OF, false, null);

        assertThat(result.schoolsProcessed()).isEqualTo(1);
        assertThat(result.schoolsFailed()).isEqualTo(1);
        assertThat(result.schoolFailures()).hasSize(1);
        assertThat(result.schoolFailures().get(0).schoolId()).isEqualTo(1L);
        assertThat(result.schoolFailures().get(0).message()).contains("tenant DB row corrupt");
        assertThat(result.schoolReports()).extracting(StudentEnrollmentBackfillReport::schoolId).containsExactly(2L);
        verify(backfillService).backfillForSchool(2L, AS_OF, false);
    }

    @Test
    void aggregateTotalsSumAcrossAllProcessedSchools() {
        School s1 = school(1L, true);
        School s2 = school(2L, true);
        when(schoolRepository.findAll()).thenReturn(List.of(s1, s2));
        when(backfillService.backfillForSchool(1L, AS_OF, true)).thenReturn(report(1L, 10, 7, 2, 1));
        when(backfillService.backfillForSchool(2L, AS_OF, true)).thenReturn(report(2L, 5, 3, 1, 0));

        StudentEnrollmentBulkBackfillReport result = bulkService.backfillActiveSchools(AS_OF, true, null);

        assertThat(result.scanned()).isEqualTo(15);
        assertThat(result.eligible()).isEqualTo(10);
        assertThat(result.created()).isEqualTo(3);
        assertThat(result.alreadyPresent()).isEqualTo(1);
        assertThat(result.schoolsProcessed()).isEqualTo(2);
        assertThat(result.schoolsFailed()).isZero();
    }

    @Test
    void repeatedInvocationIsIndependentAndDoesNotAccumulateAcrossCalls() {
        School s1 = school(1L, true);
        when(schoolRepository.findAll()).thenReturn(List.of(s1));
        when(backfillService.backfillForSchool(1L, AS_OF, true)).thenReturn(report(1L, 3, 3, 0, 0));

        StudentEnrollmentBulkBackfillReport first = bulkService.backfillActiveSchools(AS_OF, true, null);
        StudentEnrollmentBulkBackfillReport second = bulkService.backfillActiveSchools(AS_OF, true, null);

        assertThat(first.scanned()).isEqualTo(3);
        assertThat(second.scanned()).isEqualTo(3);
        verify(backfillService, times(2)).backfillForSchool(1L, AS_OF, true);
    }

    private static School school(long id, boolean active) {
        School school = new School();
        school.setId(id);
        school.setActive(active);
        return school;
    }

    private static StudentEnrollmentBackfillReport report(
            long schoolId, int scanned, int eligible, int created, int alreadyPresent) {
        return new StudentEnrollmentBackfillReport(
                schoolId, AS_OF, true, scanned, eligible, created, alreadyPresent,
                0, 0, 0, 0, 0, 0, 0, List.<StudentEnrollmentBackfillDetail>of());
    }
}
