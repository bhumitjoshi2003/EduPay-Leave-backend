package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.DemoRequestDTO;
import com.indraacademy.ias_management.dto.DemoRequestResponseDTO;
import com.indraacademy.ias_management.entity.DemoRequest;
import com.indraacademy.ias_management.entity.DemoRequestStatus;
import com.indraacademy.ias_management.repository.DemoRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Service
public class DemoRequestService {

    private static final Logger log = LoggerFactory.getLogger(DemoRequestService.class);

    @Autowired
    private DemoRequestRepository demoRequestRepository;

    @Autowired
    private EmailService emailService;

    @Value("${app.demo.notify.email}")
    private String adminEmail;

    @Transactional
    public DemoRequest save(DemoRequestDTO dto) {
        DemoRequest entity = new DemoRequest();
        entity.setSchoolName(dto.getSchoolName());
        entity.setContactName(dto.getContactName());
        entity.setEmail(dto.getEmail());
        entity.setPhone(dto.getPhone());
        entity.setNumberOfStudents(dto.getNumberOfStudents());
        entity.setCity(dto.getCity());
        entity.setMessage(dto.getMessage());

        DemoRequest saved = demoRequestRepository.save(entity);

        try {
            String subject = "New Demo Request — " + dto.getSchoolName();
            String htmlBody = buildEmailHtml(dto);
            emailService.sendHtmlEmail(adminEmail, subject, htmlBody);
        } catch (Exception e) {
            log.error("Failed to send demo request notification email: {}", e.getMessage());
        }

        return saved;
    }

