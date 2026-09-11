# Seat Availability 패키지

작성일: 2026-09-04

이 디렉터리는 Waiting Room 이후의 좌석 현황 조회, 캐시 무효화, 캐시 누락 동시성, Redis 장애 보호, Message Bus 도입 판정 문서를 관리한다.

전체 문제 해결 흐름은 [잠금부터 Waiting Room·좌석 캐시까지 끝내는 안내](../../portfolio/lock-to-waiting-room-reading-guide.md)에서 읽는다. 현재 요청의 실행 순서는 [현재 티켓팅 아키텍처 전체 흐름](../../architecture/current-ticketing-architecture-flow.md), 판단의 시간 순서는 [예약 아키텍처 의사결정 연대기](../../portfolio/imticket-architecture-decision-chronology.md)에서 확인한다.

## 현재 구현

- [좌석 현황 캐시 전체 흐름](148.4-seat-availability-cache-flow.md)
- 선택한 공연 회차의 Redis 좌석 현황 스냅샷
- 캐시 어사이드(Cache-Aside) 조회
- MySQL 커밋 이후 회차 버전 증가와 스냅샷 무효화
- 같은 JVM·회차의 single-flight 재구축
- 회차 버전 조건부 스냅샷 저장
- Redis 오류 시 회차별 데이터베이스 대체 조회 상한 5
- 상한 초과 요청의 `503 Service Unavailable`
- 좌석 선점 시 MySQL 행 잠금과 상태 재검증

## 현재 판정

| 조건 | 결과 | 판단 |
|---|---|---|
| 웜 캐시 2,000건 | MySQL 전체 좌석 조회 2,000 → 0, HikariCP 연결 대기 166 → 0 | 조회 모델 효과 확인 |
| 통제 콜드 캐시 300건 | MySQL 전체 좌석 조회 301 → 1, 성공 300/300 | single-flight 효과 확인 |
| 쓰기 50/s + 조회 100/s 혼합 | 조회 p95 7,261ms → 996ms, MySQL 조회 1,701 → 229 | 혼합 부하 적용 가치 확인 |
| 무효화 직후 조회 2,000건 | 캐시 활성 성공 101, 전송 실패 1,867, MySQL 조회 4 | HTTP 응답 용량 미충족 |
| Redis 명령·payload 격리 시험 | 무효화 Lua p99 1.511ms, 조건부 305KB 저장 p99 95.487ms, 유효한 MGET p99 10.871ms | `INCR + DEL`보다 full snapshot 재작성·전송 비용을 우선 확인 |
| AFTER_COMMIT 무효화 실패·프로세스 종료 재현 | MySQL `LOCKED`와 Redis·API `AVAILABLE` 불일치, 테스트 TTL 잔여 약 56초·17.7초 뒤 복구 | 화면 최신성 SLO와 Outbox 도입 기준 재평가 |
| Redis 장애 2,000건 | 성공 112, 명시적 503 1,888 | 데이터베이스 유입 상한과 실패 계약 확인 |
| Message Bus 도입 게이트 | 다섯 운영 기준 미충족 | 도입 보류 |

## 권장 읽기 순서

### 1. 기준선과 조회 모델

1. [146.6 Cache 요구사항](146.6-cache-sli-slo-requirements.md)
2. [146.6.1 캐시 성능 실행](146.6.1-seat-map-cache-performance-execute.md)
3. [148.1 좌석 현황 조회 계획](148.1-seat-availability-read-model-plan.md)
4. [148.1 검증 묶음](148.1-test-pack.md)
5. [148.1 종합 결과](148.1-results-and-decision.md)

### 2. 캐시 누락과 상태 변경

1. [146.6.3 상태 변경·캐시 스탬피드 진단](146.6.3-seat-map-cache-churn-stampede-decision.md)
2. [146.6.3 실행 기록](146.6.3-seat-map-cache-churn-stampede-execute.md)
3. [WAL·캐시 사고 흐름](146.6.3-seat-availability-wal-cache-thought-record.md)
4. [캐시 활성·비활성 시험의 의미](146.6.3-popular-cache-toggle-test-meaning.md)
5. [Redis 명령·좌석 snapshot 비용 격리 시험](146.6.6-redis-command-capacity-isolated-test.md)
6. [AFTER_COMMIT 무효화 실패·프로세스 종료 재현 결과](148.3-after-commit-cache-invalidation-failure-test.md)

### 3. single-flight와 버전 조건부 저장

1. [single-flight·snapshot version 설계](146.6.4-single-flight-versioned-snapshot-design.md)
2. [single-flight·snapshot version 결과](146.6.4-single-flight-versioned-snapshot-result.md)
3. [ADR-0005 좌석 현황 캐시 경계](../../architecture/decisions/0005-seat-availability-cache-boundary.md)

### 4. Message Bus 도입 판정

1. [Message Bus 초기 의사결정 검토](../../architecture/message-bus-initial-decision-review.md)
2. [ADR-0004 Redis Streams 조건부 PoC](../../architecture/decisions/0004-redis-streams-first-message-bus-poc.md)
3. [Message Bus 도입 판정 결과](148.2-message-bus-adoption-gate-test-result.md)

Redis Streams의 pending 회수와 확인 응답 동작은 검증했다. 애플리케이션의 Streams 생산자·소비자·Availability Projector·Transactional Outbox는 구현하지 않았다.

## 후속 제안

- [정적 좌석 배치·동적 좌석 상태 분리 계획](146.6.5-static-dynamic-seat-map-separation-plan.md)
- [좌석 응답 구조·전송 진단](146.6.2-seat-map-response-optimization-diagnosis.md)
- Waiting Room 승급 후보 조회 파이프라인 비교
- 별도 부하 발생기를 사용한 현재 전체 흐름 용량 재검증

후속 제안은 현재 구현 결과로 사용하지 않는다. 새로운 측정이 캐시 적용 범위나 책임 경계를 바꾸면 안내, 현재 아키텍처, 의사결정 연대기, ADR-0005를 함께 갱신한다.
