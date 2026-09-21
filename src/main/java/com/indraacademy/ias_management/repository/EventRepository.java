package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface EventRepository extends JpaRepository<Event, Long> {

    List<Event> findBySchoolIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(Long schoolId, LocalDate rangeEndDate, LocalDate rangeStartDate);

    /** Tenant-scoped lookup for the object-storage event-image upload flow (see
     * FileUploadRequestService) — never trusts a bare event id without confirming it belongs to
     * the caller's own school. */
    Optional<Event> findByIdAndSchoolId(Long id, Long schoolId);
}
