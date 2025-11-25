# 💰 Payment Service

> **결제 승인 처리 및 정산 데이터를 관리하며, 외부 PG사(가상)와의 안전한 통신을 담당하는 마이크로서비스입니다.**

## 🛠 Tech Stack
| Category | Technology |
| :--- | :--- |
| **Language** | **Java 17** |
| **Framework** | Spring Boot (WebFlux) |
| **Database** | MySQL (JPA) |
| **Messaging** | Apache Kafka |

## 📡 API Specification

### Payment Operations
| Method | URI | Auth | Description |
| :--- | :--- | :---: | :--- |
| `POST` | `/api/payments` | 🔐 | 결제 승인 요청 |
| `GET` | `/api/payments/{tripId}` | 🔐 | 특정 여정의 결제 내역 조회 |

### Event Consumers (Kafka)
* **`TripCompletedEvent`**: 운행 종료 이벤트를 수신하여 자동으로 결제 프로세스를 시작합니다.

## 🚀 Key Improvements (핵심 기술적 개선)

### 1. Transaction Separation (트랜잭션 분리)
* **Long Transaction 방지:** 외부 PG사 API 호출(Network I/O)이 DB 트랜잭션을 점유하여 커넥션 풀이 고갈되는 문제를 해결하기 위해, **`PaymentTransactionService`를 분리**하고 `REQUIRES_NEW`를 적용했습니다.
* **구조:** **[DB 생성] → [PG 호출(No Tx)] → [DB 업데이트]** 순서로 트랜잭션을 물리적으로 격리하여 외부 장애가 내부 DB 리소스에 영향을 주지 않도록 설계했습니다.

### 2. WebFlux Parallel Processing
* **병렬 호출:** 결제 승인 전 필요한 **'요금 계산(Pricing Service)'**과 **'유저 정보 조회(User Service)'**를 순차적으로 호출하지 않고, **WebFlux `Mono.zip`**을 사용하여 동시에 호출함으로써 전체 결제 지연 시간(Latency)을 단축하도록 개선했습니다.




----------

## 아키텍쳐
<img width="2324" height="1686" alt="Image" src="https://github.com/user-attachments/assets/81a25ff9-ee02-4996-80d3-f9217c3b7750" />
