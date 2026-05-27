package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One-time backfill: resolves className → classId for all entities
 * that have a className string but no classId FK populated yet.
 */
@Service
public class ClassIdBackfillService {

    private static final Logger log = LoggerFactory.getLogger(ClassIdBackfillService.class);

    @Autowired private SchoolClassRepository schoolClassRepo;
    @Autowired private StudentRepository studentRepo;
    @Autowired private AttendanceRepository attendanceRepo;
    @Autowired private LeaveRepository leaveRepo;
    @Autowired private PaymentRepository paymentRepo;
    @Autowired private FeeStructureRepository feeStructureRepo;
    @Autowired private StudentFeesRepository studentFeesRepo;
    @Autowired private ExamConfigRepository examConfigRepo;
    @Autowired private ClassSubjectRepository classSubjectRepo;
    @Autowired private TimetableRepository timetableRepo;
    @Autowired private FeeStructureRuleRepository feeRuleRepo;

    /**
     * Backfill classId for all entities in the given school.
     * Returns a summary map of entity → count of records updated.
     */
    @Transactional
    public Map<String, Integer> backfillForSchool(Long schoolId) {
        // Build name→id map for this school
        List<SchoolClass> classes = schoolClassRepo.findBySchoolIdOrderByDisplayOrderAsc(schoolId);
        Map<String, Long> nameToId = new HashMap<>();
        for (SchoolClass sc : classes) {
            nameToId.put(sc.getName(), sc.getId());
        }
        log.info("Backfill: school {} has {} classes mapped", schoolId, nameToId.size());

        Map<String, Integer> summary = new HashMap<>();

        // Students
        List<Student> students = studentRepo.findBySchoolId(schoolId);
        int count = 0;
        for (Student s : students) {
            if (s.getClassId() == null && s.getClassName() != null) {
                Long id = nameToId.get(s.getClassName());
                if (id != null) { s.setClassId(id); count++; }
            }
        }
        if (count > 0) { studentRepo.saveAll(students); }
        summary.put("Student", count);

        // Attendance
        count = backfillAttendance(schoolId, nameToId);
        summary.put("Attendance", count);

        // Leave
        count = backfillLeave(schoolId, nameToId);
        summary.put("Leave", count);

        // Payment
        count = backfillPayment(schoolId, nameToId);
        summary.put("Payment", count);

        // FeeStructure
        count = backfillFeeStructure(schoolId, nameToId);
        summary.put("FeeStructure", count);

        // StudentFees
        count = backfillStudentFees(schoolId, nameToId);
        summary.put("StudentFees", count);

        // ExamConfig
        count = backfillExamConfig(schoolId, nameToId);
        summary.put("ExamConfig", count);

        // ClassSubject
        count = backfillClassSubject(schoolId, nameToId);
        summary.put("ClassSubject", count);

        // TimetableEntry
        count = backfillTimetable(schoolId, nameToId);
        summary.put("TimetableEntry", count);

        // FeeStructureRule
        count = backfillFeeStructureRule(schoolId, nameToId);
        summary.put("FeeStructureRule", count);

        log.info("Backfill complete for school {}: {}", schoolId, summary);
        return summary;
    }

    private int backfillAttendance(Long schoolId, Map<String, Long> nameToId) {
        // Process in date-class batches to avoid loading too many records
        List<Attendance> records = attendanceRepo.findBySchoolId(schoolId);
        int count = 0;
        for (Attendance a : records) {
            if (a.getClassId() == null && a.getClassName() != null) {
                Long id = nameToId.get(a.getClassName());
                if (id != null) { a.setClassId(id); count++; }
            }
        }
        if (count > 0) attendanceRepo.saveAll(records);
        return count;
    }

    private int backfillLeave(Long schoolId, Map<String, Long> nameToId) {
        List<Leave> records = leaveRepo.findBySchoolId(schoolId);
        int count = 0;
        for (Leave l : records) {
            if (l.getClassId() == null && l.getClassName() != null) {
                Long id = nameToId.get(l.getClassName());
                if (id != null) { l.setClassId(id); count++; }
            }
        }
        if (count > 0) leaveRepo.saveAll(records);
        return count;
    }

    private int backfillPayment(Long schoolId, Map<String, Long> nameToId) {
        List<Payment> records = paymentRepo.findBySchoolId(schoolId);
        int count = 0;
        for (Payment p : records) {
            if (p.getClassId() == null && p.getClassName() != null) {
                Long id = nameToId.get(p.getClassName());
                if (id != null) { p.setClassId(id); count++; }
            }
        }
        if (count > 0) paymentRepo.saveAll(records);
        return count;
    }

    private int backfillFeeStructure(Long schoolId, Map<String, Long> nameToId) {
        List<FeeStructure> records = feeStructureRepo.findBySchoolId(schoolId);
        int count = 0;
        for (FeeStructure f : records) {
            if (f.getClassId() == null && f.getClassName() != null) {
                Long id = nameToId.get(f.getClassName());
                if (id != null) { f.setClassId(id); count++; }
            }
        }
        if (count > 0) feeStructureRepo.saveAll(records);
        return count;
    }

    private int backfillStudentFees(Long schoolId, Map<String, Long> nameToId) {
        List<StudentFees> records = studentFeesRepo.findBySchoolId(schoolId);
        int count = 0;
        for (StudentFees sf : records) {
            if (sf.getClassId() == null && sf.getClassName() != null) {
                Long id = nameToId.get(sf.getClassName());
                if (id != null) { sf.setClassId(id); count++; }
            }
        }
        if (count > 0) studentFeesRepo.saveAll(records);
        return count;
    }

    private int backfillExamConfig(Long schoolId, Map<String, Long> nameToId) {
        List<ExamConfig> records = examConfigRepo.findBySchoolId(schoolId);
        int count = 0;
        for (ExamConfig e : records) {
            if (e.getClassId() == null && e.getClassName() != null) {
                Long id = nameToId.get(e.getClassName());
                if (id != null) { e.setClassId(id); count++; }
            }
        }
        if (count > 0) examConfigRepo.saveAll(records);
        return count;
    }

    private int backfillClassSubject(Long schoolId, Map<String, Long> nameToId) {
        List<ClassSubject> records = classSubjectRepo.findBySchoolId(schoolId);
        int count = 0;
        for (ClassSubject cs : records) {
            if (cs.getClassId() == null && cs.getClassName() != null) {
                Long id = nameToId.get(cs.getClassName());
                if (id != null) { cs.setClassId(id); count++; }
            }
        }
        if (count > 0) classSubjectRepo.saveAll(records);
        return count;
    }

    private int backfillTimetable(Long schoolId, Map<String, Long> nameToId) {
        List<TimetableEntry> records = timetableRepo.findBySchoolId(schoolId);
        int count = 0;
        for (TimetableEntry t : records) {
            if (t.getClassId() == null && t.getClassName() != null) {
                Long id = nameToId.get(t.getClassName());
                if (id != null) { t.setClassId(id); count++; }
            }
        }
        if (count > 0) timetableRepo.saveAll(records);
        return count;
    }

    private int backfillFeeStructureRule(Long schoolId, Map<String, Long> nameToId) {
        List<FeeStructureRule> records = feeRuleRepo.findBySchoolId(schoolId);
        int count = 0;
        for (FeeStructureRule r : records) {
            if (r.getClassId() == null && r.getClassName() != null) {
                Long id = nameToId.get(r.getClassName());
                if (id != null) { r.setClassId(id); count++; }
            }
        }
        if (count > 0) feeRuleRepo.saveAll(records);
        return count;
    }
}
