package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Optional;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    @Autowired
    private UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String userId) throws UsernameNotFoundException {
        User user = userRepository.findByUserId(userId).orElseThrow(() -> new UsernameNotFoundException("User not found"));
        return new org.springframework.security.core.userdetails.User(user.getUserId(), user.getPassword(), new ArrayList<>());
    }

    public Optional<User> findUserByUserId(String userId) {
        return userRepository.findUserByUserId(userId);
    }

    public void save(User user) {
        userRepository.save(user);
    }
}