package com.example.payment_service.entity;

public enum FailedEventStatus {
    PENDING,    // 대기 (처리 필요)
    RESOLVED,   // 해결됨 (재발행 성공)
    IGNORED     // 무시 (데이터 오류 등으로 폐기)
}