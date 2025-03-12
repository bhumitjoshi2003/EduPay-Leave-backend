//package com.indraacademy.ias_management.controller;
//
//import org.springframework.security.core.annotation.AuthenticationPrincipal;
//import org.springframework.security.oauth2.jwt.Jwt;
//import org.springframework.web.bind.annotation.CrossOrigin;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RestController;
//
//@RestController
//@RequestMapping("")
//@CrossOrigin(origins = "http://localhost:4200")
//public class StudentController {
//
//    @GetMapping("/message")
//    public String getStudentMessage() {
//        return "HARIBOL, I am a student.";
//    }
//
//    @GetMapping("/student/details")
//    public String getStudentDetails(@AuthenticationPrincipal Jwt jwt) {
//        return "Student: " + jwt.getClaimAsString("preferred_username");
//    }
//}