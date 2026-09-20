# Phase 4 결과: 실패 조건 처리와 재처리

작성일: 2026-09-20(KST)  
상태: 부분 완료  
진행 전 계획: [Phase 4 계획](plan.md)

이번 실행은 조회 모델의 실패 근거, 순서·중복·지연 처리, Replay과 원천 대조를 코드와 격리된 JPA 시험으로 검증했다. 실제 프로세스 종료, MySQL 연결 장애와 30,000건 처리량은 아직 실행하지 않았다.

## 1. 실행 요약

| 항목 | 결과 |
| --- | --- |
| 대상 코드 | `develop` 작업 트리의 Phase 3 구현과 Phase 4 변경 |
| 추가 스키마 | `V20260920_03__add_lifecycle_recovery_metadata.sql` |
| 실행 환경 | H2 `@DataJpaTest`, Java 21, Spring Boot 3.4.4 |
| 완료 범위 | 중복·순서 역전·지연·계약 오류·Replay·Reconciliation |
| 미실행 범위 | 프로세스 중단 지점별 복구, MySQL 일시 연결 실패, 30,000건 Replay 성능 |

## 2. 실제 변경

| 영역 | 변경 | 근거 |
| --- | --- | --- |
| 적용 기록 | `errorType`, `attemptCount`, `lastAttemptedAt`, `nextAttemptAt` 추가 | 계약 오류를 재시도와 구분하고 원인 보존 |
| 재구성기 | 계약 위반을 `FAILED`로 기록하고 `MISMATCH`로 표시, 실패 결정을 반복 적용하지 않음 | 정상 Snapshot 오염과 무한 반복 방지 |
| 원천 대조 | Reservation·Seat·PaymentOrder·PaymentAttempt와 사건 버전을 비교 | `CONSISTENT`, `INCOMPLETE`, `MISMATCH`, `PROCESSING` 판정 |
| Replay | `LifecycleReplayRun`, 별도 `projectionVersion`, 실행 상태·처리 수·오류 수 저장 | 기존 조회 모델을 변경하지 않고 재구성 |
| 스키마 | 적용 오류 메타데이터, 대조 결과, Replay 실행 테이블 추가 | [데이터 모델과 ERD](../../data-model-erd.md) 반영 |

## 3. 시험 결과

| ID | 시험 조건 | 결과 |
| --- | --- | --- |
| F-01 | 적용된 결정을 다시 전달 | `DUPLICATE`, Snapshot 버전 유지, 중복 상태 변경 없음 |
| F-02 | 만료 선행 경로와 결제 검증 중 만료 경로 비교 | 같은 최종 상태에서 `EXPIRATION_FIRST`·`PAYMENT_HANDLER_EXPIRED` 구분 |
| F-03 | 높은 결정 버전을 먼저 전달 | `PENDING`, Snapshot `lastAppliedVersion=0`, 상태 미확정 |
| F-04 | 보류 뒤 앞선 결정을 전달 | 모든 결정 적용 뒤 최종 상태로 수렴 |
| F-05 | 적용 전 프로세스 종료 | 미실행. 실제 프로세스 제어 시험 필요 |
| F-06 | Snapshot 갱신 트랜잭션을 rollback-only로 종료 | Snapshot과 적용 기록이 남지 않고 재실행 뒤 정상 적용 |
| F-07 | 커밋 직후 프로세스 종료 | 미실행. 커밋 응답 경계 재전달 시험 필요 |
| F-08 | MySQL 일시 연결 실패 | 미실행. Testcontainers 네트워크 장애 주입 필요 |
| F-09 | 허용 전이를 벗어난 계약 사건 | `FAILED`, `CONTRACT`, 시도 횟수 1, Snapshot `MISMATCH`, 상태 부분 적용 없음 |
| F-10 | 원천 Lifecycle 버전보다 사건 버전이 부족 | `INCOMPLETE`, 누락 결정 버전 저장 |
| F-11 | 원천 Seat 상태를 Snapshot과 다르게 변경 | `MISMATCH`, `seatStatuses` 차이 저장 |
| F-12 | 정상 사건 4건을 `projectionVersion=2`로 Replay | `COMPLETED`, 처리 4건, 실패 0건, 기존 버전과 상태 일치 |

실행한 명령:

```text
./gradlew test --tests org.example.ticket.lifecycle.projection.LifecycleReconstructionJpaTest --tests org.example.ticket.lifecycle.projection.LifecycleRecoveryJpaTest
```

결과: 대상 시험 9건 통과.

## 4. 저장되는 복구 근거

| 근거 | 저장 위치 | 의미 |
| --- | --- | --- |
| 계약 오류 종류 | `lifecycle_event_application.error_type` | `CONTRACT` 또는 `TRANSIENT` 분류 공간 |
| 적용 시도 수 | `lifecycle_event_application.attempt_count` | 같은 사건의 적용 시도 횟수 |
| 마지막 오류 코드 | `lifecycle_event_application.error_code` | 현재는 `CONTRACT_EVENT`를 사용 |
| 원천 버전 | `lifecycle_snapshot.reconciled_source_version` | Reservation의 마지막 Lifecycle 버전 |
| 사건 버전 | `lifecycle_snapshot.reconciled_event_version` | 사건 원본에서 확인한 최대 연속 버전 |
| 필드 차이 | `lifecycle_snapshot.reconciliation_diff` | 네 상태 축과 누락 버전의 차이 |
| Replay 실행 | `lifecycle_replay_run` | 실행 ID, 대상 버전, 처리·오류 수, 결과 상태 |

## 5. 미실행 검증과 이유

| 검증 | 남은 작업 |
| --- | --- |
| F-05, F-07 | Consumer를 별도 프로세스로 실행하고 적용 전·커밋 직후 종료 지점을 주입한다. 재시작 뒤 누락·중복·부분 상태를 측정한다. |
| F-08 | MySQL Testcontainers에 일시 연결 실패를 주입하고 오류 기록과 재연결 후 처리 재개를 확인한다. |
| F-12 30,000건 | 동일 사건 생성기를 사용해 30,000건을 별도 Projection에 Replay하고 p95 지연·최대 대기량·결과 일치율을 측정한다. |
| 자동 재시도 간격 | 실제 일시 오류 결과를 얻은 뒤 최대 재시도 횟수와 백오프를 확정한다. |

## 6. 판정

- Phase 4의 결정적 데이터 처리 범위는 부분 완료다.
- 중복·순서 역전·지연·계약 오류·원천 대조·소규모 Replay의 결과를 재현할 수 있다.
- 프로세스 장애 복구와 대규모 Replay 성능은 수치 근거가 없어 완료로 표시하지 않는다.
- 다음 실행에서는 F-05~F-08과 30,000건 Replay를 수행한 뒤 [Phase 5 계획](../phase-5-query-validation/plan.md)의 단일 조회 검증으로 이동한다.

## 7. 근거 문서

- [Phase 4 계획](plan.md)
- [데이터 모델과 ERD](../../data-model-erd.md)
- [Lifecycle 재구성 명세](../../lifecycle-reconstruction-spec.md)
- `src/test/java/org/example/ticket/lifecycle/projection/LifecycleReconstructionJpaTest.java`
- `src/test/java/org/example/ticket/lifecycle/projection/LifecycleRecoveryJpaTest.java`
