package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.DemoRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DemoRequestRepository extends JpaRepository<DemoRequest, Long> {

    List<DemoRequest> findAllByOrderByRequestedAtDesc();
}
