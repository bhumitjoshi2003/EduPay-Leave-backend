package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;

// NOTE: The legacy DB constraint "uq_timetable_class_day_period" on (class_name, day, period_number)
// must be dropped manually so section-specific timetables can coexist:
//   ALTER TABLE timetable_entry DROP INDEX uq_timetable_class_day_period;
// Uniqueness is now enforced in TimetableService code (school+class+section+day+period).
@Entity
@Table(name = "timetable_entry")
public class TimetableEntry {

    @Version
    @Column(nullable = false)
    private long revision;

    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id")
    private Long schoolId;

    /** Null for every legacy row today (Phase F2 foundation only — not yet backfilled or
     *  written by any consumer). See TimetableEntry class-level context: this column exists so
     *  a later phase can make timetable writes session-aware without a breaking schema change. */
    @Column(name = "academic_session_id")
    private Long academicSessionId;

    @Column(name = "class_name", nullable = false)
    private String className;

    @Column(name = "class_id")
    private Long classId;

    @Column(name = "section_id")
    private Long sectionId;

    @Column(name = "section_name", length = 50)
    private String sectionName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Day day;

    @Column(name = "period_number", nullable = false)
    private Integer periodNumber;

    @Column(name = "start_time", nullable = false)
    private String startTime;

    @Column(name = "end_time", nullable = false)
    private String endTime;

    @Column(name = "subject_name", nullable = false)
    private String subjectName;

    @Column(name = "teacher_id")
    private String teacherId;

    @Column(name = "teacher_name")
    private String teacherName;

    /** Legacy tag from the retired "simultaneous group" feature (historical rows only). The
     *  timetable no longer has any grouping/pairing concept — any number of rows may
     *  independently occupy the same class+section+day+period, tagged or not, and this column is
     *  no longer written or interpreted by any live business path. It is kept only because
     *  {@link com.indraacademy.ias_management.service.LegacyTimetableAdoptionWorker} (a separate,
     *  one-time, SUPER_ADMIN-only diagnostic/migration tool) still reads it when classifying
     *  pre-Phase-F2 legacy rows. */
    @Column(name = "simultaneous_group", length = 100)
    private String simultaneousGroup;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public Long getClassId() { return classId; }
    public void setClassId(Long classId) { this.classId = classId; }

    public Day getDay() { return day; }
    public void setDay(Day day) { this.day = day; }

    public Integer getPeriodNumber() { return periodNumber; }
    public void setPeriodNumber(Integer periodNumber) { this.periodNumber = periodNumber; }

    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }

    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }

    public String getSubjectName() { return subjectName; }
    public void setSubjectName(String subjectName) { this.subjectName = subjectName; }

    public String getTeacherId() { return teacherId; }
    public void setTeacherId(String teacherId) { this.teacherId = teacherId; }

    public String getTeacherName() { return teacherName; }
    public void setTeacherName(String teacherName) { this.teacherName = teacherName; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public Long getAcademicSessionId() { return academicSessionId; }
    public void setAcademicSessionId(Long academicSessionId) { this.academicSessionId = academicSessionId; }

    public Long getSectionId() { return sectionId; }
    public void setSectionId(Long sectionId) { this.sectionId = sectionId; }

    public String getSectionName() { return sectionName; }
    public void setSectionName(String sectionName) { this.sectionName = sectionName; }

    public String getSimultaneousGroup() { return simultaneousGroup; }
    public void setSimultaneousGroup(String simultaneousGroup) { this.simultaneousGroup = simultaneousGroup; }
}
