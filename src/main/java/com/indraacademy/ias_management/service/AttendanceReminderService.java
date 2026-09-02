package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ClassAttendanceSummaryDTO;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import com.indraacademy.ias_management.notification.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * AttendanceReminderService — sends the low-attendance warning email, the attendance
 * counterpart of FeeReminderService's reminder-sending half (not its scheduled-run half,
 * which has no attendance equivalent yet). Deliberately a separate service rather than
 * folded into AttendanceService: AttendanceService owns attendance *computation*
 * (read-only summaries), this owns *communication* (email content + sending) built on top
 * of that computation — same separation FeeReminderService already keeps from
 * FeeCalculationService.
 */
@Service
@RequiredArgsConstructor
public class AttendanceReminderService {

    private static final Logger log = LoggerFactory.getLogger(AttendanceReminderService.class);

    @Autowired private AttendanceService attendanceService;
    @Autowired private StudentRepository studentRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private BusinessNotificationService businessNotifications;
    @Autowired private SecurityUtil securityUtil;

    /** Mirrors FeeReminderService.ReminderOutcome — same reasoning: a caller needs to know
     *  *why* nothing was sent, not just that nothing was. */
    private enum ReminderOutcome {
        SENT, SKIPPED_NOT_ACTIVE, SKIPPED_NO_EMAIL, FAILED;
        String key() { return name().toLowerCase(Locale.ROOT); }
    }

    /**
     * Sends the attendance warning email to each given student, blocking on the real SMTP
     * round-trip so it can report a genuine per-student true/false outcome — same reasoning
     * as FeeReminderService.sendReminderEmailSync, used only by the AI-workflow dispatch path.
     */
    public Map<String, String> sendAttendanceReminderEmailsWithOutcomes(List<String> studentIds, String session) {
        return sendAttendanceReminderEmailsWithOutcomes(studentIds, session, Map.of(), null);
    }

    public Map<String, String> sendAttendanceReminderEmailsWithOutcomes(List<String> studentIds, String session,
                                                                       String workflowId) {
        return sendAttendanceReminderEmailsWithOutcomes(studentIds, session, Map.of(), workflowId);
    }

    /**
     * As above, but for a batch selected by <i>recent consecutive absence</i> rather than by
     * cumulative percentage.
     *
     * <p>The distinction has to reach the email itself, not just the approval card: the standard
     * body tells the parent their child's attendance "has fallen below the school's required
     * threshold", which is simply untrue for a student absent three days running who is
     * nonetheless at 85% for the session. Passing that student's streak dates here swaps in
     * wording that states what actually happened, and surfaces the dates in the stat box, while
     * keeping one template and one visual identity for both kinds of warning.
     *
     * @param recentAbsenceDatesByStudent studentId → the streak dates (ISO), oldest first.
     *                                    Students absent from this map fall back to the standard
     *                                    threshold wording, so a mixed or empty map is safe.
     */
    public Map<String, String> sendAttendanceReminderEmailsWithOutcomes(
            List<String> studentIds, String session, Map<String, List<String>> recentAbsenceDatesByStudent) {
        return sendAttendanceReminderEmailsWithOutcomes(studentIds, session, recentAbsenceDatesByStudent, null);
    }

    public Map<String, String> sendAttendanceReminderEmailsWithOutcomes(
            List<String> studentIds, String session, Map<String, List<String>> recentAbsenceDatesByStudent,
            String workflowId) {
        Map<String, String> outcomes = new LinkedHashMap<>();
        Map<String, List<String>> absenceDates =
                recentAbsenceDatesByStudent != null ? recentAbsenceDatesByStudent : Map.of();

        // One school-wide summary query for the whole batch, not one per student — same
        // "fetch once, filter in memory" shape AttendanceService.getSchoolSummary already
        // uses internally across classes.
        List<ClassAttendanceSummaryDTO> summary;
        try {
            summary = attendanceService.getSchoolSummary("year", null, null, session);
        } catch (Exception e) {
            log.error("Failed to load attendance summary for reminder batch, session {}: {}", session, e.getMessage());
            for (String studentId : studentIds) outcomes.put(studentId, "failed");
            return outcomes;
        }
        Map<String, ClassAttendanceSummaryDTO> byStudentId = new LinkedHashMap<>();
        for (ClassAttendanceSummaryDTO row : summary) {
            byStudentId.put(row.getStudentId(), row);
        }

        for (String studentId : studentIds) {
            try {
                ReminderOutcome outcome = sendReminderEmailSync(studentId, session, byStudentId.get(studentId),
                        absenceDates.get(studentId), workflowId);
                outcomes.put(studentId, outcome.key());
            } catch (Exception e) {
                log.error("Failed to send attendance reminder for student {}: {}", studentId, e.getMessage());
                outcomes.put(studentId, ReminderOutcome.FAILED.key());
            }
        }
        return outcomes;
    }

