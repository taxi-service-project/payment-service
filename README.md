# MSA 기반 Taxi 호출 플랫폼 - Payment Service

Taxi 호출 플랫폼의 **결제 처리**를 담당하는 핵심 마이크로서비스입니다. Kafka로부터 `TripCompletedEvent`를 수신하면, 외부 서비스와 연동하여 요금을 계산하고 사용자 결제 정보를 조회한 후, 가상 PG(Payment Gateway)를 통해 결제를 시도합니다. 최종 결제 성공/실패 결과를 다시 Kafka 이벤트로 발행합니다.

## 주요 기능 및 워크플로우

1.  **운행 완료 이벤트 수신 (`PaymentEventConsumer`):**
    * `trip_events` Kafka 토픽을 구독하여 `TripCompletedEvent`를 수신합니다.
    * Reactive 파이프라인을 사용하여 비동기적으로 결제 처리를 시작합니다.
    * 메시지 처리 성공 시 오프셋을 수동으로 커밋합니다. 실패 시 Kafka의 `DefaultErrorHandler`가 재시도를 처리합니다.
2.  **요금 계산 및 사용자 정보 조회 (`PaymentService`):**
    * **Pricing Service** (`PricingServiceClient`)를 호출하여 운행 거리/시간 기반의 요금을 조회합니다.
    * **User Service** (`UserServiceClient`)를 호출하여 결제에 필요한 사용자 정보를 조회합니다.
    * `Mono.zip`을 사용하여 두 외부 호출 결과를 조합합니다.
3.  **결제 시도 및 상태 저장 (`PaymentTransactionService`):**
    * 결제 요청 정보를 DB(Payment 테이블)에 `PENDING` 상태로 우선 저장합니다.
    * **Virtual Payment Gateway**를 호출하여 실제 결제를 시도합니다.
    * 결과에 따라 Payment 상태를 `COMPLETED` 또는 `FAILED`로 업데이트하고 DB에 저장합니다.
4.  **결제 결과 이벤트 발행 (`PaymentKafkaProducer`):**
    * 결제가 성공하면 `PaymentCompletedEvent`를 `payment_events` 토픽으로 발행합니다.
    * 결제 처리 중 최종 오류 발생 시 `PaymentFailedEvent`를 `payment_events` 토픽으로 발행합니다.
5.  **최종 실패 메시지 처리 (`PaymentEventDltConsumer`):**
    * `trip_events.DLT` 토픽을 구독하여, 여러 번의 재시도 후에도 최종 실패한 `TripCompletedEvent` 메시지를 수신합니다.
    * 실패한 메시지의 내용을 DB(FailedEvent 테이블)에 영구 저장하여 추후 분석 및 수동 처리가 가능하도록 합니다.

## 기술 스택 (Technology Stack)

* **Language & Framework:** Java, Spring Boot, **Spring WebFlux/Reactor**
* **Messaging:** Spring Kafka
