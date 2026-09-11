# Waiting Room 성능 개선 문서 묶음

작성일: 2026-08-26

대상 범위: 92.x~147  
목적: 좌석 정합성, 유입량 보호, 예약 서버 보호, 상태 전달을 하나의 성능 개선 이야기로 읽는다.

## 먼저 읽을 순서

1. [핵심 성능 개선](01-core-performance.md)
2. [계획과 검증 설계](02-plan.md)
3. [실행 결과와 근거](03-execution-results.md)
4. [의사결정·종료 기준·다음 단계](04-decisions-closure-next.md)

## 무엇을 봐야 하는가

| 관심사 | 먼저 볼 문서 | 확인할 질문 |
|---|---|---|
| 포트폴리오 설명 | `01-core-performance.md` → `03-execution-results.md` | Tomcat busy와 HikariCP가 어떤 경계에서 개선됐는가? |
| 설계 판단 | `01-core-performance.md` → `04-decisions-closure-next.md` | 왜 동기 join·SSE를 유지하고 Streams join handoff를 제거했는가? |
| 테스트 재현 | `02-plan.md` → `03-execution-results.md` | A·B·C·D 실행이 어떤 가설을 검증했는가? |
| 종료 여부 | `04-decisions-closure-next.md` | 어떤 SLO를 통과했고, 어떤 값은 관찰 지표로 남겼는가? |
| 148.x 착수 | `04-decisions-closure-next.md` → [148.x 착수 기준](../../implements/seat-availability/148-seat-availability-kickoff-scope-and-exit-criteria.md) → [148.1 plan](../../implements/seat-availability/148.1-seat-availability-read-model-plan.md) | 좌석 Read Model을 시작할 근거가 있는가? |

## 핵심 결론

```text
직통 기준선
  → Reservation Application `/join`
  → Redis Waiting Room
  → Tomcat busy 200

Nginx 도입
  → Nginx ingress 전달률 제어
  → Reservation Application `/join`
  → origin Tomcat busy 57~75

별도 Waiting Room Service 분리
  → Nginx
  → Waiting Room Service
  → origin Tomcat busy 30~32, HikariCP pending 0
```

Waiting Room의 핵심 자료구조와 원자적 상태 변경은 Redis ZSET·Hash·Lua로 유지한다. 좌석 선점의 승패는 MySQL transaction과 좌석 잠금으로 즉시 결정한다. Ticket SSE는 상태 변경을 전달하고, 15초 주기의 status 재조정은 Redis의 현재 ticket 상태로 화면을 복구한다. 이 경로는 Waiting Room Service에서 처리한다. Redis Streams join handoff와 join-request SSE는 HTTP 요청의 유입량을 줄이지 못해 제거했다.

## 원문 근거

- [최종 종료 검증](../../test/2026-08-24-waiting-room-final-closure.md)
- [Waiting Room ADR](../../implements/waiting-room/146.12-waiting-room-admission-boundary-adr.md)
- [의사결정 로그](../../implements/waiting-room/146.13-waiting-room-decision-log.md)
- [전체 흐름](../../implements/waiting-room/146.14-waiting-room-flow.md)
- [Nginx ingress 경계 실험](../../implements/waiting-room/146.9-waiting-room-ingress-boundary-experiment.md)
- [C 동일 좌석 경합](../../test/2026-08-24-waiting-room-c-same-seat-contention.md)
- [Gateway 경유 핵심 E2E](../../test/2026-08-24-waiting-room-gateway-e2e.md)

원시 결과는 `build/k6-results/`와 각 실행 문서의 결과 경로에서 확인한다. 원문 문서를 먼저 읽으면 실행 이력의 세부사항이 많아 핵심 판단까지 도달하는 시간이 길어진다.
