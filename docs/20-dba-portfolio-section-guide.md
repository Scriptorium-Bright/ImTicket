# DBA 포트폴리오 섹션 구성 가이드

## 목적

이 문서는 ImTicket 포트폴리오에서 아래 5개 섹션을 어떻게 구성할지 정리한 가이드입니다.

- 2. 데이터 모델링
- 4. MySQL Lock Wait 시나리오 분석
- 5. 인덱스와 쿼리 성능 개선
- 7. Redis Stream ACK/Pending 장애 분석
- 9. 장애 대응 Runbook

목표는 프로젝트 기능 설명이 아니라, DB 운영 공고에서 보는 `데이터 구조 이해`, `SQL/락/인덱스 분석`, `장애 원인 분석`, `운영 대응 절차`를 보여주는 것입니다.

## 2. 데이터 모델링

### 이 섹션의 목적

티켓 예매 서비스의 핵심 데이터를 관계형 DB 관점에서 어떻게 나눴는지 보여줍니다.
단순 ERD가 아니라, 왜 그렇게 나눴는지 설명해야 합니다.

### 반드시 넣을 내용

- 회원, 공연, 공연 회차, 좌석, 예약, 입장 로그의 관계
- 공연장 원본 좌석과 회차별 판매 좌석을 분리한 이유
- `Reservation`과 `Seat`의 상태를 분리한 이유
- 예약 확정 전 좌석 선점 상태를 `LOCKED`로 둔 이유
- `EntryLog`를 별도 테이블로 두어 중복 입장을 막는 구조

### 추천 그림

#### 그림 1. 핵심 ERD

포함할 테이블:

- `Member`
- `Organizer`
- `Performance`
- `PerformanceTime`
- `Venue`
- `VenueHall`
- `Seat`
- `Reservation`
- `ReservedSeat`
- `EntryLog`

그림에서 강조할 관계:

- `Performance` 1:N `PerformanceTime`
- `PerformanceTime` 1:N `Seat`
- `Reservation` 1:N `ReservedSeat`
- `ReservedSeat` N:1 `Seat`
- `EntryLog` 1:1 `Reservation`

캡션 예시:

`공연 회차별 판매 좌석을 별도 Seat 테이블로 생성해 같은 공연장 좌석이라도 회차별 예약 상태를 독립적으로 관리하도록 설계했습니다.`

#### 그림 2. 좌석 데이터 분리 구조

형태:

```text
Venue / VenueHall / VenueHallFloor / Section / Row / SeatTemplate
                       |
                       | 공연 회차 생성 시 판매 좌석으로 변환
                       v
PerformanceTime -> Seat(price, status, reservation state)
```

캡션 예시:

`공연장 원본 좌석 템플릿과 회차별 판매 좌석을 분리해, 같은 공연장이라도 공연 회차마다 가격과 예약 상태를 독립적으로 관리할 수 있게 했습니다.`

### 표로 넣을 내용

| 테이블 | 역할 | 운영 관점 의미 |
| --- | --- | --- |
| `Seat` | 회차별 판매 좌석 | row lock과 상태 전이의 핵심 대상 |
| `Reservation` | 예약 단위 | 만료 cleanup과 결제 확정의 기준 |
| `ReservedSeat` | 예약-좌석 매핑 | 다중 좌석 예약을 표현 |
| `EntryLog` | 입장 기록 | 중복 입장 방지 기준 |

### 근거 문서

- [04-reservation-and-entry.md](./04-reservation-and-entry.md)
- [01-system-map.md](./01-system-map.md)

## 4. MySQL Lock Wait 시나리오 분석

### 이 섹션의 목적

예매 시스템에서 같은 좌석에 요청이 몰릴 때 MySQL row lock이 어떻게 정합성을 지키고, 동시에 어떤 운영 리스크를 만드는지 보여줍니다.

### 반드시 넣을 내용

