package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Attendance;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.AttendanceRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
public class AttendanceEmailScheduler {

    private static final Logger log = LoggerFactory.getLogger(AttendanceEmailScheduler.class);

    @Autowired private AttendanceRepository attendanceRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private EmailService emailService;

    @Scheduled(cron = "0 15 12 * * *")
    public void sendAttendanceEmails() {
        log.info("Starting scheduled job: sendAttendanceEmails at {}", LocalDate.now());
        final LocalDate today = LocalDate.now();
        List<Attendance> attendanceList = null;

        try {
            attendanceList = attendanceRepository.findByDate(today);
            if (attendanceList.isEmpty()) {
                log.info("No absent students found for today: {}", today);
                return;
            }
            log.info("Found {} absent records for today.", attendanceList.size());
        } catch (DataAccessException e) {
            log.error("Data access error while fetching absent students for date: {}", today, e);
            return;
        }

        for (Attendance attendance : attendanceList) {
            String studentId = attendance.getStudentId();

            if ("X".equals(studentId)) {
                log.warn("Skipping attendance record with non-standard student ID 'X'. Record details: {}", attendance.toString());
                continue;
            }

            Optional<Student> studentOptional = Optional.empty();
            try {
                studentOptional = studentRepository.findById(studentId);
            } catch (DataAccessException e) {
                log.error("Data access error while fetching Student ID: {}. Skipping email for this student.", studentId, e);
                continue;
            }

            if (studentOptional.isPresent()) {
                Student student = studentOptional.get();
                String parentEmail = student.getEmail();

                if (parentEmail != null && !parentEmail.trim().isEmpty()) {
                    try {
                        String studentName = student.getName() != null ? student.getName() : "your child";
                        String dateStr    = today.format(DateTimeFormatter.ofPattern("dd MMMM yyyy"));
                        String subject    = "Absence Notification – " + studentName;
                        String htmlBody   = buildAbsenceHtml(studentName, dateStr);

                        emailService.sendHtmlEmail(parentEmail, subject, htmlBody);
                        log.info("Successfully sent absence email to parent of student ID: {} ({})", studentId, parentEmail);
                    } catch (Exception e) {
                        log.error("Failed to send email to parent of student ID: {} ({})", studentId, parentEmail, e);
                    }
                } else {
                    log.warn("Parent/Guardian email not found or empty for student ID: {} (Name: {})", studentId, student.getName());
                }
            } else {
                log.warn("Student not found with ID: {}. Unable to send absence email.", studentId);
            }
        }
        log.info("Finished scheduled job: sendAttendanceEmails");
    }

    private String buildAbsenceHtml(String studentName, String dateStr) {
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
                            <h1 style="margin:0;color:#ffffff;font-size:24px;font-weight:800;">Indra Academy</h1>
                            <p style="margin:6px 0 0;color:rgba(255,255,255,0.75);font-size:13px;">Sr. Sec. School</p>
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
                              <strong>Indra Academy Sr. Sec. School</strong><br>
                              <span style="font-size:12px;color:#9ca3af;">Attendance &amp; Administration</span>
                            </p>
                          </td>
                        </tr>

                        <!-- Footer -->
                        <tr>
                          <td align="center" style="background-color:#1f2937;border-radius:0 0 16px 16px;padding:20px 40px;">
                            <p style="margin:0 0 4px;font-size:12px;color:rgba(255,255,255,0.55);">This is an automated message. Please do not reply to this email.</p>
                            <p style="margin:0;font-size:11px;color:rgba(255,255,255,0.35);">&copy; %d Indra Academy Sr. Sec. School. All rights reserved.</p>
                          </td>
                        </tr>

                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(studentName, dateStr, studentName, dateStr, year);
    }
}