package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    @Autowired private JavaMailSender javaMailSender;
    @Autowired private StudentRepository studentRepository;
    @Autowired private TeacherRepository teacherRepository;


    @Value("${app.mail.from:noreply@edunexify.co.in}")
    private String emailSender;

    @Async
    public void sendEmail(String to, String subject, String body) {
        if (to == null || to.trim().isEmpty() || subject == null || body == null) {
            log.warn("Attempted to send email with missing required field (To: {}, Subject: {}). Aborting.", to, subject);
            return;
        }

        log.info("Attempting to send async email to: {} with subject: {}", to, subject);
        System.out.println("Sending");
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(emailSender);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);

            javaMailSender.send(message);
            System.out.println("Sent");
            log.info("Successfully sent async email to: {}", to);
        } catch (MailException e) {
            log.error("MailException occurred while sending async email to: {} with subject: {}", to, subject, e);
            // Since this is @Async, re-throwing may not be effective for the caller,
            // but logging is critical.
        } catch (Exception e) {
            log.error("Unexpected error occurred while sending async email to: {} with subject: {}", to, subject, e);
        }
    }

    @Async
    public void sendHtmlEmail(String to, String subject, String htmlBody) {
        if (to == null || to.trim().isEmpty() || subject == null || htmlBody == null) {
            log.warn("Attempted to send HTML email with missing required field (To: {}, Subject: {}). Aborting.", to, subject);
            return;
        }
        log.info("Attempting to send async HTML email to: {} with subject: {}", to, subject);
        try {
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(emailSender);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            javaMailSender.send(message);
            log.info("Successfully sent async HTML email to: {}", to);
        } catch (MessagingException e) {
            log.error("MessagingException while sending HTML email to: {}", to, e);
        } catch (MailException e) {
            log.error("MailException while sending HTML email to: {}", to, e);
        } catch (Exception e) {
            log.error("Unexpected error while sending HTML email to: {}", to, e);
        }
    }

    @Async
    public void sendBulkEmail(List<String> toEmails, String subject, String body) {
        if (toEmails == null || toEmails.isEmpty() || subject == null || body == null) {
            log.warn("Attempted to send bulk email with missing required fields. Aborting.");
            return;
        }

        List<String> validEmails = toEmails.stream()
                .filter(email -> email != null && !email.trim().isEmpty())
                .toList();

        if (validEmails.isEmpty()) {
            log.warn("Filtered email list for bulk send is empty. Aborting.");
            return;
        }

        log.info("Attempting to send bulk HTML email to {} unique recipients with subject: {}", validEmails.size(), subject);

        try {
            String htmlBody = buildAnnouncementHtml(subject, body);
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(emailSender);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            helper.setBcc(validEmails.toArray(new String[0]));
            javaMailSender.send(message);
            log.info("Successfully sent bulk HTML email to {} recipients.", validEmails.size());
        } catch (MessagingException e) {
            log.error("MessagingException while sending bulk email to {} recipients with subject: {}", validEmails.size(), subject, e);
        } catch (MailException e) {
            log.error("MailException occurred while sending bulk email to {} recipients with subject: {}", validEmails.size(), subject, e);
        } catch (Exception e) {
            log.error("Unexpected error occurred while sending bulk email to {} recipients with subject: {}", validEmails.size(), subject, e);
        }
    }

    /** Wraps a plain-text notice body in the branded school announcement HTML template. */
    String buildAnnouncementHtml(String subject, String body) {
        String safeSubject = subject == null ? "" : subject;
        String safeBody    = body    == null ? "" : body.replace("&", "&amp;")
                                                        .replace("<", "&lt;")
                                                        .replace(">", "&gt;")
                                                        .replace("\n", "<br>");
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>%s</title>
                </head>
                <body style="margin:0;padding:0;background-color:#f4f6f9;font-family:Arial,Helvetica,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f4f6f9;padding:32px 16px;">
                    <tr><td align="center">
                      <table width="600" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%%;">

                        <!-- Header -->
                        <tr>
                          <td align="center" style="background-color:#1e3a5f;border-radius:16px 16px 0 0;padding:32px 40px 24px;">
                            <p style="margin:0 0 10px;font-size:40px;line-height:1;">&#127978;</p>
                            <h1 style="margin:0;color:#ffffff;font-size:24px;font-weight:800;">Indra Academy</h1>
                            <p style="margin:6px 0 0;color:rgba(255,255,255,0.75);font-size:13px;">Sr. Sec. School</p>
                          </td>
                        </tr>

                        <!-- Title band -->
                        <tr>
                          <td align="center" style="background-color:#2563eb;padding:12px 40px;">
                            <p style="margin:0;color:#ffffff;font-size:12px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;">
                              &#128226; School Notice
                            </p>
                          </td>
                        </tr>

                        <!-- Body -->
                        <tr>
                          <td style="background-color:#ffffff;padding:36px 40px;">
                            <h2 style="margin:0 0 20px;font-size:19px;font-weight:800;color:#111827;border-bottom:2px solid #dbeafe;padding-bottom:14px;">
                              %s
                            </h2>
                            <p style="margin:0 0 32px;font-size:14px;color:#374151;line-height:1.9;">%s</p>
                            <hr style="border:none;border-top:1px solid #f1f5f9;margin:0 0 24px;">
                            <p style="margin:0;font-size:14px;color:#374151;line-height:1.7;">
                              With regards,<br>
                              <strong>Indra Academy Sr. Sec. School</strong><br>
                              <span style="font-size:12px;color:#9ca3af;">Administration</span>
                            </p>
                          </td>
                        </tr>

                        <!-- Footer -->
                        <tr>
                          <td align="center" style="background-color:#1f2937;border-radius:0 0 16px 16px;padding:20px 40px;">
                            <p style="margin:0 0 4px;font-size:12px;color:rgba(255,255,255,0.55);">This is an automated message. Please do not reply to this email.</p>
                            <p style="margin:0;font-size:11px;color:rgba(255,255,255,0.35);">&copy; 2026 Indra Academy Sr. Sec. School. All rights reserved.</p>
                          </td>
                        </tr>

                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(safeSubject, safeSubject, safeBody);
    }

    @Async
    public void sendBulkEmailToClass(String subject, String body, String selectedClass) {
        if (subject == null || body == null || selectedClass == null || selectedClass.trim().isEmpty()) {
            log.warn("Attempted to send bulk email to class with missing required fields. Class: {}. Aborting.", selectedClass);
            return;
        }
        log.info("Fetching student list to send email to class: {} with subject: {}", selectedClass, subject);

        List<Student> students = Collections.emptyList();
        try {
            if ("All".equalsIgnoreCase(selectedClass)) {
                students = studentRepository.findAll();
                log.debug("Fetched all students for bulk email.");
            } else {
                students  = studentRepository.findByClassName(selectedClass);
                log.debug("Fetched {} students for class: {}", students.size(), selectedClass);
            }
        } catch (DataAccessException e) {
            log.error("Data access error occurred while fetching students for class: {}. Aborting email send.", selectedClass, e);
            return;
        }

        List<String> toEmails = students.stream()
                .map(Student::getEmail)
                .filter(email -> email != null && !email.trim().isEmpty())
                .distinct()
                .toList();

        if (toEmails.isEmpty()) {
            log.warn("No valid emails found for class: {}. Aborting email send.", selectedClass);
            return;
        }

        log.info("Found {} unique email addresses for class: {}", toEmails.size(), selectedClass);
        sendBulkEmail(toEmails, subject, body);
    }

    @Async
    public void sendBulkEmailToTeachers(String subject, String body) {
        if (subject == null || body == null) {
            log.warn("Attempted to send bulk email to teachers with missing fields. Aborting.");
            return;
        }
        log.info("Fetching all teachers for bulk email with subject: {}", subject);
        try {
            List<String> emails = teacherRepository.findAll().stream()
                    .map(Teacher::getEmail)
                    .filter(email -> email != null && !email.trim().isEmpty())
                    .distinct()
                    .toList();
            if (emails.isEmpty()) {
                log.warn("No teacher emails found. Aborting.");
                return;
            }
            log.info("Sending bulk email to {} teachers.", emails.size());
            sendBulkEmail(emails, subject, body);
        } catch (DataAccessException e) {
            log.error("Data access error fetching teachers for bulk email.", e);
        }
    }

    @Async
    public void sendBulkEmailToClassWithTeacher(String subject, String body, String className) {
        if (subject == null || body == null || className == null) {
            log.warn("Attempted to send class+teacher email with missing fields. Aborting.");
            return;
        }
        log.info("Sending bulk email to students of class {} and their class teacher.", className);
        try {
            List<String> emails = new java.util.ArrayList<>(
                    studentRepository.findByClassName(className).stream()
                            .map(Student::getEmail)
                            .filter(email -> email != null && !email.trim().isEmpty())
                            .toList()
            );
            teacherRepository.findByClassTeacher(className)
                    .map(Teacher::getEmail)
                    .filter(email -> email != null && !email.trim().isEmpty())
                    .ifPresent(emails::add);

            List<String> distinct = emails.stream().distinct().toList();
            if (distinct.isEmpty()) {
                log.warn("No emails found for class {} with teacher. Aborting.", className);
                return;
            }
            log.info("Sending bulk email to {} recipients (class {} + teacher).", distinct.size(), className);
            sendBulkEmail(distinct, subject, body);
        } catch (DataAccessException e) {
            log.error("Data access error fetching recipients for class+teacher email.", e);
        }
    }
}