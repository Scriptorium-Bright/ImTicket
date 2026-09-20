# 예약·결제 생명주기 재구성 명세

작성일: 2026-09-20(KST)  
상태: Phase 4 복구·대조 구현 반영  
기준 코드: `develop`, `47f97298cebf0fa3426a464d87b6b88b4b8e88b2`  
관련 문서: [문서 지도](README.md) · [요구사항](requirements.md) · [데이터 모델과 ERD](data-model-erd.md) · [Phase 3 계획](phases/phase-3-reconstruction/plan.md)

## 1. 왜 별도 명세가 필요한가

사건을 저장하는 것만으로 운영자의 질문에 답할 수는 없다. 같은 사건 집합을 어떤 순서로 적용하고, 불완전한 입력을 어떻게 보류하며, 동일한 최종 상태의 경로 A/B를 어떤 근거로 구분하는지가 고정돼야 한다.

이 문서는 다음 구현 사이의 공통 계약이다.

- Lifecycle Consumer
- 조회 모델 갱신
- 실행 경로 분류
- 중복·순서 역전·지연 처리
- Replay
- Reconciliation

동일한 사건 원본과 `projectionVersion`을 입력하면 실행 시각과 전달 순서에 관계없이 같은 결과를 만들어야 한다.

## 2. 데이터 grain과 키

grain은 한 행 또는 한 처리 단위가 표현하는 사실의 범위다.

| 데이터 | grain | 업무 키 |
| --- | --- | --- |
| `lifecycle_event` | 커밋된 업무 사건 한 건 | `eventId` |
| 결정 단위 | 한 Lifecycle의 한 업무 트랜잭션 결정 | `(lifecycleId, decisionVersion)` |
| 사건 적용 기록 | 조회 모델 버전에 적용한 사건 한 건 | `(projectionVersion, eventId)` |
| Lifecycle 요약 | 조회 모델 버전의 예약 한 건 | `(projectionVersion, lifecycleId)` |
| 좌석 Snapshot | 조회 모델 버전의 예약·좌석 한 건 | `(projectionVersion, lifecycleId, seatId)` |
| 결제 시도 Snapshot | 조회 모델 버전의 예약·결제 시도 한 건 | `(projectionVersion, lifecycleId, paymentAttemptId)` |

MVP의 `lifecycleId`는 `reservationId`다. 모든 순서·중복·재처리 판단은 이 키 안에서 수행한다. 서로 다른 Lifecycle 사이의 전역 순서는 정의하지 않는다.

## 3. 입력과 출력

### 입력

```text
현재 LifecycleSnapshot
+ 같은 lifecycleId와 decisionVersion의 커밋 사건 전체
+ projectionVersion
```

사건 묶음은 `eventOrdinal` 오름차순으로 정렬한다. `occurredAt`, 사건 테이블 `id`, Poller 전달 순서와 Kafka offset은 재구성 순서에 사용하지 않는다.

### 출력

```text
다음 LifecycleSnapshot
+ SeatSnapshot 목록
+ PaymentAttemptSnapshot 목록
+ EventApplication 목록
+ PathClassification
+ TrustStatus
```

재구성기는 원천 업무 테이블을 읽지 않는 결정적 함수로 구현한다. 원천 상태 확인은 Reconciliation에서 수행한다.

## 4. 결정 단위 불변 조건

Consumer는 상태를 적용하기 전에 다음 조건을 검증한다.

1. 모든 사건의 `lifecycleId`가 같다.
2. 모든 사건의 `decisionVersion`이 같다.
3. 모든 사건의 `commitGroupId`가 같다.
4. `eventOrdinal`은 0부터 시작하며 묶음 안에서 연속된다.
5. `eventId`와 `(lifecycleId, decisionVersion, eventOrdinal)`이 고유하다.
6. `schemaVersion`을 현재 코드가 해석할 수 있다.
7. 사건 종류 조합이 5절의 허용 조합 중 하나다.
8. 각 `stateChanges.fromState`가 현재 Snapshot 상태와 일치한다.
9. 같은 결정 안에서 앞 사건을 적용한 결과가 다음 사건의 `fromState` 검증 기준이 된다.

계약을 위반하면 상태를 일부 적용하지 않는다. 해당 사건 적용 상태를 `FAILED`로 기록하고 안정적인 오류 코드를 남긴다. 원천 대조 전 Lifecycle 신뢰 상태는 `PROCESSING`을 유지한다.

## 5. 허용 사건 묶음과 적용 순서