- 같은 좌석에 동시에 예매 요청이 들어오는 상황
- `SeatRepository#findByIdsForUpdate`의 `PESSIMISTIC_WRITE`
- 첫 번째 트랜잭션이 row lock을 잡고 나머지는 lock wait에 들어가는 흐름
- lock wait가 DB connection과 application thread 점유로 이어질 수 있다는 분석
- 비관적 락은 정합성 장치이지 트래픽 제어 장치가 아니라는 결론
- 분석 이후 실제 적용한 보완 장치
- `PreReserveGuard`, duplicate suppression, admission control이 DB lock path 앞단에서 어떤 역할을 하는지

### 추천 그림

#### 그림 1. 동일 좌석 동시 요청 시퀀스

형태:

```text
User A -> API -> TX A -> SELECT Seat FOR UPDATE -> lock 획득
User B -> API -> TX B -> SELECT Seat FOR UPDATE -> lock wait
User C -> API -> TX C -> SELECT Seat FOR UPDATE -> lock wait

TX A -> Seat AVAILABLE to LOCKED -> commit
TX B/C -> lock 해제 후 상태 확인 -> 이미 LOCKED -> 실패
```

캡션 예시:

`같은 Seat row에 대한 동시 요청은 첫 번째 트랜잭션만 lock을 획득하고, 나머지 요청은 lock wait 상태가 됩니다. 이 구조는 중복 예매를 막지만 대기 요청이 DB connection을 점유할 수 있습니다.`

#### 그림 2. Lock Wait가 장애로 번지는 흐름

형태:

```text
Hot seat traffic
-> row lock wait 증가
-> active DB connection 증가
-> Hikari pending thread 증가
-> unrelated API latency 증가
-> timeout / 5xx 가능성
```

캡션 예시:

`비관적 락은 데이터 정합성을 보장하지만, hot row 경합에서는 connection pool 점유를 통해 전체 API 지연으로 확산될 수 있습니다.`

### 넣을 명령어

```sql
SHOW FULL PROCESSLIST;
SHOW ENGINE INNODB STATUS\G;
SELECT * FROM performance_schema.data_locks;
SELECT * FROM performance_schema.data_lock_waits;
```

### 넣을 표

| 관측 지표 | 의미 | 확인 방법 |
| --- | --- | --- |
| lock wait transaction | 대기 중인 트랜잭션 | `data_lock_waits` |
| blocking transaction | lock을 잡고 있는 트랜잭션 | `SHOW ENGINE INNODB STATUS` |
| active connection | DB connection 점유 | Hikari metric |
| p95/p99 latency | 사용자 영향 | Prometheus / API 결과 |

### 결과 중심으로 써야 할 문장

약한 표현:

`lock wait 가능성을 분석하고 DB 진입 전 요청 제어의 필요성을 정리했습니다.`

강한 표현:

`동일 좌석 병렬 요청 시나리오를 기준으로 DB row lock 경합을 재현할 수 있는 스크립트와 관측 절차를 만들고, DB lock path 앞단에 pre-reserve duplicate suppression과 admission control을 두어 lock wait가 connection pool 점유로 번지는 위험을 줄이는 구조로 정리했습니다.`

### 코드/구조 조치로 연결할 내용

- `PreReserveGuard`로 동일 wallet + performanceTime + seatIds 조합의 중복 요청을 짧은 TTL 동안 차단합니다.
- `PreReserveAdmissionController`로 동일 공연 회차에 대한 DB lock path 진입량을 제한합니다.
- 트랜잭션 내부에서는 좌석 조회, 상태 확인, 상태 변경, 예약 저장만 수행하도록 설명합니다.
- 외부 API 호출이나 긴 I/O가 있다면 트랜잭션 밖으로 빼야 한다는 원칙을 명시합니다.

### 근거 문서

- [18-mysql-lock-wait-troubleshooting.md](./18-mysql-lock-wait-troubleshooting.md)
- [16-rate-limit-and-admission-control-baseline-and-changes.md](./16-rate-limit-and-admission-control-baseline-and-changes.md)

## 5. 인덱스와 쿼리 성능 개선

### 이 섹션의 목적

