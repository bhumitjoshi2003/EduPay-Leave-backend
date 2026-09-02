package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * An admin-issued, one-time authorization letting a teacher self-serve timetable periods for a
 * class/section they don't yet have any other connection to — no logged periods there, and not
 * their class-teacher assignment. See TimetableService#authorizeTeacherWrite, which checks this
 * as the third (and final) way a teacher may be authorized for a class+section.
 *
 * <p>Deliberately lightweight: the admin only names teacher + class + section, not an actual
 * period — unlike entering a real timetable row, this doesn't claim any day/time/subject.
 */
@Entity
@Table(name = "teacher_class_grant")
public class TeacherClassGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "teacher_id", nullable = false)
    private String teacherId;

    @Column(name = "class_name", nullable = false)
    private String className;

    @Column(name = "class_id")
    private Long classId;

    @Column(name = "section_id")
    private Long sectionId;

    @Column(name = "section_name", length = 50)
    private String sectionName;

    @Column(name = "granted_by")
    private String grantedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public TeacherClassGrant() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public String getTeacherId() { return teacherId; }
    public void setTeacherId(String teacherId) { this.teacherId = teacherId; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public Long getClassId() { return classId; }
    public void setClassId(Long classId) { this.classId = classId; }

    public Long getSectionId() { return sectionId; }
    public void setSectionId(Long sectionId) { this.sectionId = sectionId; }

    public String getSectionName() { return sectionName; }
    public void setSectionName(String sectionName) { this.sectionName = sectionName; }

    public String getGrantedBy() { return grantedBy; }
    public void setGrantedBy(String grantedBy) { this.grantedBy = grantedBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