| 업무 결정 | `eventOrdinal` 순서 | 결과 |
| --- | --- | --- |
| 예약 생성 | `0 ReservationCreated` | Reservation `PENDING_PAYMENT`, Seats `LOCKED` |
| 결제 준비 | `0 PaymentPrepared` | PaymentOrder `READY`, PaymentAttempt `READY` |
| 정상 결제 반영 | `0 PaymentApproved`, `1 ReservationCompleted` | Attempt `PAID`, Reservation `SUCCESS`, Seats `RESERVED`, Order `APPLIED` |
| 스케줄러 만료 | `0 ReservationExpired` | Reservation `EXPIRED`, Seats `AVAILABLE` |
| 경로 A의 후속 승인 | `0 PaymentApproved`, `1 PaymentRefundPending` | Attempt `PAID`, Order `REFUND_PENDING` |
| 경로 B의 승인·만료 | `0 PaymentApproved`, `1 ReservationExpired`, `2 PaymentRefundPending` | Attempt `PAID`, Reservation `EXPIRED`, Seats `AVAILABLE`, Order `REFUND_PENDING` |

같은 묶음의 사건은 하나의 DB 트랜잭션에서 함께 커밋된 사실이다. `eventOrdinal`은 Timeline과 결정적 재생을 위한 표현 순서이며 별도 커밋 순서를 뜻하지 않는다.

`PAID_UNAPPLIED`는 현재 독립 커밋 상태가 없다. 조회 모델은 `PaymentApproved`를 적용한 뒤 같은 결정의 완료 또는 환불 대기 사건까지 원자적으로 적용하므로 외부 조회에 중간 상태를 노출하지 않는다.

## 6. 상태 축별 적용 규칙

### 6.1 Reservation

| 사건 | 허용 변경 |
| --- | --- |
| `ReservationCreated` | `∅ → PENDING_PAYMENT` |
| `ReservationCompleted` | `PENDING_PAYMENT → SUCCESS` |
| `ReservationExpired` | `PENDING_PAYMENT → EXPIRED` |

`SUCCESS`와 `EXPIRED`는 MVP의 종결 상태다. 현재 사건 계약에는 두 상태 사이 전이가 없다.

### 6.2 Seat

| 사건 | 허용 변경 |
| --- | --- |
| `ReservationCreated` | 각 Seat `AVAILABLE → LOCKED` |
| `ReservationCompleted` | 각 Seat `LOCKED → RESERVED` |
| `ReservationExpired` | 각 Seat `LOCKED → AVAILABLE` |

사건의 `seatIds`와 Seat `stateChanges.entityId` 집합은 같아야 한다. 한 Lifecycle에 연결된 모든 좌석은 완료 또는 만료 결정에서 함께 변경돼야 한다.

### 6.3 PaymentOrder

| 사건 | 허용 변경 |
| --- | --- |
| `PaymentPrepared` | `∅ → READY` |
| `ReservationCompleted` | `READY → APPLIED` |
| `PaymentRefundPending` | `READY → REFUND_PENDING` |

현재 코드의 `PAID_UNAPPLIED`는 같은 트랜잭션 안의 중간 상태이므로 사건의 `fromState`와 `toState`에 사용하지 않는다.

### 6.4 PaymentAttempt

| 사건 | 허용 변경 |
| --- | --- |
| `PaymentPrepared` | `∅ → READY` |
| `PaymentApproved` | `READY → PAID` |

같은 결제 검증 요청의 멱등 재응답은 새 `PaymentApproved`를 만들지 않는다.

## 7. 순서, 중복과 지연 알고리즘

현재 Snapshot의 마지막 연속 적용 버전을 `lastAppliedVersion`이라고 한다.

```text
expectedVersion = lastAppliedVersion + 1

incomingVersion < expectedVersion
  → 이미 적용한 결정인지 eventId 적용 기록 확인
  → 적용한 사건이면 상태 변경 없이 중복으로 집계

incomingVersion = expectedVersion
  → 결정 단위 계약 검증
  → 네 상태 축과 경로를 메모리에서 계산
  → 적용 기록과 Snapshot을 한 트랜잭션으로 커밋
  → 원천 대조 전 TrustStatus=PROCESSING

incomingVersion > expectedVersion
  → 적용 보류
  → EventApplication=PENDING
  → TrustStatus=PROCESSING
```

빠진 결정이 도착하면 해당 Lifecycle의 보존 사건을 다시 읽고 `expectedVersion`부터 연속된 결정까지 적용한다. 높은 버전 사건을 먼저 적용한 뒤 이전 상태를 덮어쓰는 방식은 사용하지 않는다.

같은 `eventId`가 재전달되면 적용 기록의 고유 제약이 두 번째 상태 변경을 막는다. 같은 결정 위치에 다른 `eventId`가 들어오면 사건 원본의 결정 위치 고유 제약이 충돌을 차단한다.

## 8. 실행 경로 분류

### 8.1 분류 값

