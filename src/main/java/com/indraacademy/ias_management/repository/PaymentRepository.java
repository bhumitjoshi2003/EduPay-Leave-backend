package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Payment;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByStudentId(String studentId, Sort sort);

    Payment findByPaymentId(String paymentId);
}