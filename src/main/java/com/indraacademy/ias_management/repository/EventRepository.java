package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface EventRepository extends JpaRepository<Event, Long> {
    List<Event> findByStartDateLessThanEqualAndEndDateGreaterThanEqual(LocalDate rangeEndDate, LocalDate rangeStartDate);
}
