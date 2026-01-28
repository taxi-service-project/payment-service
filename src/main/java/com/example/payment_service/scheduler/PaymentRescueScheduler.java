package com.example.payment_service.scheduler;

import com.example.payment_service.client.VirtualPGClient;
import com.example.payment_service.entity.Payment;
import com.example.payment_service.entity.PaymentStatus;
import com.example.payment_service.kafka.dto.PaymentFailedEvent;
import com.example.payment_service.repository.PaymentRepository;
import com.example.payment_service.service.PaymentTransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentRescueScheduler {

    private final PaymentRepository paymentRepository;
    private final VirtualPGClient virtualPGClient;
    private final PaymentTransactionService paymentTransactionService;

    // 1분마다 실행
    @Scheduled(fixedDelay = 60000)
    @SchedulerLock(name = "Payment_rescueZombies", lockAtLeastFor = "PT30S", lockAtMostFor = "PT50S")
    public void rescueZombies() {
        // 기준: 10분 넘게 PROCESSING 상태인 건 (서버 다운 의심)
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(10);

        List<Payment> zombies = paymentRepository.findByStatusAndUpdatedAtBefore(PaymentStatus.PROCESSING, cutoff);

        if (zombies.isEmpty()) return;

        log.warn("🧟‍♂️ 발견된 좀비 결제(처리 중단) 건수: {}건. 구조 작업을 시작합니다.", zombies.size());

        for (Payment zombie : zombies) {
            rescueSingleZombie(zombie);
        }
    }

    private void rescueSingleZombie(Payment p) {
        try {
            log.info("🚨 좀비 데이터 구조 시작. TripID: {}, PaymentID: {}", p.getTripId(), p.getId());

            String pgTxId = p.getPgTransactionId();
            boolean needRefund = false;

            // Case 1: PG 승인 번호가 아예 없음 (PG 호출 전 or 호출 중 서버 사망)
            if (pgTxId == null) {
                log.info("👉 PG 승인 번호 없음. PG 호출 전 사망으로 판단. 즉시 실패 처리.");
                needRefund = false;
            }
            // Case 2: PG 승인 번호가 있음 (PG 성공 후 DB 저장 전 사망)
            else {
                // PG사 조회 (확실하게 하려면 조회 후 상태보고 결정)
                String status = virtualPGClient.getStatus(pgTxId);
                if ("PAID".equals(status)) {
                    log.info("👉 PG사 확인 결과: 결제 완료 상태임. 환불 필요.");
                    needRefund = true;
                } else {
                    log.info("👉 PG사 확인 결과: 이미 취소됐거나 없음. 환불 불필요.");
                    needRefund = false;
                }
            }

            // 환불이 필요하면 실행 (망취소)
            if (needRefund && pgTxId != null) {
                virtualPGClient.cancelPayment(pgTxId);
                log.info("✅ 강제 환불 성공.");
            }

            // DB 상태 (FAILED) + 이벤트 발행
            // 이 메서드는 REQUIRES_NEW 트랜잭션으로 돌므로 안전함
            PaymentFailedEvent event = new PaymentFailedEvent(p.getTripId(), "서버 장애로 인한 처리 누락 - 자동 환불 처리됨");
            paymentTransactionService.failPaymentWithOutbox(p.getId(), "좀비 데이터 자동 복구", event);

            log.info("✅ 좀비 데이터 복구 완료 (FAILED 처리).");

        } catch (Exception e) {
            log.error("💀 좀비 구조 실패 (Double Fault). 수기 확인 필요. ID: {}", p.getId(), e);
            // 최후의 수단: UNKNOWN 마킹 (운영자 개입 요청)
            paymentTransactionService.markAsUnknown(p.getId(), p.getPgTransactionId());
        }
    }
}