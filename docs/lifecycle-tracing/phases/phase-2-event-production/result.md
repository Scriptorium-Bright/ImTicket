# Phase 2 결과: 사건 생성과 수집

작성일: 2026-09-20(KST)  
상태: 부분 완료  
진행 전 계획: [Phase 2 계획](plan.md)  
관련 설계: [데이터 모델과 ERD](../../data-model-erd.md) · [재구성 명세](../../lifecycle-reconstruction-spec.md) · [ADR-001](../../phase-1-technology-adr.md)

## 1. 이번 실행에서 완료한 범위

이번 실행은 업무 트랜잭션과 같은 MySQL 트랜잭션에 사건 원본을 기록하는 경계를 구현하고, 사건량과 Writer 비용을 측정했다. 조회 모델에 사건을 적용하는 Poller·Consumer는 Phase 3에서 사건 적용 기록과 함께 활성화한다.

| 항목 | 결과 |
| --- | --- |
| 기준 코드 | `develop`, `47f97298cebf0fa3426a464d87b6b88b4b8e88b2`에서 시작 |
| 업무 사건 원본 | `lifecycle_event` MySQL 테이블과 JPA Entity 추가 |
| 예약별 순서 | `Reservation.lifecycle_version`을 1부터 증가 |
| 커밋 묶음 | 같은 트랜잭션에서 생성한 사건이 같은 `commit_group_id` 사용 |
| 기존 예약 | 버전 0 유지, 부분 Timeline 미생성 |
| 활성화 기본값 | `lifecycle.tracing.event-writer.enabled=false` |
| 결정 단위 읽기 | `(lifecycleId, decisionVersion)` 전체 사건을 `eventOrdinal` 순서로 읽는 Reader 추가 |
| 사건량 기준 | 정상 Lifecycle 4건, 만료 후 승인 Lifecycle 5건 |
| Writer 비용 기준 | MySQL 8.0에서 Writer 비활성·활성 트랜잭션 p50/p95/p99 측정 |

## 2. 실제 변경

### 2.1 데이터베이스와 Entity

| 대상 | 실제 변경 | 관련 요구사항 |
| --- | --- | --- |
| `Reservation` | `lifecycle_version BIGINT NOT NULL DEFAULT 0`과 추적 시작·증가 규칙 추가 | FR-001, FR-003 |
| `lifecycle_event` | 사건 식별자, 사건 종류, Lifecycle·결제 식별자, 결정 순번, 묶음 식별자, 주체, 시각과 JSON payload 저장 | FR-001, FR-002, FR-004 |
| 제약·인덱스 | `event_id`, `(lifecycle_id, decision_version, event_ordinal)` 고유 제약과 Lifecycle·결제 주문·묶음 조회 인덱스 | FR-002, FR-003 |
| 마이그레이션 | `V20260920_01__create_lifecycle_event_outbox.sql` 추가 | FR-001 |

### 2.2 사건 생성 위치

| 업무 경로 | 실제 사건 | 결정 규칙 |
| --- | --- | --- |
| 예약 생성·좌석 선점 | `ReservationCreated` | 신규 예약의 버전 1 |
| 결제 준비 | `PaymentPrepared` | 주문·결제 시도 저장 뒤 다음 버전 |
| 정상 승인 반영 | `PaymentApproved`, `ReservationCompleted` | 같은 버전, 같은 커밋 묶음 |
| 스케줄러 만료 | `ReservationExpired` | 예약별 다음 버전, 배치 전체의 같은 커밋 묶음 |
| 결제 반영 중 만료 | `PaymentApproved`, `ReservationExpired`, `PaymentRefundPending` | 같은 버전, 같은 커밋 묶음 |
| 이미 종결된 주문 재응답 | 새 사건 없음 | 기존 결과 반환 |

`LifecycleEventWriter`는 활성 트랜잭션이 없는 호출을 거절한다. payload 직렬화 실패와 사건 INSERT 실패는 업무 트랜잭션을 롤백시킨다. 이벤트 커밋 계수는 `afterCommit` 뒤 증가하므로 롤백된 사건을 커밋 사건 수에 포함하지 않는다.

### 2.3 설정과 관측

| 설정·지표 | 정의 |
| --- | --- |
| `lifecycle.tracing.event-writer.enabled` | 기본값 `false`. 모든 예약 처리 인스턴스가 새 코드로 교체된 뒤 활성화 |
| `imticket.lifecycle.events.committed` | 커밋된 사건 수, `event_type` 태그 포함 |
| `imticket.lifecycle.events.serialization.failures` | payload 직렬화 실패 수 |
| `imticket.lifecycle.events.legacy.skipped` | 기존 예약의 부분 사건 생성을 건너뛴 수 |

## 3. 사건 생성 위치 검증

