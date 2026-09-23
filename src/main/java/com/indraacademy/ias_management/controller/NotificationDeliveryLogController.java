package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.NotificationDeliveryLogDtos.Detail;
import com.indraacademy.ias_management.dto.NotificationDeliveryLogDtos.RowPage;
import com.indraacademy.ias_management.dto.NotificationDeliveryLogDtos.SummaryPage;
import com.indraacademy.ias_management.service.NotificationDeliveryLogService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Platform-operations view of notification delivery across every school. SUPER_ADMIN only. */
@RestController
@RequestMapping("/api/notification-deliveries")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class NotificationDeliveryLogController {

    private final NotificationDeliveryLogService service;

    public NotificationDeliveryLogController(NotificationDeliveryLogService service) {
        this.service = service;
    }

    @GetMapping
    public RowPage search(@RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "25") int size,
                          @RequestParam(required = false) String status,
                          @RequestParam(required = false) String channel,
                          @RequestParam(required = false) String eventCode,
                          @RequestParam(required = false) Long schoolId,
                          @RequestParam(required = false) String recipient,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          @RequestParam(required = false) Long notificationId) {
        return service.search(page, size, status, channel, eventCode, schoolId, recipient, from, to, notificationId);
    }

    @GetMapping("/summary")
    public SummaryPage summary(@RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "25") int size,
                               @RequestParam(required = false) String eventCode,
                               @RequestParam(required = false) Long schoolId,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                               @RequestParam(required = false) String search) {
        return service.summary(page, size, eventCode, schoolId, from, to, search);
    }

    @GetMapping("/event-codes")
    public List<String> eventCodes() {
        return service.eventCodes();
    }

    @GetMapping("/{channel}/{id}")
    public Detail detail(@PathVariable String channel, @PathVariable long id) {
        return service.detail(channel, id);
    }
}