| 값 | 의미 |
| --- | --- |
| `IN_PROGRESS` | 아직 종결 경로를 결정할 사건이 부족함 |
| `NORMAL_COMPLETED` | 승인 결제가 유효한 예약에 적용됨 |
| `EXPIRED_WITHOUT_PAYMENT` | 스케줄러가 예약을 만료했고 승인 사건이 없음 |
| `EXPIRATION_FIRST` | 스케줄러 만료 결정 뒤 별도 결정에서 승인과 환불 대기가 커밋됨 |
| `PAYMENT_HANDLER_EXPIRED` | 결제 반영 결정 안에서 승인·만료·환불 대기가 함께 커밋됨 |

`EXPIRED_WITHOUT_PAYMENT`는 이후 늦은 승인 사건이 도착하면 `EXPIRATION_FIRST`로 바뀔 수 있다. 경로 분류는 사건 전체에서 계산하는 파생 값이다.

### 8.2 판정 우선순위

1. 같은 결정에 `PaymentApproved`, `ReservationExpired(actor=PAYMENT_VERIFICATION)`, `PaymentRefundPending`이 있으면 `PAYMENT_HANDLER_EXPIRED`다.
2. 앞선 결정에 `ReservationExpired(actor=EXPIRATION_SCHEDULER)`가 있고 뒤 결정에 `PaymentApproved`, `PaymentRefundPending`이 있으면 `EXPIRATION_FIRST`다.
3. `PaymentApproved`와 `ReservationCompleted`가 있고 최종 네 상태가 성공 상태이면 `NORMAL_COMPLETED`다.
4. 스케줄러 `ReservationExpired`가 있고 승인과 환불 대기 사건이 없으면 `EXPIRED_WITHOUT_PAYMENT`다.
5. 어느 조건도 충족하지 않으면 `IN_PROGRESS`다.

경로 판정은 최종 상태만 사용하지 않는다. `actorType`, `decisionVersion`과 `commitGroupId`를 함께 사용한다.

## 9. 신뢰 상태와 원천 대조

재구성 결과와 원천 상태는 별도 단계에서 비교한다.

| 조건 | 신뢰 상태 | 근거 |
| --- | --- | --- |
| 적용 중이거나 원천 대조 전 | `PROCESSING` | 마지막 대조 근거 없음 |
| 원천 버전까지 사건이 존재하고 적용 버전·네 상태가 모두 같음 | `CONSISTENT` | 버전과 필드 일치 |
| 원천 버전까지 필요한 사건이 없거나 사건 버전에 공백이 있음 | `INCOMPLETE` | 원천 버전과 사건 원본의 최대 연속 버전 차이 |
| 적용 버전과 원천 버전은 같고 네 상태 중 하나 이상 다름 | `MISMATCH` | 필드별 차이 |
| 사건은 존재하지만 계약 위반으로 적용 상태가 `FAILED`임 | `MISMATCH` | 적용 오류 코드와 원천·조회 상태 차이 |
| 조회 모델 버전이 원천 버전보다 큼 | `MISMATCH` | 허용되지 않은 선행 적용 |

원천 버전보다 조회 모델 버전이 낮아도 필요한 사건이 원본에 모두 있으면 처리 대기 상태다. 이 경우 `PROCESSING`으로 유지한다. 원본 사건에 버전 공백이 있으면 `INCOMPLETE`로 기록한다.

원천 대조는 다음 값을 저장한다.

- `lastReconciledAt`
- 원천 `lifecycleVersion`
- 사건 원본의 최대 연속 `decisionVersion`
- 조회 모델 `lastAppliedVersion`
- Reservation, 각 Seat, PaymentOrder, 각 PaymentAttempt의 필드별 차이

Reconciliation은 사건 이력을 수정하지 않는다. `INCOMPLETE`와 `MISMATCH`는 조사와 Replay의 입력이다.

## 10. 기존 데이터와 추적 시작점

`lifecycleVersion=0`인 예약은 사건 Writer 활성화 전에 생성된 데이터다. 과거 실행 경로를 증명할 사건이 없으므로 재구성 대상에 포함하지 않는다.

- 조회 결과: `LIFECYCLE_NOT_TRACKED`
- 부분 사건 생성: 수행하지 않음
- 현재 상태 기반 사건 합성: 수행하지 않음
- 운영 지표: `lifecycle.events.legacy_skipped`

신규 추적 Lifecycle의 첫 사건은 항상 `decisionVersion=1`, `eventOrdinal=0`의 `ReservationCreated`다. Consumer는 첫 연속 버전이 1이 아닌 입력을 적용하지 않는다.

## 11. Replay 규칙

Replay는 사건 원본만 사용해 새 `projectionVersion`을 만든다.

