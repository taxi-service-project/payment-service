package com.example.payment_service;

import com.example.payment_service.client.PricingServiceClient;
import com.example.payment_service.client.UserServiceClient;
import com.example.payment_service.client.VirtualPGClient;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"trip_events", "payment_events"})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
class PaymentEventConsumerIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired
    private PaymentRepository paymentRepository;

    @MockBean
    private PricingServiceClient pricingServiceClient;
    @MockBean
    private UserServiceClient userServiceClient;
    @MockBean
    private PaymentKafkaProducer kafkaProducer;

    @MockBean
    private VirtualPGClient virtualPGClient;

    @AfterEach
    void tearDown() {
        paymentRepository.deleteAll();
    }

    @Test
    @DisplayName("TripCompleted 이벤트를 수신하면, 외부 서비스 호출 및 결제 성공 후 PaymentCompleted 이벤트를 발행한다")
    void handleTripCompletedEvent_Success() throws InterruptedException {
        // given
        String tripId = "024c3b55-8a7e-4b68-a364-6b45a1953b5b";
        String userId = "a2a2122c-29d9-4769-9412-a3e34a1b9b9a";
        TripCompletedEvent event = new TripCompletedEvent(tripId, userId, 5000, 1200, LocalDateTime.now());

        when(pricingServiceClient.calculateFare(any(), any(), any(), any()))
                .thenReturn(Mono.just(new PricingServiceClient.FareResponse(15000)));
        when(userServiceClient.getUserInfoForPayment(userId))
                .thenReturn(Mono.just(new UserServiceClient.UserInfoForPaymentResponse(userId, "test-user", "test@email.com", "pm_test", "bk_test")));

        doNothing().when(virtualPGClient).processPayment();

        // when
        kafkaTemplate.send("trip_events", event);

        // then
        ArgumentCaptor<PaymentCompletedEvent> eventCaptor = ArgumentCaptor.forClass(PaymentCompletedEvent.class);
        verify(kafkaProducer, timeout(5000)).sendPaymentCompletedEvent(eventCaptor.capture());

        PaymentCompletedEvent capturedEvent = eventCaptor.getValue();
        assertThat(capturedEvent.tripId()).isEqualTo(tripId);
        assertThat(capturedEvent.fare()).isEqualTo(15000);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Payment payment = paymentRepository.findByTripId(tripId).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
            assertThat(payment.getAmount()).isEqualTo(15000);
        });
    }
}