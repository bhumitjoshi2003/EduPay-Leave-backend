package com.indraacademy.ias_management.controller;
import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.WisdomDtos.*;
import com.indraacademy.ias_management.service.WisdomService;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
@RestController
@RequestMapping("/api/wisdom")
@PreAuthorize("hasAnyRole('"+Role.ADMIN+"','"+Role.SUB_ADMIN+"','"+Role.TEACHER+"','"+Role.STUDENT+"','"+Role.PARENT+"')")
public class WisdomController {
 private final WisdomService service;
 public WisdomController(WisdomService service) { this.service=service; }
 @GetMapping("/dashboard") public Dashboard dashboard() {return service.dashboard();}
}
