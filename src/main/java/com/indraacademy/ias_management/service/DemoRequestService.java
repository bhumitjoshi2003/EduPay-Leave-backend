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

    @Value("${app.admin.email:admin@edunexify.co.in}")
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
            String body = buildEmailBody(dto);
            emailService.sendEmail(adminEmail, subject, body);
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

    private String buildEmailBody(DemoRequestDTO dto) {
        return "A new demo request has been received.\n\n" +
                "School Name      : " + dto.getSchoolName() + "\n" +
                "Contact Name     : " + dto.getContactName() + "\n" +
                "Email            : " + dto.getEmail() + "\n" +
                "Phone            : " + dto.getPhone() + "\n" +
                "Number of Students: " + (dto.getNumberOfStudents() != null ? dto.getNumberOfStudents() : "N/A") + "\n" +
                "City             : " + (dto.getCity() != null ? dto.getCity() : "N/A") + "\n" +
                "Message          : " + (dto.getMessage() != null ? dto.getMessage() : "N/A") + "\n";
    }
}
