# 예약·결제 Lifecycle 추적 기능 MVP 구현 계획

작성일: 2026-09-20(KST)  
기준: 로컬 `develop`, 커밋 `47f97298cebf0fa3426a464d87b6b88b4b8e88b2`  
근거 문서: [문서 지도](README.md) · [요구사항](requirements.md) · [예약·결제 Lifecycle 추적 Baseline](baseline.md) · [기술 선택 ADR](phase-1-technology-adr.md) · [C4 아키텍처](c4-architecture.md) · [SLI·SLO 기준선](sli-slo-baseline.md) · [품질속성 기준](quality-attributes.md)

이 문서는 기존 구현 계획을 실제 구현 가능한 1차 범위로 압축한다. 특정 기술의 도입을 목표로 두지 않는다. Phase 1에서 요구사항을 만족하는 가장 단순한 구성을 비교하고 기술 스택을 확정한다.

문서의 구분은 다음과 같다.

- **현재 사실:** 코드와 Baseline에서 확인한 동작
- **설계 제안:** MVP가 제공할 데이터와 동작
- **구현 계획:** 단계별 산출물과 검증 조건

## 1. 문제와 Baseline

### 현재 사실

현재 시스템은 `Reservation`, `Seat`, `PaymentOrder`, `PaymentAttempt`의 최종 상태를 저장한다. 상태를 만든 커밋 결정, 변경 주체, 변경 이유와 실행 순서는 한 건의 이력으로 연결되어 있지 않다.

`paymentOrderId` 하나로 다음 질문에 답하려면 DB 상태, 로그와 코드를 함께 확인해야 한다.

> 이 결제는 승인되었는데 왜 예약은 `EXPIRED`이고 `PaymentOrder`는 `REFUND_PENDING`인가?

| 항목 | 현재 Baseline |
| --- | --- |
| 확인 Entity/Table | 5개 |
| DB 조회 | 5회 |
| 기본 로그 검색 | 4회 |
| 업무 식별자 | 6종 |
| 진단 단계 | 12단계 |
| Lifecycle 자동 연결 | 불가 |
| 경로 A/B 구분 | 최종 상태만으로 어려움 |

현재 코드는 다음 두 경로에서 같은 최종 상태를 만든다.

| 경로 | 현재 코드의 실행 사실 | 최종 상태 |
| --- | --- | --- |
| A. 예약 만료 처리 선행 | [ReservationExpirationService](../../src/main/java/org/example/ticket/reservation/booking/service/ReservationExpirationService.java)가 예약·좌석을 먼저 만료 처리하고, 이후 [ReservationCompletionService](../../src/main/java/org/example/ticket/reservation/booking/service/ReservationCompletionService.java)가 승인 결과를 반영 | `EXPIRED / AVAILABLE / REFUND_PENDING / PAID` |
| B. 결제 반영 중 만료 판단 | `ReservationCompletionService`가 결제 반영 시점에 만료 기준 초과를 확인하고 같은 트랜잭션에서 예약·좌석·결제를 변경 | `EXPIRED / AVAILABLE / REFUND_PENDING / PAID` |

[MySqlReservationStateRaceTest](../../src/test/java/org/example/ticket/reservation/booking/service/MySqlReservationStateRaceTest.java)는 만료 처리와 결제 반영이 같은 예약의 행 잠금에서 직렬화되는 상황을 검증한다. 현재 상태와 기본 로그에는 개별 운영 건이 경로 A와 B 중 어느 경로를 거쳤는지 확정할 사건 데이터가 없다.

핵심 문제는 다음 문장으로 고정한다.

> 최종 상태는 확인할 수 있지만, 그 상태를 만든 커밋된 결정 사실과 실행 경로를 한 건 단위로 바로 설명하기 어렵다.

## 2. 최종 구현 목표

### 설계 제안

MVP의 최종 목표는 다음 한 문장을 구현과 실험으로 증명하는 것이다.

> 예약·결제의 커밋된 사건을 지속적으로 수집해 한 건의 Lifecycle을 재구성하고, 중복·순서 역전·지연·처리 장애 상황에서도 신뢰할 수 있는 결과를 제공한다.

`paymentOrderId` 또는 `reservationId` 하나를 입력하면 다음 결과를 한 번에 반환한다.

```text
LifecycleView
├── Reservation 상태
├── Seat 상태 목록
├── PaymentOrder 상태
├── PaymentAttempt 상태
├── 시간순 사건 목록
├── 실행 경로
└── 데이터 신뢰 상태
```

최소 실행 경로 분류는 다음과 같다.

