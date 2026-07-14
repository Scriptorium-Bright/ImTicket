# 단일 서버 등급 및 캐시 부하 테스트

- 작성일: 2026-07-14
- 상태: 미실행
- 대상: Spring Boot 1대, MySQL 1대, Redis 1대

예약과 공연 상세 API 계약은 변경하지 않는다. 예약은 같은 좌석에 VU당 1회 요청하고, 공연 상세는 기존 `GET /api/performance/intro/{id}?cache=false|true`를 사용한다.

## 0. 락 전략 비교 환경

현재 예약 API는 `LOCK_STRATEGY`로 한 가지 전략만 선택해 실행한다. 기본값은 기존 동작인 `pessimistic`이다.

| 설정값 | 구현 | 적용 범위 |
|---|---|---|
| `pessimistic` | JPA `PESSIMISTIC_WRITE` | 단일/분산 서버 가능 |
| `synchronized` | 좌석 ID별 JVM monitor | 단일 JVM만 가능 |
| `reentrant` | 좌석 ID별 fair `ReentrantLock` | 단일 JVM만 가능 |
| `optimistic` | JPA `@Version` | 충돌 시 409로 분류 |
| `mysql-named` 또는 `advisory` | MySQL `GET_LOCK` | 같은 MySQL을 공유하는 서버 가능 |
| `single-thread` | 예약 전용 단일 워커 큐 | 단일 JVM만 가능 |

앱을 재시작할 때 전략을 바꾸고, 같은 fixture와 같은 k6 조건을 순차 실행한다.

```bash
LOCK_STRATEGY=pessimistic
LOCK_STRATEGY=synchronized
LOCK_STRATEGY=reentrant
LOCK_STRATEGY=optimistic
LOCK_STRATEGY=mysql-named
LOCK_STRATEGY=single-thread
```

`synchronized`와 `reentrant`는 서버를 두 대로 늘리면 서로 다른 JVM에서 락이 공유되지 않는다. `mysql-named`는 `GET_LOCK`을 획득한 DB connection과 해제하는 connection이 같아야 하므로 구현에서 connection을 고정한다. `optimistic`은 `Seat.version` 컬럼이 필요하므로 변경된 애플리케이션을 먼저 기동해 schema를 반영한 뒤 fixture를 생성한다.

### 추가된 코드와 동작 흐름

| 파일 | 추가 또는 변경 내용 |
|---|---|
| `reservation/lock/ReservationLockStrategy.java` | 환경변수 문자열을 전략 enum으로 변환한다. `advisory`, `named`는 `mysql-named`로 매핑한다. 값이 없으면 `pessimistic`을 사용한다. |
| `reservation/lock/ReservationLockAspect.java` | `ReservationService.createReservation(..)` 전후에 선택된 락을 적용한다. 요청의 좌석 ID를 중복 제거·오름차순 정렬해 여러 좌석을 잠글 때 순서를 고정한다. |
| `reservation/service/SeatService.java` | `pessimistic`일 때 기존 `PESSIMISTIC_WRITE` 조회를 사용하고, 그 외 전략은 일반 조회를 사용한다. |
| `reservation/repository/SeatRepository.java` | 비관적 락이 없는 좌석 조회 쿼리를 추가했다. |
| `reservation/model/Seat.java` | 낙관적 락 충돌을 감지할 `@Version Long version`을 추가했다. |
| `common/exception/GlobalExceptionHandler.java` | `OptimisticLockException`과 `ObjectOptimisticLockingFailureException`을 `SEAT_ALREADY_RESERVED`(409)로 변환한다. |
| `util/config/AsyncConfig.java` | 예약 전용 `ThreadPoolTaskExecutor`를 생성한다. 워커 수는 1개이며 대기열 크기는 `LOCK_SINGLE_THREAD_QUEUE_CAPACITY`로 설정한다. |
| `application.properties`, `docker-compose.yml` | `LOCK_STRATEGY`, `LOCK_NAMED_TIMEOUT_SECONDS`, `LOCK_SINGLE_THREAD_QUEUE_CAPACITY`를 애플리케이션에 전달한다. |

예약 요청은 다음 순서로 처리된다.

