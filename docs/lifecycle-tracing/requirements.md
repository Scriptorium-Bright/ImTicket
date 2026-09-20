# 예약·결제 생명주기 추적 요구사항

작성일: 2026-09-20(KST)  
상태: MVP 요구사항 기준  
기준 코드: `develop`, `47f97298cebf0fa3426a464d87b6b88b4b8e88b2`  
관련 문서: [문서 지도](README.md) · [현재 추적 기준선](baseline.md) · [기술 선택 ADR](phase-1-technology-adr.md) · [데이터 모델과 ERD](data-model-erd.md) · [재구성 명세](lifecycle-reconstruction-spec.md) · [SLI·SLO 기준선](sli-slo-baseline.md)

## 1. 목적

예약 생성부터 결제 결과 반영까지 커밋된 업무 사건을 보존하고, 하나의 업무 식별자로 예약·좌석·결제의 상태와 상태를 만든 실행 경로를 조회한다. 중복 전달, 전달 순서 역전, 지연과 처리기 재시작 이후에도 같은 생명주기 결과를 재구성해야 한다.

기준 질문은 다음과 같다.

> 이 결제는 승인되었는데 왜 예약은 `EXPIRED`이고 `PaymentOrder`는 `REFUND_PENDING`인가?

## 2. 현재 기준선과 해결 범위

| 항목 | 현재 기준선 | MVP가 제공할 결과 |
| --- | ---: | --- |
| 확인 Entity/Table | 5개 | 생명주기 조회 결과에 네 상태 축과 연결 관계 포함 |
| DB 조회 | 5회 | `paymentOrderId` 또는 `reservationId`로 생명주기 조회 1회 |
| 기본 로그 검색 | 4회 | 실행 경로 판정에 추가 로그 검색이 필요하지 않음 |
| 업무 식별자 | 6종 | 조회 입력 식별자 1종 |
| 진단 단계 | 12단계 | 조회 결과에서 상태·사건·경로·신뢰 상태 확인 |
| 생명주기 자동 연결 | 불가 | 예약 단위로 자동 연결 |
| 경로 A/B 판정 | 최종 상태만으로 판정 어려움 | 커밋 사건의 주체·순서·커밋 묶음으로 판정 |

현재 동일한 최종 상태를 만드는 두 경로를 구분해야 한다.

| 경로 | 의미 | 경로 분류 |
| --- | --- | --- |
| A | 예약 만료 트랜잭션이 먼저 커밋되고 결제 승인 결과가 뒤에 반영됨 | `EXPIRATION_FIRST` |
| B | 결제 반영 트랜잭션이 만료를 판단하고 예약·좌석·결제를 함께 변경함 | `PAYMENT_HANDLER_EXPIRED` |

## 3. 이해관계자와 사용 목적

| 역할 | 필요한 결과 |
| --- | --- |
| 개발자 | 상태 전이, Lifecycle별 업무 결정 순서, 커밋 묶음과 코드 규칙을 한 결과에서 확인 |
| 운영 담당자 | 결제 주문 또는 예약 식별자로 현재 상태와 데이터 신뢰 상태 확인 |
| 품질 담당자 | 중복·역전·지연·장애·재처리 시나리오의 기대 결과 검증 |
| 서비스 소유자 | 사건량, 처리 지연, 처리 대기량과 원천 불일치 상태 확인 |

고객 문의 전용 화면은 MVP 범위에 포함하지 않는다. 조회 기능은 개발자 또는 운영자용 API와 최소 조회 도구로 제공한다.

## 4. 용어와 상태 모델