- `EXPIRATION_FIRST`: 스케줄러 만료 커밋 후 결제 승인 반영
- `PAYMENT_HANDLER_EXPIRED`: 결제 반영 트랜잭션에서 만료 판단과 만료 처리
- `NORMAL_COMPLETED`: 승인 결제가 유효한 예약에 정상 반영
- `EXPIRED_WITHOUT_PAYMENT`: 결제 승인 없이 예약 만료

핵심 완료 기준은 동일한 최종 상태에서도 `EXPIRATION_FIRST`와 `PAYMENT_HANDLER_EXPIRED`를 사건 데이터로 판별하는 것이다.

## 3. 구현 범위

### 반드시 구현할 여섯 기능

| 번호 | 기능 | MVP 결과 |
| --- | --- | --- |
| 1 | 커밋된 예약·결제 사건 생성 및 수집 | 업무 트랜잭션 결과와 일치하는 사건 원본 |
| 2 | 사건 기반 Lifecycle 상태 재구성 | 네 상태 축, 시간순 사건, 실행 경로, 데이터 신뢰 상태 |
| 3 | 단일 식별자 조회 | `paymentOrderId` 또는 `reservationId`로 같은 Lifecycle 조회 |
| 4 | 경로 A/B 구분 | 만료 사건의 주체, 커밋 순서와 커밋 묶음으로 판정 |
| 5 | 중복·순서 역전·지연 처리 | 사건 전달 순서와 횟수에 관계없이 같은 최종 결과로 수렴 |
| 6 | 장애 후 재처리와 원천 대조 | 재처리(Replay) 결과 동일성 및 원천 MySQL 상태와의 일치 여부 제공 |

이 여섯 기능의 완료를 스트리밍 데이터 엔지니어링 확장 1차 완료로 본다.

### 초기 범위에서 제외할 항목

다음 항목은 후속 확장 후보로만 남긴다.

- 고객 문의 전용 화면과 복잡한 관리자 UI
- 장기 BI 통계, 매출·고객 행동 분석
- 자동 환불 처리와 복잡한 운영 보정 절차
- 장기 보존 정책 최적화와 별도 감사 시스템
- 다수의 이상 탐지 규칙
- 전체 시스템의 전역 사건 순서와 다중 리전 구성
- 사람 3명 이상을 이용한 정식 사용성 실험
- 진단 시간 감소율의 사전 목표값

MVP 조회는 API 또는 개발자용 최소 조회 도구까지 구현한다. 화면 완성도는 완료 조건에 포함하지 않는다.

## 4. 사건 모델

### 4.1 모델 원칙

설계 제안은 사건 본문과 처리 메타데이터를 분리한다.

- **사건 본문:** 업무에서 커밋된 결정과 상태 변경
- **처리 메타데이터:** 수집 시각, 처리 위치, 중복 여부, 재처리 실행 ID, 오류와 체크포인트

하나의 사건이 여러 Entity를 바꿀 수 있으므로 `fromState`와 `toState`를 단일 값으로 두지 않는다. `stateChanges` 목록 안에 Entity별 변경을 기록한다.

```text
stateChanges[]
├── entityType
├── entityId
├── fromState
└── toState
```

같은 업무 트랜잭션에서 여러 사건이 함께 커밋될 수 있다. MVP는 이를 `commitGroupId`로 묶는다. 경로 B의 `PaymentApproved`, `ReservationExpired`, `PaymentRefundPending`은 같은 `commitGroupId`를 가진다.

### 4.2 MVP 필드

| 필드 | 포함 이유 |
| --- | --- |
| `eventId` | 동일 사건 재전달을 식별하고 중복 적용을 막음 |
| `eventType` | 어떤 업무 결정인지 판정하고 상태 재구성 규칙을 선택 |
| `schemaVersion` | 사건 계약 변경 시 기존 데이터를 계속 해석 |
| `lifecycleId` | 한 예약·결제 흐름을 묶고 같은 키로 순차 처리 |
| `reservationId` | 주문 생성 전 사건부터 전체 Lifecycle의 기준 식별자로 사용 |
| `paymentOrderId` | 결제 준비 이후 조회와 주문 사건 연결에 사용. 이전 사건에서는 비어 있을 수 있음 |
| `paymentAttemptId` | 승인 사건을 정확한 결제 시도에 연결. 이전 사건에서는 비어 있을 수 있음 |
| `seatIds` | 예약 생성·완료·만료 시 영향을 받은 좌석을 당시 값으로 보존 |
| `stateChanges` | 각 Entity의 `entityType`, `entityId`, `fromState`, `toState`를 함께 기록 |
| `reasonCode` | 같은 최종 상태를 만든 업무 이유를 설명 |
| `actorType` | `EXPIRATION_SCHEDULER`, `PAYMENT_VERIFICATION` 등 변경 주체로 경로 A/B를 구분 |
| `occurredAt` | 사건의 업무 발생 시각을 시간순 조회에 사용 |
| `sourceOrder` | 같은 Lifecycle의 업무 결정 순서를 나타내는 `(decisionVersion, eventOrdinal)` |
| `commitGroupId` | 같은 트랜잭션에서 함께 커밋된 사건을 식별 |

