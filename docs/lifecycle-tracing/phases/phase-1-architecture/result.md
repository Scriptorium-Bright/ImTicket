# Phase 1 결과: 사건 모델과 기술 선택

작성일: 2026-09-20(KST)  
상태: 완료  
진행 전 계획: [Phase 1 계획](plan.md)  
결정 기록: [ADR-001](../../phase-1-technology-adr.md)

## 1. 결과 요약

1차 생명주기 추적은 트랜잭션 아웃박스, MySQL 폴링, Spring 멱등 Consumer와 MySQL 조회 모델로 구성하기로 결정했다. 같은 생명주기의 순서는 예약 행 잠금 안에서 증가시키는 `decisionVersion`과 같은 결정 안의 `eventOrdinal`로 정의했다.

| 책임 | 결정 |
| --- | --- |
| 사건 생성 | 업무 상태와 같은 MySQL 트랜잭션의 트랜잭션 아웃박스 |
| 사건 원본 | MySQL `lifecycle_event` |
| 전달 | Spring `@Scheduled` Poller와 ShedLock |
| 처리 | `lifecycleId`별 멱등 Consumer |
| 현재 상태 | MySQL 조회 모델 |
| 중복 제거 | `eventId`와 업무 순번 유일성, 적용과 조회 모델의 단일 트랜잭션 |
| 생명주기 순서 | `(decisionVersion, eventOrdinal)` |
| 장애 재개 | 미처리 사건 재조회와 동일 Consumer 재실행 |
| 재처리 | 보존 사건을 새 `projectionVersion`에 전체 적용 |
| 원천 대조 | `reservationId` 단위 네 상태 축 비교 |

## 2. 확정한 사건 모델

### 2.1 사건 종류

- `ReservationCreated`
- `PaymentPrepared`
- `PaymentApproved`
- `ReservationCompleted`
- `ReservationExpired`
- `PaymentRefundPending`

필수 계약은 `eventId`, `eventType`, `schemaVersion`, `lifecycleId`, 업무 식별자, `stateChanges`, `reasonCode`, `actorType`, `occurredAt`, `decisionVersion`, `eventOrdinal`, `commitGroupId`로 확정했다.

### 2.2 순서와 커밋 묶음

```text
sourceOrder = (decisionVersion, eventOrdinal)
```

- `decisionVersion`은 예약 행 잠금 안에서 한 업무 결정마다 증가한다.
- `eventOrdinal`은 같은 결정에서 생성된 사건의 표시 순서다.
- `commitGroupId`는 같은 데이터베이스 트랜잭션의 사건을 묶는다.
- 아웃박스 기본 키, 애플리케이션 서버 시각과 Poller 조회 순서는 생명주기 결정 순서에 사용하지 않는다.

### 2.3 경로 판정

| 경로 | 사건 근거 | 분류 |
| --- | --- | --- |
| 만료 처리 선행 | 스케줄러의 `ReservationExpired(v)` 뒤 승인·환불 대기 `(v+1)` | `EXPIRATION_FIRST` |
| 결제 반영 중 만료 | 승인·만료·환불 대기가 같은 버전과 커밋 묶음 | `PAYMENT_HANDLER_EXPIRED` |
| 정상 완료 | 승인 뒤 예약·좌석·주문 완료 | `NORMAL_COMPLETED` |
| 결제 없는 만료 | 승인 사건 없이 예약 만료 | `EXPIRED_WITHOUT_PAYMENT` |

## 3. 확정한 장애 처리

- `eventId`와 `(lifecycleId, decisionVersion, eventOrdinal)`에 유일성 제약을 둔다.
- 적용 기록과 조회 모델 변경은 같은 Consumer 트랜잭션에서 커밋한다.
- 순번 공백은 적용을 보류하고 `PROCESSING`으로 표시한다.
- 원천 대조로 사건 부재를 확인하면 `INCOMPLETE`로 표시한다.
- 같은 버전에서 네 상태 축이 다르면 `MISMATCH`로 표시한다.
- Poller는 미처리 상태와 적용 기록을 기준으로 사건을 찾는다.
- 초기 실행은 ShedLock으로 Poller 하나를 유지한다.

## 4. 기술 비교 결과

