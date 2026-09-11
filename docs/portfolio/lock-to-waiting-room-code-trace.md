# 잠금부터 Waiting Room·좌석 캐시까지 코드 추적표

관련 안내: [잠금부터 Waiting Room·좌석 캐시까지 끝내는 안내](lock-to-waiting-room-reading-guide.md)

## 1. 이 문서의 역할

읽기 가이드는 문제, 측정 결과, 선택 이유를 시간순으로 설명한다. 이 문서는 읽기 가이드의 설명이 현재 소스 코드와 만나는 위치를 파일 단위로 연결한다. 코드에서는 현재 요청 경로와 상태 전이를 확인하고, 수치와 당시의 판단은 연결된 실험·결과 문서에서 확인한다.

| 구분 | 확인할 대상 | 예시 |
|---|---|---|
| 현재 구현 | Java·TypeScript·Lua·Nginx 설정 | Waiting Room ticket 상태, SSE, status API, 캐시 재구축 |
| 실행 환경 | Docker Compose·애플리케이션 설정 | Gateway와 Waiting Room Service 역할 분리, 처리량·TTL 설정 |
| 부하 시나리오 | k6·Node 실행 스크립트 | 2,000명 전체 흐름, Ticket SSE, 캐시 집중 조회 |
| 관측 결과·결정 | `docs/`의 실행 결과와 ADR | p95, HikariCP 대기, 채택·제거·보류 판단 |

소스 파일 경로는 저장소 루트를 기준으로 한다. 링크를 따라갈 때는 `Controller → Service → Store 또는 Repository → Redis·MySQL` 순서로 읽는다.

## 2. 읽기 가이드 절과 코드의 대응

