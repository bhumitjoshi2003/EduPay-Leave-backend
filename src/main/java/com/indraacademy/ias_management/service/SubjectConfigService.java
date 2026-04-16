package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.OptionalGroupResponseDTO;
import com.indraacademy.ias_management.dto.StreamResponseDTO;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    // ─── ClassSubject ─────────────────────────────────────────────────────────

    public List<ClassSubject> getClassSubjects(String className) {
        log.info("Fetching subjects for class {}", className);
        return classSubjectRepository.findByClassName(className);
    }

    public ClassSubject addClassSubject(String className, String subjectName) {
        if (className == null || className.isBlank() || subjectName == null || subjectName.isBlank()) {
            throw new IllegalArgumentException("className and subjectName are required.");
        }
        if (classSubjectRepository.existsByClassNameAndSubjectName(className, subjectName)) {
            throw new IllegalArgumentException(
                    "Subject '" + subjectName + "' already exists for class " + className + ".");
        }
        ClassSubject cs = new ClassSubject();
        cs.setClassName(className);
        cs.setSubjectName(subjectName);
        ClassSubject saved = classSubjectRepository.save(cs);
        log.info("Added subject '{}' to class {}", subjectName, className);
        return saved;
    }

    @Transactional
    public void deleteClassSubject(Long id) {
        if (!classSubjectRepository.existsById(id)) {
            throw new NoSuchElementException("ClassSubject not found: " + id);
        }
        classSubjectRepository.deleteById(id);
        log.info("Deleted ClassSubject id={}", id);
    }

    // ─── AcademicStream ───────────────────────────────────────────────────────

    public List<StreamResponseDTO> getAllStreams() {
        log.info("Fetching all streams with core subjects");
        return academicStreamRepository.findAll().stream()
                .map(stream -> {
                    List<StreamResponseDTO.CoreSubjectDTO> cores = streamCoreSubjectRepository
                            .findByStreamId(stream.getId())
                            .stream()
                            .map(s -> new StreamResponseDTO.CoreSubjectDTO(s.getId(), s.getSubjectName()))
                            .collect(Collectors.toList());
                    return new StreamResponseDTO(stream.getId(), stream.getStreamName(), cores);
                })
                .collect(Collectors.toList());
    }

    public AcademicStream addStream(String streamName) {
        if (streamName == null || streamName.isBlank()) {
            throw new IllegalArgumentException("streamName is required.");
        }
        if (academicStreamRepository.existsByStreamName(streamName)) {
            throw new IllegalArgumentException("Stream '" + streamName + "' already exists.");
        }
        AcademicStream stream = new AcademicStream();
        stream.setStreamName(streamName);
        AcademicStream saved = academicStreamRepository.save(stream);
        log.info("Added stream '{}'", streamName);
        return saved;
    }

    @Transactional
    public void deleteStream(Long id) {
        if (!academicStreamRepository.existsById(id)) {
            throw new NoSuchElementException("Stream not found: " + id);
        }
        streamCoreSubjectRepository.deleteByStreamId(id);
        academicStreamRepository.deleteById(id);
        log.info("Deleted stream id={} and its core subjects", id);
    }

    // ─── StreamCoreSubject ────────────────────────────────────────────────────

    public StreamCoreSubject addStreamCoreSubject(Long streamId, String subjectName) {
        if (!academicStreamRepository.existsById(streamId)) {
            throw new NoSuchElementException("Stream not found: " + streamId);
        }
        if (subjectName == null || subjectName.isBlank()) {
            throw new IllegalArgumentException("subjectName is required.");
        }
        if (streamCoreSubjectRepository.existsByStreamIdAndSubjectName(streamId, subjectName)) {
            throw new IllegalArgumentException(
                    "Subject '" + subjectName + "' already exists in this stream.");
        }
        StreamCoreSubject scs = new StreamCoreSubject();
        scs.setStreamId(streamId);
        scs.setSubjectName(subjectName);
        StreamCoreSubject saved = streamCoreSubjectRepository.save(scs);
        log.info("Added core subject '{}' to stream id={}", subjectName, streamId);
        return saved;
    }

    @Transactional
    public void deleteStreamCoreSubject(Long id) {
        if (!streamCoreSubjectRepository.existsById(id)) {
            throw new NoSuchElementException("StreamCoreSubject not found: " + id);
        }
        streamCoreSubjectRepository.deleteById(id);
        log.info("Deleted StreamCoreSubject id={}", id);
    }

    // ─── OptionalSubjectGroup ─────────────────────────────────────────────────

    public List<OptionalGroupResponseDTO> getAllOptionalGroups() {
        log.info("Fetching all optional subject groups");
        return optionalSubjectGroupRepository.findAll().stream()
                .map(group -> {
                    List<OptionalGroupResponseDTO.OptionalSubjectDTO> subjects = optionalSubjectRepository
                            .findByGroupId(group.getId())
                            .stream()
                            .map(s -> new OptionalGroupResponseDTO.OptionalSubjectDTO(s.getId(), s.getSubjectName()))
                            .collect(Collectors.toList());
                    return new OptionalGroupResponseDTO(group.getId(), group.getGroupName(), subjects);
                })
                .collect(Collectors.toList());
    }

    public OptionalSubjectGroup addOptionalGroup(String groupName) {
        if (groupName == null || groupName.isBlank()) {
            throw new IllegalArgumentException("groupName is required.");
        }
        OptionalSubjectGroup group = new OptionalSubjectGroup();
        group.setGroupName(groupName);
        OptionalSubjectGroup saved = optionalSubjectGroupRepository.save(group);
        log.info("Added optional subject group '{}'", groupName);
        return saved;
    }

    @Transactional
    public void deleteOptionalGroup(Long id) {
        if (!optionalSubjectGroupRepository.existsById(id)) {
            throw new NoSuchElementException("OptionalSubjectGroup not found: " + id);
        }
        optionalSubjectRepository.deleteByGroupId(id);
        optionalSubjectGroupRepository.deleteById(id);
        log.info("Deleted OptionalSubjectGroup id={} and its subjects", id);
    }

    // ─── OptionalSubject ──────────────────────────────────────────────────────

    public OptionalSubject addOptionalSubject(Long groupId, String subjectName) {
        if (!optionalSubjectGroupRepository.existsById(groupId)) {
            throw new NoSuchElementException("OptionalSubjectGroup not found: " + groupId);
        }
        if (subjectName == null || subjectName.isBlank()) {
            throw new IllegalArgumentException("subjectName is required.");
        }
        OptionalSubject os = new OptionalSubject();
        os.setGroupId(groupId);
        os.setSubjectName(subjectName);
        OptionalSubject saved = optionalSubjectRepository.save(os);
        log.info("Added optional subject '{}' to group id={}", subjectName, groupId);
        return saved;
    }

    @Transactional
    public void deleteOptionalSubject(Long id) {
        if (!optionalSubjectRepository.existsById(id)) {
            throw new NoSuchElementException("OptionalSubject not found: " + id);
        }
        optionalSubjectRepository.deleteById(id);
        log.info("Deleted OptionalSubject id={}", id);
    }
}