| 후보 | 결과 | 이유 |
| --- | --- | --- |
| 애플리케이션 직접 메시지 발행 | 제외 | 업무 DB 커밋과 메시지 발행 사이의 실패 구간 |
| 트랜잭션 아웃박스 | 선택 | 업무 의미와 커밋 원자성, 재처리 원본 제공 |
| 도메인 테이블 CDC | 보류 | 여러 상태 변경의 결정 사유·주체 복원 규칙 추가 필요 |
| 아웃박스 + CDC | 확장 후보 | 독립 전달과 원천 로그가 필요할 때 재검토 |
| MySQL 폴링 | 선택 | 현재 Spring·MySQL 구성으로 초기 요구 충족 가능 |
| Kafka | 보류 | 현재 규모에서 브로커 운영 책임이 추가됨 |
| 멱등 단순 Consumer | 선택 | 여섯 사건의 결정적 재구성에 적합 |
| Flink | 보류 | 시간 창·다중 스트림 조인·대규모 상태 요구가 생길 때 재검토 |

## 5. 문서 변경

| 문서 | 반영 내용 |
| --- | --- |
| [요구사항](../../requirements.md) | FR-001~FR-014, 사건·조회 계약, 인수 시나리오 |
| [ADR-001](../../phase-1-technology-adr.md) | 기술 선택, 순서·장애 규칙, 재검토 조건 |
| [C4](../../c4-architecture.md) | 컨텍스트·컨테이너·컴포넌트·배포 경계 |
| [데이터 모델과 ERD](../../data-model-erd.md) | 현재·목표 관계, 키·제약·인덱스와 기존 데이터 전환 규칙 |
| [재구성 명세](../../lifecycle-reconstruction-spec.md) | 사건 적용, 경로 판정, 신뢰 상태와 Replay 규칙 |
| [SLI·SLO](../../sli-slo-baseline.md) | 정확성 고정 SLO와 성능 측정 기준 |
| [품질속성](../../quality-attributes.md) | QA-01~QA-11과 품질 게이트 |

애플리케이션 코드, 데이터베이스 스키마와 실행 설정은 변경하지 않았다.

## 6. 검증 결과

| 검증 항목 | 결과 | 근거 |
| --- | --- | --- |
| 정상 커밋·롤백 원자성 | 설계 충족 | 같은 MySQL 트랜잭션의 업무 상태와 사건 |
| 경로 A/B 표현 | 설계 충족 | 주체·결정 버전·커밋 묶음 규칙 |
| 중복 적용 방지 | 설계 충족 | 유일성 제약과 단일 Consumer 트랜잭션 |
| 순서 역전·지연 | 설계 충족 | 결정 버전 정렬과 공백 보류 |
| 장애 재개 | 설계 충족 | 내구성 있는 사건 원본과 적용 기록 |
| 재처리 | 설계 충족 | 보존 사건과 조회 모델 버전 분리 |
| 기존 코드의 잠금 전제 | 확인 | 결제 준비·반영·만료의 Reservation 쓰기 잠금 |
| 로컬 문서 링크 | 통과 | 끊어진 링크 0건 |

Phase 1은 아키텍처 결정 단계이므로 새 애플리케이션 시험과 부하 시험을 실행하지 않았다. 선택 기술의 실제 정확성과 성능은 Phase 2~4에서 검증한다.

## 7. 예상 효과와 비용

### 효과

- 업무 상태와 사건의 커밋 일치를 MySQL 트랜잭션으로 보장할 수 있다.
- 서버 시각과 전달 순서의 영향을 받지 않는 생명주기 순서를 만든다.
- 동일한 사건 원본에서 조회 모델과 경로를 재생할 수 있다.
- 기술 확장 여부를 처리 지연·대기량·독립 Consumer 요구로 판단할 수 있다.

### 비용

- 업무 트랜잭션에 사건 INSERT와 예약 생명주기 버전 갱신이 추가된다.
- MySQL에 사건 원본과 조회 모델의 저장·조회 부하가 생긴다.
- 모든 생명주기 상태 변경 경로가 예약 행 잠금과 사건 생성 규칙을 지켜야 한다.
- Poller 처리 간격만큼 조회 모델 반영이 늦을 수 있다.

## 8. 계획 대비 차이

기술 선택과 문서 산출물은 계획 범위대로 완료했다. 실제 사건량은 Phase 0에서 아직 측정하지 않았으며, ADR은 부하 스크립트와 만료 설정에서 산출한 전제를 사용한다. Phase 2의 Poller 설정을 확정하기 전에 실제 상태 전이 기반 사건량을 반영한다.

## 9. 완료 판정과 다음 Phase

사건 계약, 기술 구성, 순서·중복·장애 규칙, C4 경계와 재검토 조건을 확정해 Phase 1을 완료로 판정한다.

Phase 2 진입 전 다음 두 항목을 확인한다.

1. 실제 업무 상태 전이 기반 사건량 측정 결과를 Poller 배치 설계에 반영한다.
2. 승인된 [데이터 모델과 ERD](../../data-model-erd.md)를 데이터베이스 마이그레이션과 Entity 설계에 반영한다.