    @Transactional(readOnly = true)
    public List<DemoRequestResponseDTO> getAll() {
        return demoRequestRepository.findAllByOrderByRequestedAtDesc()
                .stream()
                .map(this::toResponseDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public DemoRequestResponseDTO updateStatus(Long id, DemoRequestStatus status) {
        DemoRequest entity = demoRequestRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Demo request not found with id: " + id));
        entity.setStatus(status);
        return toResponseDTO(demoRequestRepository.save(entity));
    }

    private DemoRequestResponseDTO toResponseDTO(DemoRequest entity) {
        DemoRequestResponseDTO dto = new DemoRequestResponseDTO();
        dto.setId(entity.getId());
        dto.setSchoolName(entity.getSchoolName());
        dto.setContactName(entity.getContactName());
        dto.setEmail(entity.getEmail());
        dto.setPhone(entity.getPhone());
        dto.setNumberOfStudents(entity.getNumberOfStudents());
        dto.setCity(entity.getCity());
        dto.setMessage(entity.getMessage());
        dto.setStatus(entity.getStatus());
        dto.setRequestedAt(entity.getRequestedAt());
        return dto;
    }

    private String buildEmailHtml(DemoRequestDTO dto) {
        int year = LocalDate.now().getYear();
        String na = "N/A";
        String students  = dto.getNumberOfStudents() != null ? String.valueOf(dto.getNumberOfStudents()) : na;
        String city      = dto.getCity()    != null && !dto.getCity().isBlank()    ? dto.getCity()    : na;
        String message   = dto.getMessage() != null && !dto.getMessage().isBlank() ? dto.getMessage() : na;

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>New Demo Request</title>
                </head>
                <body style="margin:0;padding:0;background-color:#f1f5f9;font-family:Arial,Helvetica,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f1f5f9;padding:32px 16px;">
                    <tr><td align="center">
                      <table width="600" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%%;">

                        <!-- Header -->
                        <tr>
                          <td align="center" style="background-color:#0f172a;border-radius:16px 16px 0 0;padding:32px 40px 24px;">
                            <p style="margin:0 0 10px;font-size:44px;line-height:1;">&#127760;</p>
                            <h1 style="margin:0;color:#ffffff;font-size:24px;font-weight:800;">EduNexify</h1>
                            <p style="margin:6px 0 0;color:rgba(255,255,255,0.6);font-size:13px;">School Management Platform</p>
                          </td>
                        </tr>

                        <!-- Band -->
                        <tr>
                          <td align="center" style="background-color:#1e293b;padding:10px 40px;">
                            <p style="margin:0;color:#94a3b8;font-size:12px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;">
                              &#128204; New Demo Request Received
                            </p>
                          </td>
                        </tr>

                        <!-- Body -->
                        <tr>
                          <td style="background-color:#ffffff;padding:36px 40px;">
                            <p style="margin:0 0 8px;font-size:16px;color:#111827;">A new school has requested a demo.</p>
                            <p style="margin:0 0 28px;font-size:13px;color:#6b7280;">Please review the details below and follow up at your earliest convenience.</p>

                            <!-- Details table -->
                            <table width="100%%" cellpadding="0" cellspacing="0" style="margin-bottom:28px;border:1px solid #e2e8f0;border-radius:12px;overflow:hidden;">
                              <tr style="background-color:#f8fafc;">
                                <td colspan="2" style="padding:14px 20px;font-size:11px;font-weight:700;color:#475569;letter-spacing:1.2px;text-transform:uppercase;border-bottom:1px solid #e2e8f0;">
                                  Request Details
                                </td>
                              </tr>
                              <tr>
                                <td style="padding:12px 20px;font-size:13px;color:#64748b;font-weight:600;border-bottom:1px solid #f1f5f9;width:38%%;">School Name</td>
                                <td style="padding:12px 20px;font-size:13px;color:#111827;font-weight:700;border-bottom:1px solid #f1f5f9;">%s</td>
                              </tr>
                              <tr>
                                <td style="padding:12px 20px;font-size:13px;color:#64748b;font-weight:600;border-bottom:1px solid #f1f5f9;">Contact Person</td>
                                <td style="padding:12px 20px;font-size:13px;color:#111827;border-bottom:1px solid #f1f5f9;">%s</td>
                              </tr>
                              <tr style="background-color:#f8fafc;">
                                <td style="padding:12px 20px;font-size:13px;color:#64748b;font-weight:600;border-bottom:1px solid #f1f5f9;">Email</td>
                                <td style="padding:12px 20px;font-size:13px;color:#2563eb;border-bottom:1px solid #f1f5f9;">%s</td>
                              </tr>
                              <tr>
                                <td style="padding:12px 20px;font-size:13px;color:#64748b;font-weight:600;border-bottom:1px solid #f1f5f9;">Phone</td>
                                <td style="padding:12px 20px;font-size:13px;color:#111827;border-bottom:1px solid #f1f5f9;">%s</td>
                              </tr>
                              <tr style="background-color:#f8fafc;">
                                <td style="padding:12px 20px;font-size:13px;color:#64748b;font-weight:600;border-bottom:1px solid #f1f5f9;">No. of Students</td>
                                <td style="padding:12px 20px;font-size:13px;color:#111827;border-bottom:1px solid #f1f5f9;">%s</td>
                              </tr>
                              <tr>
                                <td style="padding:12px 20px;font-size:13px;color:#64748b;font-weight:600;border-bottom:1px solid #f1f5f9;">City</td>
                                <td style="padding:12px 20px;font-size:13px;color:#111827;border-bottom:1px solid #f1f5f9;">%s</td>
                              </tr>
                              <tr style="background-color:#f8fafc;">
                                <td style="padding:12px 20px;font-size:13px;color:#64748b;font-weight:600;vertical-align:top;">Message</td>
                                <td style="padding:12px 20px;font-size:13px;color:#374151;font-style:italic;">%s</td>
                              </tr>
                            </table>

                            <hr style="border:none;border-top:1px solid #f1f5f9;margin:0 0 24px;">
                            <p style="margin:0;font-size:13px;color:#6b7280;">
                              Please log in to the EduNexify admin portal to view and manage all demo requests.
                            </p>
                          </td>
                        </tr>

                        <!-- Footer -->
                        <tr>
                          <td align="center" style="background-color:#0f172a;border-radius:0 0 16px 16px;padding:20px 40px;">
                            <p style="margin:0 0 4px;font-size:12px;color:rgba(255,255,255,0.5);">This is an automated internal notification from EduNexify.</p>
                            <p style="margin:0;font-size:11px;color:rgba(255,255,255,0.3);">&copy; %d EduNexify. All rights reserved.</p>
                          </td>
                        </tr>

                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(dto.getSchoolName(), dto.getContactName(), dto.getEmail(),
                              dto.getPhone(), students, city, message, year);
    }
}
