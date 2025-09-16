package com.example.payment_service.entity;

public enum PaymentStatus {
    REQUESTED, // 결제 요청됨
    COMPLETED, // 결제 완료
    FAILED     // 결제 실패
}