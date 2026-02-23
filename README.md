# 💰 Payment Service

> **결제 승인 처리 및 정산 데이터를 관리하며, 외부 PG사(가상)와의 안전한 통신을 담당하는 핵심 마이크로서비스입니다.**

## 🛠 Tech Stack
| Category | Technology                                |
| :--- |:------------------------------------------|
| **Language** | **Java 17** |
| **Framework** | Spring Boot (WebFlux + MVC Hybrid)        |
| **Database** | MySQL (JPA)                               |
| **Messaging** | Apache Kafka (Reactive Consumer, Outbox)  |
| **Others** | ShedLock (분산 락 스케줄러)                    |

## 📡 API Specification

### Internal API (Microservice Communication)
| Method | URI | Description |
| :--- | :--- | :--- |
| `GET` | `/internal/api/payments?tripId={tripId}` | **[내부망]** 특정 여정의 결제 내역 단건 조회 |

### Admin API (DLT Management)
| Method | URI | Description |
| :--- | :--- | :--- |
| `POST` | `/api/payments/admin/failed-events/retry-all` | **[관리자]** 실패한 결제 큐(DLT) 벌크 재시도 |
| `POST` | `/api/payments/admin/failed-events/{eventId}/ignore` | **[관리자]** 복구 불가능한 메시지 영구 폐기 |

*💡 결제 프로세스의 시작은 REST API가 아닌 `TripCompletedEvent` (Kafka) 수신을 통해 비동기로 이루어집니다.*

## 🚀 Key Improvements (핵심 기술적 개선)

### 1. 분산 환경의 동시성 제어 (Optimistic Locking & Skip Locked)
* **결제 중복 방지 (선점 로직):** 다중 서버 환경에서 동일한 결제 이벤트가 동시에 처리되는 것을 막기 위해 DB 상태 기반의 원자적 업데이트(`UPDATE ... WHERE status = 'REQUESTED'`)를 활용하여 결제 처리 권한을 안전하게 선점합니다.
* **Outbox 폴링 최적화:** 카프카 발행을 대기하는 이벤트를 스케줄러가 읽어갈 때, `FOR UPDATE SKIP LOCKED`를 적용하여 여러 스레드나 서버가 경합 없이 각자의 이벤트 행(Row)만 빠르게 가져가도록 최적화했습니다.

### 2. 물리적 트랜잭션 분리 (Avoiding Long Transactions)
* 외부 PG사 API 호출(Network I/O)이 DB 트랜잭션을 길게 물고 있어 커넥션 풀이 고갈되는 현상을 방지하고자 클래스를 분리(`PaymentTransactionService`)하고 `@Transactional(propagation = Propagation.REQUIRES_NEW)`를 적용했습니다.
* **흐름:** [DB 생성/커밋] → [트랜잭션 없이 PG 호출] → [새로운 DB 트랜잭션으로 결과 업데이트]

### 3. 강력한 장애 복구 메커니즘 (Resilience & Auto-Recovery)
* **Double Fault 방어 (자동 환불):** PG 결제 승인 후 DB 상태 저장 과정에서 서버 장애가 발생하면, `catch` 블록에서 즉시 PG사 결제 취소 API를 호출하여 고객의 돈이 공중에 뜨는 현상을 방어합니다.
* **좀비 결제 구조대 (Rescue Scheduler):** 결제 처리 중 서버 자체가 다운되어 `PROCESSING` 상태로 영원히 멈춘 데이터(좀비)를 찾아내는 스케줄러(`PaymentRescueScheduler`)를 구현했습니다. PG사 상태 조회를 통해 자동 환불 처리를 수행하고 분산 락(ShedLock)을 걸어 다중 서버 환경에서도 중복 실행되지 않도록 보장합니다.
* **DLT & 어드민 대시보드 연동:** 최종 처리 실패한 메시지는 `TripEventDltConsumer`가 `FailedEvent` DB에 적재하며, 이를 관리자가 직접 일괄 재발행하거나 폐기할 수 있도록 복구 파이프라인을 완성했습니다.

### 4. WebFlux Parallel Processing
* **병렬 호출 최적화:** 결제 전 필요한 '요금 계산(Pricing)'과 '유저 정보(User)'를 `Mono.zip`을 사용하여 동시에 논블로킹으로 호출함으로써 결제 파이프라인의 전체 지연 시간(Latency)을 최소화했습니다.

----------

## 아키텍쳐
<img width="2324" height="1686" alt="Image" src="https://github.com/user-attachments/assets/81a25ff9-ee02-4996-80d3-f9217c3b7750" />
