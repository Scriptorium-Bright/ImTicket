# ADR-001: 예약·결제 Lifecycle 추적 기술 선택

- 식별자: `ADR-001`
- 상태: 승인
- 결정일: 2026-09-20(KST)
- 코드 기준: `develop`, `47f97298cebf0fa3426a464d87b6b88b4b8e88b2`
- 관련 문서: [문서 지도](README.md), [요구사항](requirements.md), [Baseline](baseline.md), [C4 아키텍처](c4-architecture.md), [데이터 모델과 ERD](data-model-erd.md), [재구성 명세](lifecycle-reconstruction-spec.md), [SLI·SLO 기준선](sli-slo-baseline.md), [품질속성 기준](quality-attributes.md), [구현 계획](implementation-plan.md)

## 1. 결정 요약

ImTicket의 1차 예약·결제 Lifecycle 추적 기능은 다음 구성으로 구현한다.

| 책임 | 선택 |
| --- | --- |
| 사건 생성 | 애플리케이션이 업무 상태와 Lifecycle 사건을 같은 MySQL 트랜잭션에 기록하는 트랜잭션 아웃박스(Transactional Outbox) |
| 사건 원본 | MySQL `lifecycle_event` 테이블에 실험 기간 전체 사건 보존 |
| 전달 | Spring 주기 실행 작업이 미처리 사건을 DB에서 조회하는 폴링 방식 |
| 처리 | `lifecycleId`별로 결정적 상태를 재구성하는 단순 멱등 소비자(Consumer) |
| 현재 상태 저장 | MySQL Lifecycle 조회 모델 |
| 중복 제거 | `eventId`와 업무 순번 유일성 제약, 사건 적용과 조회 모델 갱신의 단일 트랜잭션 |
| Lifecycle 순서 | 예약 행 잠금 안에서 증가시키는 `decisionVersion`과 같은 커밋 묶음 안의 `eventOrdinal` |
| 장애 복구 | 미처리 사건 재조회, 동일 Consumer 재실행 |
| 재처리(Replay) | 보존된 `lifecycle_event`를 새 조회 모델 버전에 처음부터 재적용 |
| 원천 대조 | 예약·좌석·결제 원천 상태와 조회 모델을 `reservationId` 단위로 비교 |

초기 스택에는 Kafka, Debezium, Flink를 포함하지 않는다. 현재 문제는 하나의 Spring 애플리케이션과 MySQL 안에서 발생하는 최대 6종의 업무 사건을 예약별로 접는 작업이다. 예상 사건량, Consumer 수, 상태 연산의 복잡도에 비해 별도 분산 데이터 플랫폼의 운영 책임이 크다. 폴링 처리기(Poller)는 새 사건이 있는지 DB를 주기적으로 조회해 Consumer에 전달하는 구성 요소다.

## 2. 해결해야 하는 요구사항

### 확인된 현재 사실

- 예약 생성, 결제 준비, 결제 반영, 예약 만료는 모두 Spring과 JPA가 관리하는 MySQL 트랜잭션에서 업무 상태를 변경한다.
- [PaymentPreparationService](../../src/main/java/org/example/ticket/payment/service/PaymentPreparationService.java), [ReservationCompletionService](../../src/main/java/org/example/ticket/reservation/booking/service/ReservationCompletionService.java), [ReservationExpirationService](../../src/main/java/org/example/ticket/reservation/booking/service/ReservationExpirationService.java)는 상태 변경 전에 `Reservation` 행에 쓰기 잠금을 건다.
- 결제 반영과 만료 처리는 같은 예약 행 잠금으로 직렬화되며, [MySqlReservationStateRaceTest](../../src/test/java/org/example/ticket/reservation/booking/service/MySqlReservationStateRaceTest.java)가 양쪽 선행 경합을 검증한다.
- 경로 A와 B는 같은 최종 상태로 수렴한다. 현재 테이블에는 어느 서비스가 만료를 결정했는지와 어떤 상태 변화가 같은 트랜잭션에서 커밋됐는지가 없다.
- 현재 [빌드 구성](../../build.gradle)과 [Compose 구성](../../docker-compose.yml)은 Spring Boot 3.4.4, Java 21, MySQL 8.0, Spring Data JPA, ShedLock 5.13.0, Micrometer와 Prometheus를 사용한다.
- 저장소에는 Kafka, Kafka Connect, Debezium, Flink 의존성과 실행 구성이 없다.