| guide 절 | 설명 | 현재 코드 또는 근거 |
|---|---|---|
| 3. 잠금과 멱등성 | 같은 좌석의 최종 승자와 재요청 처리 | [예약 진입](../../src/main/java/org/example/ticket/reservation/booking/service/ReservationPreReserveService.java) → [claim 실행](../../src/main/java/org/example/ticket/reservation/booking/service/ReservationClaimExecutionService.java) → [잠금 전략 선택](../../src/main/java/org/example/ticket/reservation/booking/util/lock/ReservationLockStrategy.java)·[AOP 경계](../../src/main/java/org/example/ticket/reservation/booking/util/aop/ReservationLockAspect.java) → [예약 트랜잭션](../../src/main/java/org/example/ticket/reservation/booking/service/ReservationService.java) → [행 잠금 조회](../../src/main/java/org/example/ticket/reservation/booking/repository/SeatRepository.java) |
| 4. 좌석별 진입 제어 | DB 작업 전 요청 수 제한과 `429` | [SeatAdmissionService](../../src/main/java/org/example/ticket/reservation/booking/util/admission/SeatAdmissionService.java) · [ReservationClaimExecutionService](../../src/main/java/org/example/ticket/reservation/booking/service/ReservationClaimExecutionService.java) |
| 5. 종료한 Redis Streams 예약 큐 | 비동기 예약 처리의 구현·종료 판단 | 현재 제품 경로의 Streams worker·예약 티켓 코드는 제거됨. [보관 색인](../archive/145-retired-reservation-stream-queue/README.md)과 [ADR-0001](../architecture/decisions/0001-entry-waiting-room-and-synchronous-pre-reserve.md)에서 당시 설계를 확인 |
| 6. Waiting Room | join, 순번, 승급, entry pass, SSE·HTTP polling | [Waiting Room Controller](../../src/main/java/org/example/ticket/reservation/waitingroom/controller/WaitingRoomController.java) · [Redis Store](../../src/main/java/org/example/ticket/reservation/waitingroom/repository/redis/RedisWaitingRoomStore.java) · [Lua](../../src/main/resources/redis/waiting-room) · [프런트 대기 화면](../../frontend/src/app/waiting-room/[id]/page.tsx) |
| 6.2. Nginx·서비스 분리 | Gateway에서 Waiting Room 경로 분리 | [Nginx 설정](../../infra/nginx/waiting-room-gateway.conf) · [Compose 역할 분리](../../docker-compose.yml) |
| 단일 서버 메모리 대안 | 현재 애플리케이션 코드에 없는 설계 후보, 테스트 전용 구현 있음 | [메모리 대안 의사결정](../implements/waiting-room/146.18-single-server-memory-queue-decision.md) · 현재 제품 경로는 Redis Store를 사용 |
| embedded Tomcat API 비교 | Controller·Service·저장소의 join·status p95와 API handler 동시 실행 최대 | [API 성능 비교 테스트](../../src/test/java/org/example/ticket/reservation/waitingroom/api/WaitingRoomApiPerformanceComparisonTest.java) · [비교 결과](../implements/waiting-room/146.18-single-server-memory-queue-decision.md#10-embedded-tomcat-api-비교-결과) |
| 7~8. 좌석 캐시·single-flight | Cache-Aside, 버전, 동시 재구축, DB 대체 조회 | [SeatMapCacheReader](../../src/main/java/org/example/ticket/reservation/booking/cache/SeatMapCacheReader.java) · [Redis Store](../../src/main/java/org/example/ticket/reservation/booking/cache/RedisSeatMapCacheStore.java) · [DB Reader](../../src/main/java/org/example/ticket/reservation/booking/cache/SeatMapDatabaseReader.java) |
| 9. 예약 완료·만료 | `LOCKED` 좌석의 확정·해제 | [예약 완료](../../src/main/java/org/example/ticket/reservation/booking/service/ReservationCompletionService.java) · [예약 만료](../../src/main/java/org/example/ticket/reservation/booking/service/ReservationExpirationService.java) |
| 10. 커밋 이후 반영·Message Bus | 현재 `AFTER_COMMIT` 무효화와 Outbox 보류 | [무효화 이벤트 발행](../../src/main/java/org/example/ticket/reservation/booking/cache/SeatMapInvalidationPublisher.java) · [커밋 이후 Listener](../../src/main/java/org/example/ticket/reservation/booking/cache/SeatMapCacheInvalidationListener.java) · [도입 판정](../implements/seat-availability/148.2-message-bus-adoption-gate-test-result.md) |
| 11. 현재 아키텍처 | 위 구성 요소를 잇는 실행 경로 | 아래 3절의 요청별 코드 흐름 |

## 3. 요청별 코드 흐름

### 3.1 Waiting Room 등록·상태 전달·입장

```text
대기 화면
  → frontend/src/app/waiting-room/[id]/page.tsx
  → frontend/src/services/api.ts
  → infra/nginx/waiting-room-gateway.conf
  → WaitingRoomController
  → WaitingRoomService
  → RedisWaitingRoomStore
  → waiting_room_join.lua / waiting_room_promote*.lua
  → Redis ticket Hash·waiting/deadline/active ZSET
```

1. [프런트 대기 화면](../../frontend/src/app/waiting-room/[id]/page.tsx)은 ticket 상태를 보관하고 `join` 뒤 SSE와 HTTP polling을 시작한다. [API 모듈](../../frontend/src/services/api.ts)은 `join`, `status`, `events` 요청을 만든다.
2. [Gateway 설정](../../infra/nginx/waiting-room-gateway.conf)은 `/join`에 초당 100건의 `limit_req`를 적용하고, `/api/reservation/waiting-room/` 경로를 Waiting Room Service로 보낸다. SSE 전달을 위해 이 경로의 proxy buffering을 끈다.
3. [WaitingRoomController](../../src/main/java/org/example/ticket/reservation/waitingroom/controller/WaitingRoomController.java)는 `join`, `status`, 취소 API를 받고 [WaitingRoomService](../../src/main/java/org/example/ticket/reservation/waitingroom/service/WaitingRoomService.java)로 전달한다.
4. [RedisWaitingRoomStore](../../src/main/java/org/example/ticket/reservation/waitingroom/repository/redis/RedisWaitingRoomStore.java)는 Lua 실행과 Redis 조회를 맡는다. [WaitingRoomKeyFactory](../../src/main/java/org/example/ticket/reservation/waitingroom/repository/redis/WaitingRoomKeyFactory.java)는 같은 회차의 `waiting`, `deadline`, `active` ZSET과 ticket Hash·owner String을 만든다. 각 ZSET의 역할은 다음과 같다.

| Redis 키 | member와 score | 사용하는 작업 |
|---|---|---|
| `waiting` ZSET | ticket ID, 입장 순번 | 현재 순번 조회와 다음 승급 대상 선택 |
| `deadline` ZSET | ticket ID, 대기 만료 시각 | WAITING ticket 만료 정리 |
| `active` ZSET | ticket ID, entry lease 만료 시각 | ADMITTED ticket 만료 정리와 active session 계산 |
| ticket Hash | 상태·회원·순번·만료 시각 | status 응답과 상태 전이의 기준 데이터 |

5. [WaitingRoomPromotionScheduler](../../src/main/java/org/example/ticket/reservation/waitingroom/scheduler/WaitingRoomPromotionScheduler.java)는 Waiting Room 역할 인스턴스에서 주기적으로 `promote`를 호출한다. Lua는 만료 ticket을 정리하고 `maxActiveSessions`, `admitPerInterval`을 확인한 뒤 앞 순번 ticket을 `ADMITTED`로 바꾼다.
6. [HmacWaitingRoomPassCodec](../../src/main/java/org/example/ticket/reservation/waitingroom/pass/HmacWaitingRoomPassCodec.java)는 ADMITTED ticket의 entry pass를 서명·검증한다. [WaitingRoomAccessGuard](../../src/main/java/org/example/ticket/reservation/waitingroom/service/WaitingRoomAccessGuard.java)는 좌석 조회와 `pre-reserve` 전에 적용 회차 여부를 먼저 확인한다. 미적용 회차는 `BYPASS`로 처리하고, 적용 회차는 pass, 회원, 회차, 만료, Redis ticket 상태를 검사한다.

### 3.2 Ticket SSE와 HTTP polling

```text
SSE:     page.tsx → api.ts events() → WaitingRoomSseController
            → WaitingRoomSseNotificationService → SseEmitter
            ← Redis Pub/Sub ← WaitingRoomLifecyclePublisher

polling: page.tsx → api.ts status() → WaitingRoomController
            → WaitingRoomService.status() → Redis ticket Hash·waiting ZSET
```

- [WaitingRoomSseController](../../src/main/java/org/example/ticket/reservation/waitingroom/controller/WaitingRoomSseController.java)는 `text/event-stream` 연결을 열고 Nginx 버퍼링 해제 헤더를 보낸다. [WaitingRoomSseNotificationService](../../src/main/java/org/example/ticket/reservation/waitingroom/sse/WaitingRoomSseNotificationService.java)와 [WaitingRoomSseEmitterRegistry](../../src/main/java/org/example/ticket/reservation/waitingroom/sse/WaitingRoomSseEmitterRegistry.java)가 초기 snapshot과 이후 이벤트를 연결별 `SseEmitter`로 보낸다.
- [WaitingRoomLifecyclePublisher](../../src/main/java/org/example/ticket/reservation/waitingroom/sse/WaitingRoomLifecyclePublisher.java)는 상태 전이 이벤트를 Redis Pub/Sub 채널에 발행한다. [WaitingRoomLifecycleSubscriber](../../src/main/java/org/example/ticket/reservation/waitingroom/sse/WaitingRoomLifecycleSubscriber.java)는 연결을 소유한 인스턴스에서 이를 받아 SSE를 전송한다.
- [프런트 대기 화면](../../frontend/src/app/waiting-room/[id]/page.tsx)은 SSE 연결을 유지하면서도 15초마다 `status()`를 호출한다. SSE 오류가 세 번 누적된 뒤와 새로고침 뒤에도 status API를 호출한다. status는 Redis ticket Hash와 `waiting` ZSET 순번을 다시 읽으므로 Pub/Sub 전달 누락과 화면 상태 차이를 복구한다.
- `WaitingRoomStatusResponse`에는 `pollAfterMs`가 포함되지만, 현재 프런트엔드의 재조정 주기는 `setInterval(..., 15_000)`으로 고정돼 있다. 현재 화면과 같은 status RPS·Redis 비용은 [후속 우선순위 조사](../implements/waiting-room/146.17-waiting-room-next-priority-review.md)에서 측정 항목으로 관리한다.

### 3.3 보호 구역과 좌석 선점

```text
SeatController / ReservationController
  → WaitingRoomAccessGuard
  → ReservationPreReserveService
  → ReservationClaimExecutionService
  → SeatAdmissionService
  → ReservationIdempotentCreationService / ReservationService
  → SeatRepository PESSIMISTIC_WRITE
  → MySQL commit
  → WaitingRoomService.complete()
```

- [SeatController](../../src/main/java/org/example/ticket/reservation/booking/controller/SeatController.java)와 [ReservationController](../../src/main/java/org/example/ticket/reservation/booking/controller/ReservationController.java)는 `X-Waiting-Room-Pass`를 받아 보호 구역 검사를 시작한다.
- [ReservationPreReserveService](../../src/main/java/org/example/ticket/reservation/booking/service/ReservationPreReserveService.java)는 entry pass를 먼저 검증하고 멱등성 claim 실행으로 요청을 넘긴다. 예약 커밋 뒤에는 `WaitingRoomService.complete()`로 active slot 반환을 시도한다.
- [ReservationClaimExecutionService](../../src/main/java/org/example/ticket/reservation/booking/service/ReservationClaimExecutionService.java)는 같은 멱등 키의 최초 결과 재생과 새로운 claim 생성을 처리한다. [SeatAdmissionService](../../src/main/java/org/example/ticket/reservation/booking/util/admission/SeatAdmissionService.java)는 좌석별 입장 허용량을 관리한다.
- [ReservationIdempotentCreationService](../../src/main/java/org/example/ticket/reservation/booking/service/ReservationIdempotentCreationService.java)와 [ReservationService](../../src/main/java/org/example/ticket/reservation/booking/service/ReservationService.java)는 트랜잭션에서 좌석 상태를 확인한다. [SeatRepository](../../src/main/java/org/example/ticket/reservation/booking/repository/SeatRepository.java)의 `PESSIMISTIC_WRITE` 조회가 MySQL 행 잠금 경계를 만든다.

### 3.4 좌석 현황 캐시와 커밋 이후 무효화

전체 캐시 흐름은 [좌석 현황 캐시 전체 흐름](../implements/seat-availability/148.4-seat-availability-cache-flow.md)에서 별도로 설명한다.

```text
SeatController
  → SeatMapCacheReader
       ├─ RedisSeatMapCacheStore 읽기
       ├─ miss: same-JVM CompletableFuture single-flight
       │          → SeatMapDatabaseReader → MySQL
       │          → 버전 조건부 Redis 저장
       └─ Redis 오류: Semaphore로 제한한 DB 대체 조회 또는 503

MySQL commit
  → SeatMapInvalidationPublisher
  → AFTER_COMMIT SeatMapCacheInvalidationListener
  → Redis 버전 증가·snapshot 삭제
```

- [SeatMapCacheReader](../../src/main/java/org/example/ticket/reservation/booking/cache/SeatMapCacheReader.java)는 cache hit·miss, same-JVM `CompletableFuture` single-flight, fallback 동시성 상한을 한곳에서 조정한다.
- [SeatMapDatabaseReader](../../src/main/java/org/example/ticket/reservation/booking/cache/SeatMapDatabaseReader.java)는 MySQL의 전체 좌석 projection을 읽고, [RedisSeatMapCacheStore](../../src/main/java/org/example/ticket/reservation/booking/cache/RedisSeatMapCacheStore.java)는 snapshot·버전·조건부 저장을 담당한다.
- [SeatMapInvalidationPublisher](../../src/main/java/org/example/ticket/reservation/booking/cache/SeatMapInvalidationPublisher.java)가 좌석 상태 변경 중 무효화 이벤트를 발행하고, [SeatMapCacheInvalidationListener](../../src/main/java/org/example/ticket/reservation/booking/cache/SeatMapCacheInvalidationListener.java)가 `AFTER_COMMIT`에서 Redis version 증가와 snapshot 삭제를 수행한다.

## 4. Nginx와 애플리케이션 역할 분리

[docker-compose.yml](../../docker-compose.yml)은 같은 애플리케이션 이미지를 Reservation Application 역할과 Waiting Room Service 역할로 실행한다. [WaitingRoomPromotionScheduler](../../src/main/java/org/example/ticket/reservation/waitingroom/scheduler/WaitingRoomPromotionScheduler.java)는 `ticket.application.role=waiting-room`일 때만 동작한다. Gateway는 join·status·SSE 요청을 Waiting Room Service로 보내고, 좌석 조회·선점 요청은 Reservation Application으로 보낸다.

이 경계는 애플리케이션 코드와 배포 설정이 함께 만드는 동작이다. Java 코드만 읽으면 상태 전이와 보호 구역을 이해할 수 있고, Nginx 설정과 Compose를 함께 읽으면 요청이 어느 인스턴스에 도달하는지 확인할 수 있다.

## 5. 코드가 없는 판단과 보관된 구현

| guide 설명 | 현재 상태 | 확인할 근거 |
|---|---|---|
| Redis Streams 예약 큐·비동기 join handoff | 제품 경로에서 제거 | [보관 색인](../archive/145-retired-reservation-stream-queue/README.md) · [A1·A2 비교](../test/2026-08-20-waiting-room-a1-a2-comparison.md) |
| Outbox·Message Bus | 생산자·소비자·Outbox 테이블이 현재 애플리케이션에 없음 | [Message Bus 도입 판정](../implements/seat-availability/148.2-message-bus-adoption-gate-test-result.md) |
| Nginx·Waiting Room 용량 수치 | 실행 결과 | [Waiting Room 성능 결과](waiting-room-performance/03-execution-results.md) · [전체 종료 기준](waiting-room-performance-improvement-summary.md) |
| 캐시 적중률·p95·DB projection 수치 | 실행 결과 | [캐시 종합 결과](../implements/seat-availability/148.1-results-and-decision.md) |

이 구분을 따르면 현재 코드에서 확인할 수 있는 동작과, 실험으로 확인한 수치 및 도입 보류 판단을 같은 주장으로 섞지 않게 된다.

## 6. 재현 스크립트와 결과 문서

| 확인 대상 | 실행 스크립트 | 결과 문서 |
|---|---|---|
| Waiting Room 2,000명 전체 흐름 | [최종 종료 실행](../../scripts/test/run_waiting_room_final_performance_closure.sh) | [2026-08-24 최종 검증](../test/2026-08-24-waiting-room-final-closure.md) |
| Ticket SSE 연결·재연결 | [SSE 부하 실행기](../../scripts/test/146-waiting-room-sse-load.mjs) | [후속 우선순위 조사](../implements/waiting-room/146.17-waiting-room-next-priority-review.md) |
| 메모리·Redis 저장소 비교 | [WaitingRoomStore 성능 비교 테스트](../../src/test/java/org/example/ticket/reservation/waitingroom/repository/WaitingRoomStorePerformanceComparisonTest.java) · [메모리 계약 테스트](../../src/test/java/org/example/ticket/reservation/waitingroom/repository/inmemory/InMemoryWaitingRoomStoreTest.java) | [단일 서버 메모리 대안 의사결정](../implements/waiting-room/146.18-single-server-memory-queue-decision.md) |
| 캐시 집중 조회·스탬피드 | [캐시 집중 부하](../../scripts/test/run_performance_cache_k6.sh) · [스탬피드 실행기](../../scripts/test/05-cache-stampede.js) | [캐시 종합 결과](../implements/seat-availability/148.1-results-and-decision.md) |

부하 수치를 다시 확인할 때는 스크립트의 입력 조건과 결과 문서를 함께 읽는다. 코드 파일은 실행한 기능의 경계와 측정 대상이 무엇인지 설명한다.