반복 실행되는 운영성 쿼리를 어떻게 찾고, 어떤 인덱스가 필요한지 설명합니다.
가능하면 실행 계획과 전후 수치를 넣어야 합니다.

### 반드시 넣을 내용

- 예약 만료 cleanup 쿼리의 병목 가능성
- `reservation_expired_time` 인덱스가 필요한 이유
- 회차별 좌석 조회에서 `performance_time_id`, `seat_status` 조건이 중요한 이유
- 전체 join을 한 번에 처리하는 방식보다 ID 선조회 후 batch 처리하는 방식이 왜 유리한지
- 기존 문서의 wall-clock 개선 수치

### 추천 그림

#### 그림 1. 예약 만료 cleanup 쿼리 개선 전후

형태:

```text
Before
Reservation + ReservedSeat + Seat join
-> expiredTime 조건으로 만료 예약 조회
-> 큰 join 결과를 한 번에 처리

After
Reservation(expiredTime index)로 expired reservation id 선조회
-> id 목록 기준 ReservedSeat/Seat fetch
-> batch 단위 좌석 상태 복구
```

캡션 예시:

`만료 예약 cleanup은 조건 컬럼 인덱스로 대상 ID를 먼저 좁힌 뒤, 필요한 좌석만 batch로 조회하도록 구조를 나누는 것이 유리합니다.`

#### 그림 2. 인덱스 적용 전후 비교 막대그래프

추천 데이터:

- 기존 cleanup wall-clock: `1.84~2.18s`
- 개선 후 cleanup wall-clock: `0.06~0.15s`

그래프 형태:

```text
cleanup wall-clock
Before | #################### 1.84~2.18s
After  | ##                   0.06~0.15s
```

캡션 예시:

`예약 만료 cleanup 병목 후보를 측정하고, 인덱스와 batch 조회 구조를 통해 wall-clock 시간을 줄이는 방향을 검증했습니다.`

### 넣을 SQL 예시

```sql
EXPLAIN ANALYZE
SELECT id
FROM Reservation
WHERE reservation_expired_time < NOW()
ORDER BY reservation_expired_time
LIMIT 5000;
```

Before 쿼리 shape:

```sql
EXPLAIN ANALYZE
SELECT DISTINCT r.id
FROM bench_reservation r
LEFT JOIN bench_reserved_seat rs ON rs.reservation_id = r.id
LEFT JOIN bench_seat s ON s.id = rs.seat_id
WHERE r.reservation_expired_time < NOW(6);
```

After 쿼리 shape:

```sql
EXPLAIN ANALYZE
SELECT r.id
FROM bench_reservation r
WHERE r.reservation_expired_time < NOW(6)
ORDER BY r.reservation_expired_time
LIMIT 5000;
```

```sql
SHOW INDEX FROM Reservation;
SHOW INDEX FROM Seat;
```

### 넣을 표

| 개선 대상 | 문제 | 적용한 방향 | 기대 효과 |
| --- | --- | --- | --- |
| 만료 예약 cleanup | 만료 조건 조회와 join 비용 | `reservation_expired_time` 인덱스 | scan 범위 축소 |
| 좌석 조회 | 회차별 좌석 상태 조회 반복 | `performance_time_id, seat_status` 복합 인덱스 | 조회 조건 최적화 |
| cleanup 처리 | 큰 join 결과 일괄 처리 | ID 선조회 후 batch fetch | 메모리/락 범위 축소 |

### EXPLAIN / 정량 근거 표

포트폴리오에는 아래처럼 실행 계획과 수치를 반드시 한 표로 넣습니다.

| 쿼리 | 데이터 규모 | 개선 전 실행 계획 | 개선 전 시간 | 개선 후 방향 | 개선 후 시간 |
| --- | ---: | --- | ---: | --- | ---: |
| `phone_number` 존재 확인 | 500,000 rows | table scan | `90.2~201ms` | phone index 후보 | 측정 필요 |
| `reservation_expired_time` count | 300,000 rows | table scan | `96.8ms` | range scan 후보 | 측정 필요 |
| 만료 예약 + 좌석 join | 300,000 reservations + 600,000 seats | table scan + nested loop + temp dedup | `1.36~1.50s` | expired id 선조회 + batch fetch | `0.06~0.15s` |