- **생명주기(Lifecycle):** 하나의 예약에서 좌석 선점, 결제 준비, 승인 반영, 완료 또는 만료까지 이어지는 업무 흐름이다.
- **업무 사건:** 트랜잭션이 커밋한 상태 변경과 결정 사유를 표현하는 불변 기록이다.
- **트랜잭션 아웃박스(Transactional Outbox):** 업무 상태와 사건을 같은 데이터베이스 트랜잭션에 저장하는 방식이다.
- **조회 모델(Projection):** 사건을 순서대로 적용해 조회에 적합한 현재 상태와 실행 경로를 만든 결과다.
- **재처리(Replay):** 보존된 사건 원본을 처음부터 적용해 조회 모델을 다시 만드는 작업이다.
- **원천 대조(Reconciliation):** 조회 모델과 원천 MySQL의 현재 상태를 비교하는 작업이다.

조회 결과는 다음 네 상태 축을 합성한다.

| 상태 축 | 핵심 상태 |
| --- | --- |
| Reservation | `PENDING_PAYMENT`, `SUCCESS`, `EXPIRED` |
| Seat | `LOCKED`, `RESERVED`, `AVAILABLE` |
| PaymentOrder | `READY`, `APPLIED`, `REFUND_PENDING` |
| PaymentAttempt | `READY`, `PAID` |

기존 열거형에 존재하는 추가 상태는 입력 사건에 나타나면 원문 값을 보존한다. MVP 경로 분류와 인수 시나리오는 위 핵심 상태 전이를 기준으로 한다.

## 5. 기능 요구사항

| ID | 요구사항 | 인수 기준 |
| --- | --- | --- |
| FR-001 | 업무 상태와 사건의 원자성 | 정상 커밋에는 상태와 사건이 함께 존재하고, 롤백에는 둘 다 존재하지 않는다. |
| FR-002 | 커밋 사건 원본 보존 | 여섯 사건 종류와 필수 계약 필드를 `lifecycle_event`에 저장하고 재처리에 사용할 수 있다. |
| FR-003 | 생명주기 순서 결정 | 같은 예약의 사건은 `(decisionVersion, eventOrdinal)`로 항상 같은 순서를 만든다. |
| FR-004 | 커밋 묶음 식별 | 같은 트랜잭션에서 생성된 사건은 같은 `commitGroupId`를 가지며, 같은 Lifecycle의 사건은 같은 `decisionVersion`도 가진다. |
| FR-005 | 멱등 적용 | 동일 `eventId`가 반복 전달돼도 상태 변경은 한 번만 적용된다. |
| FR-006 | 상태 재구성 | 사건 원본에서 네 상태 축과 시간순 사건 목록을 만든다. |
| FR-007 | 실행 경로 분류 | 정상 완료, 결제 없는 만료, 경로 A와 경로 B를 사건 근거로 분류한다. |
| FR-008 | 지연·순서 역전 처리 | 높은 순번의 사건을 먼저 받으면 적용을 보류하고, 빠진 사건이 도착하면 연속된 버전까지 다시 구성한다. |
| FR-009 | 단일 식별자 조회 | `paymentOrderId`와 `reservationId` 각각으로 동일한 생명주기 결과를 조회한다. |
| FR-010 | 데이터 신뢰 상태 | 조회 결과에 `PROCESSING`, `CONSISTENT`, `INCOMPLETE`, `MISMATCH` 중 하나와 판정 근거를 제공한다. |
| FR-011 | 원천 대조 | Reservation, 모든 Seat, PaymentOrder, 모든 PaymentAttempt를 조회 모델과 비교하고 필드별 차이를 기록한다. |
| FR-012 | 장애 후 재개 | Poller 또는 Consumer가 중단돼도 미처리 사건을 다시 찾아 누락과 중복 적용 없이 처리한다. |
| FR-013 | 재처리 | 빈 조회 모델 버전에 전체 사건을 적용하면 기존 결과와 같은 상태·사건·경로를 만든다. |
| FR-014 | 운영 관측 | 입력·처리·누락·중복 적용·처리 지연·처리 대기량·복구 시간·원천 일치율을 측정한다. |

## 6. 사건 계약

### 6.1 필수 사건 종류

