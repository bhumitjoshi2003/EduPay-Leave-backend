package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Collections;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(UserDetailsServiceImpl.class);

    @Autowired
    private UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String userId) throws UsernameNotFoundException {
        if (userId == null || userId.trim().isEmpty()) {
            log.warn("Attempted to load user with null/empty userId.");
            throw new UsernameNotFoundException("User ID must be provided.");
        }
        log.info("Attempting to load user details for ID: {}", userId);

        User user;
        try {
            user = userRepository.findByUserId(userId).orElseThrow(() -> new UsernameNotFoundException("User not found with ID: " + userId));
        } catch (DataAccessException e) {
            log.error("Data access error while loading user by ID: {}", userId, e);
            throw new UsernameNotFoundException("Failed to load user details due to a database issue.", e);
        }

        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole()));

        return new org.springframework.security.core.userdetails.User(user.getUserId(), user.getPassword(), authorities);
    }

    public Optional<User> findUserByUserId(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return Optional.empty();
        }
        try {
            return userRepository.findUserByUserId(userId);
        } catch (DataAccessException e) {
            log.error("Data access error finding user by ID: {}", userId, e);
            throw new RuntimeException("Failed to retrieve user data.", e);
        }
    }

    public String findUserRole(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            log.warn("Attempted to find user role with null/empty userId.");
            throw new IllegalArgumentException("User ID must be provided.");
        }
        log.info("Finding role for user ID: {}", userId);

        try {
            Optional<User> userOptional = userRepository.findUserByUserId(userId);
            User user = userOptional.orElseThrow(() -> {
                log.warn("User not found to determine role for ID: {}", userId);
                return new UsernameNotFoundException("User not found to determine role.");
            });
            return user.getRole();
        } catch (DataAccessException e) {
            log.error("Data access error finding user role for ID: {}", userId, e);
            throw new RuntimeException("Failed to retrieve user role due to a database issue.", e);
        }
    }

    public void save(User user) {
        if (user == null || user.getUserId() == null) {
            log.warn("Attempted to save null User object or object with null ID.");
            throw new IllegalArgumentException("User object and ID must be provided for saving.");
        }
        log.info("Saving user details for ID: {}", user.getUserId());
        try {
            userRepository.save(user);
        } catch (DataAccessException e) {
            log.error("Data access error while saving user ID: {}", user.getUserId(), e);
            throw new RuntimeException("Failed to save user details due to a database issue.", e);
        }
    }
}