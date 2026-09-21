package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.SchoolSetupHealthResponse;
import com.indraacademy.ias_management.dto.SchoolSetupHealthResponse.Importance;
import com.indraacademy.ias_management.dto.SchoolSetupHealthResponse.Item;
import com.indraacademy.ias_management.dto.SchoolSetupHealthResponse.ItemStatus;
import com.indraacademy.ias_management.dto.SchoolSetupHealthResponse.Status;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.entity.TeacherStatus;
import com.indraacademy.ias_management.repository.AcademicSessionRepository;
import com.indraacademy.ias_management.repository.FeeStructureRuleRepository;
import com.indraacademy.ias_management.repository.ParentStudentRelationshipRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.TimetableRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class SchoolSetupHealthService {
    private final SecurityUtil securityUtil;
    private final SchoolRepository schoolRepository;
    private final AcademicSessionRepository academicSessionRepository;
    private final TeacherRepository teacherRepository;
    private final StudentRepository studentRepository;
    private final TimetableRepository timetableRepository;
    private final ParentStudentRelationshipRepository parentRelationshipRepository;
    private final FeeStructureRuleRepository feeRuleRepository;

    public SchoolSetupHealthService(SecurityUtil securityUtil,
                                    SchoolRepository schoolRepository,
                                    AcademicSessionRepository academicSessionRepository,
                                    TeacherRepository teacherRepository,
                                    StudentRepository studentRepository,
                                    TimetableRepository timetableRepository,
                                    ParentStudentRelationshipRepository parentRelationshipRepository,
                                    FeeStructureRuleRepository feeRuleRepository) {
        this.securityUtil = securityUtil;
        this.schoolRepository = schoolRepository;
        this.academicSessionRepository = academicSessionRepository;
        this.teacherRepository = teacherRepository;
        this.studentRepository = studentRepository;
        this.timetableRepository = timetableRepository;
        this.parentRelationshipRepository = parentRelationshipRepository;
        this.feeRuleRepository = feeRuleRepository;
    }

    @Transactional(readOnly = true)
    public SchoolSetupHealthResponse getSetupHealth() {
        Long schoolId = securityUtil.getSchoolId();
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found: " + schoolId));
        Optional<AcademicSession> currentSession = academicSessionRepository.findBySchoolIdAndCurrentTrue(schoolId);
        long activeTeachers = teacherRepository.countBySchoolIdAndStatus(schoolId, TeacherStatus.ACTIVE);
        long activeStudents = studentRepository.countByStatusAndSchoolId(StudentStatus.ACTIVE, schoolId);

        List<Item> items = new ArrayList<>();
        items.add(item("SCHOOL_PROFILE", "School profile", profileComplete(school), Importance.REQUIRED,
                "School name and board are set.",
                "Add your school's name and board."));
        items.add(item("ACADEMIC_SESSION", "Academic session", currentSession.isPresent(), Importance.REQUIRED,
                currentSession.map(s -> "Current session " + s.getLabel() + " is active.").orElse(""),
                "Choose a current academic session."));
        items.add(item("TEACHERS", "Teachers", activeTeachers > 0, Importance.REQUIRED,
                activeTeachers + " active teacher" + (activeTeachers == 1 ? " is" : "s are") + " available.",
                "Add at least one active teacher."));
        items.add(item("ATTENDANCE_CONFIGURATION", "Staff attendance", attendanceConfigured(school), Importance.REQUIRED,
                "Working days, timezone and check-in times are configured.",
                "Set the school start time and check-in window."));

        boolean timetableReady = currentSession
                .map(s -> timetableRepository.existsByAcademicSessionIdAndSchoolId(s.getId(), schoolId))
                .orElse(false);
        items.add(item("TIMETABLE", "Timetable", timetableReady, Importance.REQUIRED,
                "The current academic session has timetable entries.",
                currentSession.isPresent() ? "Add timetable entries for the current session." : "Set a current session before building the timetable."));

        items.add(item("SCHOOL_LOGO", "School logo", hasText(school.getLogoUrl()), Importance.RECOMMENDED,
                "A school logo is available for reports and communication.", "Upload the school logo."));
        items.add(item("STUDENTS", "Students", activeStudents > 0, Importance.RECOMMENDED,
                activeStudents + " active student" + (activeStudents == 1 ? " is" : "s are") + " enrolled.",
                "Add or import students when the school is ready."));

        if (activeStudents == 0) {
            items.add(new Item("PARENT_CONTACTS", "Parent contacts",
                    "This becomes relevant after students are added.", ItemStatus.NOT_APPLICABLE, Importance.OPTIONAL));
        } else {
            long studentsWithContacts = parentRelationshipRepository.countStudentsWithActiveParentContact(schoolId);
            items.add(item("PARENT_CONTACTS", "Parent contacts", studentsWithContacts >= activeStudents, Importance.OPTIONAL,
                    "Every active student has an active parent phone contact.",
                    (activeStudents - Math.min(activeStudents, studentsWithContacts)) + " active student(s) still need a parent phone contact."));
        }

        boolean feesReady = currentSession
                .map(s -> feeRuleRepository.existsBySchoolIdAndAcademicSessionId(schoolId, s.getId()))
                .orElse(false);
        items.add(item("FEE_CONFIGURATION", "Fee configuration", feesReady, Importance.OPTIONAL,
                "Fee structure is set up for the current session.",
                currentSession.isPresent() ? "Set up fee structure if the school will collect fees." : "Set a current academic session before configuring fees."));

        int totalRequired = (int) items.stream().filter(i -> i.importance() == Importance.REQUIRED).count();
        int completedRequired = (int) items.stream()
                .filter(i -> i.importance() == Importance.REQUIRED && i.status() == ItemStatus.COMPLETED)
                .count();
        int percentage = totalRequired == 0 ? 100 : completedRequired * 100 / totalRequired;
        Status status = completedRequired == totalRequired ? Status.READY
                : completedRequired == 0 ? Status.NOT_STARTED : Status.IN_PROGRESS;
        return new SchoolSetupHealthResponse(percentage, completedRequired, totalRequired, status, List.copyOf(items));
    }

    private Item item(String key, String title, boolean complete, Importance importance,
                      String completedDescription, String incompleteDescription) {
        return new Item(key, title, complete ? completedDescription : incompleteDescription,
                complete ? ItemStatus.COMPLETED : ItemStatus.INCOMPLETE, importance);
    }

    /**
     * Address/city/state/pincode/email/phone are all genuinely optional at school onboarding —
     * neither SchoolOnboardRequest's bean validation nor the SUPER_ADMIN onboarding form require
     * them (see the Phase 1 school-setup-health review report), and the rest of the app already
     * degrades gracefully without them (e.g. ReportCardPdfGenerator only conditionally includes
     * the address line). Requiring all eight fields here would flag a genuinely valid,
     * intentionally-minimal school profile as incomplete forever. Name is always present (the
     * one truly required onboarding field); board type is kept because it is a real academic
     * classification — not contact metadata — that the current onboarding UI always sets but
     * bean validation does not enforce, so a school onboarded any other way could still lack it.
     */
    private boolean profileComplete(School school) {
        return hasText(school.getName()) && school.getBoardType() != null;
    }

    private boolean attendanceConfigured(School school) {
        return hasText(school.getWorkingDays()) && hasText(school.getTimezone())
                && school.getSchoolStartTime() != null
                && school.getCheckinWindowStart() != null && school.getCheckinWindowEnd() != null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