| 사건 | 업무 의미 | 주요 상태 변경 |
| --- | --- | --- |
| `ReservationCreated` | 좌석 선점과 결제 대기 예약 생성 | Reservation `∅ → PENDING_PAYMENT`, Seat `AVAILABLE → LOCKED` |
| `PaymentPrepared` | 결제 주문과 결제 시도 생성 | PaymentOrder `∅ → READY`, PaymentAttempt `∅ → READY` |
| `PaymentApproved` | 승인 정보 저장 | PaymentAttempt `READY → PAID` |
| `ReservationCompleted` | 유효한 예약에 승인 결제 적용 | Reservation `PENDING_PAYMENT → SUCCESS`, Seat `LOCKED → RESERVED`, PaymentOrder `READY → APPLIED` |
| `ReservationExpired` | 스케줄러 또는 결제 반영 중 예약 만료 | Reservation `PENDING_PAYMENT → EXPIRED`, Seat `LOCKED → AVAILABLE` |
| `PaymentRefundPending` | 만료된 예약의 승인 결제를 환불 대기로 전환 | PaymentOrder `READY → REFUND_PENDING` |

`PAID_UNAPPLIED`는 현재 하나의 결제 반영 트랜잭션 안에서만 나타나는 중간 상태다. 독립 커밋 상태가 생기면 사건 종류와 재구성 규칙을 변경한다.

### 6.2 필수 필드

| 필드 | 규칙 |
| --- | --- |
| `eventId` | 사건의 전역 고유 식별자이며 중복 제거 키로 사용 |
| `eventType` | 여섯 사건 종류 중 하나 |
| `schemaVersion` | 사건 계약 해석 버전 |
| `lifecycleId` | 예약 생명주기의 안정적인 식별자 |
| `reservationId` | 모든 사건에서 생명주기 연결 기준으로 사용. MVP 물리 저장은 같은 값의 `lifecycleId` 한 열로 보존 |
| `paymentOrderId` | 결제 준비 이후 사건에서 사용 |
| `paymentAttemptId` | 결제 시도 관련 사건에서 사용 |
| `seatIds` | 사건 당시 영향을 받은 좌석 식별자 목록 |
| `stateChanges` | Entity별 식별자, 변경 전 상태와 변경 후 상태 목록 |
| `reasonCode` | 업무 결정 사유 |
| `actorType` | `EXPIRATION_SCHEDULER`, `PAYMENT_VERIFICATION` 등 변경 주체 |
| `occurredAt` | 사용자에게 표시할 업무 발생 시각 |
| `decisionVersion` | 예약 행 잠금 안에서 증가하는 생명주기 결정 순번 |
| `eventOrdinal` | 같은 결정 안의 사건 표시 순서 |
| `commitGroupId` | 같은 데이터베이스 트랜잭션의 사건 묶음 식별자 |

유일성 제약은 `eventId`와 `(lifecycleId, decisionVersion, eventOrdinal)`에 적용한다. 경로 판정은 `decisionVersion`, `actorType`, `commitGroupId`를 사용한다.

## 7. 조회 결과 계약

생명주기 조회 1회는 다음 정보를 제공해야 한다.

```text
LifecycleView
├── lifecycleId / reservationId / paymentOrderId
├── Reservation 현재 상태
├── Seat 현재 상태 목록
├── PaymentOrder 현재 상태
├── PaymentAttempt 현재 상태 목록
├── decisionVersion 순서의 사건 목록
├── pathClassification
├── trustStatus
└── 마지막 원천 대조 시각과 차이 목록
```

조회 권한과 오류 응답 계약은 Phase 5 API 설계에서 확정한다. 조회 응답에는 카드 정보, 인증 토큰, 결제대행사 비밀 값과 원본 요청 본문을 포함하지 않는다.

## 8. 필수 인수 시나리오

