package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.repository.TeacherRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class TeacherService {

    @Autowired
    private TeacherRepository teacherRepository;

    public Optional<Teacher> getTeacher(String teacherId) {
        return teacherRepository.findById(teacherId);
    }

}