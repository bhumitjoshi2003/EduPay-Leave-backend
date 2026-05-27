package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.NotificationChannelDTO;
import com.indraacademy.ias_management.service.NotificationChannelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notification-channels")
public class NotificationChannelController {

    @Autowired private NotificationChannelService channelService;

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @GetMapping
    public ResponseEntity<List<NotificationChannelDTO>> getChannels() {
        return ResponseEntity.ok(channelService.getChannelsForSchool());
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @PutMapping
    public ResponseEntity<NotificationChannelDTO> upsertChannel(@RequestBody NotificationChannelDTO dto) {
        return ResponseEntity.ok(channelService.upsertChannel(dto));
    }
}