`lifecycleId`는 예약 Lifecycle을 안정적으로 가리켜야 한다. 현재 구조에서는 `reservationId` 기반 값이 첫 후보이며, Phase 1에서 생성 시점과 형식을 확정한다.

`sourceOrder`는 애플리케이션 서버 시각과 Outbox의 자동 증가 키로 만들지 않는다. Phase 1에서 확정한 `decisionVersion`, `eventOrdinal` 조합으로 재처리 뒤에도 같은 업무 결정 순서를 만든다.

### 4.3 확장 필드

| 필드 | MVP 제외 이유 | 추가 시점 |
| --- | --- | --- |
| `actorInstanceId` | 경로 A/B는 `actorType`으로 판정 가능 | 복수 인스턴스 장애 분석 필요 시 |
| 복잡한 `causationId` 연결 | MVP는 Lifecycle 키, 커밋 묶음과 원천 순서로 재구성 가능 | 서비스 간 연쇄 사건이 늘어날 때 |
| `retryOrdinal` | 중복 제거는 `eventId`로 가능 | 재시도 행동 분석이 제품 요구가 될 때 |
| 세부 규칙 변경 이력 | `schemaVersion`으로 초기 호환성 관리 가능 | 경로 판정 규칙을 여러 버전으로 운영할 때 |
| 장기 감사 필드 | 별도 감사 시스템이 초기 범위에 없음 | 감사·규제 요구가 생길 때 |
| 잠금 획득·대기 등 세밀한 실행 추적 | 최종 경로 판정에는 커밋 사건이면 충분 | 경합 성능 분석 기능을 확장할 때 |
| HTTP `correlationId`, 스케줄러 `runId` | 원인 판정은 업무 ID, 주체와 커밋 순서로 가능 | 로그 직접 이동 기능을 추가할 때 |

처리 지연 계산에 필요한 수집·가시화 시각은 처리 메타데이터에 둔다. 업무 사건 계약을 성능 측정 필드로 확장하지 않는다.

### 4.4 최소 사건 종류

추가 도메인 사건 없이 다음 여섯 종류로 현재 핵심 흐름을 표현할 수 있다.

| 사건 | 현재 코드의 의미 | 주요 `stateChanges` |
| --- | --- | --- |
| `ReservationCreated` | 좌석 선점과 결제 대기 예약 생성 | Reservation `∅ → PENDING_PAYMENT`, Seat `AVAILABLE → LOCKED` |
| `PaymentPrepared` | 결제 주문과 결제 시도 생성 | PaymentOrder `∅ → READY`, PaymentAttempt `∅ → READY` |
| `PaymentApproved` | 승인 정보가 내부 결제 시도에 커밋 | PaymentAttempt `READY → PAID` |
| `ReservationCompleted` | 유효한 예약에 승인 결제 적용 | Reservation `PENDING_PAYMENT → SUCCESS`, Seat `LOCKED → RESERVED`, PaymentOrder `READY → APPLIED` |
| `ReservationExpired` | 스케줄러 또는 결제 반영 서비스가 예약 만료 | Reservation `PENDING_PAYMENT → EXPIRED`, Seat `LOCKED → AVAILABLE` |
| `PaymentRefundPending` | 만료된 예약의 승인 결제를 환불 대기로 전환 | PaymentOrder `READY → REFUND_PENDING` |

현재 코드의 `PAID_UNAPPLIED`는 `ReservationCompletionService`의 한 트랜잭션 안에서 `APPLIED` 또는 `REFUND_PENDING` 직전에 설정된다. 독립 커밋 상태로 남는 현재 경로가 없으므로 MVP 사건으로 만들지 않는다. 향후 별도 트랜잭션에서 커밋되면 사건 모델을 확장한다.

재시도, 처리 장애, 재처리와 원천 대조는 도메인 사건 종류를 추가하지 않고 처리 메타데이터와 검증 결과로 관리한다.

### 4.5 경로 A/B 표현