| 시나리오 | 확인한 결과 | 근거 시험 |
| --- | --- | --- |
| 예약 생성 | `ReservationCreated`, 버전 1, 순번 0 기록 | `LifecycleEventWriterJpaTest` |
| 결제 준비 | `PaymentPrepared` payload와 상태 변경 구성 | `PaymentPreparationServiceTest` |
| 정상 결제 완료 | 승인·완료 두 사건이 한 결정으로 기록 | `ReservationCompletionServiceTest`, MySQL 경합 시험 |
| 스케줄러 만료 | 만료 사건에 `EXPIRATION_SCHEDULER`와 예약별 좌석 목록 기록 | `ReservationExpirationServiceTest` |
| 경로 A | 만료 결정 뒤 승인·환불 대기 결정, 두 묶음 구분 | `LifecycleEventProductionJpaTest`, `MySqlReservationStateRaceTest` |
| 경로 B | 승인·만료·환불 대기 세 사건이 한 버전·묶음 | `LifecycleEventProductionJpaTest`, `ReservationCompletionServiceTest` |
| 롤백 | Reservation과 사건이 함께 사라짐 | `LifecycleEventWriterJpaTest` |
| 기존 예약 | 버전 0 유지, 부분 사건 0건 | `LifecycleEventWriterJpaTest` |

## 4. 검증 결과

| 명령 또는 시험 | 결과 | 핵심 확인 |
| --- | --- | --- |
| `./gradlew test --tests org.example.ticket.lifecycle.event.LifecycleEventWriterJpaTest` | 통과 | 원자성, 순번, 묶음, 롤백, 기존 데이터 처리, 결정 단위 조회 |
| `./gradlew test --tests org.example.ticket.lifecycle.event.LifecycleEventProductionJpaTest` | 통과 | 경로 A/B의 동일 최종 상태와 서로 다른 사건 근거 |
| `./gradlew test --tests org.example.ticket.reservation.booking.service.MySqlReservationStateRaceTest` | 통과 | MySQL 행 잠금 경합과 실제 사건 Writer의 A 경로 기록 |
| `./gradlew test` | 통과 | 기존 예약·결제·문서화 규칙 회귀 없음 |

전체 회귀 시험 중 Correlation ID 경고 로그 검증이 전역 Logger 수준 변경의 영향을 받는 조건을 발견했다. 대상 Logger에 임시 로그 수집기(appender)와 `WARN` 수준을 시험 범위에서 설정하고 원래 상태로 복원하도록 보정했으며, 단독 시험과 전체 회귀 시험에서 통과를 확인했다.

## 5. 아직 수행할 Phase 2 항목

| 항목 | 상태 | 다음 작업 |
| --- | --- | --- |
| Poller 활성화 | 보류 | Phase 3에서 적용 기록과 Consumer를 추가한 뒤 미처리 결정만 선택 |
| Poller 재시도·ShedLock 시험 | 보류 | 적용 기록 기반의 미처리 선택 규칙과 함께 검증 |
| 실제 사건량 | 완료 | 정상 4건, 만료 후 승인 5건의 표본 사건량 확인 |
| API 지연·잠금 기준선 | 부분 완료 | Writer 트랜잭션 p50/p95/p99를 측정했고 API·잠금 비교는 후속 수행 |
| 500 events/s·5,000건 만료 버스트 | 후속 측정 | Phase 3 조회 모델 처리율과 함께 실행 |

`LifecycleEventDecisionReader`는 전역 자동 증가 키 체크포인트를 사용하지 않는다. 이 Reader는 한 결정의 전체 사건을 읽는 경계만 제공한다. 미처리 여부와 재시도 상태는 조회 모델 적용 기록이 생기는 Phase 3부터 판정한다.

## 6. 현재 판정

- 유지할 변경: 사건 원본, 예약별 논리 순서, 커밋 묶음, 기존 데이터 경계, 결정 단위 Reader
- 보완할 변경: 500 events/s·5,000건 버스트와 조회 모델 처리 지연 측정
- 되돌릴 변경: 없음
- Phase 3 진입: 사건 원본·사건량 기준·Writer 비용 기준과 A/B 표현 기능을 확인했다. 조회 모델 적용은 Phase 3에서 수행한다.

다음 구현 작업은 Phase 3의 상태 재구성이다. `lifecycle_event_application`, Snapshot 테이블, 멱등 적용과 경로 분류를 추가해 현재 사건 원본을 운영자가 조회 가능한 Lifecycle 결과로 만든다.

## 7. Phase 2 측정 결과

측정 시험: `./gradlew test --tests org.example.ticket.lifecycle.event.LifecycleEventPhase2MeasurementTest --info`  
환경: Testcontainers MySQL 8.0, 표본 60건, 워밍업 5건. 각 표본은 원장 INSERT와 사건 기록을 하나의 트랜잭션으로 실행했다. 수치는 제품 SLO가 아닌 구현 비용 기준선이다.

| 조건 | 사건 수/생명주기 | p50(ms) | p95(ms) | p99(ms) | 평균(ms) | 평균 처리율(tx/s) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Writer 비활성 | 0 | 7.131 | 16.677 | 19.794 | 8.145 | 122.77 |
| 정상 Lifecycle | 4 | 12.093 | 16.448 | 24.712 | 12.274 | 81.47 |
| 만료 후 승인 Lifecycle | 5 | 10.096 | 20.342 | 45.275 | 11.896 | 84.07 |

활성 표본은 총 560건의 사건을 기록했다. 정상 사건은 `ReservationCreated`, `PaymentPrepared`, `PaymentApproved`, `ReservationCompleted`의 4건이다. 경로 A/B와 같은 만료 후 승인 사건은 5건이다. Writer 활성화에 따른 트랜잭션 평균 증가는 정상 표본 4.129ms, 만료 후 승인 표본 3.751ms로 측정됐다. 이 결과는 조회 모델 Consumer의 처리 예산을 정할 때 입력량 기준으로 사용한다.
