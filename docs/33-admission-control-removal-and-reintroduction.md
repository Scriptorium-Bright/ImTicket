# Admission Control 제거와 재도입 기준

## 결정

2026-06-13 기준으로 `POST /api/reservation/pre-reserve`의 Redis slot lease 기반 Admission Control을 제거한다.

제거 대상:

- `PreReserveAdmissionController`
- `reservation.admission.performance_time` 정책
- `adm:pre-reserve:{performanceTimeId}:slot:{n}` lease
- `PreReserveGuard`의 admission 획득 및 해제

유지 대상:

- wallet + performanceTime 단위 rate limit
- wallet + performanceTime + seatIds 단위 duplicate suppression
- MySQL `PESSIMISTIC_WRITE` 기반 최종 좌석 정합성

이 결정은 Admission Control이 항상 불필요하다는 뜻이 아니다. 현재 프로젝트에는 고정값 `16 slots`, `10 seconds TTL`을 설명할 lock wait, HikariCP, latency 측정 결과가 없으므로 먼저 제거하고 기준선을 측정한다.

## 제거 이유

### 1. 해결할 문제가 수치로 확인되지 않았다

비관적 락 대기가 DB connection을 점유할 가능성은 있지만, 현재 저장소에는 다음 결과가 없다.

- 동시 요청 수별 `data_lock_waits`
- Hikari active/pending connection 변화
- Admission 적용 전후 p95/p99 latency
- DB transaction 처리 시간 분포
- 허용 slot 수별 성공률과 429 비율

따라서 현재 slot 수는 capacity planning 결과가 아니라 임의의 상수다.

### 2. Admission key와 실제 lock 대상이 일치하지 않는다

Admission은 요청 본문의 `performanceTimeId`를 사용했지만 DB lock은 `seatIds`만으로 획득한다. 회차와 좌석의 소속 검증이 없는 상태에서는 클라이언트가 다른 `performanceTimeId`를 보내 동일한 hot seat 요청을 별도 bucket으로 분산시킬 수 있다.

Admission Control보다 먼저 다음 invariant를 보장해야 한다.

```text
모든 requested seat는 request.performanceTimeId에 속해야 한다.
```

### 3. lease 구현의 운영 계약이 부족하다

기존 구현에는 다음 결정이 명확하지 않았다.

- Redis 장애 시 fail-open 또는 fail-closed
- 10초를 넘는 transaction의 lease 연장
- token compare-and-delete의 원자성
- slot full 응답을 사용자가 어떻게 재시도할지
- 여러 애플리케이션 인스턴스에서 Redis 장애가 미치는 범위

측정 없이 이 복잡도를 유지하면 보호 효과보다 운영 불확실성이 커진다.

## 현재 예매 보호 구조

```text
pre-reserve 요청
-> wallet/performanceTime rate limit
-> 동일 좌석 조합 duplicate suppression
-> MySQL transaction
   -> seatIds 기준 좌석 조회 및 row lock
   -> 좌석 상태 확인
   -> 좌석 LOCKED 변경
   -> Reservation 저장
```

역할은 다음처럼 분리한다.

| 장치 | 역할 |
| --- | --- |
| Rate Limit | 동일 사용자의 과도한 반복 요청 제한 |
| Dedupe | 더블클릭과 동일 요청의 짧은 시간 중복 제출 억제 |
| DB Pessimistic Lock | 동일 좌석 중복 선점 방지 |
| Admission Control | 현재 비활성. DB connection 보호 필요성이 측정된 경우에만 재검토 |

현재 구조에는 `performanceTimeId`와 실제 좌석 소속을 함께 검증하지 않는 문제가 남아 있다. 이 문제는 Admission Control 재도입보다 먼저 해결한다.

## 작업 순서

### 1. 정합성 기준부터 고정

Repository lock query에 `performanceTimeId`와 `seatIds`를 함께 사용한다. 조회된 좌석 수가 요청한 고유 seat ID 수와 다르면 예약을 거절한다.

### 2. Admission 없는 기준선 측정

동일 회차의 동일 좌석에 20, 50, 100개 동시 요청을 보낸다.

기록 항목:

- HTTP 성공/실패 수
- p50, p95, p99, max latency
- `performance_schema.data_lock_waits`
- lock wait 시간과 timeout 수
- `hikaricp_connections_active`
- `hikaricp_connections_pending`
- `hikaricp_connections_timeout_total`
- 다른 비경합 API의 latency 변화

### 3. 재도입 여부 판단

다음 현상이 반복 재현될 때만 Admission Control을 후보로 올린다.

- lock wait 요청이 connection pool을 장시간 점유한다.
- Hikari pending 또는 connection timeout이 발생한다.
- hot seat 경합이 다른 API latency까지 악화시킨다.
- rate limit과 dedupe만으로 DB 진입량을 충분히 줄이지 못한다.

일시적인 latency 증가만 있고 connection pool과 다른 API가 안정적이면 재도입하지 않는다.

### 4. 대안부터 비교

Admission Control 재도입 전에 아래 대안을 먼저 검토한다.

- transaction 범위 축소
- lock wait timeout 명시
- 빠른 충돌 응답
- 좌석 상태 조건을 포함한 update 전략
- 애플리케이션 단일 인스턴스라면 로컬 semaphore

여러 인스턴스에서 회차별 전역 동시 실행량을 제한해야 할 때 Redis 방식을 선택한다.

## Redis Admission 재도입 조건

재도입 시 다음 조건을 모두 만족해야 한다.

1. slot 수는 Hikari pool 크기, 비예매 트래픽 예약분, 예매 transaction 시간으로 산정한다.
2. slot 수와 lease TTL은 환경 설정으로 외부화한다.
3. release는 Lua compare-and-delete로 원자적으로 수행한다.
4. transaction이 lease TTL을 넘을 수 있다면 갱신 또는 충분한 TTL 근거가 있어야 한다.
5. Redis 장애에 대한 fail-open/fail-closed 정책을 문서화한다.
6. admission reject, active lease, acquisition latency를 메트릭으로 남긴다.
7. 적용 전후 동일 부하 테스트 결과를 비교한다.

비교 결과에는 처리량만 쓰지 않는다. DB connection 보호, tail latency, 정상 요청 성공률을 함께 본다.

## 포트폴리오 서사

권장 제목:

```text
예매 트랜잭션 진입 제어 재검토와 측정 기반 구조 단순화
```

권장 설명:

```text
초기에는 인기 회차의 DB lock 경합을 줄이기 위해 Redis lease 기반 Admission Control을 도입했습니다. 그러나 16개 slot과 10초 TTL이 실제 lock wait 및 connection pool 측정에서 나온 값이 아니었고, 요청 회차와 실제 lock 대상 좌석의 소속 검증도 먼저 보장되지 않은 상태였습니다. 이에 Admission Control을 제거하고 rate limit, duplicate suppression, DB 비관적 락만 남겨 기준선을 단순화했습니다. 이후 동일 좌석 병렬 요청에서 lock wait, Hikari active/pending, p95/p99를 측정하고 connection pool 고갈이 재현되는 경우에만 설정 외부화, 원자적 lease 해제, 장애 정책을 포함해 재도입하도록 기준을 세웠습니다.
```

피해야 할 표현:

- “Redis Admission Control로 대규모 트래픽을 해결했다.”
- “비관적 락의 성능 문제를 해결했다.”
- “16개가 최적 동시 처리량이다.”

측정 전에는 모두 증명되지 않은 주장이다.
