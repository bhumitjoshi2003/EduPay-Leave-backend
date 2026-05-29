package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.OptionalGroupResponseDTO;
import com.indraacademy.ias_management.dto.StreamResponseDTO;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Service
public class SubjectConfigService {

    private static final Logger log = LoggerFactory.getLogger(SubjectConfigService.class);

    @Autowired private ClassSubjectRepository classSubjectRepository;
    @Autowired private AcademicStreamRepository academicStreamRepository;
    @Autowired private StreamCoreSubjectRepository streamCoreSubjectRepository;
    @Autowired private OptionalSubjectGroupRepository optionalSubjectGroupRepository;
    @Autowired private OptionalSubjectRepository optionalSubjectRepository;
    @Autowired private SecurityUtil securityUtil;

    // ─── ClassSubject ─────────────────────────────────────────────────────────

    @Cacheable(value = "subject-config", key = "@securityUtil.getSchoolId() + ':class-' + #className")
    @Transactional(readOnly = true)
    public List<ClassSubject> getClassSubjects(String className) {
        log.info("Fetching subjects for class {}", className);
        return classSubjectRepository.findByClassNameAndSchoolId(className, securityUtil.getSchoolId());
    }

    @CacheEvict(value = "subject-config", allEntries = true)
    @Transactional
    public ClassSubject addClassSubject(String className, String subjectName,
                                        boolean optional, String optionalGroup) {
        if (className == null || className.isBlank() || subjectName == null || subjectName.isBlank()) {
            throw new IllegalArgumentException("className and subjectName are required.");
        }
        if (optional && (optionalGroup == null || optionalGroup.isBlank())) {
            throw new IllegalArgumentException("optionalGroup is required when optional=true.");
        }
        Long schoolId = securityUtil.getSchoolId();
        if (classSubjectRepository.existsByClassNameAndSubjectNameAndSchoolId(className, subjectName, schoolId)) {
            throw new IllegalArgumentException(
                    "Subject '" + subjectName + "' already exists for class " + className + ".");
        }
        ClassSubject cs = new ClassSubject();
        cs.setClassName(className);
        cs.setSubjectName(subjectName);
        cs.setOptional(optional);
        cs.setOptionalGroup(optional ? optionalGroup.trim() : null);
        cs.setSchoolId(schoolId);
        ClassSubject saved = classSubjectRepository.save(cs);
        log.info("Added subject '{}' to class {} (optional={}, group={})", subjectName, className, optional, optionalGroup);
        return saved;
    }

    @CacheEvict(value = "subject-config", allEntries = true)
    @Transactional
    public void deleteClassSubject(Long id) {
        Long schoolId = securityUtil.getSchoolId();
        if (!classSubjectRepository.existsByIdAndSchoolId(id, schoolId)) {
            throw new NoSuchElementException("ClassSubject not found: " + id);
        }
        classSubjectRepository.deleteById(id);
        log.info("Deleted ClassSubject id={}", id);
    }

    // ─── AcademicStream ───────────────────────────────────────────────────────

    @Cacheable(value = "subject-config", key = "@securityUtil.getSchoolId() + ':all-streams'")
    @Transactional(readOnly = true)
    public List<StreamResponseDTO> getAllStreams() {
        log.info("Fetching all streams with core subjects");
        Long schoolId = securityUtil.getSchoolId();
        return academicStreamRepository.findBySchoolId(schoolId).stream()
                .map(stream -> {
                    List<StreamResponseDTO.CoreSubjectDTO> cores = streamCoreSubjectRepository
                            .findByStreamIdAndSchoolId(stream.getId(), schoolId)
                            .stream()
                            .map(s -> new StreamResponseDTO.CoreSubjectDTO(s.getId(), s.getSubjectName()))
                            .collect(Collectors.toList());
                    return new StreamResponseDTO(stream.getId(), stream.getStreamName(), cores);
                })
                .collect(Collectors.toList());
    }

    @CacheEvict(value = "subject-config", allEntries = true)
    @Transactional
    public StreamResponseDTO addStream(String streamName) {
        if (streamName == null || streamName.isBlank()) {
            throw new IllegalArgumentException("streamName is required.");
        }
        Long schoolId = securityUtil.getSchoolId();
        if (academicStreamRepository.existsByStreamNameAndSchoolId(streamName, schoolId)) {
            throw new IllegalArgumentException("Stream '" + streamName + "' already exists.");
        }
        AcademicStream stream = new AcademicStream();
        stream.setStreamName(streamName);
        stream.setSchoolId(schoolId);
        AcademicStream saved = academicStreamRepository.save(stream);
        log.info("Added stream '{}'", streamName);
        return new StreamResponseDTO(saved.getId(), saved.getStreamName(), new java.util.ArrayList<>());
    }

    @CacheEvict(value = "subject-config", allEntries = true)
    @Transactional
    public void deleteStream(Long id) {
        Long schoolId = securityUtil.getSchoolId();
        if (!academicStreamRepository.existsByIdAndSchoolId(id, schoolId)) {
            throw new NoSuchElementException("Stream not found: " + id);
        }
        streamCoreSubjectRepository.deleteByStreamIdAndSchoolId(id, schoolId);
        academicStreamRepository.deleteById(id);
        log.info("Deleted stream id={} and its core subjects", id);
    }

    // ─── StreamCoreSubject ────────────────────────────────────────────────────

