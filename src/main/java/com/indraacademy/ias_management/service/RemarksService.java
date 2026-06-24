package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ClassRemarksDTO;
import com.indraacademy.ias_management.dto.CoScholasticRequest;
import com.indraacademy.ias_management.dto.RemarksRequest;
import com.indraacademy.ias_management.dto.ReportCardDataDTO;
import com.indraacademy.ias_management.entity.CoScholasticEntry;
import com.indraacademy.ias_management.entity.ReportCardRemark;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class RemarksService {

    @Autowired private ReportCardRemarkRepository remarkRepo;
    @Autowired private CoScholasticEntryRepository coScholasticRepo;
    @Autowired private StudentRepository studentRepo;
    @Autowired private ReportCardTemplateRepository templateRepo;
    @Autowired private SecurityUtil securityUtil;

    // ── Bulk save remarks ─────────────────────────────────────────────────

    @Transactional
    public void saveRemarks(RemarksRequest req) {
        Long schoolId = securityUtil.getSchoolId();
        String enteredBy = currentUsername();

        // Validate template belongs to school
        templateRepo.findByIdAndSchoolId(req.getTemplateId(), schoolId)
                .orElseThrow(() -> new NoSuchElementException("Template not found: " + req.getTemplateId()));

        for (RemarksRequest.StudentRemarkItem item : req.getStudentRemarks()) {
            if (item.getTeacherRemark() != null) {
                upsertRemark(schoolId, item.getStudentId(), req.getTemplateId(),
                        req.getSession(), "TEACHER", item.getTeacherRemark(), enteredBy);
            }
            if (item.getPrincipalRemark() != null) {
                upsertRemark(schoolId, item.getStudentId(), req.getTemplateId(),
                        req.getSession(), "PRINCIPAL", item.getPrincipalRemark(), enteredBy);
            }
        }
    }

    // ── Bulk save co-scholastic ───────────────────────────────────────────

    @Transactional
    public void saveCoScholastic(CoScholasticRequest req) {
        Long schoolId = securityUtil.getSchoolId();
        String enteredBy = currentUsername();

        templateRepo.findByIdAndSchoolId(req.getTemplateId(), schoolId)
                .orElseThrow(() -> new NoSuchElementException("Template not found: " + req.getTemplateId()));

        for (CoScholasticRequest.StudentCoScholasticItem item : req.getStudentEntries()) {
            if (item.getEntries() == null) continue;
            for (CoScholasticRequest.ActivityGrade ag : item.getEntries()) {
                if (ag.getActivity() == null || ag.getActivity().isBlank()) continue;
                upsertCoScholastic(schoolId, item.getStudentId(), req.getTemplateId(),
                        req.getSession(), ag.getActivity(), ag.getGrade(), enteredBy);
            }
        }
    }

    // ── Load class-level data ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ClassRemarksDTO getClassRemarks(Long templateId, String session, String className) {
        Long schoolId = securityUtil.getSchoolId();

        templateRepo.findByIdAndSchoolId(templateId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Template not found: " + templateId));

        // Load active students for the class (ACTIVE + UPCOMING)
        List<Student> students = studentRepo.findByClassNameAndSchoolId(className, schoolId)
                .stream()
                .filter(s -> s.getStatus() != null && !s.getStatus().isExitStatus())
                .collect(Collectors.toList());

        // Batch load all remarks for this template+session+school
        List<ReportCardRemark> allRemarks =
                remarkRepo.findByTemplateIdAndSessionAndSchoolId(templateId, session, schoolId);
        Map<String, Map<String, String>> remarksByStudent = new HashMap<>();
        for (ReportCardRemark r : allRemarks) {
            remarksByStudent
                    .computeIfAbsent(r.getStudentId(), k -> new HashMap<>())
                    .put(r.getRemarkType(), r.getRemarkText());
        }

        // Batch load all co-scholastic entries
        List<CoScholasticEntry> allEntries =
                coScholasticRepo.findByTemplateIdAndSessionAndSchoolId(templateId, session, schoolId);
        Map<String, List<ClassRemarksDTO.ActivityGrade>> coByStudent = new HashMap<>();
        for (CoScholasticEntry e : allEntries) {
            coByStudent
                    .computeIfAbsent(e.getStudentId(), k -> new ArrayList<>())
                    .add(new ClassRemarksDTO.ActivityGrade(e.getActivity(), e.getGrade()));
        }

        List<ClassRemarksDTO.StudentRemarksData> rows = students.stream()
                .map(s -> {
                    Map<String, String> remarks = remarksByStudent.getOrDefault(s.getStudentId(), Collections.emptyMap());
                    List<ClassRemarksDTO.ActivityGrade> coEntries = coByStudent.getOrDefault(s.getStudentId(), Collections.emptyList());
                    return new ClassRemarksDTO.StudentRemarksData(
                            s.getStudentId(),
                            s.getName(),
                            remarks.get("TEACHER"),
                            remarks.get("PRINCIPAL"),
                            coEntries
                    );
                })
                .collect(Collectors.toList());

        return new ClassRemarksDTO(rows);
    }

    // ── Per-student retrieval (for assembler) ─────────────────────────────

    @Transactional(readOnly = true)
    public String getStudentRemark(String studentId, Long templateId, String session,
                                    String remarkType, Long schoolId) {
        return remarkRepo.findByStudentIdAndTemplateIdAndSessionAndRemarkTypeAndSchoolId(
                        studentId, templateId, session, remarkType, schoolId)
                .map(ReportCardRemark::getRemarkText)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<ReportCardDataDTO.CoScholasticGrade> getStudentCoScholastic(
            String studentId, Long templateId, String session, Long schoolId) {
        return coScholasticRepo.findByStudentIdAndTemplateIdAndSessionAndSchoolId(
                        studentId, templateId, session, schoolId)
                .stream()
                .map(e -> new ReportCardDataDTO.CoScholasticGrade(e.getActivity(), e.getGrade()))
                .collect(Collectors.toList());
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void upsertRemark(Long schoolId, String studentId, Long templateId,
                               String session, String type, String text, String enteredBy) {
        ReportCardRemark remark = remarkRepo
                .findByStudentIdAndTemplateIdAndSessionAndRemarkTypeAndSchoolId(
                        studentId, templateId, session, type, schoolId)
                .orElseGet(ReportCardRemark::new);

        remark.setSchoolId(schoolId);
        remark.setStudentId(studentId);
        remark.setTemplateId(templateId);
        remark.setSession(session);
        remark.setRemarkType(type);
        remark.setRemarkText(text);
        remark.setEnteredBy(enteredBy);
        remark.setEnteredAt(LocalDateTime.now());
        remarkRepo.save(remark);
    }

    private void upsertCoScholastic(Long schoolId, String studentId, Long templateId,
                                     String session, String activity, String grade,
                                     String enteredBy) {
        CoScholasticEntry entry = coScholasticRepo
                .findByStudentIdAndTemplateIdAndSessionAndActivityAndSchoolId(
                        studentId, templateId, session, activity, schoolId)
                .orElseGet(CoScholasticEntry::new);

        entry.setSchoolId(schoolId);
        entry.setStudentId(studentId);
        entry.setTemplateId(templateId);
        entry.setSession(session);
        entry.setActivity(activity);
        entry.setGrade(grade);
        entry.setEnteredBy(enteredBy);
        entry.setEnteredAt(LocalDateTime.now());
        coScholasticRepo.save(entry);
    }

    private String currentUsername() {
        try {
            return SecurityContextHolder.getContext().getAuthentication().getName();
        } catch (Exception e) {
            return "system";
        }
    }
}
