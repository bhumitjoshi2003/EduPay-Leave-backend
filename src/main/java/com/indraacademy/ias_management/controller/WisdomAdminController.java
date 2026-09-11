package com.indraacademy.ias_management.controller;
import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.WisdomDtos.*;
import com.indraacademy.ias_management.service.WisdomService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
@RestController
@RequestMapping("/api/wisdom/admin")
@PreAuthorize("hasAnyRole('"+Role.ADMIN+"','"+Role.SUB_ADMIN+"')")
public class WisdomAdminController {
 private final WisdomService service;
 public WisdomAdminController(WisdomService service) {this.service=service;}
 @GetMapping("/status") public Management status(){return service.management();}
 @GetMapping("/thoughts") public Object thoughts(@RequestParam(defaultValue="0") int page){return service.thoughts(page);}
 @PostMapping("/thoughts") public Object createThought(@Valid @RequestBody ThoughtInput input,HttpServletRequest r){return service.saveThought(null,input,r);}
 @PutMapping("/thoughts/{id}") public Object editThought(@PathVariable Long id,@Valid @RequestBody ThoughtInput input,HttpServletRequest r){return service.saveThought(id,input,r);}
 @GetMapping("/overrides") public Object overrides(@RequestParam(defaultValue="0") int page){return service.overrides(page);}
 @PutMapping("/overrides") public Object scheduleThought(@Valid @RequestBody OverrideInput input,HttpServletRequest r){return service.scheduleThought(input,r);}
 @DeleteMapping("/overrides/{id}") public void removeOverride(@PathVariable Long id,HttpServletRequest r){service.removeOverride(id,r);}
}
