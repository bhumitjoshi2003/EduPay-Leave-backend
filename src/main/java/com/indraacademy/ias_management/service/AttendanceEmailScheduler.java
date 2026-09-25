package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.AttendanceStatus;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.AttendanceRow;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentAttendanceRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Daily absence email to the address on the student record (the family contact), sent at 12:15
 * school time (cron zone IST, the platform default) for Attendance V2 rows explicitly marked
 * ABSENT on that school's own local date. An absence covered by APPROVED leave is authorized,
 * so it does not get this "your child was absent" email; the leave flow already notified the
 * family. Only ACTIVE students are emailed.
 */
@Service
public class AttendanceEmailScheduler {

    private static final Logger log = LoggerFactory.getLogger(AttendanceEmailScheduler.class);

    @Autowired private StudentAttendanceRepository studentAttendanceRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private AttendanceService attendanceService;
    @Autowired private EmailService emailService;
    @Autowired private Clock clock;

    @Scheduled(cron = "0 15 12 * * *", zone = "Asia/Kolkata")
    public void sendAttendanceEmails() {
        LocalDate utcToday = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        List<AttendanceRow> absences;
        try {
            // Every school's local "today" lies within UTC today ± 1; filtered per school below.
            absences = studentAttendanceRepository.findRowsWithStatusOnDates(AttendanceStatus.ABSENT,
                    List.of(utcToday.minusDays(1), utcToday, utcToday.plusDays(1)));
        } catch (DataAccessException e) {
            log.error("Data access error while fetching today's absences", e);
            return;
        }
        Map<Long, List<AttendanceRow>> bySchool = absences.stream().collect(Collectors.groupingBy(AttendanceRow::schoolId));
        int sent = 0;
        for (Map.Entry<Long, List<AttendanceRow>> entry : bySchool.entrySet()) {
            Long schoolId = entry.getKey();
            School school = schoolRepository.findById(schoolId).orElse(null);
            if (school == null) continue;
            LocalDate today = attendanceService.schoolToday(school);
            Set<String> approvedLeave = attendanceService.approvedLeaveKeys(schoolId, today, today);
            for (AttendanceRow absence : entry.getValue()) {
                if (!today.equals(absence.date())) continue;
                if (approvedLeave.contains(AttendanceService.leaveKey(absence.studentId(), today))) continue;
                if (sendAbsenceEmail(school, absence.studentId(), today)) sent++;
            }
        }
        log.info("Finished scheduled job: sendAttendanceEmails — {} email(s) sent", sent);
    }

    private boolean sendAbsenceEmail(School school, String studentId, LocalDate date) {
        Optional<Student> studentOptional;
        try {
            studentOptional = studentRepository.findByStudentIdAndSchoolId(studentId, school.getId());
        } catch (DataAccessException e) {
            log.error("Data access error while fetching student {}; skipping absence email.", studentId, e);
            return false;
        }
        if (studentOptional.isEmpty()) return false;
        Student student = studentOptional.get();
        if (student.getStatus() != StudentStatus.ACTIVE) return false;
        String parentEmail = student.getEmail();
        if (parentEmail == null || parentEmail.trim().isEmpty()) {
            log.warn("Parent/Guardian email not found for student ID: {}", studentId);
            return false;
        }
        try {
            String studentName = student.getName() != null ? student.getName() : "your child";
            String dateStr = date.format(DateTimeFormatter.ofPattern("dd MMMM yyyy"));
            String subject = "Absence Notification – " + studentName;
            String htmlBody = buildAbsenceHtml(studentName, dateStr, school.getName());
            emailService.sendHtmlEmail(EmailPurpose.NOTIFICATION, parentEmail, subject, htmlBody);
            return true;
        } catch (Exception e) {
            log.error("Failed to send absence email for student ID: {}", studentId, e);
            return false;
        }
    }

