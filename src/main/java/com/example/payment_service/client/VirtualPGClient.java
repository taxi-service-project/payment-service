package com.example.payment_service.client;

import org.springframework.stereotype.Component;

@Component
public class VirtualPGClient {

    public void processPayment() throws InterruptedException {
        Thread.sleep(1000); // 1초 지연 시뮬레이션
        if (Math.random() < 0.2) {
            throw new RuntimeException("카드사 통신 오류 (가상 시나리오)");
        }
    }
}