| 구분 | 사건 순서와 커밋 묶음 | 판정 규칙 |
| --- | --- | --- |
| 경로 A | `ReservationExpired(actor=EXPIRATION_SCHEDULER, group=G1)` → `PaymentApproved(group=G2)` → `PaymentRefundPending(group=G2)` | 스케줄러 만료 결정의 `decisionVersion`이 승인 반영 결정보다 작고 커밋 묶음이 다름 |
| 경로 B | `PaymentApproved(group=G1)` + `ReservationExpired(actor=PAYMENT_VERIFICATION, group=G1)` + `PaymentRefundPending(group=G1)` | 만료 주체가 결제 검증이고 세 사건의 `commitGroupId`가 같음 |

`occurredAt`은 화면의 시간 정보로 사용한다. 경로 판정은 재현 가능한 `decisionVersion`, `eventOrdinal`, `actorType`, `commitGroupId`를 사용한다.

## 5. 상태 재구성 모델

### 설계 제안

네 Entity의 상태 모델을 유지하고 한 Lifecycle 조회에서 합성한다.

```text
LifecycleSnapshot
├── reservationStatus
├── seatStatuses[seatId]
├── paymentOrderStatus
├── paymentAttemptStatuses[paymentAttemptId]
├── timeline[event]
├── pathClassification
└── trustStatus
```

| 상태 축 | MVP에서 사용하는 상태 |
| --- | --- |
| Reservation | `PENDING_PAYMENT`, `SUCCESS`, `EXPIRED` |
| Seat | `LOCKED`, `RESERVED`, `AVAILABLE` |
| PaymentOrder | `READY`, `APPLIED`, `REFUND_PENDING` |
| PaymentAttempt | `READY`, `PAID` |

현재 enum의 `LOCKED` 예약 상태, `UNAVAILABLE` 좌석 상태, `FAILED`, `UNKNOWN`, `MANUAL_REVIEW`, `REFUNDED`, `REQUESTED` 등은 입력 계약이 알 수 있는 값으로는 보존한다. MVP 재구성 규칙과 시나리오는 현재 확인된 핵심 전이에 집중한다.

### 재구성 규칙

1. `lifecycleId`별로 사건을 모은다.
2. 동일 `eventId`는 한 번만 적용한다.
3. `(decisionVersion, eventOrdinal)`로 사건을 정렬하고 같은 `commitGroupId`를 하나의 커밋 단위로 적용한다.
4. 각 사건의 `stateChanges`를 Entity별 상태에 적용한다.
5. 현재 상태와 사건 조합으로 실행 경로를 분류한다.
6. 처리 상태와 마지막 원천 대조 결과로 데이터 신뢰 상태를 계산한다.

데이터 신뢰 상태는 네 값으로 제한한다.

| 값 | 의미 |
| --- | --- |
| `PROCESSING` | 수집된 사건을 아직 모두 현재 상태에 반영하지 못함 |
| `CONSISTENT` | 현재 상태가 마지막 원천 대조에서 MySQL과 일치 |
| `INCOMPLETE` | 필수 선행 사건이나 원천 순서에 공백이 있음 |
| `MISMATCH` | 재구성 결과와 원천 MySQL 상태가 다름 |

원천 대조(Reconciliation)는 `Reservation`, 연결된 모든 `Seat`, `PaymentOrder`, 모든 `PaymentAttempt`의 현재 상태를 Lifecycle 결과와 비교한다. 이 결과는 도메인 사건 이력을 변경하지 않고 신뢰 상태와 차이 정보만 갱신한다.

## 6. 핵심 실패 시나리오

| 시나리오 | 입력 또는 장애 | 기대 결과 |
| --- | --- | --- |
| 정상 | 예약 생성 → 결제 준비 → 승인 → 예약 완료 | `SUCCESS / RESERVED / APPLIED / PAID`, 경로 `NORMAL_COMPLETED` |
| 만료 | 예약 생성 → 선택적 결제 준비 → 스케줄러 만료 | `EXPIRED / AVAILABLE`, 경로 `EXPIRED_WITHOUT_PAYMENT` |
| 경로 A | 스케줄러 만료 커밋 후 승인 반영 | 공통 최종 상태와 `EXPIRATION_FIRST` 판정 |
| 경로 B | 결제 반영 중 만료 판단, 한 트랜잭션에서 만료·환불 대기 | 공통 최종 상태와 `PAYMENT_HANDLER_EXPIRED` 판정 |
| 중복 | 같은 `eventId`를 여러 번 전달 | 상태 변경은 한 번만 적용, 중복 적용 0 |
| 순서 역전 | 승인·만료 관련 사건의 전달 순서를 변경 | `(decisionVersion, eventOrdinal)`로 재구성한 최종 상태와 경로가 기준 결과와 일치 |
| 지연 | 일부 사건을 보류했다가 나중에 전달 | 중간에는 `PROCESSING` 또는 `INCOMPLETE`, 전달 후 기준 결과로 수렴 |
| 처리 장애 | 사건 처리 도중 프로세스 종료 후 재시작 | 마지막 확정 위치부터 재개, 누락 0, 중복 적용 0 |
| Replay | 같은 원천 범위를 처음부터 다시 처리 | 동일한 사건 이력, 현재 상태와 경로 생성 |
| 원천 대조 | MySQL 현재 상태와 Lifecycle 결과 비교 | 필드별 일치 시 `CONSISTENT`, 차이가 있으면 `MISMATCH`와 차이 표시 |