### 이번 결정이 충족할 조건

1. 업무 상태와 사건 기록이 함께 커밋되거나 함께 롤백돼야 한다.
2. 같은 Lifecycle의 순서를 재처리해도 동일하게 결정할 수 있어야 한다.
3. 중복 전달, 전달 순서 역전, 지연과 Consumer 재시작 뒤에도 같은 결과를 만들어야 한다.
4. `paymentOrderId` 또는 `reservationId`로 네 상태, 사건, 경로와 데이터 신뢰 상태를 조회해야 한다.
5. 경로 A와 B를 커밋된 사건으로 구분해야 한다.
6. 원천 MySQL과의 차이를 발견하고 같은 사건 원본으로 결과를 다시 만들 수 있어야 한다.

## 3. 예상 사건량과 선택 전제

현재 저장소에는 운영 결제량 측정값이 없다. 아래 값은 [결제 부하 스크립트](../../scripts/test/146-popular-payment-load.js)의 기본 입력률 `10 requests/s`, [ReservationExpirationScheduler](../../src/main/java/org/example/ticket/reservation/booking/service/ReservationExpirationScheduler.java)의 30초 실행 간격, [ReservationService](../../src/main/java/org/example/ticket/reservation/booking/service/ReservationService.java)의 최대 만료 처리량 5,000건과 사건 모델을 결합한 설계 전제다. 실제 성공 트랜잭션 수를 측정한 결과로 취급하지 않는다.

| Lifecycle 경로 | 한 건당 사건 수 |
| --- | ---: |
| 정상 결제 | 4개: 생성, 준비, 승인, 완료 |
| 결제 없는 예약 만료 | 2개: 생성, 만료 |
| 만료 후 승인 경로 A/B | 5개: 생성, 준비, 만료, 승인, 환불 대기 |

결제 단계별로 초당 10건의 신규 업무 처리가 동시에 진행된다고 가정하면 정상 흐름은 약 40 events/s, 만료 후 승인 흐름은 최대 약 50 events/s다. 만료 스케줄러가 배치를 가득 채우면 한 번의 커밋으로 `ReservationExpired` 5,000건이 보일 수 있다. 이는 30초 구간 평균 약 167 events/s이며, Poller 관점에서는 5,000건의 순간 처리 대기량(backlog)이다.

Phase 2 성능 검증은 지속 500 events/s, 한 트랜잭션에서 발생한 만료 사건 5,000건, 30,000건의 Replay backlog를 시험 범위로 사용한다. 이 수치는 제품 서비스 수준 목표가 아니다. DB 폴링 방식의 여유와 재검토 필요성을 판단하는 실험 경계다. 만료 트랜잭션에 5,000건의 사건 INSERT가 추가되므로 사건 기록 전후의 트랜잭션 시간과 잠금 유지 시간도 반드시 비교한다.

Phase 0의 실제 사건량 측정에서 전제가 달라지면 처리 간격, 배치 크기와 기술 재검토 조건을 같은 근거로 갱신한다.

## 4. 후보 비교

### 4.1 사건 생성 위치

변경 데이터 캡처(Change Data Capture, CDC)는 MySQL binlog와 같은 변경 로그에서 커밋된 행 변경을 읽는 방식이다.

