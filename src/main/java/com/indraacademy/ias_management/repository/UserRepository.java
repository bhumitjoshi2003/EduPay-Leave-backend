package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByStudentId(String studentId);
    Optional<User> findByEmail(String email);
    Optional<User> findByStudentIdOrEmail(String identifier, String identifier2);
}