## 7. 구현 Phase

### Phase 0. Baseline 및 범위 확정

상세 문서: [진행 전 계획](phases/phase-0-baseline/plan.md) · [실행 결과](phases/phase-0-baseline/result.md)

| 항목 | 내용 |
| --- | --- |
| 목표 | 현재 문제, MVP 여섯 기능과 제외 범위를 고정하고 예상 사건량 측정 |
| 산출물 | 이 범위 문서, 기존 Baseline, 작업별 사건 수, 정상·최대 예상 발생률 |
| 검증 | 기존 예약·결제 부하 시나리오의 요청률과 6개 사건의 발생 조건으로 사건량 산출 |
| 완료 조건 | 구현 범위 변경 없이 Phase 1 비교에 사용할 예상 사건량 확보 |

### Phase 1. 사건 모델과 기술 선택

상세 문서: [진행 전 계획](phases/phase-1-architecture/plan.md) · [실행 결과](phases/phase-1-architecture/result.md)

| 항목 | 내용 |
| --- | --- |
| 목표 | 6개 사건 계약, 커밋 사실 생성 위치, 순서·중복·재시작·Replay 규칙과 기술 스택 확정 |
| 산출물 | 사건 계약서, 경로 A/B 예제 데이터, 기술 비교표, 선택 결정 기록, 최소 실행 구성도 |
| 검증 | 정상·A·B·롤백 예제를 후보 구성에 대입해 원자성, 순서, 복구와 Replay 가능 여부 점검 |
| 완료 조건 | 8절의 필수 질문에 답하고 실제 제품·라이브러리·버전·배포 단위를 확정 |

### Phase 2. 사건 생성과 수집

상세 문서: [진행 전 계획](phases/phase-2-event-production/plan.md) · [실행 결과](phases/phase-2-event-production/result.md)

| 항목 | 내용 |
| --- | --- |
| 목표 | 예약·결제의 커밋 결과와 일치하는 사건을 지속적으로 생성·수집 |
| 산출물 | 사건 생성 코드, 내구성 있는 원본, 수집기, 체크포인트와 기본 관측 지표 |
| 검증 | 정상 트랜잭션은 사건 존재, 롤백은 상태 변경 사건 없음, 재시도는 업무 사건 중복 없음 |
| 완료 조건 | 기존 정합성 테스트 통과, 커밋 사건 누락 0, 롤백 사건 0, 추가 전후 API 지연과 DB lock wait 비교 완료 |

### Phase 3. Lifecycle 재구성

상세 문서: [진행 전 계획](phases/phase-3-reconstruction/plan.md) · [실행 결과](phases/phase-3-reconstruction/result.md)

| 항목 | 내용 |
| --- | --- |
| 목표 | 사건에서 네 상태 축, 시간순 이력, 실행 경로와 신뢰 상태 생성 |
| 산출물 | 상태 재구성기, 현재 상태 저장, 사건 이력 저장, 경로 분류기 |
| 검증 | 정상 결제, 예약 만료, 경로 A, 경로 B, 재시도 시나리오를 원천 DB와 비교 |
| 완료 조건 | 기준 시나리오의 최종 상태 일치율 100%, A/B 판정 정확도 100% |

### Phase 4. 스트리밍 실패 조건 처리

상세 문서: [진행 전 계획](phases/phase-4-failure-recovery/plan.md) · [실행 결과](phases/phase-4-failure-recovery/result.md)

| 항목 | 내용 |
| --- | --- |
| 목표 | 중복·순서 역전·지연·계약 오류에서 같은 최종 결과로 수렴하고 Replay·원천 대조 근거를 남김 |
| 산출물 | 중복 제거, 순서 보류, 실패 적용 기록, 별도 Projection Replay, Reconciliation |
| 검증 | F-01~F-04, F-06, F-09~F-11과 소규모 F-12를 격리된 JPA 시험으로 실행 |
| 완료 조건 | 결정적 처리 범위 통과. 프로세스 중단·MySQL 장애·30,000건 처리량은 별도 검증 전제로 기록 |

### Phase 5. 조회 및 최종 검증

상세 문서: [진행 전 계획](phases/phase-5-query-validation/plan.md) · [실행 결과](phases/phase-5-query-validation/result.md)