| 후보 | 커밋 원자성 | 업무 의미 | 순서·중복 | 장애 복구·Replay | 현재 규모의 비용 | 결정 |
| --- | --- | --- | --- | --- | --- | --- |
| 애플리케이션 직접 메시지 발행 | DB 커밋과 발행 사이 실패 구간 존재 | 서비스의 결정 사유 보존 가능 | 메시지 계층 설정에 의존 | 원본 로그를 별도로 구성 | 중간 | 제외 |
| 트랜잭션 아웃박스 | 업무 상태와 사건을 같은 트랜잭션에 기록 | `reasonCode`, `actorType`, 커밋 묶음 보존 | 애플리케이션 순번과 DB 제약으로 통제 | 사건 테이블에서 재처리 | 낮음 | 선택 |
| 도메인 테이블 CDC | 커밋된 행 변경을 수집 | 여러 테이블 변경의 사유와 처리 주체 복원이 어려움 | binlog 위치와 트랜잭션 메타데이터 사용 가능 | 커넥터 위치와 binlog 보존 필요 | 높음 | 보류 |
| 아웃박스 + CDC | 아웃박스 원자성과 binlog 전달 결합 | 높음 | 원천 위치와 메시지 키 사용 가능 | 내구성 있는 전달과 Replay에 유리 | 높음 | 확장 후보 |

트랜잭션 아웃박스는 현재 `@Transactional` 경계 안에서 업무 상태와 사건 행을 함께 저장할 수 있다. `AFTER_COMMIT` 후속 작업이나 네트워크 발행을 생산 경계로 사용하지 않는다. 프로세스가 커밋 직후 종료돼도 사건 원본이 MySQL에 남아야 한다.

CDC만 적용하면 `Reservation`, `Seat`, `PaymentOrder`, `PaymentAttempt`의 행 변경은 얻을 수 있다. 경로 B의 만료 사유와 처리 주체, 여러 변경이 표현하는 하나의 업무 결정을 추가 규칙 없이 복원하기 어렵다. CDC를 도입하는 시점에도 업무 의미의 원본은 아웃박스 사건으로 유지한다.

### 4.2 전달 방식

| 후보 | 순서 범위 | 중복 처리 | 복구·Replay | 운영 책임 | 현재 적합성 | 결정 |
| --- | --- | --- | --- | --- | --- | --- |
| MySQL 폴링 | 애플리케이션의 논리 순번 사용 | DB 유일성 제약과 적용 트랜잭션 | 미처리 행 재조회, 전체 사건 재적용 | 기존 MySQL과 Spring 작업만 운영 | 높음 | 선택 |
| Kafka | 같은 키를 같은 파티션에 보낼 때 파티션 순서 제공 | Producer·Consumer 설정과 결과 저장의 멱등성 필요 | 보존 로그와 Consumer offset으로 지원 | Broker, topic, partition, 보존, 장애 운영 추가 | 중간 | 보류 |
| Debezium + Kafka | binlog의 커밋 변경을 Kafka로 전달 | 커넥터 재전달을 고려한 결과 저장의 멱등성 필요 | 커넥터 offset과 Kafka 보존 사용 | MySQL binlog, Kafka Connect, Kafka 운영 추가 | 중간 | 보류 |
| 프로세스 내부 큐 | 현재 프로세스 범위 | 메모리 상태에 의존 | 종료 시 원본과 위치 유실 가능 | 낮음 | 낮음 | 제외 |

Kafka의 파티션 순서는 전달 로그의 순서다. 경로 A/B 판정에 필요한 업무 결정 순서는 생산자가 정확한 키와 순번을 제공해야 한다. Kafka를 추가해도 `sourceOrder` 정의 문제는 남는다.

### 4.3 Lifecycle 처리 방식

