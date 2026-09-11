# ImTicket 🎟️

> **트래픽 경합을 측정하고, 실패를 API 계약으로 바꾼 티켓팅 백엔드**
> Spring Boot 3.4 · Java 21 · MySQL · Redis · Prometheus/Grafana · k6

ImTicket은 공연의 좌석 조회, 임시 선점, 결제 검증, 동적 QR 입장을 제공하는 티켓팅 백엔드입니다. 핵심 주제는 인기 공연 오픈 시 동일 좌석으로 몰리는 요청을 **중복 판매 없이 처리하고, 과부하 실패를 HTTP timeout 대신 409/429로 명확히 분류하는 것**입니다.

## 핵심 결과

- 좌석 상태 전이와 다중 좌석 원자성으로 동일 좌석의 임시 선점은 정확히 한 건만 성공하도록 설계했습니다.
- controller-before-lock admission으로 lock queue 진입 전 요청을 즉시 거절합니다. 동일 hot-seat 2,000 VU 재측정 3회에서 성공·post-run `ReservedSeat` 각 1건, transport timeout 0건을 확인했습니다.
- p95만 보지 않고 200/409/429/5xx/transport failure, Tomcat·Hikari·MySQL·JVM 지표, post-run DB 상태를 함께 수집합니다.
- 결제는 Fake PG 기반 `prepare → server-side verify → reservation completion` vertical slice를 구현했습니다. 서버 주문과 provider의 주문 ID·금액·통화를 대조한 뒤 예약·좌석·결제 주문을 함께 확정합니다.

`2,000 VU`는 2,000명의 예약 성공이나 실서비스 처리량을 뜻하지 않습니다. 같은 좌석에 VU당 한 요청을 보낸 one-shot 실험에서 성공은 1건이 정상이며, 나머지 요청의 실패 의미와 전송 안정성을 검증한 조건입니다.

## 기술 스택

| 영역 | 구성 |
| --- | --- |
| Backend | Java 21, Spring Boot 3.4, Spring MVC, Spring Security, Gradle |
| Persistence | MySQL 8, JPA/Hibernate, HikariCP |
| Concurrency | MySQL pessimistic lock, fair `ReentrantLock`, JVM-local seat admission, ShedLock |
| Cache / integration | Redis, CoolSMS, MetaMask ECDSA(Web3j), Fake Payment Gateway |
| Observability | Actuator, Micrometer, Prometheus, Grafana, MDC correlation ID |
| Delivery / test | Docker Compose, Swagger UI, JUnit, k6 |

## 아키텍처

```text
React client / k6
       │
       ▼
Spring Boot :10080
  ├─ Spring Security + JWT + MetaMask signature
  ├─ Correlation-ID filter
  ├─ Reservation: Admission → Lock → Transaction
  ├─ Payment: prepare → provider verify → completion
  ├─ Entry: HMAC-SHA256 dynamic QR
  └─ Actuator / Micrometer ──► management :10081 (control 배치)
       │
       ├───────── MySQL 8: 예약·결제 원장과 최종 정합성 경계
       └───────── Redis: cache·SMS 인증 인프라

Prometheus :9090 ── scrape ──► Grafana :3000
```

현재 기본 토폴로지는 Spring Boot·MySQL·Redis가 각각 1대입니다. 다중 app, load balancer, Redis shared admission, virtual waiting room은 구현 완료 기능이 아니며 필요 조건과 검증 계획만 문서화되어 있습니다.

## 주요 흐름

```text
AVAILABLE seat
  → pre-reserve: admission → lock → LOCKED + PENDING_PAYMENT (7분)
  → payment prepare: idempotency key로 PaymentOrder / PaymentAttempt 생성
  → payment verify: 주문 ID·금액·통화 검증
  → completion transaction: Seat RESERVED + Reservation SUCCESS + PaymentOrder APPLIED
  → entry: 60초 HMAC 동적 QR 검증과 중복 입장 차단
```

### 과부하 보호 경로

```text
POST /api/reservation/pre-reserve
  → seat ID 정렬·중복 제거
  → seat별 permit 즉시 확인
      ├─ 실패: SEAT_ADMISSION_REJECTED (429), DB/AOP 진입 없음
      └─ 통과: configured lock → 좌석 상태 검증 → transaction commit
  → 정상·예외 모두에서 permit 반환
```

`ReentrantLock`은 단일 JVM 비교용 전략입니다. app을 여러 대로 늘리면 JVM마다 lock map이 분리되므로, 다중 인스턴스의 최종 정합성은 MySQL `SELECT ... FOR UPDATE` 같은 공유 경계로 다시 보장해야 합니다.

## 검증 결과와 해석

| 조건 | 확인한 사실 | 해석할 때의 주의점 |
| --- | --- | --- |
| 동일 hot-seat, admission 전 2,000 VU | 일부 run에서 lock queue·Tomcat 포화 뒤 transport timeout 132/806건 | DB/Hikari 병목으로 단정하지 않음 |
| 동일 hot-seat, admission 후 2,000 VU 3회 | 성공·DB `ReservedSeat` 각 1건, transport 0, `SEAT_ADMISSION_REJECTED` 1,982~1,988건 | 성공률 개선이 아니라 timeout을 명시적 거절로 전환한 결과 |
| management endpoint 분리 control | observation loss 0 | Tomcat 200/200과 logging contention은 남아 관측 개선일 뿐 성능 해법이 아님 |
| 외부 k6 one-shot 5,000·7,500 VU | transport failure 0, 대부분 admission 429 | 지속 처리량·실사용자 수로 일반화하지 않음 |
| 외부 k6 one-shot 10,000 VU | transport failure 약 18.1% | TCP connect·k6 start lag·app 경로가 함께 포함된 end-to-end 경계 |

## 빠른 시작

```bash
# .env를 준비한 뒤 전체 환경 실행
docker compose up -d --build
docker compose ps

# API 문서
open http://localhost:10080/swagger-ui/index.html

# 전체 테스트
./gradlew test
```

주요 환경변수는 datasource, JWT/QR secret, SMS secret, `LOCK_STRATEGY`, `RESERVATION_ADMISSION_PER_SEAT_PERMITS`입니다. 실제 값은 로컬 `.env`로 관리하며 저장소에 포함하지 않습니다.

## 현재 한계와 다음 단계

- 현재 결제는 Fake PG vertical slice입니다. 실 PG sandbox, webhook 서명·중복 처리, 재조정, 환불은 후속 범위입니다.
- 결제 확정과 만료 cleanup의 경쟁은 동일 lock 순서와 조건부 상태 전이로 추가 보강해야 합니다.
- 다중 app 환경에서는 JVM-local admission을 shared Redis admission으로 대체하기 전에, MySQL pessimistic lock 기반 two-app PoC와 제품의 즉시 거절/공정 대기 정책을 먼저 검증해야 합니다.
- 4개 hot-seat, 3,000 VU 단계형 실험은 측정 계약과 스크립트를 준비했으며 아직 결과가 없습니다.

## 문서

- [Product Server Developer 포트폴리오 통합 문서](docs/114-product-server-developer-portfolio.md)
- [현재 작업 문서 색인](docs/README.md)
- [락 전략 비교 결과](docs/95-lock-strategy-comparison-results.md)
- [admission 재측정 통합 판정](docs/109-seat-admission-remeasurement-decision.md)
- [외부 k6 CPU 분리·경계 탐색](docs/110-k6-cpu-separation-control.md)
- [수평 확장·대기열 도입 조건](docs/112-hot-seat-horizontal-scaling-plan.md)