주의:

- `측정 필요`인 항목은 포트폴리오에서 확정 개선 결과처럼 쓰지 않습니다.
- 이미 측정된 `1.36~1.50s -> 0.06~0.15s` 항목만 결과로 강하게 씁니다.
- `type: ALL`, `range scan`, `rows`, `temporary table`, `dedup` 같은 실행 계획 키워드를 캡처 또는 표로 보여줍니다.

### 결과 중심으로 써야 할 문장

약한 표현:

`인덱스 설계가 조회 성능에 미치는 영향을 분석했습니다.`

강한 표현:

`만료 예약 cleanup의 join shape을 EXPLAIN ANALYZE로 확인한 결과, 300,000건 예약 테이블에서 table scan과 nested loop, temporary dedup이 발생했습니다. 이를 만료 예약 ID 선조회 후 batch fetch하는 구조로 분리해 cleanup wall-clock을 1.36~1.50s에서 0.06~0.15s 수준으로 줄이는 방향을 검증했습니다.`

### 근거 문서

- [09-index-bottleneck-benchmark.md](./09-index-bottleneck-benchmark.md)
- [13-imticket-dba-portfolio-bridge.md](./13-imticket-dba-portfolio-bridge.md)

## 7. Redis Stream ACK/Pending 장애 분석

### 이 섹션의 목적

Redis Stream을 단순 비동기 처리 도구로 썼다는 설명에서 끝내지 않고, ACK 전 장애와 pending message 재처리 문제를 이해하고 있음을 보여줍니다.

### 반드시 넣을 내용

- 좌석 생성 작업을 Redis Stream으로 분리한 이유
- `XADD -> XREADGROUP -> 처리 -> XACK` 흐름
- ACK 전에 consumer가 죽으면 pending message가 남는 구조
- `XPENDING`으로 pending 1건 확인
- `XACK` 후 pending 0건 확인
- 재처리 시 중복 생성 위험
- 멱등성, jobId, unique key, DLQ 필요성

### 추천 그림

#### 그림 1. 정상 처리 흐름

형태:

```text
Producer
  -> XADD seat-creation-stream
  -> Consumer Group
  -> Consumer
  -> DB seat creation
  -> XACK
  -> pending 0
```

캡션 예시:

`정상 처리에서는 consumer가 DB 작업을 완료한 뒤 XACK를 보내 pending entries list에서 메시지를 제거합니다.`

#### 그림 2. ACK 전 장애 흐름

형태:

```text
Consumer XREADGROUP
-> DB 작업 수행
-> ACK 전 장애
-> pending entries list에 message 유지
-> recovery consumer 재처리
-> 중복 생성 위험
```

캡션 예시:

`Redis Stream은 at-least-once 전달을 전제로 보아야 하며, ACK 전 장애 시 같은 메시지가 다시 처리될 수 있습니다.`

#### 그림 3. 실제 관측 결과 캡처

넣을 내용:

```text
XPENDING summary
1
1778399325599-0
1778399325599-0
pending-debugger
1

XACK
1

XPENDING after ack
0
```

캡션 예시:

`Troubleshooting stream에서 XREADGROUP 후 ACK를 생략해 pending 1건을 만들고, XACK 후 pending이 0건으로 줄어드는 것을 확인했습니다.`

### 넣을 표

| 장애 지점 | 발생 가능한 문제 | 대응 방향 |
| --- | --- | --- |
| XADD 실패 | 작업 요청 유실 | 발행 실패 로깅/재시도 |
| DB commit 후 XACK 전 장애 | pending 재처리 | jobId 기반 멱등 처리 |
| consumer 반복 실패 | pending 누적 | retry count / DLQ |
| pending 장기 방치 | Redis memory 압박 | XPENDING 모니터링 / XAUTOCLAIM |

### 결과 중심으로 써야 할 문장

약한 표현:

`Redis Stream의 ACK와 pending 구조를 분석했습니다.`

