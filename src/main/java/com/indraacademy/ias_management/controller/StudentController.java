package com.indraacademy.ias_management.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/student")
public class StudentController {

    @GetMapping("/detail")
    public String getStudentDetails() {
        return "HARIBOL, I am a student.";
    }

    @GetMapping("/details")
    public String getStudentDetails(@AuthenticationPrincipal Jwt jwt) {
        return "Student: " + jwt.getClaimAsString("preferred_username");
    }
}