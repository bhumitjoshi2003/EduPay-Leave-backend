package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.NotificationChannelDTO;
import com.indraacademy.ias_management.entity.NotificationChannel;
import com.indraacademy.ias_management.repository.NotificationChannelRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class NotificationChannelService {

    private static final Logger log = LoggerFactory.getLogger(NotificationChannelService.class);

    @Autowired private NotificationChannelRepository repository;
    @Autowired private SecurityUtil securityUtil;

    public List<NotificationChannelDTO> getChannelsForSchool() {
        Long schoolId = securityUtil.getSchoolId();
        return repository.findBySchoolId(schoolId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public List<NotificationChannelDTO> getEnabledChannels() {
        Long schoolId = securityUtil.getSchoolId();
        return repository.findBySchoolIdAndEnabledTrue(schoolId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public NotificationChannelDTO upsertChannel(NotificationChannelDTO dto) {
        Long schoolId = securityUtil.getSchoolId();
        NotificationChannel channel = repository
                .findBySchoolIdAndChannelType(schoolId, dto.getChannelType())
                .orElseGet(() -> {
                    NotificationChannel nc = new NotificationChannel();
                    nc.setSchoolId(schoolId);
                    nc.setChannelType(dto.getChannelType());
                    return nc;
                });

        channel.setEnabled(dto.isEnabled());
        channel.setConfigJson(dto.getConfigJson());
        NotificationChannel saved = repository.save(channel);
        log.info("Upserted notification channel {} for school {}", dto.getChannelType(), schoolId);
        return toDTO(saved);
    }

    @Transactional
    public void seedDefaultsForSchool(Long schoolId) {
        if (repository.findBySchoolId(schoolId).isEmpty()) {
            NotificationChannel push = new NotificationChannel();
            push.setSchoolId(schoolId);
            push.setChannelType("PUSH");
            push.setEnabled(true);
            repository.save(push);
            log.info("Seeded default PUSH channel for school {}", schoolId);
        }
    }

    private NotificationChannelDTO toDTO(NotificationChannel entity) {
        return new NotificationChannelDTO(
                entity.getId(),
                entity.getChannelType(),
                entity.isEnabled(),
                entity.getConfigJson()
        );
    }
}