강한 표현:

`Troubleshooting stream에서 XREADGROUP 후 XACK를 생략해 pending 1건을 재현했고, XACK 후 XPENDING이 0건으로 줄어드는 것을 확인했습니다. 이를 통해 좌석 생성 consumer는 at-least-once 전달을 전제로 jobId와 business key 기반 멱등 처리가 필요하다고 정리했습니다.`

### 근거 문서

- [19-redis-stream-ack-pending-troubleshooting.md](./19-redis-stream-ack-pending-troubleshooting.md)
- [10-redis-stream-hardening-plan.md](./10-redis-stream-hardening-plan.md)

## 9. 장애 대응 Runbook

### 이 섹션의 목적

장애가 났을 때 무엇을 어떤 순서로 확인하는지 보여줍니다.
DB 운영 직무에서는 “분석할 수 있다”보다 “확인 순서가 있다”가 더 강합니다.

### 반드시 넣을 내용

- lock wait 발생 시 확인 순서
- Redis pending 증가 시 확인 순서
- 예약 만료 cleanup 지연 시 확인 순서
- 관측 명령어
- 판단 기준
- 즉시 조치
- 재발 방지

### 추천 그림

#### 그림 1. 장애 대응 의사결정 플로우

형태:

```text
API latency 증가
  |
  +-- Hikari active/pending 증가?
  |     |
  |     +-- yes -> MySQL processlist / data_lock_waits 확인
  |     +-- no  -> Redis/API/외부 I/O 확인
  |
  +-- Redis pending 증가?
        |
        +-- yes -> XPENDING / XINFO GROUPS / consumer log 확인
        +-- no  -> application log / DB slow query 확인
```

캡션 예시:

`API 지연이 발생했을 때 DB connection 점유, lock wait, Redis pending 여부를 순서대로 확인해 원인을 좁힙니다.`

#### 그림 2. Runbook 체크리스트 표

포트폴리오에는 이미지보다 표가 더 좋습니다.

| 증상 | 1차 확인 | 2차 확인 | 즉시 조치 | 재발 방지 |
| --- | --- | --- | --- | --- |
| 예매 API 지연 | Hikari active/pending | `data_lock_waits` | 요청 제한/재시도 유도 | admission control |
| 중복 예매 의심 | Seat 상태 | Reservation/ReservedSeat | 상태 정합성 확인 | row lock + 상태 조건 |
| Redis pending 증가 | `XPENDING` | consumer log | recovery consumer 처리 | XAUTOCLAIM + DLQ |
| cleanup 지연 | scheduler log | EXPLAIN / index | batch size 조정 | index / batch 조회 |

### 넣을 명령어 묶음

#### MySQL lock wait

```sql
SHOW FULL PROCESSLIST;
SHOW ENGINE INNODB STATUS\G;
SELECT * FROM performance_schema.data_lock_waits;
```

#### Redis pending

```bash
redis-cli XINFO GROUPS seat-creation-stream
redis-cli XPENDING seat-creation-stream seat-creation-group
redis-cli XPENDING seat-creation-stream seat-creation-group - + 10
```

#### 애플리케이션 지표

```bash
curl -s http://127.0.0.1:10080/actuator/prometheus | grep hikaricp_connections_active
curl -s http://127.0.0.1:10080/actuator/prometheus | grep hikaricp_connections_pending
```

### Runbook은 조치까지 써야 한다

Runbook은 확인 명령어 나열로 끝나면 약합니다.
각 증상마다 아래 4단계가 있어야 합니다.

1. 탐지
2. 원인 좁히기
3. 즉시 조치
4. 재발 방지

예시:

| 증상 | 탐지 | 원인 좁히기 | 즉시 조치 | 재발 방지 |
| --- | --- | --- | --- | --- |
| 예매 API p99 증가 | Prometheus latency / Hikari pending | `data_lock_waits`, `SHOW ENGINE INNODB STATUS` | hot path 요청 제한, 사용자 재시도 응답 | admission control, lock timeout |
| Redis pending 증가 | `XINFO GROUPS`, `XPENDING` | consumer log, delivery count | recovery consumer로 재처리 후 `XACK` | XAUTOCLAIM, retry count, DLQ |
| cleanup 지연 | scheduler duration log | `EXPLAIN ANALYZE`, index 확인 | batch size 축소, cleanup 재실행 | index, ID 선조회, 실행 계획 점검 |

