package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Sibling;
import com.indraacademy.ias_management.repository.SiblingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class SiblingService {

    @Autowired
    private SiblingRepository siblingRepository;

    public List<Sibling> getSiblingsByStudentId(String studentId) {
        return siblingRepository.findByStudentId(studentId);
    }

    public Sibling addSibling(Sibling sibling) {
        return siblingRepository.save(sibling);
    }

    public void deleteSibling(Long id) {
        siblingRepository.deleteById(id);
    }

    public Optional<Sibling> getSibling(Long id){
        return siblingRepository.findById(id);
    }
}