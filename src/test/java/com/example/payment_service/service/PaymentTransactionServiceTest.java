package com.example.payment_service.service;

import com.example.payment_service.entity.Payment;
import com.example.payment_service.entity.PaymentStatus;
import com.example.payment_service.kafka.dto.TripCompletedEvent;
import com.example.payment_service.repository.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class PaymentTransactionServiceTest {

    @InjectMocks
    private PaymentTransactionService transactionService;

    @Mock
    private PaymentRepository paymentRepository;

    @Test
    @DisplayName("결제 요청 시 REQUESTED 상태의 Payment 엔티티가 저장되어야 한다")
    void createPendingPayment_Success() {
        // Given
        TripCompletedEvent event = new TripCompletedEvent(
                "trip-123", "user-1", 1000, 500, LocalDateTime.now()
        );
        String userId = "user-1";
        String methodId = "card-1";
        Integer fare = 5000;

        given(paymentRepository.save(any(Payment.class))).willAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            ReflectionTestUtils.setField(p, "id", 1L);
            return p;
        });

        // When
        Payment result = transactionService.createPendingPayment(event, userId, methodId, fare);

        // Then
        then(paymentRepository).should(times(1)).save(any(Payment.class));

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        assertThat(result.getAmount()).isEqualTo(5000);
        assertThat(result.getTripId()).isEqualTo("trip-123");
        assertThat(result.getPaymentId()).isNotNull();
    }

    @Test
    @DisplayName("결제 성공 처리 시 상태가 COMPLETED로 변경되고 PG TxId가 기록되어야 한다")
    void updatePaymentStatus_Success() {
        // Given
        Long dbId = 1L;
        Payment payment = Payment.builder()
                                 .tripId("trip-1")
                                 .userId("user-1")
                                 .paymentMethodId("card-1")
                                 .amount(5000)
                                 .build();

        // 테스트를 위해 ID 강제 주입
        ReflectionTestUtils.setField(payment, "id", dbId);

        given(paymentRepository.findById(dbId)).willReturn(Optional.of(payment));
        given(paymentRepository.save(any(Payment.class))).willReturn(payment);

        // When
        Payment updated = transactionService.updatePaymentStatus(dbId, true, null);

        // Then
        assertThat(updated.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(updated.getPgTransactionId()).startsWith("dummy-tx-");
        assertThat(updated.getCompletedAt()).isNotNull();

        then(paymentRepository).should().save(payment);
    }

    @Test
    @DisplayName("결제 실패 처리 시 상태가 FAILED로 변경되어야 한다")
    void updatePaymentStatus_Fail() {
        // Given
        Long dbId = 1L;
        Payment payment = Payment.builder()
                                 .tripId("trip-1")
                                 .userId("user-1")
                                 .paymentMethodId("card-1")
                                 .amount(5000)
                                 .build();

        ReflectionTestUtils.setField(payment, "id", dbId);

        given(paymentRepository.findById(dbId)).willReturn(Optional.of(payment));
        given(paymentRepository.save(any(Payment.class))).willReturn(payment);

        // When
        Payment updated = transactionService.updatePaymentStatus(dbId, false, "잔액 부족");

        // Then
        assertThat(updated.getStatus()).isEqualTo(PaymentStatus.FAILED);
        then(paymentRepository).should().save(payment);
    }
}