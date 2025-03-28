package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.entity.Leave;
import com.indraacademy.ias_management.service.LeaveService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/leaves")
@CrossOrigin(origins = "http://localhost:4200")
public class LeaveController {

    @Autowired
    private LeaveService leaveService;

    @PostMapping("/apply-leave")
    public ResponseEntity<String> applyLeave(@RequestBody Leave leave) {
        leaveService.applyLeave(leave);
        return ResponseEntity.ok("Leave applied successfully");
    }

    @GetMapping("/date/{date}/class/{className}")
        public ResponseEntity<List<String>> getLeavesByDateAndClass(@PathVariable String date, @PathVariable String className) {
        List<String> leaves = leaveService.getLeavesByDateAndClass(date, className);
        return new ResponseEntity<>(leaves, HttpStatus.OK);
    }

    @DeleteMapping("/delete/{studentId}/{leaveDate}")
    public ResponseEntity<String> deleteLeave(@PathVariable String studentId, @PathVariable String leaveDate) {
        leaveService.deleteLeave(studentId, leaveDate);
        return new ResponseEntity<>("Leave deleted successfully", HttpStatus.OK);
    }

    @GetMapping("/{studentId}")
    public ResponseEntity<List<Leave>> getLeavesByStudentId(@PathVariable String studentId){
        return ResponseEntity.ok(leaveService.getLeavesByStudentId(studentId));
    }
}