    private String buildAbsenceHtml(String studentName, String dateStr, String schoolName) {
        String safeSchool = (schoolName != null && !schoolName.isBlank()) ? schoolName : "School";
        int year = LocalDate.now().getYear();
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>Absence Notification</title>
                </head>
                <body style="margin:0;padding:0;background-color:#f4f6f9;font-family:Arial,Helvetica,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f4f6f9;padding:32px 16px;">
                    <tr><td align="center">
                      <table width="600" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%%;">

                        <!-- Header -->
                        <tr>
                          <td align="center" style="background-color:#92400e;border-radius:16px 16px 0 0;padding:32px 40px 24px;">
                            <p style="margin:0 0 10px;font-size:44px;line-height:1;">&#128197;</p>
                            <h1 style="margin:0;color:#ffffff;font-size:24px;font-weight:800;">%s</h1>
                          </td>
                        </tr>

                        <!-- Band -->
                        <tr>
                          <td align="center" style="background-color:#d97706;padding:10px 40px;">
                            <p style="margin:0;color:#ffffff;font-size:12px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;">
                              Attendance Notification
                            </p>
                          </td>
                        </tr>

                        <!-- Body -->
                        <tr>
                          <td style="background-color:#ffffff;padding:36px 40px;">
                            <p style="margin:0 0 20px;font-size:16px;color:#111827;">Dear Parent / Guardian,</p>
                            <p style="margin:0 0 28px;font-size:14px;color:#6b7280;line-height:1.8;">
                              We would like to inform you that your child, <strong style="color:#111827;">%s</strong>,
                              was marked <strong style="color:#b45309;">absent</strong> on
                              <strong style="color:#111827;">%s</strong>.
                            </p>

                            <!-- Info box -->
                            <table width="100%%" cellpadding="0" cellspacing="0" style="margin-bottom:28px;">
                              <tr>
                                <td style="background-color:#fffbeb;border:2px solid #fcd34d;border-radius:12px;padding:20px 24px;">
                                  <p style="margin:0 0 10px;font-size:11px;font-weight:700;color:#d97706;letter-spacing:1.5px;text-transform:uppercase;">Absence Details</p>
                                  <table width="100%%" cellpadding="0" cellspacing="0">
                                    <tr>
                                      <td style="font-size:13px;color:#92400e;padding:4px 0;width:40%%;font-weight:600;">Student Name</td>
                                      <td style="font-size:13px;color:#1f2937;padding:4px 0;font-weight:700;">%s</td>
                                    </tr>
                                    <tr>
                                      <td style="font-size:13px;color:#92400e;padding:4px 0;font-weight:600;">Date of Absence</td>
                                      <td style="font-size:13px;color:#1f2937;padding:4px 0;font-weight:700;">%s</td>
                                    </tr>
                                  </table>
                                </td>
                              </tr>
                            </table>

                            <!-- Note -->
                            <table width="100%%" cellpadding="0" cellspacing="0" style="margin-bottom:28px;">
                              <tr>
                                <td style="background-color:#f0fdf4;border-left:4px solid #16a34a;padding:14px 18px;border-radius:0 8px 8px 0;">
                                  <p style="margin:0;font-size:13px;color:#166534;line-height:1.7;">
                                    If this absence was planned or there is a valid reason, please submit a leave application
                                    through the <strong>Edunexify</strong> app or contact the school office.
                                  </p>
                                </td>
                              </tr>
                            </table>

                            <p style="margin:0 0 28px;font-size:13px;color:#6b7280;line-height:1.8;">
                              If you believe this is an error, please contact the school office during working hours.
                            </p>
                            <hr style="border:none;border-top:1px solid #f1f5f9;margin:0 0 24px;">
                            <p style="margin:0;font-size:14px;color:#374151;line-height:1.7;">
                              With regards,<br>
                              <strong>%s</strong><br>
                              <span style="font-size:12px;color:#9ca3af;">Attendance &amp; Administration</span>
                            </p>
                          </td>
                        </tr>

                        <!-- Footer -->
                        <tr>
                          <td align="center" style="background-color:#1f2937;border-radius:0 0 16px 16px;padding:20px 40px;">
                            <p style="margin:0 0 4px;font-size:12px;color:rgba(255,255,255,0.55);">This is an automated message. Please do not reply to this email.</p>
                            <p style="margin:0;font-size:11px;color:rgba(255,255,255,0.35);">&copy; %d %s. All rights reserved.</p>
                          </td>
                        </tr>

                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(safeSchool, studentName, dateStr, studentName, dateStr, safeSchool, year, safeSchool);
    }
}
