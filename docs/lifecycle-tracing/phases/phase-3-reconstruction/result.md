# Phase 3 결과: 생명주기 재구성

작성일: 2026-09-20(KST)  
상태: 부분 완료  
진행 전 계획: [Phase 3 계획](plan.md)  
관련 설계: [재구성 명세](../../lifecycle-reconstruction-spec.md) · [데이터 모델과 ERD](../../data-model-erd.md)

## 1. 실행 정보

| 항목 | 결과 |
| --- | --- |
| 실행 환경 | Spring Boot 3.4.4, Java 21, H2 2.3.232 통합 시험과 MySQL 사건 생성 시험 |
| 조회 모델 버전 | `projectionVersion=1` |
| 입력 단위 | `(lifecycleId, decisionVersion)`의 전체 사건 |
| 상태 | 사건 원본과 조회 모델을 같은 JPA 트랜잭션에서 적용 |
| 최종 판정 | 정상·경로 A·경로 B·순서 역전 보류·중복 재전달 통과 |

## 2. 실제 변경

### 2.1 조회 모델 스키마와 저장소

| 대상 | 실제 변경 | 책임 |
| --- | --- | --- |
| `lifecycle_event_application` | 복합 키 `(projection_version,event_id)`, 결정 버전·순번·적용 상태 저장 | 사건별 멱등 적용 근거 |
| `lifecycle_snapshot` | 예약·결제 주문 상태, 마지막 적용 버전, 경로, 신뢰 상태 저장 | 단건 현재 상태 조회 기반 |
| `lifecycle_seat_snapshot` | 예약별 좌석 상태 저장 | 좌석 상태 축 보존 |
| `lifecycle_payment_attempt_snapshot` | 예약별 결제 시도 상태 저장 | 결제 시도 상태 축 보존 |
| `V20260920_02__create_lifecycle_projection.sql` | 네 파생 테이블과 인덱스·외래 키 추가 | MySQL 배포 스키마 |

Phase 3 시점에는 `providerTransactionId`, `approvedAt`, 원천 대조 차이와 시도 횟수의 세부 보존을 조회 모델 쓰기 계약에 넣지 않았다. Phase 4에서 적용 오류 메타데이터와 원천 대조 차이를 추가했고, 승인 부가 정보의 상세 투영은 후속 범위로 남겼다.

### 2.2 재구성 코드

| 파일·구성 요소 | 실제 동작 |
| --- | --- |
| `LifecycleReconstructionService` | 사건 계약, schemaVersion, eventOrdinal, commitGroupId와 변경 전 상태를 검증한 뒤 상태 축을 적용 |
| `LifecycleEventApplication` | 사건별 `PENDING`, `APPLIED`, `DUPLICATE`, `FAILED`와 오류 메타데이터 기록 |
| `LifecyclePathClassification` | `NORMAL_COMPLETED`, `EXPIRED_WITHOUT_PAYMENT`, `EXPIRATION_FIRST`, `PAYMENT_HANDLER_EXPIRED`, `IN_PROGRESS` 판정 |
| `LifecycleTrustStatus` | 사건 적용 직후 `PROCESSING`, Phase 4 원천 대조 뒤 `CONSISTENT`·`INCOMPLETE`·`MISMATCH` 기록 |
| `LifecycleReconstructionPoller` | 설정이 켜진 경우 결정 단위 Reader와 ShedLock으로 반복 전달. 기본값은 비활성 |

조회 모델은 사건 원본의 상태 변경을 순서대로 적용한다. 전달 순서가 역전되면 높은 결정 버전을 `PENDING`으로 보류하고, 누락 버전이 적용된 뒤 다시 적용한다. 이미 반영한 사건의 재전달은 Snapshot을 변경하지 않는다.

## 3. 상태 재구성 결과

| 시나리오 | 원천 최종 상태 | 조회 모델 상태 | 경로 판정 | 결과 |
| --- | --- | --- | --- | --- |
| 정상 결제 | `SUCCESS / RESERVED / APPLIED / PAID` | 동일한 네 상태 축 | `NORMAL_COMPLETED` | 통과 |
| 결제 없는 만료 | `EXPIRED / AVAILABLE` | 동일한 상태 축 | `EXPIRED_WITHOUT_PAYMENT` | 통과 |
| 경로 A | `EXPIRED / AVAILABLE / REFUND_PENDING / PAID` | 동일한 네 상태 축 | `EXPIRATION_FIRST` | 통과 |
| 경로 B | `EXPIRED / AVAILABLE / REFUND_PENDING / PAID` | 동일한 네 상태 축 | `PAYMENT_HANDLER_EXPIRED` | 통과 |
| 중복 재전달 | 기존 Snapshot 유지 | 적용 사건 수 증가 없음 | 기존 경로 유지 | 통과 |
| 순서 역전 | 최종 기준 상태 | 누락 버전 적용 전 `PROCESSING`, 이후 기준 상태 | 기준 경로 유지 | 통과 |

