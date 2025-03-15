package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.entity.Sibling;
import com.indraacademy.ias_management.service.SiblingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/siblings")
@CrossOrigin(origins = "http://localhost:4200")
public class SiblingController {

    @Autowired
    private SiblingService siblingService;

    @GetMapping("/{studentId}")
    public ResponseEntity<List<Sibling>> getSiblingsByStudentId(
            @PathVariable String studentId) {
        return ResponseEntity.ok(siblingService.getSiblingsByStudentId(studentId));
    }

    @PostMapping("/")
    public ResponseEntity<Sibling> addSibling(@RequestBody Sibling sibling) {
        return ResponseEntity.ok(siblingService.addSibling(sibling));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSibling(@PathVariable Long id) {
        siblingService.deleteSibling(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Sibling> getSibling(@PathVariable Long id){
        Optional<Sibling> sibling = siblingService.getSibling(id);
        return sibling.map(ResponseEntity::ok).orElseGet(()->ResponseEntity.notFound().build());
    }
}