package com.indraacademy.ias_management.dto;

import java.time.LocalDateTime;

public class PaymentHistoryDTO {
    private String paymentId;
    private String orderId;
    private int amount;
    private LocalDateTime paymentDate;
    private String status;


    public PaymentHistoryDTO() {
    }

    public PaymentHistoryDTO(String paymentId, String orderId, int amount, LocalDateTime paymentDate, String status) {
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.amount = amount;
        this.paymentDate = paymentDate;
        this.status = status;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public LocalDateTime getPaymentDate() {
        return paymentDate;
    }

    public void setPaymentDate(LocalDateTime paymentDate) {
        this.paymentDate = paymentDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