| 항목 | 내용 |
| --- | --- |
| 목표 | 하나의 업무 식별자로 Lifecycle을 조회하고 Baseline 질문에 답함 |
| 산출물 | `paymentOrderId`·`reservationId` 조회 기능, 원천 대조 기능, 최종 실험 문서 |
| 검증 | 경로 A/B 각각을 조회하고 중복·역전·지연·장애·Replay·원천 대조 결과 확인 |
| 완료 조건 | Lifecycle 조회 1회로 네 상태, 사건, 경로, 신뢰 상태를 확인하고 10절 완료 조건 충족 |

## 8. Phase 1 기술 선택 기준

기술 선택은 Phase 1 마지막에 수행한다. 다음 요구사항에 답한 뒤 가장 단순한 구성을 선택한다.

### 8.1 먼저 확정할 질문

1. 커밋된 업무 사실을 어디에서 얻는가?
2. 업무 상태와 사건 기록의 원자성을 어떻게 보장하는가?
3. Lifecycle 단위 순서를 어떤 원천 정보로 결정하는가?
4. 동일 사건 중복을 어떻게 식별하고 한 번만 적용하는가?
5. 처리 결과와 체크포인트 중 어느 지점까지 확정한 뒤 다음 사건을 읽는가?
6. 늦게 들어온 사건을 언제, 어떤 상태로 다시 적용하는가?
7. 같은 원천 범위를 재처리했을 때 같은 결과를 만드는가?

### 8.2 사건 생성 위치 비교

트랜잭션 아웃박스(Transactional Outbox)는 업무 데이터와 발행할 사건을 같은 DB 트랜잭션에 기록한 뒤 별도 처리기가 전달하는 방식이다. 변경 데이터 캡처(Change Data Capture, CDC)는 원천 DB의 커밋 변경을 읽는 방식이다.

| 후보 | ImTicket 적합성 | 커밋 원자성 | 업무 의미 | 순서·중복 처리 | 장애 복구·Replay | 운영 복잡도·규모 대비 비용 | Phase 1 판단 기준 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 애플리케이션 직접 발행 | 낮음 | DB 커밋과 발행 사이 실패 구간 존재 | 높음 | 발행 식별자와 키별 순서를 별도 구현 | 내구성 있는 원본과 소비 위치를 별도 구성 | 초기 비용은 낮고 누락 방지 비용은 증가 | 누락 없는 커밋 사건 요구를 충족하기 어려우면 제외 |
| 트랜잭션 아웃박스 | 높음 | 업무 상태와 사건 원본을 같은 트랜잭션에 기록 | 높음 | `eventId`와 아웃박스 순번으로 구성 가능 | 보존된 아웃박스와 처리 위치에서 재개·Replay 가능 | 중간, 현재 단일 서비스 규모에 적용 가능 | 사유·주체를 보존하면서 트랜잭션 지연을 허용 범위로 유지하는지 확인 |
| 도메인 테이블 CDC | 중간 | 커밋된 DB 변경을 읽음 | 상태 변화는 확보하며 사유·주체 정보는 추가 설계 필요 | 원천 로그 위치로 순서를 정하고 중복 반영을 차단 | 원천 로그 보존 범위에서 재개·Replay 가능 | 중간 이상, CDC 운영 책임 추가 | 여러 테이블 변경을 한 업무 결정으로 묶고 A/B 사유를 복원 가능한지 확인 |
| 애플리케이션 아웃박스 + CDC 전달 | 높음 | 아웃박스 원자성과 CDC 원천 위치 사용 | 높음 | `eventId`, Lifecycle 키와 원천 로그 위치 활용 | 소비 위치와 원천 로그 보존 범위에서 재개·Replay 가능 | 높음, 현재 규모에서 추가 구성의 효용을 입증해야 함 | DB 조회 전달의 처리량·복구 요구가 측정 한계를 넘을 때 선택 |

현재 요구에는 트랜잭션 아웃박스가 가장 단순한 우선 후보다. Phase 1에서 사건량, 기존 트랜잭션 지연과 운영 비용을 측정한 뒤 확정한다. 직접 발행은 원자성 증거를 제공할 때만 후보로 유지한다.

### 8.3 전달 방식 비교

