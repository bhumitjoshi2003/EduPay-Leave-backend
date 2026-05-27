package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.SchoolHolidayDTO;
import com.indraacademy.ias_management.entity.SchoolHoliday;
import com.indraacademy.ias_management.repository.SchoolHolidayRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SchoolHolidayService {

    private static final Logger log = LoggerFactory.getLogger(SchoolHolidayService.class);

    @Autowired private SchoolHolidayRepository repository;
    @Autowired private SecurityUtil securityUtil;

    public List<SchoolHolidayDTO> getHolidays(String academicYear) {
        Long schoolId = securityUtil.getSchoolId();
        List<SchoolHoliday> holidays;
        if (academicYear != null && !academicYear.isBlank()) {
            holidays = repository.findBySchoolIdAndAcademicYearOrderByDateAsc(schoolId, academicYear);
        } else {
            holidays = repository.findBySchoolIdOrderByDateAsc(schoolId);
        }
        return holidays.stream().map(this::toDTO).collect(Collectors.toList());
    }

    public List<SchoolHolidayDTO> getHolidaysBetween(LocalDate start, LocalDate end) {
        Long schoolId = securityUtil.getSchoolId();
        return repository.findBySchoolIdAndDateBetweenOrderByDateAsc(schoolId, start, end)
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    public boolean isHoliday(LocalDate date) {
        Long schoolId = securityUtil.getSchoolId();
        return repository.existsBySchoolIdAndDate(schoolId, date);
    }

    @Transactional
    public SchoolHolidayDTO createHoliday(SchoolHolidayDTO dto) {
        Long schoolId = securityUtil.getSchoolId();
        if (repository.existsBySchoolIdAndDate(schoolId, dto.getDate())) {
            throw new IllegalArgumentException("A holiday already exists on " + dto.getDate());
        }

        SchoolHoliday holiday = new SchoolHoliday();
        holiday.setSchoolId(schoolId);
        holiday.setDate(dto.getDate());
        holiday.setName(dto.getName());
        holiday.setHolidayType(dto.getHolidayType());
        holiday.setAffectsAll(dto.isAffectsAll());
        holiday.setAcademicYear(dto.getAcademicYear());

        SchoolHoliday saved = repository.save(holiday);
        log.info("Created holiday '{}' on {} for school {}", saved.getName(), saved.getDate(), schoolId);
        return toDTO(saved);
    }

    @Transactional
    public SchoolHolidayDTO updateHoliday(Long id, SchoolHolidayDTO dto) {
        Long schoolId = securityUtil.getSchoolId();
        SchoolHoliday holiday = repository.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Holiday not found"));

        holiday.setDate(dto.getDate());
        holiday.setName(dto.getName());
        holiday.setHolidayType(dto.getHolidayType());
        holiday.setAffectsAll(dto.isAffectsAll());
        holiday.setAcademicYear(dto.getAcademicYear());

        SchoolHoliday saved = repository.save(holiday);
        log.info("Updated holiday '{}' on {} for school {}", saved.getName(), saved.getDate(), schoolId);
        return toDTO(saved);
    }

    @Transactional
    public void deleteHoliday(Long id) {
        Long schoolId = securityUtil.getSchoolId();
        SchoolHoliday holiday = repository.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Holiday not found"));
        repository.delete(holiday);
        log.info("Deleted holiday '{}' on {} for school {}", holiday.getName(), holiday.getDate(), schoolId);
    }

    private SchoolHolidayDTO toDTO(SchoolHoliday entity) {
        return new SchoolHolidayDTO(
                entity.getId(),
                entity.getDate(),
                entity.getName(),
                entity.getHolidayType(),
                entity.isAffectsAll(),
                entity.getAcademicYear()
        );
    }
}
