package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.PaymentHistoryDTO;
import com.indraacademy.ias_management.dto.PaymentResponseDTO;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.repository.PaymentRepository;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class PaymentService {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ModelMapper modelMapper;

    public List<PaymentHistoryDTO> getPaymentHistory(String studentId) {
        List<Payment> payments = paymentRepository.findByStudentIdOrderByPaymentDateDesc(studentId);
        return payments.stream()
                .map(payment -> modelMapper.map(payment, PaymentHistoryDTO.class))
                .collect(Collectors.toList());
    }

    public PaymentResponseDTO getPaymentHistoryDetails(String paymentId) {
        Payment payment = paymentRepository.findByPaymentId(paymentId);
        return new PaymentResponseDTO(
                payment.getStudentId(),
                payment.getStudentName(),
                payment.getClassName(),
                payment.getSession(),
                payment.getMonth(),
                payment.getAmount(),
                payment.getPaymentId(),
                payment.getOrderId(),
                payment.getPaymentDate(),
                payment.getStatus(),
                payment.getBusFee(),
                payment.getTuitionFee(),
                payment.getAnnualCharges(),
                payment.getLabCharges(),
                payment.getEcaProject(),
                payment.getExaminationFee(),
                payment.getAmountPaid()
        );
    }

    public List<PaymentHistoryDTO> getAllPaymentHistory() {
        List<Payment> allPayments = paymentRepository.findAllByOrderByPaymentDateDesc(); // Example - adjust query as needed
        return allPayments.stream()
                .map(payment -> modelMapper.map(payment, PaymentHistoryDTO.class))
                .collect(Collectors.toList());
    }

    public List<PaymentHistoryDTO> getPaymentHistoryByClass(String className) {
        List<Payment> classPayments = paymentRepository.findByClassNameOrderByPaymentDateDesc(className);
        return classPayments.stream()
                .map(payment -> modelMapper.map(payment, PaymentHistoryDTO.class))
                .collect(Collectors.toList());
    }
}