| 후보 | 중복·순서 | 장애 복구·Replay | 운영 복잡도 | 선택 조건 |
| --- | --- | --- | --- | --- |
| DB 기반 아웃박스 조회 | Lifecycle 키별 정렬과 멱등 처리를 직접 구현 | 보존된 아웃박스 범위에서 가능 | 낮음 | 예상 사건량을 여유 있게 처리하고 단일 프로젝트 운영을 단순화할 때 |
| 내구성 있는 메시징/이벤트 로그 | 키 단위 순서, 소비 위치와 처리 대기량 관리에 유리 | 보존 범위 Replay에 유리 | 중간 이상 | 장애 실험, 처리량 또는 독립 확장 요구가 DB 방식의 한계를 넘을 때 |
| 프로세스 내부 큐 | 프로세스 범위에서만 제공 | 재시작 시 원본과 위치 보존을 별도 구현 | 낮음 | 내구성·Replay 요구를 단독으로 충족하지 못하므로 보조 용도로만 사용 |

Phase 1에서는 실제 제품, 보존 설정, 키 분배 방식과 장애 시 재개 위치까지 확정한다.

### 8.4 Lifecycle 처리 방식 비교

| 후보 | 적합성 | 장점 | 비용 | 선택 조건 |
| --- | --- | --- | --- | --- |
| 멱등 단순 Consumer | 높음 | 6개 사건과 키 단위 순서를 작은 코드로 처리 | 지연·재정렬·체크포인트를 직접 구현 | 예상 사건량과 지연 규칙을 충분히 처리할 때 |
| 상태 저장 스트림 처리(Stateful stream processing) | 조건부 | 상태, 시간, 재정렬과 체크포인트 지원 | 학습·배포·운영 비용 증가 | 순서 역전·지연 범위와 처리량이 단순 Consumer의 범위를 넘을 때 |
| 주기 배치 재구성 | 낮음 | 구현과 Replay가 단순 | 지속 수집과 빠른 조회 요구 충족이 어려움 | 원천 대조와 복구 보조 작업에 사용 |

### 8.5 상태 저장, 중복 제거와 Replay

| 결정 | 단순 우선안 | 대안으로 넘어갈 조건 |
| --- | --- | --- |
| 상태·이력 저장 | 기존 관계형 DB의 Lifecycle 현재 상태와 사건 이력 | 조회량·쓰기 경합·보존량이 측정 한계를 넘을 때 별도 저장소 검토 |
| 중복 제거 | `eventId` 유일성 제약과 상태 갱신을 한 트랜잭션에서 처리 | 처리 엔진의 원자적 상태·체크포인트가 더 단순한 경우 해당 기능 사용 |
| 순서 | `lifecycleId`별 `(decisionVersion, eventOrdinal)` 적용 | 다중 파티션·지연 범위가 커지면 상태 저장 처리의 순서 기능 검토 |
| 체크포인트 | 결과 커밋 이후 원천 위치 확정 | 전달 계층이 원자적 위치·상태 확정을 제공하면 해당 기능 사용 |
| Replay | 보존된 사건 원본에서 빈 Lifecycle 결과를 다시 생성 | 무중단 전환이 필요하면 새 버전 결과에 재생 후 교체 |
| 원천 대조 | `paymentOrderId` 또는 `reservationId` 단위 MySQL 비교 | 전체 대조 비용이 커지면 범위 분할·증분 대조 검토 |

Phase 1의 최종 산출물은 선택 기술 이름과 버전, 구성 요소, 데이터 흐름, 장애 재개 위치, 선택·제외 이유를 담은 결정 기록이다.

## 9. 검증 지표

### 최소 수집 지표

| 지표 | 정의 |
| --- | --- |
| 입력 사건 수 | 수집 경계에 들어온 전체 사건 수. 중복 포함 |
| 처리 사건 수 | 중복 제거 후 상태 재구성에 적용한 고유 사건 수 |
| 누락 건수 | 기준 원천 사건 중 Lifecycle 결과에 반영되지 않은 사건 수 |
| 중복 적용 건수 | 같은 `eventId`가 상태에 두 번 이상 적용된 수 |
| 최종 상태 일치율 | 원천 MySQL과 네 상태 축이 모두 일치한 Lifecycle 비율 |
| p95 처리 지연 | 사건 수집 시점부터 현재 상태 조회에 반영될 때까지 시간의 95백분위수 |
| 최대 처리 대기량(backlog) | 실험 중 아직 확정 처리하지 못한 사건의 최대 수 |
| 장애 복구 시간 | 처리기 재시작부터 backlog 해소와 정상 지연 회복까지 걸린 시간 |

p95 지연, 최대 backlog와 복구 시간의 사전 목표값은 두지 않는다. Phase 4 실측값과 현재 사건량을 함께 기록해 이후 기준으로 사용한다. 정확성 기준은 누락 0, 중복 적용 0, 최종 상태 일치율 100%로 고정한다.

### 필수 실험

