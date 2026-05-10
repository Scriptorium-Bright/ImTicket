# MySQL Lock Wait Troubleshooting

## 목적

이 문서는 ImTicket의 예매 선점 흐름에서 같은 좌석에 요청이 몰릴 때 발생할 수 있는 MySQL row lock wait 상황을 재현하고 분석하기 위한 기준 문서입니다.

이 작업의 목적은 기능 추가가 아니라 아래 질문에 답할 수 있는 운영 근거를 만드는 것입니다.

1. 어떤 DB row에 lock이 걸리는가
2. 동시 요청이 들어오면 어떤 요청이 대기하는가
3. 대기 상황을 MySQL과 애플리케이션 지표에서 어떻게 확인하는가
4. 정합성 장치인 비관적 락이 어떤 운영 리스크를 만드는가

## 1.1 현재 예매 락 흐름 기준선

### 진입점

- API: `POST /api/reservation/pre-reserve`
- Controller: `ReservationController#registerReservation`
- Service: `ReservationService#createReservation`
- Lock path: `SeatService#findAndLockSeatsByIds`
- Repository: `SeatRepository#findByIdsForUpdate`

### 현재 흐름

1. 인증된 사용자가 `POST /api/reservation/pre-reserve`로 좌석 ID 목록을 보냅니다.
2. `ReservationController`는 `PreReserveGuard`를 먼저 통과한 뒤 `ReservationService#createReservation`을 호출합니다.
3. `ReservationService#createReservation`은 `seatService.findAndLockSeatsByIds(request.getSeatIds())`를 호출합니다.
4. `SeatRepository#findByIdsForUpdate`는 `@Lock(LockModeType.PESSIMISTIC_WRITE)`로 같은 seat row에 write lock을 겁니다.
5. 첫 번째 트랜잭션은 lock을 잡고 좌석 상태를 `LOCKED`로 변경합니다.
6. 같은 좌석을 잡으려는 다른 트랜잭션은 lock wait 상태가 되거나, lock 해제 뒤 이미 `LOCKED`인 좌석으로 판단되어 실패합니다.

### 분석 포인트

- 비관적 락은 중복 예매를 막는 정합성 장치입니다.
- 하지만 hot seat에 요청이 몰리면 lock wait 중인 요청도 애플리케이션 thread와 DB connection을 점유할 수 있습니다.
- 따라서 비관적 락 자체는 필요하지만, 운영 관점에서는 lock wait 시간과 connection pool 점유를 함께 관찰해야 합니다.

## 1.2 Lock Wait 재현 도구

### 스크립트

- `scripts/troubleshooting/reproduce_lock_wait.sh`

### 재현 조건

- 애플리케이션이 실행 중이어야 합니다.
- MySQL에 예약 가능한 `Seat` row가 있어야 합니다.
- 인증된 JWT가 필요합니다.
- 같은 `SEAT_ID` 또는 같은 `SEAT_IDS`를 여러 요청이 동시에 사용해야 합니다.

### 실행 명령

```bash
BASE_URL=http://127.0.0.1:10080 \
JWT='<access-token>' \
SEAT_ID=1 \
CONCURRENCY=20 \
scripts/troubleshooting/reproduce_lock_wait.sh
```

여러 좌석을 동시에 잡는 예시는 아래와 같습니다.

```bash
BASE_URL=http://127.0.0.1:10080 \
JWT='<access-token>' \
SEAT_IDS=1,2,3 \
CONCURRENCY=20 \
scripts/troubleshooting/reproduce_lock_wait.sh
```

### 출력

스크립트는 요청별 HTTP status, `time_total`, response body 파일 경로를 TSV로 남깁니다.

예상 출력 형식:

```text
request	status	time_total	response_file
1	200	0.123456	/tmp/imticket-lock-wait/lock-wait-...-1.body
2	429	0.045678	/tmp/imticket-lock-wait/lock-wait-...-2.body
3	500	2.001234	/tmp/imticket-lock-wait/lock-wait-...-3.body
```

### 기대 결과

- 하나의 요청만 좌석 선점에 성공합니다.
- 나머지 요청은 rate limit, duplicate suppression, 이미 잠긴 좌석, timeout 중 하나로 실패할 수 있습니다.
- lock path에 진입한 요청이 많을수록 latency가 증가할 수 있습니다.

## 1.3 관측 절차

### 스크립트

- `scripts/troubleshooting/observe_mysql_lock_wait.sh`

### 실행 명령

```bash
MYSQL_HOST=127.0.0.1 \
MYSQL_PORT=10047 \
MYSQL_USER=capstone \
MYSQL_PASSWORD='<password>' \
MYSQL_DATABASE=capstone \
scripts/troubleshooting/observe_mysql_lock_wait.sh
```

