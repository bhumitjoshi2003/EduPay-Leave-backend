package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.SchoolHolidayDTO;
import com.indraacademy.ias_management.service.SchoolHolidayService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/holidays")
public class SchoolHolidayController {

    @Autowired private SchoolHolidayService holidayService;

    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public ResponseEntity<List<SchoolHolidayDTO>> getHolidays(
            @RequestParam(required = false) String academicYear) {
        return ResponseEntity.ok(holidayService.getHolidays(academicYear));
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/range")
    public ResponseEntity<List<SchoolHolidayDTO>> getHolidaysByRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return ResponseEntity.ok(holidayService.getHolidaysBetween(start, end));
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/check")
    public ResponseEntity<Boolean> isHoliday(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(holidayService.isHoliday(date));
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping
    public ResponseEntity<SchoolHolidayDTO> createHoliday(@Valid @RequestBody SchoolHolidayDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(holidayService.createHoliday(dto));
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PutMapping("/{id}")
    public ResponseEntity<SchoolHolidayDTO> updateHoliday(@PathVariable Long id, @Valid @RequestBody SchoolHolidayDTO dto) {
        return ResponseEntity.ok(holidayService.updateHoliday(id, dto));
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteHoliday(@PathVariable Long id) {
        holidayService.deleteHoliday(id);
        return ResponseEntity.noContent().build();
    }
}