### 포트폴리오에서 강조할 문장

`장애 대응 Runbook은 특정 기술을 사용했다는 설명이 아니라, 장애 발생 시 어떤 지표와 명령으로 원인을 좁혀가는지 보여주는 운영 역량의 근거입니다.`

### 근거 문서

- [18-mysql-lock-wait-troubleshooting.md](./18-mysql-lock-wait-troubleshooting.md)
- [19-redis-stream-ack-pending-troubleshooting.md](./19-redis-stream-ack-pending-troubleshooting.md)
- [16-rate-limit-and-admission-control-baseline-and-changes.md](./16-rate-limit-and-admission-control-baseline-and-changes.md)

## 최종 포트폴리오 구성 팁

### 순서

1. 데이터 모델링
2. MySQL Lock Wait 시나리오 분석
3. 인덱스와 쿼리 성능 개선
4. Redis Stream ACK/Pending 장애 분석
5. 장애 대응 Runbook

### 그림 우선순위

가장 먼저 만들어야 할 그림:

1. 핵심 ERD
2. 동일 좌석 동시 요청 시퀀스
3. 예약 만료 cleanup 개선 전후
4. Redis Stream ACK 전 장애 흐름
5. 장애 대응 의사결정 플로우

### 주의할 점

- Spring/JPA 구현 설명보다 MySQL row, lock, index, transaction을 먼저 설명합니다.
- Redis Stream은 “비동기 처리 적용”보다 “ACK 전 장애와 pending 재처리”를 앞세웁니다.
- 실제 운영 경험이라고 과장하지 말고, “운영 상황을 재현하고 분석한 경험”으로 표현합니다.
- 숫자가 있는 부분은 반드시 수치를 넣고, 없는 부분은 재현 명령과 기대 결과를 명시합니다.

## 피드백 반영 체크리스트

### 추상 표현 금지

아래 표현은 단독으로 쓰지 않습니다.

- `성능 개선 관점을 갖추고 있습니다`
- `인덱스 설계를 검토했습니다`
- `장애 상황을 분석했습니다`
- `운영 역량을 확장하고자 합니다`

대신 아래 요소 중 최소 2개를 같이 씁니다.

- 실행한 명령: `EXPLAIN ANALYZE`, `SHOW ENGINE INNODB STATUS`, `XPENDING`
- 관측한 현상: table scan, nested loop, lock wait, pending count
- 정량 수치: rows, wall-clock, pending count, p95/p99
- 실제 조치: ID 선조회, batch fetch, admission control, duplicate suppression
- 결과: 시간 감소, pending 제거, lock wait 확인 절차 확보

### Linux / 운영 경험 표현

현재 포트폴리오만으로 Linux DB 운영, Replication, HA, backup/restore를 강하게 주장하면 안 됩니다.
아직 실습하지 않은 항목은 아래처럼 씁니다.

약한 표현:

`앞으로 Linux 환경에서의 DB 운영과 Replication 역량을 확장하고자 합니다.`

대체 표현:

`현재 포트폴리오에서는 MySQL 트랜잭션 경합, 인덱스 병목, Redis pending 재처리처럼 운영 중 발생할 수 있는 장애 상황을 재현하고 분석한 경험을 중심으로 제시합니다. Replication, backup/restore, Linux 운영 자동화는 별도 실습 산출물로 보완할 계획입니다.`

### 최종 자기소개 문장에 반드시 들어갈 것

- MySQL row lock과 lock wait
- EXPLAIN ANALYZE 기반 실행 계획 확인
- cleanup wall-clock 전후 수치
- Redis Stream pending 1건 재현과 XACK 후 0건 확인
- 운영 상황을 재현하고 Runbook으로 정리한 경험