| 후보 | 상태 연산 | 역전·지연 | 장애 복구 | 운영 비용 | 현재 적합성 | 결정 |
| --- | --- | --- | --- | --- | --- | --- |
| Spring 단순 Consumer | 예약당 최대 5개 수준의 사건을 결정적 함수로 재구성 | `decisionVersion` 공백을 보류하고 후속 재처리 | DB 트랜잭션과 미처리 사건 재조회 | 낮음 | 높음 | 선택 |
| Kafka Consumer | 단순 Consumer와 같은 로직, 입력이 Kafka | 파티션 내 순서와 애플리케이션 순번 함께 사용 | offset과 Sink 커밋 조정 필요 | 중간 | 조건부 | 보류 |
| Flink 상태 저장 스트림 처리 | 큰 상태, 사건 시각 기반 시간 창(event-time window), 다중 스트림 연산에 적합 | 워터마크(watermark)와 상태로 지연 처리 | 체크포인트(checkpoint)와 재처리 가능한 원천 필요 | 높음 | 낮음 | 보류 |
| 주기 배치 재구성 | 전체 범위를 반복 계산 | 최종 수렴은 단순 | 실행 사이 최신성 저하 | 낮음 | 원천 대조·Replay 보조에 적합 | 보조 사용 |

현재 연산은 `lifecycleId`별 소수 사건을 정렬하고 상태를 접는 결정적 함수다. 시간 창(window), 여러 스트림의 시간 기준 조인, 대규모 장기 상태가 필요하지 않다. Flink의 checkpoint, watermark, 상태 저장소와 클러스터 운영은 현재 기능 범위를 넘는다.

### 4.4 상태 저장

| 후보 | 장점 | 비용 | 결정 |
| --- | --- | --- | --- |
| MySQL 사건 원본 + MySQL 조회 모델 | 원천 트랜잭션, 적용 결과와 중복 제거를 한 DB에서 원자적으로 관리 | 원천 DB 쓰기와 저장량 증가 | 선택 |
| Redis 조회 모델 | 빠른 조회 | 영속 원본과 복구 경로를 별도로 유지 | 보류 |
| Kafka compacted topic | 로그 기반 현재 상태 공유 | 조회 API용 저장소와 운영 구성이 추가로 필요 | 보류 |
| Flink 상태 + 외부 Sink | 스트림 연산과 checkpoint 통합 | 조회, 배포, 상태 복구 구성이 복잡 | 보류 |

이번 조회는 한 건 단위이며 예상 사건량도 작다. 기존 MySQL에 사건 원본과 조회 모델을 두면 원천 대조, 멱등 적용과 Replay 검증을 가장 적은 구성 요소로 구현할 수 있다.

## 5. 확정 아키텍처

```text
예약·결제 @Transactional 서비스
  ├─ Reservation / Seat / PaymentOrder / PaymentAttempt 변경
  ├─ Reservation.lifecycleVersion 증가
  └─ lifecycle_event 사건 저장
               │ 같은 MySQL 트랜잭션
               ▼
Spring Lifecycle Poller + ShedLock
  └─ 미처리 commitGroup 조회
               ▼
멱등 Lifecycle Consumer
  ├─ 사건 순서 검증
  ├─ 현재 상태 재구성
  ├─ 경로 A/B 판정
  └─ 사건 적용 기록 + 조회 모델 갱신
               │ 같은 MySQL 트랜잭션
               ▼
Lifecycle 조회 API
  └─ paymentOrderId / reservationId → 상태·Timeline·경로·신뢰 상태

Reconciliation / Replay
  ├─ 원천 네 상태와 조회 모델 비교
  └─ 보존 사건 → 새 조회 모델 버전 재생
```

### 5.1 생산 경계

각 업무 서비스는 도메인 상태를 변경한 같은 트랜잭션 안에서 `lifecycle_event`를 저장한다.

- 정상 커밋: 상태와 사건이 함께 존재한다.
- 롤백: 상태 변경과 사건이 함께 사라진다.
- 멱등 재응답: 새로운 상태 변경이 없으므로 새로운 업무 사건도 만들지 않는다.
- 같은 트랜잭션의 여러 사건: 하나의 `commitGroupId`를 공유한다. 같은 Lifecycle의 사건은 같은 `decisionVersion`도 공유한다.