1. AOP가 예약 요청에서 좌석 ID를 추출하고 오름차순으로 정렬한다.
2. `synchronized`는 좌석 ID별 JVM monitor를 중첩 획득하고, `reentrant`는 좌석 ID별 공정 `ReentrantLock`을 중첩 획득한다.
3. `mysql-named`는 같은 MySQL connection으로 좌석별 `GET_LOCK`을 호출한 뒤 예약 처리가 끝나면 역순으로 `RELEASE_LOCK`을 호출한다.
4. `pessimistic`은 서비스에서 DB `PESSIMISTIC_WRITE` 조회를 수행한다.
5. `optimistic`은 일반 조회 후 좌석 변경 시 `version`을 함께 갱신한다. 같은 좌석을 먼저 갱신한 요청이 있으면 JPA 예외를 409 응답으로 변환한다.
6. `single-thread`는 좌석별 락을 사용하지 않고 예약 메서드 실행 자체를 예약 전용 워커 하나에 맡긴다. 웹 요청 스레드는 `Future`의 결과를 기다리고, 워커에서 발생한 예외는 원래 예외 타입으로 다시 전달한다.
7. JVM 락 또는 named lock은 예약 처리가 끝난 뒤 해제한다.

전략은 한 번에 하나만 활성화한다. 애플리케이션을 재시작할 때 `LOCK_STRATEGY`만 변경하고, 같은 좌석 fixture와 같은 k6 입력을 사용해야 전략 간 결과를 비교할 수 있다.

```bash
LOCK_STRATEGY=pessimistic docker compose up -d --build app
LOCK_STRATEGY=optimistic docker compose up -d --build app
LOCK_STRATEGY=mysql-named docker compose up -d --build app
LOCK_STRATEGY=single-thread docker compose up -d --build app
```

`synchronized`, `reentrant`, `single-thread`는 애플리케이션 인스턴스가 하나일 때만 정합성을 보장한다. `single-thread`는 예약 요청 전체를 순서대로 처리하므로 큐가 가득 차지 않도록 `LOCK_SINGLE_THREAD_QUEUE_CAPACITY`를 함께 조정한다.

기본 대기열은 1,000건이다. 대기열이 가득 차면 새 요청은 executor에서 거부되며, 현재 공통 예외 처리에서는 5xx로 관측된다. 이는 중복 예약이 아니라 단일 워커의 처리 한계를 넘긴 상황이다.

## 1. G1~G4 동시 예약

이 테스트에서 VU는 가상 사용자 1명이며 VU당 예약 요청은 1회다. 따라서 아래 값은 축소값이 아니라 k6가 생성하는 실제 가상 사용자 수다.

| 등급 | 최소 VU | 최대 VU |
|---|---:|---:|
| G1 | 500 | 5,000 |
| G2 | 5,000 | 30,000 |
| G3 | 20,000 | 100,000 |
| G4 | 80,000 | 300,000 |

`TRAFFIC_PROFILE=minimum|maximum`으로 범위 양 끝을 선택하고, 중간값은 `CONCURRENCY`로 지정한다.

```bash
MYSQL_PASSWORD='<local-db-password>' \
scripts/load/seed_pessimistic_lock_fixture.sh

GRADE=1 \
TRAFFIC_PROFILE=minimum \
PT_ID='<performance-time-id>' \
SEAT_ID='<fresh-seat-id>' \
JWT_SECRET='<same-test-secret-as-server>' \
scripts/load/run_pessimistic_lock_k6.sh
```

G2 최대, G3/G4 및 `CONCURRENCY=5001` 이상은 실행 의도를 명시해야 한다.

```bash
GRADE=3 \
TRAFFIC_PROFILE=minimum \
ALLOW_LARGE_LOAD=true \
PT_ID='<performance-time-id>' \
SEAT_ID='<fresh-seat-id>' \
JWT_SECRET='<same-test-secret-as-server>' \
scripts/load/run_pessimistic_lock_k6.sh
```

각 등급과 반복마다 새 `AVAILABLE` 좌석을 사용한다. baseline의 통과 조건은 성공 1건, 나머지 `409 SEAT_ALREADY_RESERVED`, 5xx/timeout/인증 실패 0건이다. 종료 후 `Seat`, `Reservation`, `ReservedSeat` 상태도 확인한다.

현재 스크립트는 같은 테스트 JWT로 좌석 lock 정합성을 확인한다. 사용자별 인증, 세션, 재시도, 대기열 행동은 포함하지 않는다.

### G3/G4 부하 발생기 분리

G3/G4의 2만~30만 VU는 단일 노트북 k6 프로세스가 아닌 여러 부하 발생기로 나눈다. 대상 애플리케이션은 계속 단일 서버다.

모든 발생기에 같은 `START_AT_EPOCH_MS`를 전달하고, 충분히 미래인 Unix epoch milliseconds 값을 사용한다. 각 발생기는 서로 다른 execution segment를 사용한다.

