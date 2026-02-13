package com.example.payment_service.kafka;

import com.example.payment_service.kafka.dto.TripCompletedEvent;
import com.example.payment_service.service.PaymentService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.CommandLineRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.util.retry.Retry;

import java.time.Duration;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentReactiveConsumer implements CommandLineRunner, DisposableBean {

    private final KafkaReceiver<String, String> kafkaReceiver;
    private final PaymentService paymentService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private Disposable subscription;

    @Override
    public void run(String... args) {
        log.info("🚀 [Reactive Kafka] 운행 종료 이벤트 리스너 시작");

        this.subscription = kafkaReceiver.receive()
                                         .flatMap(record -> {
                                             return processRecord(record)
                                                     // 성공하든(비즈니스 완료), 실패해서 DLT를 갔든(handleFailure 완료), 파싱 에러든
                                                     // 여기까지 오면 이 메시지에 대한 처리는 끝난 것이므로 무조건 커밋(Ack)합니다.
                                                     .doOnSuccess(v -> record.receiverOffset().acknowledge());
                                         }, 32)
                                         .subscribe(
                                                 null,
                                                 e -> log.error("🔥 [Fatal Error] Consumer 구독이 비정상 종료되었습니다. 앱 재시작이 필요합니다.", e)
                                         );
    }

    private Mono<Void> processRecord(ReceiverRecord<String, String> record) {
        TripCompletedEvent event;

        try {
            String jsonPayload = record.value();
            event = objectMapper.readValue(jsonPayload, TripCompletedEvent.class);
        } catch (JsonProcessingException e) {
            // JSON 형식이 아니거나 파싱 불가능한 데이터가 온 경우
            log.error("🗑️ [Bad Request] JSON 파싱 실패. 메시지를 스킵합니다. Payload: {}", record.value());
            // 에러를 던지지 않고 빈 Mono를 리턴하여 Ack를 유도 (스트림 중단 방지)
            return Mono.empty();
        }

        // 3. 비즈니스 로직 수행
        return paymentService.processPayment(event)
                          .then() // 결과값은 필요 없으니 Void로 변환
                          .doOnSubscribe(s -> log.debug("⚡ [Start] TripID={}", event.tripId()))

                          // 재시도 전략 (1초 간격, 최대 3회)
                          .retryWhen(Retry.backoff(3, Duration.ofMillis(1000))
                                          .transientErrors(true)
                                          .doBeforeRetry(signal -> log.warn("🔄 [Retry] ({}/3) Error: {}",
                                                  signal.totalRetries() + 1, signal.failure().getMessage())))

                          // 3번 다 실패하면 DLT 로직으로 넘어감
                          .onErrorResume(e -> handleFailure(record, e));
    }

    // DLT 전송 및 데이터 보존 로직
    private Mono<Void> handleFailure(ReceiverRecord<String, String> record, Throwable e) {
        String dltTopic = record.topic() + ".DLT";
        log.error("🚨 [Final Fail] 재시도 초과. DLT 전송 시도. Topic={}, Error={}", dltTopic, e.getMessage());

        // KafkaTemplate의 Future(비동기)를 Mono(리액티브)로 변환하여 '기다림'을 구현
        return Mono.fromFuture(() -> kafkaTemplate.send(dltTopic, record.key(), record.value()))
                   .flatMap(sendResult -> {
                       log.info("[DLT Sent] DLT 전송 성공. Offset을 커밋합니다.");
                       return Mono.empty();
                   })
                   .onErrorResume(dltEx -> {
                       // 최후의 보루: DLT 브로커마저 죽었을 때
                       // 데이터 유실을 막기 위해 로그 파일에 Payload를 강제로 기록
                       log.error("[FATAL] DLT 전송 실패! 데이터 유실 방지용 로그 기록.\nKEY: {}\nPAYLOAD: {}\nERROR: {}",
                               record.key(), record.value(), dltEx.getMessage());

                       // 에러를 다시 던지지 않고 Mono.empty()를 반환해야
                       // 메인 흐름이 끊기지 않고 다음 메시지(오프셋 커밋)로 넘어갑니다.
                       return Mono.empty();
                   })
                   .then(); // Mono<SendResult> -> Mono<Void>
    }

    // 앱 종료 시 카프카 연결을 깔끔하게 끊어줌
    @Override
    public void destroy() {
        if (subscription != null && !subscription.isDisposed()) {
            log.info("🛑 [Shutdown] Reactive Consumer 구독을 안전하게 종료합니다.");
            subscription.dispose();
        }
    }
}