사건 저장 실패는 해당 업무 트랜잭션을 실패시킨다. Lifecycle 사건은 현재 문제를 설명하는 필수 커밋 사실이므로 최선 노력(best effort) 기록으로 처리하지 않는다.

### 5.2 `sourceOrder` 결정

`sourceOrder`를 물리적인 DB 커밋 순서로 정의하지 않는다. MVP의 `sourceOrder`는 다음 두 값의 조합이다.

```text
sourceOrder = (decisionVersion, eventOrdinal)
```

- `decisionVersion`: `Reservation`에 저장하는 Lifecycle별 단조 증가 값이다. 예약 생성은 `1`로 시작한다. 이후 사건을 만드는 트랜잭션은 `Reservation` 쓰기 잠금을 얻은 뒤 한 번 증가시킨다.
- `eventOrdinal`: 같은 트랜잭션에서 생성한 사건의 표시 순서다. 이 값들은 하나의 원자적 커밋 묶음에 속하며 서로 독립된 커밋을 의미하지 않는다.
- `commitGroupId`: 같은 DB 트랜잭션에서 생성한 사건을 묶는 식별자다. 만료 배치처럼 한 트랜잭션이 여러 Lifecycle을 바꾸면 여러 예약 사건이 같은 값을 가질 수 있다.

현재 결제 준비, 결제 반영과 예약 만료가 같은 `Reservation` 행을 잠근다는 코드 규칙을 활용한다. 같은 Lifecycle의 다음 트랜잭션은 앞 트랜잭션의 커밋 또는 롤백 뒤에 잠금을 얻는다. 이 경계 안에서 증가한 `decisionVersion`은 업무 결정 순서를 나타낸다.

다음 값은 Lifecycle 순서 판정에 사용하지 않는다.

- Outbox의 `AUTO_INCREMENT` PK: 값은 INSERT 시점에 할당되며 롤백으로 공백이 생길 수 있다. 동시 트랜잭션의 커밋 순서를 표현하지 않는다.
- `occurredAt`과 애플리케이션 서버 시각: 시계 오차와 재처리 시점의 영향을 받는다.
- 폴링 조회 순서: 전달 순서이며 업무 결정 순서와 의미가 다르다.
- Kafka offset: Kafka를 도입한 뒤에도 Producer가 기록한 전달 로그 순서다.

이 설계의 코드 불변 조건은 “Lifecycle 사건을 만드는 모든 후속 상태 변경이 먼저 `Reservation` 행을 잠그고 `decisionVersion`을 증가시킨다”이다. 아키텍처 테스트와 MySQL 경합 테스트로 이 조건을 검증한다.

### 5.3 경로 A/B 판정

| 경로 | 사건 근거 |
| --- | --- |
| A: 만료 처리 선행 | `ReservationExpired(actor=EXPIRATION_SCHEDULER, version=v)` 뒤에 `PaymentApproved`와 `PaymentRefundPending(version=v+1)`이 존재 |
| B: 결제 반영 중 만료 판단 | `PaymentApproved`, `ReservationExpired(actor=PAYMENT_VERIFICATION)`, `PaymentRefundPending`이 같은 `decisionVersion`과 `commitGroupId`에 존재 |

최종 상태와 `occurredAt`의 대소 비교는 경로 판정 근거로 사용하지 않는다.

### 5.4 중복, 역전과 지연

