package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.NotBlank;

public class TimetableDtos {

    /**
     * Body for POST /api/timetable/{id}/simultaneous. Deliberately carries only what actually
     * differs between the two subjects — class/section/day/period/time are inherited from the
     * existing entry server-side (see TimetableService#addSimultaneous), so the client can never
     * send a mismatched slot, and the simultaneousGroup tag is generated/reused automatically
     * rather than typed by the admin.
     */
    public record AddSimultaneousRequest(
            @NotBlank(message = "Subject name is required.") String subjectName,
            @NotBlank(message = "Please select a teacher.") String teacherId
    ) {}
}
