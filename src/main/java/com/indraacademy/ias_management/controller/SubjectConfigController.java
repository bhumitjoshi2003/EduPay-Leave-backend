package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.OptionalGroupResponseDTO;
import com.indraacademy.ias_management.dto.StreamResponseDTO;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.service.SubjectConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Handles all subject/stream configuration endpoints.
 * All endpoints require ADMIN role.
 *
 * /api/subjects/class/**      — ClassSubject (classes 1–10)
 * /api/streams/**             — AcademicStream + StreamCoreSubject
 * /api/optional-groups/**     — OptionalSubjectGroup + OptionalSubject
 * /api/optional-subjects/**   — OptionalSubject (delete only)
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:4200")
@PreAuthorize("hasRole('" + Role.ADMIN + "')")
public class SubjectConfigController {

    private static final Logger log = LoggerFactory.getLogger(SubjectConfigController.class);

    @Autowired private SubjectConfigService subjectConfigService;

    // ─── ClassSubject ─────────────────────────────────────────────────────────

    @GetMapping("/subjects/class/{className}")
    public ResponseEntity<List<ClassSubject>> getClassSubjects(@PathVariable String className) {
        log.info("GET /api/subjects/class/{}", className);
        return ResponseEntity.ok(subjectConfigService.getClassSubjects(className));
    }

    @PostMapping("/subjects/class")
    public ResponseEntity<?> addClassSubject(@RequestBody Map<String, String> body) {
        String className   = body.get("className");
        String subjectName = body.get("subjectName");
        log.info("POST /api/subjects/class: className={}, subjectName={}", className, subjectName);
        ClassSubject saved = subjectConfigService.addClassSubject(className, subjectName);
        return new ResponseEntity<>(saved, HttpStatus.CREATED);
    }

    @DeleteMapping("/subjects/class/{id}")
    public ResponseEntity<?> deleteClassSubject(@PathVariable Long id) {
        log.info("DELETE /api/subjects/class/{}", id);
        subjectConfigService.deleteClassSubject(id);
        return ResponseEntity.noContent().build();
    }

    // ─── AcademicStream ───────────────────────────────────────────────────────

    @GetMapping("/streams")
    public ResponseEntity<List<StreamResponseDTO>> getAllStreams() {
        log.info("GET /api/streams");
        return ResponseEntity.ok(subjectConfigService.getAllStreams());
    }

    @PostMapping("/streams")
    public ResponseEntity<?> addStream(@RequestBody Map<String, String> body) {
        String streamName = body.get("streamName");
        log.info("POST /api/streams: streamName={}", streamName);
        AcademicStream saved = subjectConfigService.addStream(streamName);
        return new ResponseEntity<>(saved, HttpStatus.CREATED);
    }

    @DeleteMapping("/streams/{id}")
    public ResponseEntity<?> deleteStream(@PathVariable Long id) {
        log.info("DELETE /api/streams/{}", id);
        subjectConfigService.deleteStream(id);
        return ResponseEntity.noContent().build();
    }

    // ─── StreamCoreSubject ────────────────────────────────────────────────────

    @PostMapping("/streams/{streamId}/subjects")
    public ResponseEntity<?> addStreamCoreSubject(@PathVariable Long streamId,
                                                  @RequestBody Map<String, String> body) {
        String subjectName = body.get("subjectName");
        log.info("POST /api/streams/{}/subjects: subjectName={}", streamId, subjectName);
        StreamCoreSubject saved = subjectConfigService.addStreamCoreSubject(streamId, subjectName);
        return new ResponseEntity<>(saved, HttpStatus.CREATED);
    }

    @DeleteMapping("/streams/subjects/{id}")
    public ResponseEntity<?> deleteStreamCoreSubject(@PathVariable Long id) {
        log.info("DELETE /api/streams/subjects/{}", id);
        subjectConfigService.deleteStreamCoreSubject(id);
        return ResponseEntity.noContent().build();
    }

    // ─── OptionalSubjectGroup ─────────────────────────────────────────────────

    @GetMapping("/optional-groups")
    public ResponseEntity<List<OptionalGroupResponseDTO>> getAllOptionalGroups() {
        log.info("GET /api/optional-groups");
        return ResponseEntity.ok(subjectConfigService.getAllOptionalGroups());
    }

    @PostMapping("/optional-groups")
    public ResponseEntity<?> addOptionalGroup(@RequestBody Map<String, String> body) {
        String groupName = body.get("groupName");
        log.info("POST /api/optional-groups: groupName={}", groupName);
        OptionalSubjectGroup saved = subjectConfigService.addOptionalGroup(groupName);
        return new ResponseEntity<>(saved, HttpStatus.CREATED);
    }

    @DeleteMapping("/optional-groups/{id}")
    public ResponseEntity<?> deleteOptionalGroup(@PathVariable Long id) {
        log.info("DELETE /api/optional-groups/{}", id);
        subjectConfigService.deleteOptionalGroup(id);
        return ResponseEntity.noContent().build();
    }

    // ─── OptionalSubject ──────────────────────────────────────────────────────

    @PostMapping("/optional-groups/{groupId}/subjects")
    public ResponseEntity<?> addOptionalSubject(@PathVariable Long groupId,
                                                @RequestBody Map<String, String> body) {
        String subjectName = body.get("subjectName");
        log.info("POST /api/optional-groups/{}/subjects: subjectName={}", groupId, subjectName);
        OptionalSubject saved = subjectConfigService.addOptionalSubject(groupId, subjectName);
        return new ResponseEntity<>(saved, HttpStatus.CREATED);
    }

    @DeleteMapping("/optional-subjects/{id}")
    public ResponseEntity<?> deleteOptionalSubject(@PathVariable Long id) {
        log.info("DELETE /api/optional-subjects/{}", id);
        subjectConfigService.deleteOptionalSubject(id);
        return ResponseEntity.noContent().build();
    }
}