- `eventId`에 유일성 제약을 둔다.
- `(lifecycleId, decisionVersion, eventOrdinal)`에도 유일성 제약을 둔다.
- 사건 적용 기록과 조회 모델 변경은 같은 Consumer 트랜잭션에서 커밋한다.
- 이미 적용한 `eventId`는 상태를 다시 변경하지 않는다.
- 다음 `decisionVersion`보다 큰 사건이 먼저 전달되면 적용을 보류하고 신뢰 상태를 `PROCESSING`으로 둔다.
- 빠진 버전이 도착하면 해당 Lifecycle의 보존 사건을 다시 정렬해 연속된 버전까지 재구성한다.
- 같은 Lifecycle과 `decisionVersion`의 사건은 `commitGroupId`를 확인해 원자적 결정 하나로 반영한다. Poller의 처리 단위는 `(lifecycleId, decisionVersion)`이므로 만료 배치 5,000건을 하나의 조회 모델 트랜잭션으로 묶지 않는다.
- Poller가 후보 사건 하나를 찾으면 같은 `(lifecycleId, decisionVersion)`의 커밋된 사건을 모두 읽는다. 폴링 배치 한도가 같은 커밋 묶음을 나누지 않게 한다.

DB Poller는 Outbox PK의 최댓값을 체크포인트로 사용하지 않는다. 늦게 커밋한 작은 PK를 건너뛸 수 있기 때문이다. 미처리 상태와 적용 기록을 기준으로 사건을 찾는다. 여러 Poller를 허용하게 되면 MySQL의 `FOR UPDATE SKIP LOCKED`를 작업 분배에 사용할 수 있다. 1차 구현은 ShedLock으로 Poller 하나만 실행한다.

### 5.5 데이터 신뢰 상태

MVP는 사건 부재를 실시간으로 추측하지 않는다. 순번 공백은 처리 중 상태로 유지하고, 원천 대조가 증거를 제공한 뒤 누락 또는 불일치를 확정한다.

| 상태 | 판정 근거 |
| --- | --- |
| `PROCESSING` | 새 사건 처리 중, 아직 적용하지 않은 원본 사건이 존재, `decisionVersion` 공백 대기 중, 또는 아직 원천 대조하지 않음 |
| `CONSISTENT` | 원천 대조 시 네 상태와 원천 `lifecycleVersion`이 조회 모델과 일치 |
| `INCOMPLETE` | 원천 `lifecycleVersion`까지 필요한 사건이 원본에 없거나 원본의 `decisionVersion` 사이에 공백이 있음을 원천 대조로 확인 |
| `MISMATCH` | 적용 버전은 원천과 같지만 네 상태 축 중 하나 이상이 다르거나, 사건 계약 위반으로 원본 사건을 적용할 수 없음 |

원천 버전보다 조회 모델 버전이 낮더라도 필요한 사건이 원본에 모두 있으면 처리 backlog로 분류한다. 이 경우 신뢰 상태는 `PROCESSING`이다. 원천 대조는 `lastReconciledAt`, 원천 버전, 사건 원본의 최대 연속 버전, 조회 모델 버전과 필드별 차이를 함께 기록한다. `INCOMPLETE`와 `MISMATCH`는 자동 보정 지시가 아니다. Replay 또는 결함 조사 대상으로 표시한다.

### 5.6 장애 복구와 Replay

- Poller가 사건 적용 전 종료: 사건은 미처리 상태로 남아 재시작 뒤 다시 조회된다.
- 조회 모델 갱신 중 종료: 적용 기록과 조회 모델 트랜잭션이 롤백되며 같은 사건을 다시 처리한다.
- 커밋 응답 뒤 Consumer가 종료: 재전달될 수 있으며 유일성 제약으로 중복 적용을 막는다.
- Replay: `projectionVersion`이 다른 빈 조회 모델에 모든 사건을 `decisionVersion`, `eventOrdinal` 순서로 적용한다.
- Replay 비교: 기존 결과와 새 결과의 네 상태, Timeline, 경로 분류를 비교한다.

원본 사건의 업무 필드는 수정하지 않는다. 처리 상태, 처리 시도 시각과 오류 같은 운영 메타데이터는 별도 열 또는 별도 적용 기록에 저장할 수 있다. 장기 보존 정책 최적화는 1차 범위에 포함하지 않는다.

## 6. 구현에 사용할 구성 요소