    /**
     * The single send boundary both AI attendance-workflow dispatch endpoints funnel through
     * (class-teacher and admin variants). studentIds arriving here originate from a request
     * body, not a freshly-recomputed candidate list — a stale or directly-supplied exited
     * studentId must be rejected here, independent of whatever filtering happened upstream when
     * the candidate list was first built.
     */
    private ReminderOutcome sendReminderEmailSync(String studentId, String session, ClassAttendanceSummaryDTO attendance,
                                          List<String> recentAbsenceDates, String workflowId) {
        if (attendance == null) {
            log.warn("Cannot send attendance reminder: no attendance summary row for student {} in session {}.", studentId, session);
            return ReminderOutcome.FAILED;
        }

        Student student = studentRepository.findByStudentIdAndSchoolId(studentId, securityUtil.getSchoolId())
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));

        if (student.getStatus() != StudentStatus.ACTIVE) {
            log.info("Skipping attendance reminder for non-active student {} (status={})", studentId, student.getStatus());
            return ReminderOutcome.SKIPPED_NOT_ACTIVE;
        }

        String email = student.getEmail();
        if (email == null || email.isBlank()) {
            log.warn("Cannot send attendance reminder (sync): student {} has no email.", studentId);
            return ReminderOutcome.SKIPPED_NO_EMAIL;
        }

        String studentName = student.getName() != null ? student.getName() : "Parent/Guardian";
        String schoolName = schoolRepository.findById(securityUtil.getSchoolId())
                .map(School::getName).orElse("School");
        String subject = "Attendance Warning – " + session;
        String htmlBody = buildAttendanceReminderHtml(studentName, session, schoolName, attendance, recentAbsenceDates);

        boolean consecutiveAbsence = recentAbsenceDates != null && !recentAbsenceDates.isEmpty();
        NotificationEventCode eventCode = consecutiveAbsence
                ? NotificationEventCode.STUDENT_ABSENT : NotificationEventCode.ATTENDANCE_LOW;
        String key = "attendance:" + securityUtil.getSchoolId() + ":" + studentId + ":" + session + ":"
                + (workflowId == null ? "manual:" + LocalDate.now() : "workflow:" + workflowId);
        businessNotifications.studentAndParents(securityUtil.getSchoolId(), studentId,
                NotificationAudienceType.STUDENT_WITH_ATTENDANCE_PARENTS, eventCode,
                NotificationCategory.ATTENDANCE, subject,
                consecutiveAbsence
                        ? "Recent consecutive absences need your attention. Review the attendance details."
                        : "Attendance has fallen below the required threshold. Review the attendance details.",
                "Attendance", studentId, "/dashboard/attendance-summary", "SYSTEM", key,
                Set.of(ExternalDeliveryChannel.PUSH, ExternalDeliveryChannel.EMAIL));
        log.info("Attendance notification queued for student {}", studentId);
        return ReminderOutcome.SENT;
    }

    // ─── Email template ───────────────────────────────────────────────────────

    /**
     * Same layout system as FeeReminderService.buildFeeReminderHtml (rounded header card,
     * colored session band, highlighted stat box, tip box, footer) — an amber/orange
     * "warning" palette instead of the fee reminder's red "overdue" palette, so the two stay
     * visually distinct in an inbox but consistent in design language.
     */
    private String buildAttendanceReminderHtml(String studentName, String session, String schoolName,
                                                ClassAttendanceSummaryDTO attendance,
                                                List<String> recentAbsenceDates) {
        String safeSchool = (schoolName != null && !schoolName.isBlank()) ? schoolName : "School";
        int year = LocalDate.now().getYear();
        String pct = String.format("%.1f%%", attendance.getAttendancePercentage());
        String daysLine = formatDayCount(attendance.getDaysPresent()) + " of "
                + attendance.getTotalWorkingDays() + " working days present";

        // The only two pieces that differ between a threshold batch and a consecutive-absence
        // batch. Everything below — layout, palette, stat box, tip, footer — is shared, so the
        // two warnings stay visually identical while each states something true.
        boolean isRecentAbsence = recentAbsenceDates != null && !recentAbsenceDates.isEmpty();
        String reasonSentence = isRecentAbsence
                ? ("We hope this message finds you well. This is to inform you that your child has been"
                    + " absent from school for <strong style=\"color:#374151;\">"
                    + recentAbsenceDates.size() + " consecutive school day"
                    + (recentAbsenceDates.size() == 1 ? "" : "s")
                    + "</strong> (" + formatAbsenceDates(recentAbsenceDates) + ") and this needs your attention.")
                : ("We hope this message finds you well. This is to inform you that attendance for the academic"
                    + " session <strong style=\"color:#374151;\">" + session + "</strong> has fallen below the"
                    + " school's required threshold and needs your attention.");
        String absenceDatesLine = isRecentAbsence
                ? "<p style=\"margin:6px 0 0;font-size:12px;color:#b45309;\">Recent absences: "
                    + formatAbsenceDates(recentAbsenceDates) + "</p>"
                : "";

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Attendance Warning</title>
            </head>
            <body style="margin:0;padding:0;background-color:#f4f6f9;font-family:Arial,Helvetica,sans-serif;">
              <table width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f4f6f9;padding:32px 16px;">
                <tr>
                  <td align="center">
                    <table width="600" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%%;">

                      <!-- ── Header ── -->
                      <tr>
                        <td align="center" style="background-color:#92400e;border-radius:16px 16px 0 0;padding:36px 40px 28px;">
                          <p style="margin:0 0 12px;font-size:48px;line-height:1;">&#128197;</p>
                          <h1 style="margin:0;color:#ffffff;font-size:26px;font-weight:800;letter-spacing:-0.5px;">%s</h1>
                        </td>
                      </tr>

                      <!-- ── Session band ── -->
                      <tr>
                        <td align="center" style="background-color:#d97706;padding:10px 40px;">
                          <p style="margin:0;color:#ffffff;font-size:12px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;">
                            Attendance Warning &mdash; %s
                          </p>
                        </td>
                      </tr>

                      <!-- ── Body ── -->
                      <tr>
                        <td style="background-color:#ffffff;padding:36px 40px;">

                          <p style="margin:0 0 20px;font-size:16px;color:#111827;line-height:1.5;">
                            Dear <strong>%s</strong>,
                          </p>

                          <p style="margin:0 0 28px;font-size:14px;color:#6b7280;line-height:1.8;">
                            %s
                          </p>

                          <!-- Attendance stat box -->
                          <table width="100%%" cellpadding="0" cellspacing="0" style="margin-bottom:28px;">
                            <tr>
                              <td style="background-color:#fffbeb;border:2px solid #fde68a;border-radius:12px;padding:22px 26px;">
                                <p style="margin:0 0 10px;font-size:11px;font-weight:700;color:#d97706;letter-spacing:1.5px;text-transform:uppercase;">
                                  Current Attendance
                                </p>
                                <p style="margin:0 0 6px;font-size:20px;font-weight:800;color:#92400e;">%s</p>
                                <p style="margin:0;font-size:12px;color:#b45309;">%s &middot; Academic Session: %s</p>
                                %s
                              </td>
                            </tr>
                          </table>

                          <!-- Edunexify tip -->
                          <table width="100%%" cellpadding="0" cellspacing="0" style="margin-bottom:28px;">
                            <tr>
                              <td style="background-color:#f0fdf4;border-left:4px solid #16a34a;padding:16px 18px;border-radius:0 8px 8px 0;">
                                <p style="margin:0;font-size:13px;color:#166534;line-height:1.7;">
                                  &#128161; <strong>Stay on track:</strong> You can view a full day-by-day attendance breakdown any time through the
                                  <strong>Edunexify</strong> app. Regular attendance is important for continued academic progress.
                                </p>
                              </td>
                            </tr>
                          </table>

                          <p style="margin:0 0 32px;font-size:13.5px;color:#6b7280;line-height:1.8;">
                            If you believe this notice was sent in error, or would like to discuss the reasons for absence,
                            please contact the school office during working hours.
                          </p>

                          <hr style="border:none;border-top:1px solid #f1f5f9;margin:0 0 24px;">

                          <p style="margin:0;font-size:14px;color:#374151;line-height:1.7;">
                            With regards,<br>
                            <strong>%s</strong><br>
                            <span style="font-size:12px;color:#9ca3af;">Attendance Management Team</span>
                          </p>
                        </td>
                      </tr>

                      <!-- ── Footer ── -->
                      <tr>
                        <td align="center" style="background-color:#1f2937;border-radius:0 0 16px 16px;padding:22px 40px;">
                          <p style="margin:0 0 6px;font-size:12px;color:rgba(255,255,255,0.55);">
                            This is an automated message. Please do not reply to this email.
                          </p>
                          <p style="margin:0;font-size:11px;color:rgba(255,255,255,0.35);">
                            &copy; %d %s. All rights reserved.
                          </p>
                        </td>
                      </tr>

                    </table>
                  </td>
                </tr>
              </table>
            </body>
            </html>
            """.formatted(safeSchool, session, studentName, reasonSentence, pct, daysLine, session,
                          absenceDatesLine, safeSchool, year, safeSchool);
    }

    private String formatDayCount(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** "2026-08-14","2026-08-15","2026-08-16" → "14, 15 &amp; 16 Aug" — readable in an email,
     *  and collapsed to one month name when the streak doesn't cross a month boundary. */
    private String formatAbsenceDates(List<String> isoDates) {
        List<LocalDate> dates = isoDates.stream().map(LocalDate::parse).sorted().toList();
        if (dates.isEmpty()) return "";

        boolean sameMonth = dates.stream().map(LocalDate::getMonth).distinct().count() == 1;
        DateTimeFormatter dayOnly = DateTimeFormatter.ofPattern("d", Locale.ENGLISH);
        DateTimeFormatter dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

        List<String> parts = new java.util.ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            boolean last = (i == dates.size() - 1);
            // Only the final date carries the month when they all share one, so the list reads
            // "14, 15 & 16 Aug" rather than repeating "Aug" three times.
            parts.add(dates.get(i).format(sameMonth && !last ? dayOnly : dayMonth));
        }
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " &amp; " + parts.get(parts.size() - 1);
    }
}
