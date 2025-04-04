package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.util.JwtUtil; //add this import
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.Date;
import java.util.Optional;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "http://localhost:4200")
public class AuthController {
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JavaMailSender mailSender;
    @Autowired private JwtUtil jwtUtil; //add this autowired

    @Value("${jwt.secret-key}")
    private String secretKey;

    @GetMapping("/hari")
    public String message() {
        return "HARIBOL";
    }

    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody User user) {
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        userRepository.save(user);
        return ResponseEntity.ok("User registered successfully");
    }

    @PostMapping("/login")
    public ResponseEntity<?> loginUser(@RequestBody User user) {
        Optional<User> foundUser = userRepository.findByStudentId(user.getStudentId());
        if (foundUser.isPresent() && passwordEncoder.matches(user.getPassword(), foundUser.get().getPassword())) {
            String token = Jwts.builder()
                    .setSubject(foundUser.get().getStudentId())
                    .claim("role", foundUser.get().getRole())
                    .claim("studentId", foundUser.get().getStudentId())
                    .setIssuedAt(new Date())
                    .setExpiration(new Date(System.currentTimeMillis() + 3600000))
                    .signWith(jwtUtil.getPrivateKey(), SignatureAlgorithm.RS256)
                    .compact();
            return ResponseEntity.ok(token);
        }
        return ResponseEntity.badRequest().body("Invalid credentials");
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody User request) {
        Optional<User> foundUser = userRepository.findByStudentIdOrEmail(request.getStudentId(),request.getEmail());
        if (foundUser.isPresent()) {
            String resetToken = Jwts.builder()
                    .setSubject(foundUser.get().getStudentId())
                    .setIssuedAt(new Date())
                    .setExpiration(new Date(System.currentTimeMillis() + 3600000)) // 1 hour
                    .signWith(jwtUtil.getPrivateKey(), SignatureAlgorithm.RS256) // Corrected line
                    .compact();

            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(foundUser.get().getEmail());
            message.setSubject("Password Reset Link");
            message.setText("Click this link to reset your password: http://localhost:4200/reset-password?token=" + resetToken);
            mailSender.send(message);

            return ResponseEntity.ok("Password reset link sent to your email.");
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody User request) {
        try {
            String studentId = Jwts.parser().setSigningKey(jwtUtil.getPublicKey()).parseClaimsJws(request.getPassword()).getBody().getSubject();
            User user = userRepository.findByStudentId(studentId).get();
            user.setPassword(passwordEncoder.encode(request.getEmail())); // email field is used for new password.
            userRepository.save(user);
            return ResponseEntity.ok("Password reset successfully.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid or expired token.");
        }
    }
}