| 구성 요소 | 버전·방식 | 선택 이유 |
| --- | --- | --- |
| Java | 현재 Java 21 | 기존 애플리케이션과 테스트 환경 유지 |
| Spring Boot | 현재 3.4.4 | 기존 트랜잭션, JPA, 스케줄러, Actuator 사용 |
| MySQL | 현재 8.0 | 원천 상태, 아웃박스, 조회 모델의 원자적 경계 제공 |
| Spring Data JPA/JDBC | 기존 JPA와 필요한 잠금 조회용 JDBC | 도메인 저장과 폴링 쿼리 구현 |
| Spring Scheduler | `@Scheduled` | 작은 사건량의 지속 폴링 |
| ShedLock | 현재 5.13.0 | 여러 애플리케이션 인스턴스에서 Poller 하나 실행 |
| Micrometer/Prometheus | 기존 구성 | 입력·처리·누락·지연·backlog·복구 지표 수집 |
| SQL migration | 기존 `scripts/db/migrations` 방식 | 새 테이블·열·제약·인덱스를 명시적으로 배포 |

추가 데이터 플랫폼 의존성은 없다. 사건 JSON 직렬화는 Spring Boot에 포함된 Jackson을 사용하며, `schemaVersion`별 역직렬화 계약을 테스트한다.

## 7. 구현 경계

### 사건 원본

`lifecycle_event`는 다음 검색·제약 열을 구조화하고, 상태 변화 목록처럼 가변적인 부분은 JSON payload에 둔다.

- 구조화 열: `event_id`, `event_type`, `schema_version`, `lifecycle_id`, `payment_order_id`, `payment_attempt_id`, `decision_version`, `event_ordinal`, `commit_group_id`, `actor_type`, `occurred_at`
- 유일성: `event_id`, `(lifecycle_id, decision_version, event_ordinal)`
- 조회 인덱스: 미처리 상태, `lifecycle_id`, `payment_order_id`, `commit_group_id`
- payload: `seatIds`, `stateChanges`, `reasonCode`

### 조회 모델

- Lifecycle 요약: Reservation, PaymentOrder, 경로, 신뢰 상태, 마지막 적용 버전, 마지막 대조 시각
- Seat 상태: Lifecycle에 연결된 좌석별 현재 상태
- PaymentAttempt 상태: 결제 시도별 현재 상태와 승인 시각
- Timeline: `lifecycle_event`의 커밋 묶음과 표시 순서
- 조회 키: `reservationId` 기본 키, `paymentOrderId` 고유 또는 일반 인덱스

테이블, 키, 제약과 인덱스는 [데이터 모델과 ERD](data-model-erd.md)에 정의한다. 업무 사건 원본과 재생 가능한 조회 결과의 책임은 분리한다.

## 8. 검증과 기술 전환 조건

### 선택 기술의 통과 조건

1. 정상 트랜잭션은 기대 사건을 남기고 롤백은 상태 변경 사건을 남기지 않는다.
2. 동일 사건 재전달 100% 조건에서 중복 적용은 0건이다.
3. 역전·지연 주입 뒤 원천 상태와 최종 상태 일치율은 100%다.
4. Consumer 강제 종료와 재시작 뒤 누락과 중복 적용은 0건이다.
5. 지속 500 events/s, 만료 사건 5,000건 커밋 버스트와 30,000건 Replay에서 backlog, p95 지연과 복구 시간을 기록한다.
6. Replay 결과의 네 상태, Timeline과 경로 분류가 기존 결과와 일치한다.
7. 원천 대조가 의도적으로 만든 누락과 상태 차이를 각각 `INCOMPLETE`, `MISMATCH`로 분류한다.
8. 사건 기록 추가 전후 예약·결제 API p95와 MySQL lock wait를 비교한다.

### Kafka·Debezium 재검토 조건

다음 중 하나가 측정되면 MySQL 폴링과 Kafka 기반 전달을 다시 비교한다.

