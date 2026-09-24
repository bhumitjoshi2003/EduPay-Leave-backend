package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.HomeworkClassworkAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HomeworkClassworkAttachmentRepository extends JpaRepository<HomeworkClassworkAttachment, Long> {

    boolean existsByObjectKey(String objectKey);
}
