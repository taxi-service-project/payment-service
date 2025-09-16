package com.example.payment_service;

import com.example.payment_service.client.PricingServiceClient;
import com.example.payment_service.client.UserServiceClient;
import com.example.payment_service.entity.Payment;
import com.example.payment_service.entity.PaymentStatus;
import com.example.payment_service.kafka.PaymentKafkaProducer;
import com.example.payment_service.kafka.dto.PaymentCompletedEvent;
import com.example.payment_service.kafka.dto.TripCompletedEvent;
import com.example.payment_service.repository.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"trip_events", "payment_events"},
        brokerProperties = {"listeners=PLAINTEXT://localhost:9092", "port=9092"})
@TestPropertySource(properties = {
        "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
        "spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer",
        "spring.kafka.consumer.properties.spring.json.trusted.packages=*"
})
class PaymentEventConsumerIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired
    private PaymentRepository paymentRepository;

    @MockitoBean
    private PricingServiceClient pricingServiceClient;
    @MockitoBean
    private UserServiceClient userServiceClient;
    @MockitoBean
    private PaymentKafkaProducer kafkaProducer;

    @AfterEach
    void tearDown() {
        paymentRepository.deleteAll();
    }

    @Test
    @DisplayName("TripCompleted 이벤트를 수신하면, 외부 서비스 호출 및 가상 결제 후 PaymentCompleted 이벤트를 발행한다")
    void handleTripCompletedEvent_Success() {
        // given: 테스트 시나리오 설정
        TripCompletedEvent event = new TripCompletedEvent(1L, 101L, 5000, 1200, LocalDateTime.now());

        // 가격 서비스는 15000원을 반환하도록 설정
        when(pricingServiceClient.calculateFare(any(), any(), any(), any()))
                .thenReturn(Mono.just(new PricingServiceClient.FareResponse(15000)));
        // 사용자 서비스는 결제수단 ID 1L을 반환하도록 설정
        when(userServiceClient.getDefaultPaymentMethod(101L))
                .thenReturn(Mono.just(new UserServiceClient.PaymentMethodResponse(1L)));

        // when: 테스트의 시작점. trip_events 토픽으로 메시지를 발행
        kafkaTemplate.send("trip_events", event);

        // then: 최종 결과 검증
        // 1. 최종적으로 PaymentCompleted 이벤트가 발행되는지 검증
        ArgumentCaptor<PaymentCompletedEvent> eventCaptor = ArgumentCaptor.forClass(PaymentCompletedEvent.class);
        // 비동기 처리(2초 지연 포함)를 기다리기 위해 timeout 설정
        verify(kafkaProducer, timeout(5000)).sendPaymentCompletedEvent(eventCaptor.capture());

        PaymentCompletedEvent capturedEvent = eventCaptor.getValue();
        assertThat(capturedEvent.tripId()).isEqualTo(1L);
        assertThat(capturedEvent.fare()).isEqualTo(15000);

        // 2. DB에 Payment 데이터가 최종적으로 COMPLETED 상태로 저장되었는지 검증
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Payment> payments = paymentRepository.findAll();
            assertThat(payments).hasSize(1);
            Payment payment = payments.get(0);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
            assertThat(payment.getAmount()).isEqualTo(15000);
            assertThat(payment.getPgTransactionId()).isNotNull();
        });
    }
}