- 500 events/s 실험에서 Poller가 지속적으로 backlog를 해소하지 못함
- 사건 전달 지연이 조회 기능의 측정 목표를 충족하지 못함
- Lifecycle 외에 독립적으로 배포되는 Consumer가 여러 개 필요함
- 사건 보존 부하를 원천 MySQL에서 분리해야 함
- 다른 서비스와 계정 경계를 넘어 사건을 전달해야 함
- MySQL CDC 플랫폼이 별도 운영 기준으로 이미 도입됨

Kafka를 선택할 경우 `lifecycleId`를 메시지 키로 사용하고, 아웃박스 원본은 유지한다. Debezium은 아웃박스 테이블 전달 수단으로 평가한다.

### Flink 재검토 조건

다음 요구가 생기면 단순 Consumer와 Flink를 다시 비교한다.

- 여러 Lifecycle을 묶는 시간 창 집계가 핵심 기능이 됨
- event time과 watermark를 사용하는 지연 허용 정책이 필요함
- 여러 입력 스트림의 상태 저장 조인이 필요함
- Consumer 코드로 관리하기 어려운 대규모 keyed state와 checkpoint 복구가 필요함

한 건의 Lifecycle 재구성과 A/B 판정은 이 조건에 해당하지 않는다.

## 9. 결과

### 얻는 효과

- 현재 Spring 트랜잭션 경계에 사건 원자성을 추가한다.
- 하나의 MySQL 안에서 생산, 처리, 조회, 원천 대조와 Replay를 검증할 수 있다.
- `decisionVersion`으로 Outbox PK와 서버 시각의 순서 오해를 제거한다.
- 재구성 함수와 원천 사건을 유지해 중복, 역전, 지연과 장애 실험을 반복할 수 있다.
- Kafka·Debezium·Flink 도입 여부를 실제 backlog와 처리 지연으로 판단할 수 있다.

### 수용하는 비용

- 업무 트랜잭션마다 사건 INSERT와 `Reservation.lifecycleVersion` 갱신이 추가된다.
- 원천 MySQL에 사건 원본과 조회 모델의 저장·조회 부하가 생긴다.
- 모든 Lifecycle 상태 변경 경로가 예약 행 잠금과 사건 생성 규칙을 지켜야 한다.
- Poller 처리 지연만큼 조회 모델이 원천 상태보다 늦을 수 있다.

## 10. 공식 근거

- [Spring 선언적 트랜잭션](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-decl-explained.html)
- [MySQL InnoDB AUTO_INCREMENT 처리](https://dev.mysql.com/doc/refman/8.0/en/innodb-auto-increment-handling.html)
- [MySQL Binary Log](https://dev.mysql.com/doc/refman/8.0/en/binary-log.html)
- [MySQL `SKIP LOCKED`](https://dev.mysql.com/doc/refman/8.4/en/select.html)
- [Debezium Outbox Event Router](https://debezium.io/documentation/reference/transformations/outbox-event-router.html)
- [Debezium MySQL Connector](https://debezium.io/documentation/reference/stable/connectors/mysql.html)
- [Apache Kafka 개요와 파티션 순서](https://kafka.apache.org/intro/)
- [Apache Kafka 전달 보장](https://kafka.apache.org/41/design/design/)
- [Apache Flink 장애 허용](https://nightlies.apache.org/flink/flink-docs-stable/docs/learn-flink/fault_tolerance/)
- [Apache Flink event time과 순서 역전](https://nightlies.apache.org/flink/flink-docs-stable/docs/ops/debugging/debugging_event_time/)

## 최종 결정

> ImTicket의 1차 Lifecycle 추적은 트랜잭션 아웃박스, MySQL 폴링, Spring 멱등 Consumer와 MySQL 조회 모델로 구현한다. 같은 Lifecycle의 순서는 `Reservation` 행 잠금 안에서 증가하는 `decisionVersion`과 `eventOrdinal`로 정의한다. Kafka, Debezium과 Flink는 측정된 처리량 또는 기능 요구가 8절의 재검토 조건을 충족할 때 도입을 다시 판단한다.