경로 A와 B는 최종 상태가 같지만 `ReservationExpired`의 actor와 decisionVersion·commitGroupId 근거로 구분됐다. 실제 서비스가 생성한 사건을 사용하는 `LifecycleEventProductionJpaTest`에서 두 경로 판정을 확인했다.

## 4. 시험 결과

| 명령 또는 시험 | 결과 | 핵심 확인 |
| --- | --- | --- |
| `./gradlew test --tests org.example.ticket.lifecycle.projection.LifecycleReconstructionJpaTest` | 통과 | 정상 상태, A/B 판정, 중복, 순서 역전 보류 |
| 재구성 계약 위반 단위 시험 | 통과 | 허용되지 않은 `fromState → toState` 적용 거부와 부분 Snapshot 미생성 |
| `./gradlew test --tests org.example.ticket.lifecycle.event.LifecycleEventProductionJpaTest` | 통과 | 실제 예약 만료·결제 서비스 사건의 A/B 재구성 |
| `./gradlew test --tests org.example.ticket.lifecycle.event.LifecycleEventWriterJpaTest` | 통과 | 사건 원본 원자성과 Projection 코드 회귀 |
| `./gradlew test --tests org.example.ticket.lifecycle.event.LifecycleEventPhase2MeasurementTest` | 통과 | MySQL 사건량·Writer 비용 측정 |

## 5. SLI·SLO 결과

| 지표 | 결과 | 목표 | 판정 |
| --- | ---: | ---: | --- |
| 중복 적용 수 | 0건 | 0건 | 통과 |
| 정상·만료·A·B 최종 상태 일치 | 4/4 | 100% | 통과 |
| A/B 경로 판정 정확도 | 2/2 | 100% | 통과 |
| 순서 역전 보류 후 수렴 | 1/1 | 최종 상태 일치 | 통과 |
| 사건 적용 직후 신뢰 상태 | `PROCESSING` | 원천 대조 전 처리 중 | 통과 |
| 사건 가시화 지연 p95 | 미측정 | Phase 3 처리량 시험에서 기록 | 후속 |
| Consumer 처리 시간 p95 | 미측정 | Phase 3 처리량 시험에서 기록 | 후속 |

## 6. 계획 대비 변경

| 계획 | 실제 | 변경 이유 |
| --- | --- | --- |
| Poller를 Phase 3에서 활성화 | Poller 코드는 추가했지만 기본 설정은 `false` | 운영 데이터에 자동 적용하기 전 Phase 4 실패 실험과 함께 켤 수 있도록 안전한 기본값 유지 |
| 원천 대조를 Phase 3에서 제공 | Snapshot 신뢰 상태를 `PROCESSING`으로 기록하고 대조는 Phase 5로 이동 | 원천 필드 차이 저장과 API 응답 계약을 한 Phase에서 검증하기 위함 |
| 실패 상태 `FAILED` 세부 이력 | Phase 4에서 계약 위반을 `FAILED`로 저장하고 Snapshot을 `MISMATCH`로 표시 | 정상 상태 부분 적용을 막고 오류 근거를 보존 |

## 7. 결과 판정

- 유지할 변경: 결정 단위 재구성, 상태 전이 검증, 적용 기록 복합 키, 경로 A/B 분류, 순서 역전 보류
- 보완할 변경: 프로세스 장애 재시작, MySQL 연결 장애, 500 events/s와 대규모 Replay 처리율 측정
- 되돌릴 변경: 없음
- Phase 4 진입: 가능. 중복·순서 역전·지연·장애 재시작과 Replay 실험으로 확장한다.

## 8. 근거

- 사건 원본: `src/main/java/org/example/ticket/lifecycle/event/`
- 조회 모델: `src/main/java/org/example/ticket/lifecycle/projection/`
- 마이그레이션: `scripts/db/migrations/V20260920_02__create_lifecycle_projection.sql`
- 재구성 통합 시험: `src/test/java/org/example/ticket/lifecycle/projection/LifecycleReconstructionJpaTest.java`
- 실제 서비스 사건 시험: `src/test/java/org/example/ticket/lifecycle/event/LifecycleEventProductionJpaTest.java`
- Phase 2 측정: `src/test/java/org/example/ticket/lifecycle/event/LifecycleEventPhase2MeasurementTest.java`