| ID | 입력·조건 | 기대 결과 |
| --- | --- | --- |
| AC-001 | 예약 생성 → 결제 준비 → 승인 → 예약 완료 | `SUCCESS / RESERVED / APPLIED / PAID`, `NORMAL_COMPLETED` |
| AC-002 | 예약 생성 → 결제 승인 없이 스케줄러 만료 | `EXPIRED / AVAILABLE`, `EXPIRED_WITHOUT_PAYMENT` |
| AC-003 | 스케줄러 만료 커밋 후 승인 반영 | 공통 최종 상태와 `EXPIRATION_FIRST` |
| AC-004 | 결제 반영 트랜잭션 안에서 만료 판단 | 공통 최종 상태와 `PAYMENT_HANDLER_EXPIRED` |
| AC-005 | 같은 사건을 반복 전달 | 중복 적용 0건, 기준 실행과 동일한 결과 |
| AC-006 | 승인·만료 사건의 전달 순서 교환 | 원천 순서에 따른 동일한 상태와 경로 |
| AC-007 | 중간 사건 전달 지연 | 대기 중 신뢰 상태 표시, 전달 후 기준 결과로 수렴 |
| AC-008 | Consumer 처리 중 강제 종료 후 재시작 | 누락 0건, 중복 적용 0건, 처리 대기량 해소 |
| AC-009 | 빈 조회 모델에 전체 사건 재처리 | 상태·사건·경로가 기존 결과와 동일 |
| AC-010 | 원천 상태와 조회 모델의 의도적 차이 | `MISMATCH`와 필드별 차이 표시 |
| AC-011 | 원천 버전까지 필요한 사건 일부 삭제 | `INCOMPLETE`와 순번 공백 표시 |
| AC-012 | `paymentOrderId`와 `reservationId`로 각각 조회 | 동일한 `lifecycleId`와 동일한 결과 반환 |
| AC-013 | 사건 Writer 활성화 전에 생성된 `lifecycleVersion=0` 예약 조회 | `LIFECYCLE_NOT_TRACKED` 응답, 합성 Timeline 없음 |

### 기존 데이터 적용 범위

MVP의 완전한 Timeline은 사건 Writer 활성화 뒤 `ReservationCreated`부터 기록된 예약에 제공한다. 기존 예약에는 실행 주체와 커밋 묶음을 증명할 과거 사건이 없으므로 현재 상태에서 이력을 합성하지 않는다. Writer는 모든 애플리케이션 인스턴스가 새 코드로 교체된 뒤 활성화한다. `lifecycleVersion=0`인 기존 예약은 후속 변경도 사건으로 만들지 않고 `LIFECYCLE_NOT_TRACKED`로 구분한다.

## 9. 제외 범위

- 고객 문의 전용 화면과 완성형 관리자 화면
- 자동 환불 실행과 운영 데이터 자동 보정
- 매출·고객 행동 분석과 장기 BI 집계
- 장기 보존·삭제 정책과 별도 감사 시스템
- 전체 시스템의 전역 사건 순서와 다중 리전 구성
- 다수의 이상 탐지 규칙과 자동 대응
- 잠금 획득·스레드 실행 같은 세밀한 분산 추적
- Kafka, Debezium, Flink의 초기 도입

## 10. 단계별 추적성

| Phase | 주요 요구사항 | 완료 근거 |
| --- | --- | --- |
| 0 | 문제·범위·현재 진단 비용 | `baseline.md`, 이 문서 |
| 1 | FR-001~FR-005의 설계, 기술 선택 | `phase-1-technology-adr.md`, `c4-architecture.md` |
| 2 | FR-001~FR-005, FR-014 | 사건 생성·수집 시험과 성능 비교 결과 |
| 3 | FR-006, FR-007, FR-009~FR-011 | 상태·경로 재구성 및 원천 대조 시험 |
| 4 | FR-005, FR-008, FR-012, FR-013 | 중복·역전·지연·장애·재처리 시험 |
| 5 | FR-009~FR-014 | 조회 인수 시험과 기준선 재측정 결과 |

요구사항을 변경할 때는 해당 ID의 인수 기준, SLI·SLO와 품질속성 시나리오를 함께 검토한다. 기술 선택을 바꾸는 변경은 ADR을 추가하거나 기존 ADR의 대체 상태를 기록한다.