```bash
GRADE=4 \
TRAFFIC_PROFILE=minimum \
ALLOW_LARGE_LOAD=true \
DISTRIBUTED=true \
START_AT_EPOCH_MS='<shared-future-epoch-ms>' \
K6_EXECUTION_SEGMENT='0:1/4' \
K6_EXECUTION_SEGMENT_SEQUENCE='0,1/4,1/2,3/4,1' \
PT_ID='<performance-time-id>' \
SEAT_ID='<fresh-seat-id>' \
JWT_SECRET='<same-test-secret-as-server>' \
scripts/load/run_pessimistic_lock_k6.sh
```

다른 발생기는 `1/4:1/2`, `1/2:3/4`, `3/4:1` segment로 실행한다. 분산 실행에서는 각 발생기 내부에서 성공이 0~1건인지 확인하고, 전체 성공 1건과 최종 DB 정합성은 모든 실행이 끝난 뒤 합산 확인한다. `request_start_lag`가 크게 벌어진 실행은 동시성 결과로 비교하지 않는다.

## 2. 공연 100개 direct/cache 비교

fixture는 공연, 회차, 가격 100세트를 만들고 기존 load fixture만 정리한다.

```bash
MYSQL_PASSWORD='<local-db-password>' \
scripts/load/seed_performance_cache_fixture.sh
```

출력된 `PERFORMANCE_IDS`를 사용하면 100개 공연마다 정확히 50회씩 요청한다. 기본값은 5,000 VU가 각각 한 번씩 요청하는 burst다.

```bash
ACTION=comparison \
PERFORMANCE_IDS='<comma-separated-100-ids>' \
USERS=5000 \
CONCURRENCY=5000 \
scripts/load/run_performance_cache_k6.sh
```

`comparison`은 direct 5,000 VU burst를 먼저 실행하고, 대상 key 100개만 삭제 및 warm-up한 뒤 cache 5,000 VU burst를 실행한다. 두 구간의 warming 요청은 결과에서 제외된다.

기본 설정의 SQL 및 요청 로그는 양쪽 결과를 왜곡하므로, 앱을 시작하는 프로세스 또는 컨테이너에 같은 값을 전달한다.

```bash
SPRING_JPA_PROPERTIES_HIBERNATE_SHOW_SQL=false
LOGGING_LEVEL_ROOT=WARN
```

## 3. 캐시 스탬피드

cold와 warm은 같은 공연 ID 및 같은 VU 수로 분리한다. Redis 전체를 비우지 않고 `performance:details:{id}`만 삭제한다.

```bash
ACTION=stampede-cold \
PERFORMANCE_ID='<fixture-performance-id>' \
STAMPEDE_CONCURRENCY=5000 \
scripts/load/run_performance_cache_k6.sh

ACTION=stampede-warm \
PERFORMANCE_ID='<same-performance-id>' \
STAMPEDE_CONCURRENCY=5000 \
scripts/load/run_performance_cache_k6.sh
```

5,000보다 큰 hot-key burst는 `ALLOW_LARGE_LOAD=true`를 명시한다. cold 구간의 cache miss와 cache write 증가량이 1보다 크면 여러 요청이 동시에 DB load와 cache write를 수행한 것이다.

## 4. 수집 및 중단 기준

각 실행은 k6 summary와 실행 전후 Actuator 원본을 `build/k6-results`에 남긴다. 확인할 값은 다음과 같다.

- HTTP 요청 수, 2xx/409/5xx/timeout, avg/p95/p99/max
- 예약 성공 수, 최종 Seat/Reservation/ReservedSeat 정합성
- cache hit/miss/error/write 증가량
- Hikari active/pending/timeout, MySQL lock wait, JVM과 컨테이너 CPU/memory
- k6 CPU/memory와 `request_start_lag`

성공 예약이 2건 이상이거나, 5xx/timeout/인증 실패, Hikari timeout, 앱/DB/Redis health 실패, container restart/OOM, 부하 발생기 포화가 발생하면 상위 등급으로 진행하지 않는다.

Prometheus 기본 15초 scrape는 짧은 burst peak를 놓칠 수 있다. G3/G4에서는 1초 이하 간격의 Hikari, MySQL, JVM 관측을 별도로 수집한다.

단일 애플리케이션 서버 결과는 운영 최대 수용량이나 실제 공연 전체 트래픽 보장이 아니다. 이 테스트는 동일 좌석에 집중된 예약 경합과 1회 상세 조회를 측정한다. load balancing 비교 시에는 동일 fixture와 입력을 유지하고 `BASE_URL`을 분산 진입점으로 변경한다.