    @CacheEvict(value = "subject-config", allEntries = true)
    @Transactional
    public StreamCoreSubject addStreamCoreSubject(Long streamId, String subjectName) {
        Long schoolId = securityUtil.getSchoolId();
        if (!academicStreamRepository.existsByIdAndSchoolId(streamId, schoolId)) {
            throw new NoSuchElementException("Stream not found: " + streamId);
        }
        if (subjectName == null || subjectName.isBlank()) {
            throw new IllegalArgumentException("subjectName is required.");
        }
        if (streamCoreSubjectRepository.existsByStreamIdAndSubjectNameAndSchoolId(streamId, subjectName, schoolId)) {
            throw new IllegalArgumentException(
                    "Subject '" + subjectName + "' already exists in this stream.");
        }
        StreamCoreSubject scs = new StreamCoreSubject();
        scs.setStreamId(streamId);
        scs.setSubjectName(subjectName);
        scs.setSchoolId(schoolId);
        StreamCoreSubject saved = streamCoreSubjectRepository.save(scs);
        log.info("Added core subject '{}' to stream id={}", subjectName, streamId);
        return saved;
    }

    @CacheEvict(value = "subject-config", allEntries = true)
    @Transactional
    public void deleteStreamCoreSubject(Long id) {
        Long schoolId = securityUtil.getSchoolId();
        if (!streamCoreSubjectRepository.existsByIdAndSchoolId(id, schoolId)) {
            throw new NoSuchElementException("StreamCoreSubject not found: " + id);
        }
        streamCoreSubjectRepository.deleteById(id);
        log.info("Deleted StreamCoreSubject id={}", id);
    }

    // ─── OptionalSubjectGroup ─────────────────────────────────────────────────

    @Cacheable(value = "subject-config", key = "@securityUtil.getSchoolId() + ':all-optional-groups'")
    @Transactional(readOnly = true)
    public List<OptionalGroupResponseDTO> getAllOptionalGroups() {
        log.info("Fetching all optional subject groups");
        Long schoolId = securityUtil.getSchoolId();
        return optionalSubjectGroupRepository.findBySchoolId(schoolId).stream()
                .map(group -> {
                    List<OptionalGroupResponseDTO.OptionalSubjectDTO> subjects = optionalSubjectRepository
                            .findByGroupIdAndSchoolId(group.getId(), schoolId)
                            .stream()
                            .map(s -> new OptionalGroupResponseDTO.OptionalSubjectDTO(s.getId(), s.getSubjectName()))
                            .collect(Collectors.toList());
                    return new OptionalGroupResponseDTO(group.getId(), group.getGroupName(), subjects);
                })
                .collect(Collectors.toList());
    }

    @CacheEvict(value = "subject-config", allEntries = true)
    @Transactional
    public OptionalGroupResponseDTO addOptionalGroup(String groupName) {
        if (groupName == null || groupName.isBlank()) {
            throw new IllegalArgumentException("groupName is required.");
        }
        Long schoolId = securityUtil.getSchoolId();
        if (optionalSubjectGroupRepository.existsByGroupNameAndSchoolId(groupName, schoolId)) {
            throw new IllegalArgumentException("Optional group '" + groupName + "' already exists.");
        }
        OptionalSubjectGroup group = new OptionalSubjectGroup();
        group.setGroupName(groupName);
        group.setSchoolId(schoolId);
        OptionalSubjectGroup saved = optionalSubjectGroupRepository.save(group);
        log.info("Added optional subject group '{}'", groupName);
        return new OptionalGroupResponseDTO(saved.getId(), saved.getGroupName(), new java.util.ArrayList<>());
    }

    @CacheEvict(value = "subject-config", allEntries = true)
    @Transactional
    public void deleteOptionalGroup(Long id) {
        Long schoolId = securityUtil.getSchoolId();
        if (!optionalSubjectGroupRepository.existsByIdAndSchoolId(id, schoolId)) {
            throw new NoSuchElementException("OptionalSubjectGroup not found: " + id);
        }
        optionalSubjectRepository.deleteByGroupIdAndSchoolId(id, schoolId);
        optionalSubjectGroupRepository.deleteById(id);
        log.info("Deleted OptionalSubjectGroup id={} and its subjects", id);
    }

    // ─── OptionalSubject ──────────────────────────────────────────────────────

    @CacheEvict(value = "subject-config", allEntries = true)
    @Transactional
    public OptionalSubject addOptionalSubject(Long groupId, String subjectName) {
        Long schoolId = securityUtil.getSchoolId();
        if (!optionalSubjectGroupRepository.existsByIdAndSchoolId(groupId, schoolId)) {
            throw new NoSuchElementException("OptionalSubjectGroup not found: " + groupId);
        }
        if (subjectName == null || subjectName.isBlank()) {
            throw new IllegalArgumentException("subjectName is required.");
        }
        OptionalSubject os = new OptionalSubject();
        os.setGroupId(groupId);
        os.setSubjectName(subjectName);
        os.setSchoolId(schoolId);
        OptionalSubject saved = optionalSubjectRepository.save(os);
        log.info("Added optional subject '{}' to group id={}", subjectName, groupId);
        return saved;
    }

    @CacheEvict(value = "subject-config", allEntries = true)
    @Transactional
    public void deleteOptionalSubject(Long id) {
        Long schoolId = securityUtil.getSchoolId();
        if (!optionalSubjectRepository.existsByIdAndSchoolId(id, schoolId)) {
            throw new NoSuchElementException("OptionalSubject not found: " + id);
        }
        optionalSubjectRepository.deleteById(id);
        log.info("Deleted OptionalSubject id={}", id);
    }
}