| 실험 | 입력 조건 | 성공 기준 |
| --- | --- | --- |
| 정상 | 정상 예약·결제 Lifecycle | 입력 고유 사건이 모두 처리되고 원천 최종 상태와 일치 |
| 중복 | 동일 사건을 추가로 재전달 | 중복 적용 0, 기준 실행과 같은 최종 결과 |
| 순서 역전 | 결제 승인·만료 사건의 전달 순서를 교환 | `(decisionVersion, eventOrdinal)` 기준으로 같은 최종 상태와 경로 생성 |
| 지연 | 사건 일부를 보류한 뒤 전달 | 보류 중 신뢰 상태 표시, 전달 후 원천 상태로 수렴 |
| 장애 | 처리 도중 프로세스 종료 후 재시작 | 누락 0, 중복 적용 0, backlog 해소와 복구 시간 측정 |
| Replay | 같은 원천 범위를 빈 결과에 다시 처리 | 사건 이력, 최종 상태와 경로가 기준 실행과 동일 |
| 원천 대조 | MySQL과 Lifecycle 결과 비교, 의도적 불일치 포함 | 일치는 `CONSISTENT`, 차이는 `MISMATCH`로 판정 |

### Baseline 재측정

Phase 5에서 기존 질문을 그대로 사용한다.

> 이 결제는 승인되었는데 왜 예약은 `EXPIRED`이고 `PaymentOrder`는 `REFUND_PENDING`인가?

| 항목 | 현재 | Phase 5 측정 |
| --- | --- | --- |
| 확인 Entity/Table | 5개 | 직접 원천 테이블 확인 수 기록 |
| DB 조회 | 5회 | Lifecycle 조회 외 추가 DB 조회 수 기록 |
| 로그 검색 | 4회 | 추가 로그 검색 수 기록 |
| 업무 식별자 | 6종 | 사용자가 입력한 식별자 종류 기록 |
| 진단 단계 | 12단계 | 입력부터 경로 확인까지 단계 기록 |
| Lifecycle 자동 연결 | 불가 | 네 상태 축과 사건 연결 여부 확인 |
| 경로 A/B | 구분 어려움 | 제어된 A/B 데이터의 판정 정확도 측정 |

사전 감소율은 정하지 않는다. MVP 기능 요구상 Lifecycle 조회 자체는 1회여야 한다. 원천 DB와 로그의 추가 확인 수는 실험 결과를 그대로 기록한다.

## 10. 완료 조건

다음 조건을 모두 만족하면 1차 구현을 완료한다.

- 6개 사건 계약과 MVP 필드가 문서·코드·테스트에서 일치한다.
- 정상 트랜잭션의 사건이 존재하고 롤백된 상태 변경 사건은 존재하지 않는다.
- `paymentOrderId`와 `reservationId` 각각으로 같은 Lifecycle을 한 번 조회할 수 있다.
- 조회 결과에 네 상태 축, 사건 목록, 실행 경로와 데이터 신뢰 상태가 포함된다.
- 경로 A와 B의 동일한 최종 상태를 정확히 구분한다.
- 중복, 순서 역전과 지연 이후 최종 상태가 원천과 일치한다.
- 처리기 중단 후 누락과 중복 적용 없이 재개한다.
- 같은 사건 원본을 Replay하면 같은 사건 이력, 상태와 경로를 만든다.
- 원천 MySQL 대조에서 일치와 불일치를 정확히 표시한다.
- 기존 예약·결제 정합성 테스트가 유지되고 사건 기록 전후 성능 차이가 문서화된다.
- 9절의 최소 지표와 필수 실험 결과가 문서에 남는다.

후속 확장은 3절에서 제외한 화면, 분석, 자동 환불, 이상 탐지, 장기 보존과 운영 기능을 실제 요구 순서대로 선택한다.

### 1. 무엇을 만드는가?

예약·결제의 커밋 사건을 지속적으로 수집하고, 한 업무 식별자로 네 상태 축·사건 이력·실행 경로·데이터 신뢰 상태를 조회하는 Lifecycle 재구성 기능을 만든다.

### 2. 왜 필요한가?

현재는 5개 테이블, 5회 DB 조회, 4회 로그 검색과 12단계를 거쳐도 같은 최종 상태를 만든 경로 A/B를 개별 건에서 확정하기 어렵다. 커밋 사건을 보존하면 최종 상태와 실제 결정 경로를 한 결과에서 설명할 수 있다.

### 3. 어디까지 구현하면 1차 완료인가?

여섯 사건의 생성·수집, Lifecycle 재구성, 단일 조회, 경로 A/B 판정, 중복·순서 역전·지연 처리, 장애 후 Replay와 원천 MySQL 대조까지 구현하고 7개 필수 실험에서 정확성을 확인하면 1차 완료다.
