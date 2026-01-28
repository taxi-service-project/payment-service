package com.example.payment_service.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Slf4j
public class VirtualPGClient {

    public String processPayment() {
        simulateNetworkLatency();

        // 20% 확률로 결제 실패 상황 연출
        if (Math.random() < 0.2) {
            log.warn("❌ [Virtual-PG] 결제 승인 거절 (잔액 부족/통신 오류 시뮬레이션)");
            throw new RuntimeException("카드사 통신 오류 (가상 시나리오)");
        }

        String pgTransactionId = "tx_" + UUID.randomUUID().toString().substring(0, 8);
        log.info("✅ [Virtual-PG] 결제 승인 성공. TxID: {}", pgTransactionId);

        return pgTransactionId;
    }

    public void cancelPayment(String pgTransactionId) {
        simulateNetworkLatency();
        log.info("🔄 [Virtual-PG] 결제 취소(환불) 승인 완료. 대상 TxID: {}", pgTransactionId);
    }

    private void simulateNetworkLatency() {
        try {
            Thread.sleep(500); // 0.5초 지연
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}