1. 대상 `projectionVersion`에 Snapshot과 적용 기록이 없는지 확인한다. 이미 사용 중인 버전은 재사용하지 않는다.
2. `lifecycleId` 단위로 사건을 묶는다.
3. 각 Lifecycle에서 `(decisionVersion, eventOrdinal)` 순서로 결정 단위를 적용한다.
4. 계약 위반, 순번 공백과 알 수 없는 `schemaVersion`을 결과에 기록한다.
5. 기존 활성 버전과 네 상태, Timeline, 경로 분류를 비교한다.
6. 원천 대조로 신뢰 상태와 필드별 차이를 계산한다.
7. 모든 정확성 SLO를 통과한 뒤 조회 설정의 활성 버전을 전환한다. 버전 전환은 Phase 4 범위에 포함하지 않는다.

Replay 중에도 기존 활성 조회 모델은 유지한다. 사건 payload와 기존 조회 모델을 수정하지 않는다.

## 12. 실패 처리

| 실패 | 처리 |
| --- | --- |
| 알 수 없는 `schemaVersion` | 적용 중단, `FAILED`, 오류 코드 `UNSUPPORTED_SCHEMA_VERSION` |
| 사건 조합 위반 | 적용 중단, `FAILED`, `INVALID_EVENT_GROUP` |
| `eventOrdinal` 공백·중복 | 적용 중단, `FAILED`, `INVALID_EVENT_ORDINAL` |
| `fromState` 불일치 | 적용 중단, `FAILED`, `STATE_TRANSITION_MISMATCH` |
| Consumer DB 오류 | 트랜잭션 롤백, 미처리 상태로 재시도 |
| 다음 버전 선도착 | `PENDING`, `PROCESSING`, 앞 버전 도착 뒤 재적용 |
| 원천 사건 누락 확인 | `INCOMPLETE`와 누락 버전 기록 |
| 원천 상태 차이 확인 | `MISMATCH`와 필드별 차이 기록 |

오류 로그에는 `lifecycleId`, `decisionVersion`, `projectionVersion`과 안정적인 오류 코드를 남긴다. 결제대행사 비밀 값과 사건 payload 전체는 기록하지 않는다.

## 13. 기준 시나리오

### 정상 결제

```text
v1 ReservationCreated
v2 PaymentPrepared
v3 PaymentApproved + ReservationCompleted
→ SUCCESS / RESERVED / APPLIED / PAID
→ NORMAL_COMPLETED
```

### 예약 만료

```text
v1 ReservationCreated
v2 ReservationExpired(actor=EXPIRATION_SCHEDULER)
→ EXPIRED / AVAILABLE
→ EXPIRED_WITHOUT_PAYMENT
```

### 경로 A

```text
v1 ReservationCreated
v2 PaymentPrepared
v3 ReservationExpired(actor=EXPIRATION_SCHEDULER, group=G1)
v4 PaymentApproved + PaymentRefundPending(group=G2)
→ EXPIRED / AVAILABLE / REFUND_PENDING / PAID
→ EXPIRATION_FIRST
```

### 경로 B

```text
v1 ReservationCreated
v2 PaymentPrepared
v3 PaymentApproved
   + ReservationExpired(actor=PAYMENT_VERIFICATION)
   + PaymentRefundPending
   (모두 group=G1)
→ EXPIRED / AVAILABLE / REFUND_PENDING / PAID
→ PAYMENT_HANDLER_EXPIRED
```

## 14. 구현 시험 추적표

| 규칙 | 최소 시험 |
| --- | --- |
| 결정 단위 불변 조건 | 묶음 키·순번·스키마·상태 전이 단위 시험 |
| 정상 상태 재구성 | 정상·만료·경로 A/B 고정 데이터 시험 |
| 중복 | 모든 사건 100% 재전달, 중복 적용 0건 |
| 순서 역전 | v3 선도착 후 v2 도착, 최종 결과 일치 |
| 지연 | 중간 결정 보류 중 `PROCESSING`, 도착 후 수렴 |
| Consumer 장애 | 적용 전·트랜잭션 중·커밋 응답 뒤 종료와 재시작 |
| Replay | 새 `projectionVersion` 결과 완전 일치 |
| Reconciliation | `CONSISTENT`, `INCOMPLETE`, `MISMATCH` 기준 데이터 |
| 기존 데이터 | `lifecycleVersion=0` 조회와 후속 변경 건너뜀 |

Phase 4 구현은 계약 오류의 적용 기록에 `errorType`, `attemptCount`, `lastAttemptedAt`, `nextAttemptAt`을 저장한다. 자동 재시도 대상은 별도 스케줄러 정책으로 확정하지 않았고, 계약 오류는 같은 결정의 반복 적용을 차단한다. 운영자가 원인을 수정한 뒤 `retryFailed` 경계로 재시도할 수 있다.

이 명세가 바뀌면 요구사항의 FR-003~FR-013, 품질속성 QA-02~QA-05와 Phase 3·4 시험을 함께 갱신한다.