### MySQL 관측 포인트

`SHOW FULL PROCESSLIST`는 lock wait 중인 session, query, time 값을 빠르게 확인하는 용도입니다.

```sql
SHOW FULL PROCESSLIST;
```

`SHOW ENGINE INNODB STATUS`는 latest detected deadlock, transaction wait, locked row 정보를 확인하는 용도입니다.

```sql
SHOW ENGINE INNODB STATUS\G
```

`performance_schema.data_locks`는 현재 lock을 잡고 있거나 기다리는 transaction의 lock mode와 대상 object를 확인하는 용도입니다.

```sql
SELECT
  ENGINE_TRANSACTION_ID,
  OBJECT_SCHEMA,
  OBJECT_NAME,
  INDEX_NAME,
  LOCK_TYPE,
  LOCK_MODE,
  LOCK_STATUS,
  LOCK_DATA
FROM performance_schema.data_locks
WHERE OBJECT_SCHEMA = DATABASE()
ORDER BY ENGINE_TRANSACTION_ID, OBJECT_NAME, INDEX_NAME;
```

`performance_schema.data_lock_waits`는 어떤 transaction이 어떤 transaction을 기다리는지 확인하는 용도입니다.

```sql
SELECT
  REQUESTING_ENGINE_TRANSACTION_ID,
  BLOCKING_ENGINE_TRANSACTION_ID,
  REQUESTING_THREAD_ID,
  BLOCKING_THREAD_ID
FROM performance_schema.data_lock_waits;
```

### 애플리케이션 관측 포인트

Actuator / Prometheus가 켜져 있으면 아래 지표를 함께 봅니다.

```bash
curl -s http://127.0.0.1:10080/actuator/prometheus | grep 'hikaricp_connections_active'
curl -s http://127.0.0.1:10080/actuator/prometheus | grep 'hikaricp_connections_pending'
curl -s http://127.0.0.1:10080/actuator/prometheus | grep 'http_server_requests'
```

### 판단 기준

- `data_lock_waits`에 row가 생기면 lock wait 관계가 관측된 것입니다.
- `SHOW ENGINE INNODB STATUS`의 transaction 섹션에서 waiting lock과 blocking transaction을 확인합니다.
- Hikari active connection이 증가하고 pending thread가 생기면 lock wait가 애플리케이션 자원 점유로 번지는 신호입니다.

## 1.4 재현 결과

### 수행한 검증

- `bash -n scripts/troubleshooting/reproduce_lock_wait.sh`
- `bash -n scripts/troubleshooting/observe_mysql_lock_wait.sh`

결과:

- 두 스크립트 모두 shell syntax 검증을 통과했습니다.

### 미수행 항목

- 실제 `POST /api/reservation/pre-reserve` 병렬 호출
- MySQL `SHOW PROCESSLIST` / `SHOW ENGINE INNODB STATUS` 실시간 관측
- `performance_schema.data_locks`, `performance_schema.data_lock_waits` 결과 수집
- Hikari active / pending connection 지표 수집

### 미수행 사유

- 현재 로컬 `http://127.0.0.1:10080/actuator/health`에 연결할 수 없어 애플리케이션이 실행 중이 아닌 상태로 판단했습니다.
- 인증된 JWT와 예약 가능한 테스트 좌석 ID가 필요하므로 실제 lock wait 재현은 실행 환경 준비 후 수행해야 합니다.

### 실행 환경 준비 후 재현 순서

1. 애플리케이션과 MySQL을 실행합니다.
2. 예약 가능한 `Seat` ID를 하나 선택합니다.
3. 인증된 JWT를 준비합니다.
4. 터미널 A에서 lock wait 재현 스크립트를 실행합니다.
5. 터미널 B에서 MySQL 관측 스크립트를 반복 실행합니다.
6. HTTP status, latency, MySQL lock wait, Hikari 지표를 함께 기록합니다.

### 대응 방향

- DB row lock은 최종 정합성 방어선으로 유지합니다.
- hot seat 요청이 DB lock wait로 직접 몰리지 않도록 pre-reserve admission control과 duplicate suppression을 먼저 통과시킵니다.
- lock wait timeout을 명시적으로 관리하고, 실패 시 사용자에게 빠르게 재시도 가능한 응답을 주는 방향을 검토합니다.
- 트랜잭션 안에서는 좌석 상태 확인과 변경만 수행하고 외부 호출은 넣지 않습니다.

## 포트폴리오 문장 초안

MySQL 기반 예매 선점 흐름에서 같은 좌석 row에 동시 요청이 몰릴 때 비관적 락이 어떻게 중복 예매를 막는지 확인하고, 동시에 lock wait가 connection pool 점유와 API 지연으로 이어질 수 있음을 재현 가능한 시나리오로 정리했습니다.
