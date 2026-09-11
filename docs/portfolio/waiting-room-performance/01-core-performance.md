# 핵심 성능 개선 내용

작성일: 2026-08-26

## 1. 문제를 세 구간으로 분리했다

| 구간 | 보호 대상 | 핵심 지표 |
|---|---|---|
| 유입 경계 | Reservation Application Tomcat | busy thread, `/join` latency |
| 보호 구간 | HikariCP·MySQL·예약 transaction | Hikari pending, seat map·pre-reserve latency |
| 좌석 경합 | 좌석 상태 정합성 | 성공 1건, `409` N-1건, deadlock |

세 구간은 같은 부하에서 관찰되지만 해결책은 각각 다르다. 이 분리를 기준으로 Waiting Room과 좌석 예약의 책임을 정리했다.

## 2. 최종 요청 흐름

Nginx 도입 전에는 대기열 등록 요청이 Reservation Application의 `/join`으로 직접 들어갔다. Redis Waiting Room의 순번·상태 관리는 동작했지만 join·status·SSE와 좌석 조회·선점이 같은 Tomcat 자원을 사용했다.

```text
Client
  → Reservation Application `/join`
  → Redis ZSET·Hash·Lua
  → ticket status·SSE
  → seat map·pre-reserve
```

첫 번째 Nginx 단계에서는 `/join` 전달률을 조절하면서 기존 Reservation Application을 유지했다. 최종 단계에서 Waiting Room Service를 별도 자원 풀로 분리했다.

```text
Client
  → Nginx Gateway
  → Waiting Room Service
  → Redis ZSET·Hash·Lua
  → Ticket SSE + 15초 status 재조정
  → entry pass
  → Reservation Application
  → MySQL 좌석 잠금·예약 transaction
```

Nginx는 요청을 적절한 서비스로 전달하고 `/join` burst의 origin 전달 시점을 조절한다. Waiting Room Service는 순번·대기 상태·입장 상태를 관리한다. Reservation Application은 entry pass를 확인한 뒤 좌석 API와 예약 transaction을 수행한다.

## 3. 개선 효과

| 비교 지표 | 기준 | 적용 후 | 결과 |
|---|---:|---:|---:|
| 좌석별 입장 제어 전송 시간 초과 | 938건 | 0건 | 100% 감소 |
| Nginx 적용 후 origin Tomcat busy | 200 | 57~75 | 62.5~71.5% 감소 |
| 별도 Waiting Room Service 이후 origin Tomcat busy | 200 | 30~32 | 84~85% 감소 |
| D 보호 구간 HikariCP pending | 관측 대상 | 0 | DB 연결 대기 없음 |
| 동일 좌석 경합 | 다중 성공 가능성 | 성공 1건·`409` N-1건 | 정합성 계약 통과 |

Nginx의 `100 req/s` 전달률은 client `/join` latency를 증가시켰다. 별도 Waiting Room Service에서는 join server p95가 201~319ms로 측정됐다. 사용자 지연과 origin 보호 효과를 함께 기록해야 한다.

## 4. 포트폴리오에서 설명할 인과관계

```text
2,000명 burst가 예약 애플리케이션에 직접 도달
  → Tomcat worker 200개 포화
  → Nginx ingress 경계에서 전달률 제어
  → origin Tomcat busy 감소
  → Waiting Room Service를 별도 자원 풀로 분리
  → 예약 애플리케이션의 Tomcat busy 30~32, HikariCP pending 0
```

핵심 개선은 Redis 명령을 비동기로 바꾼 결과에 있다기보다, 보호해야 할 예약 애플리케이션 앞에 유입 경계와 active session 상한을 둔 결과로 설명한다. Redis Lua는 순번·상태·만료·입장 전환의 원자성을 담당한다.

## 5. 유지·제거 범위

- 유지: Redis ZSET·Hash·Lua, promotion scheduler, `maxActiveSessions`, Ticket SSE, 15초 status 재조정, Redis Pub/Sub, entry pass
- 유지: 동기 `pre-reserve`, MySQL transaction, 좌석 잠금, idempotency
- 제거: Redis Streams join handoff, join-request SSE, 비동기 join 전용 worker와 설정
- 후속 후보: 좌석 조회 Read Model, Outbox, Message Bus

상세 흐름은 [전체 흐름](../../implements/waiting-room/146.14-waiting-room-flow.md), 결정 근거는 [ADR](../../implements/waiting-room/146.12-waiting-room-admission-boundary-adr.md)에서 확인한다.
