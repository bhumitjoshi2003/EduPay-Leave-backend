package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.SupportTicketDtos.*;
import com.indraacademy.ias_management.entity.SupportTicketCategory;
import com.indraacademy.ias_management.entity.SupportTicketStatus;
import com.indraacademy.ias_management.service.SupportTicketService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/support-tickets")
public class SupportTicketController {
    private final SupportTicketService service;

    public SupportTicketController(SupportTicketService service) {
        this.service = service;
    }

    /** Any authenticated user — Teacher/Student/Parent/Admin/Sub-admin alike. Never routed to
     *  the reporter's own school admin; this is the Edunexify platform support queue. */
    @PreAuthorize("isAuthenticated()")
    @PostMapping
    public TicketDetail createTicket(@Valid @RequestBody CreateRequest request, HttpServletRequest http) {
        return service.createTicket(request, http);
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/mine")
    public Page<TicketSummary> myTickets(Pageable pageable) {
        return service.myTickets(pageable);
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/mine/{id}")
    public TicketDetail myTicketDetail(@PathVariable Long id) {
        return service.myTicketDetail(id);
    }

    /** SUPER_ADMIN only — the global cross-school queue. School ADMIN/SUB_ADMIN never reach
     *  this: they only ever see their own submitted tickets via /mine, same as any other role. */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @GetMapping
    public Page<TicketSummary> allTickets(
            @RequestParam(required = false) SupportTicketStatus status,
            @RequestParam(required = false) SupportTicketCategory category,
            @RequestParam(required = false) Long schoolId,
            Pageable pageable) {
        return service.allTickets(status, category, schoolId, pageable);
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @GetMapping("/{id}")
    public TicketDetail adminTicketDetail(@PathVariable Long id) {
        return service.adminTicketDetail(id);
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PutMapping("/{id}/status")
    public TicketDetail updateStatus(@PathVariable Long id, @Valid @RequestBody StatusUpdateRequest request,
            HttpServletRequest http) {
        return service.updateStatus(id, request, http);
    }
}
