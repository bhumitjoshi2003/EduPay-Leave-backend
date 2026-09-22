package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.StaffAdoptionReminderPreviewResponse;
import com.indraacademy.ias_management.dto.StaffAdoptionReminderRequest;
import com.indraacademy.ias_management.dto.StaffAdoptionReminderSendResponse;
import com.indraacademy.ias_management.dto.StaffAdoptionReminderType;
import com.indraacademy.ias_management.dto.StaffAdoptionResponse;
import com.indraacademy.ias_management.service.StaffAdoptionReminderService;
import com.indraacademy.ias_management.service.StaffAdoptionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/staff-adoption")
@PreAuthorize("hasRole('" + Role.ADMIN + "')")
public class StaffAdoptionController {
    private final StaffAdoptionService service;
    private final StaffAdoptionReminderService reminderService;

    public StaffAdoptionController(StaffAdoptionService service, StaffAdoptionReminderService reminderService) {
        this.service = service;
        this.reminderService = reminderService;
    }

    @GetMapping
    public StaffAdoptionResponse getStaffAdoption() {
        return service.getStaffAdoption();
    }

    @GetMapping("/reminders/preview")
    public StaffAdoptionReminderPreviewResponse previewReminder(@RequestParam StaffAdoptionReminderType type) {
        return reminderService.preview(type);
    }

    /** Body carries only {@code type} — recipients are always re-resolved server-side from the
     *  authenticated admin's own school, never taken from the client. */
    @PostMapping("/reminders")
    public StaffAdoptionReminderSendResponse sendReminder(@RequestBody StaffAdoptionReminderRequest request) {
        return reminderService.send(request.type());
    }
}
