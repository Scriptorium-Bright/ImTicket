# 예약·결제 생명주기 추적 Phase 문서 지도

작성일: 2026-09-20(KST)  
관련 문서: [상위 문서 지도](../README.md) · [전체 구현 계획](../implementation-plan.md)

## 1. Phase별 문서

| Phase | 범위 | 진행 전 계획 | 실행 후 결과 | 현재 상태 |
| ---: | --- | --- | --- | --- |
| 0 | 기준선 및 범위 확정 | [계획](phase-0-baseline/plan.md) | [결과](phase-0-baseline/result.md) | 부분 완료 |
| 1 | 사건 모델과 기술 선택 | [계획](phase-1-architecture/plan.md) | [결과](phase-1-architecture/result.md) | 완료 |
| 2 | 사건 생성과 수집 | [계획](phase-2-event-production/plan.md) | [결과](phase-2-event-production/result.md) | 부분 완료 |
| 3 | 생명주기 재구성 | [계획](phase-3-reconstruction/plan.md) | [결과](phase-3-reconstruction/result.md) | 부분 완료 |
| 4 | 실패 조건 처리와 재처리 | [계획](phase-4-failure-recovery/plan.md) | [결과](phase-4-failure-recovery/result.md) | 부분 완료 |
| 5 | 조회 및 최종 검증 | [계획](phase-5-query-validation/plan.md) | [결과](phase-5-query-validation/result.md) | 미실행 |

Phase 0은 현재 진단 비용 측정을 완료했다. 실제 업무 실행에서 사건 발생량을 계측하는 작업이 남아 있어 부분 완료로 관리한다. Phase 1은 기술 선택과 목표 구조를 승인해 완료로 기록한다. Phase 2는 트랜잭션 아웃박스 사건 원본과 결정 단위 Reader를 구현하고 MySQL 사건량·Writer 비용을 측정했다. Phase 3은 조회 모델, 멱등 적용, 순서 역전 보류와 경로 A/B 판정을 구현했다. Phase 4는 계약 오류 기록·재시도 차단, 별도 조회 모델 버전 Replay, 원천 MySQL 대조와 `CONSISTENT`·`INCOMPLETE`·`MISMATCH` 판정을 구현했고, H2 격리 시험에서 30,000건 Replay 처리량을 측정했다. 실제 프로세스 중단과 MySQL 연결 장애 주입은 별도 실행이 남아 있어 부분 완료로 관리한다.

## 2. 계획 문서의 필수 내용

각 `plan.md`는 구현 전에 다음 질문에 답한다.

1. 왜 이 Phase를 수행하는가?
2. 어떤 현재 문제와 선행 조건을 기준으로 하는가?
3. 코드·스키마·설정·시험·문서 중 무엇을 바꾸는가?
4. 어떤 순서와 트랜잭션 경계로 구현하는가?
5. 변경 뒤 시스템 동작과 관측값이 어떻게 달라질 것으로 예상하는가?
6. 어떤 시험과 SLI·SLO로 성공을 판정하는가?
7. 중단·되돌리기 조건과 다음 Phase 진입 조건은 무엇인가?

계획 승인 뒤 범위가 달라지면 문서의 변경 기록에 이유와 영향을 남긴다.

## 3. 결과 문서의 필수 내용

각 `result.md`는 실행 후 다음 사실을 기록한다.

1. 실제로 변경한 파일·스키마·설정과 동작
2. 계획과 달라진 부분과 변경 이유
3. 실행한 시험의 조건·명령·결과
4. 변경 전후 SLI와 SLO 판정
5. 실행 중 발생한 실패, 원인과 수정 내용
6. 유지·보완·되돌리기 결정
7. 남은 작업과 다음 Phase 진입 여부
8. 원시 결과와 관련 결정 문서 위치

실행 명령 전체와 반복 로그는 결과 문서에 나열하지 않는다. 판단을 바꾼 실패와 측정값, 재현에 필요한 명령만 보존한다.

## 4. 문서 분할 기준

다음 조건에서는 상세 문서를 같은 Phase 디렉터리에 분리한다.

| 조건 | 분리 문서 예시 | 결과 문서의 역할 |
| --- | --- | --- |
| 사건 계약이나 스키마가 독립 검토 대상 | `event-contract.md`, `schema-design.md` | 최종 선택과 링크 요약 |
| 부하 시험의 조건·회차·원시 결과가 많음 | `performance-test-plan.md`, `performance-test-results.md` | 핵심 수치와 SLO 판정 요약 |
| 장애 주입 조합이 여러 개 | `failure-matrix.md`, `recovery-results.md` | 장애별 최종 판정 요약 |
| API 계약과 보안 검증이 독립적 | `query-api-contract.md`, `security-test-results.md` | 최종 응답·권한 계약 요약 |
| 결정이 기존 ADR의 기술 경계를 바꿈 | 새 ADR | 채택 결과와 ADR 링크 요약 |

`plan.md`와 `result.md`는 Phase의 진입·종료 문서로 유지한다. 분리 문서는 근거와 재현 절차를 담당한다.

## 5. 상태와 완료 규칙

| 상태 | 의미 |
| --- | --- |
| 미실행 | 계획만 존재하며 코드·시험 실행이 시작되지 않음 |
| 진행 중 | 승인된 계획에 따라 구현 또는 시험 중 |
| 부분 완료 | 일부 종료 조건을 충족했고 남은 조건이 명시됨 |
| 완료 | 모든 종료 조건과 품질 게이트를 통과함 |
| 중단 | 중단 조건이 발생했고 유지·되돌리기 판단을 기록함 |

Phase 완료는 결과 문서의 근거로 판정한다. 계획 문서에 정의된 예상 효과만으로 완료 상태를 부여하지 않는다.
