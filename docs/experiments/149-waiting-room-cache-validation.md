# Waiting Room / Seat Cache Validation Experiment

Branch: `experiment/waiting-room-cache-validation`

Base: `develop`

목적은 새로운 기능을 추가하는 것이 아니라, 포트폴리오에서 주장하려는 네 가지 사실을 코드와 반복 가능한 실험으로 닫는 것이다.

1. Waiting Room promotion의 어느 단계가 느린지 분해 계측한다.
2. 후보별 Redis metadata 조회의 N round trip을 pipeline으로 줄였을 때 실제 효과를 A/B 측정한다.
3. 서로 다른 application instance가 동시에 promote해도 shared Redis quota/FIFO 불변식이 유지되는지 검증한다.
4. Seat Cache single-flight가 JVM-local이라는 현재 한계를 deterministic test로 고정한다.

---

## 1. Waiting Room promotion phase 계측

`RedisWaitingRoomStore.promote()`의 다음 구간을 Micrometer Timer로 기록한다.

- `expiry_total`
- `expiry_waiting_lookup`
- `expiry_active_lookup`
- `expiry_transition`
- `candidate_lookup`
- `candidate_metadata`
- `batch_transition`

Metric:

```text
imticket.waiting-room.promotion.phase.duration
  phase=<phase>
  metadata_mode=legacy|pipeline
```

후보 수는 다음 DistributionSummary로 기록한다.

```text
imticket.waiting-room.promotion.candidate.count
  metadata_mode=legacy|pipeline
```

Prometheus에서는 점이 underscore로 변환된다.

```text
imticket_waiting_room_promotion_phase_duration_seconds_count
imticket_waiting_room_promotion_phase_duration_seconds_sum
imticket_waiting_room_promotion_phase_duration_seconds_max
```

### 현재 가설

기존 경로는 후보 N명에 대해 `find()`를 반복하고, 각 `find()`가 ticket Hash 전체를 `HGETALL`한다.

```text
ZRANGE candidates
  -> HGETALL ticket 1
  -> HGETALL ticket 2
  -> ...
  -> HGETALL ticket N
  -> batch Lua
```

따라서 `candidate_metadata`가 promotion cycle에서 의미 있는 비중을 차지할 가능성이 있다.

이 가설은 **계측 결과가 나오기 전까지 사실로 확정하지 않는다.**

---

## 2. Legacy vs Pipeline A/B

실험 branch에서는 기존 경로를 기본값으로 유지한다.

```properties
reservation.waiting-room.promotion-metadata-pipeline-enabled=false
```

환경변수:

```bash
RESERVATION_WAITING_ROOM_PROMOTION_METADATA_PIPELINE_ENABLED=false
RESERVATION_WAITING_ROOM_PROMOTION_METADATA_PIPELINE_ENABLED=true
```

### Legacy

후보마다 기존 `find()`를 호출한다.

```text
N candidates
-> N × HGETALL
```

### Pipeline

후보마다 필요한 것은 owner key를 만들기 위한 `memberId`뿐이므로, ticket Hash 전체를 읽지 않고 `HGET memberId`를 pipeline에 enqueue한다.

```text
N candidates
-> N × HGET memberId
-> one pipeline flush
```

promotion의 최종 상태 전이는 기존 `waiting_room_promote_batch.lua`를 그대로 사용한다.

즉 변경 범위는 **candidate metadata network round trip**이고, Redis atomic admission boundary는 바꾸지 않는다.

---

## 3. A/B 부하 실행

기존 종료 시험과 동일한 조건을 재사용한다.

기본 조건:

- 2,000 concurrent users
- 3 runs
- max active sessions = 30
- admit per interval = 25
- 기존 full-flow Waiting Room test

실행:

```bash
bash scripts/test/run_waiting_room_promotion_metadata_ab.sh
```

이 실행기는 순서대로:

1. legacy mode로 2,000 × 3회
2. pipeline mode로 2,000 × 3회
3. 각 mode의 최종 Prometheus snapshot 저장
4. 3회 closure matrix와 phase timer를 자동 집계

한다.

### 생성 결과

```text
build/k6-results/149-promotion-metadata-ab/
  <timestamp>-legacy/
    closure-matrix.tsv
    waiting-room-prometheus-final.txt
    ...
  <timestamp>-pipeline/
    closure-matrix.tsv
    waiting-room-prometheus-final.txt
    ...
  <timestamp>-summary/
    RESULTS.md
    promotion-metadata-ab.tsv
```

`RESULTS.md`에는 다음 값을 자동으로 비교한다.

- waiting admission rate
- scheduler duration max
- queue wait p95
- join p95
- Tomcat busy max
- Hikari pending max
- 각 promotion phase average/max duration

### 이전 실험값의 취급

과거 문서에서 실제 promotion이 설정 25/s 대비 약 12~17/s 수준으로 관찰된 적이 있다.

이 값은 **이번 branch의 baseline 숫자가 아니다.**
이번 A/B의 legacy 3회 결과를 새로운 baseline으로 사용한다.

---

## 4. Merge 판단 규칙

### pipeline 최적화를 유지할 근거

다음을 함께 본다.

1. `candidate_metadata` avg/max가 legacy 대비 감소한다.
2. admission quota/FIFO integration test가 그대로 통과한다.
3. end-to-end에서 regression이 없다.
4. admission rate 또는 scheduler duration/queue wait에서 실제 효과가 있는지 확인한다.

phase micro metric만 줄고 end-to-end 차이가 없다면:

> metadata round trip은 줄었지만 전체 Waiting Room bottleneck은 다른 구간에 있다.

라고 기록한다.

이 경우 pipeline 코드를 유지할지는 복잡도 대비 효과로 다시 판단한다.

### 버릴 근거

- candidate_metadata가 실제 병목이 아니었음
- pipeline이 end-to-end regression을 만듦
- correctness test 실패
- 개선폭이 구현 복잡도를 정당화하지 못함

실험 branch이므로 이 경우 merge하지 않는다.

---

## 5. Shared Redis concurrent promotion test

기존 integration test에는 두 Store가 같은 Redis quota를 **순차적으로** 공유하는 시험이 있었다.

이번 branch는 두 Store를 실제 thread에서 동시에 시작한다.

```text
Store A ----+
            +--> promote(same performance, same window)
Store B ----+
                    |
                    v
               Shared Redis
                    |
          waiting_room_promote_batch.lua
```

검증:

- 총 admitted = interval quota
- admitted ticket 중복 = 0
- first FIFO candidates만 admitted
- active ZSET cardinality = quota
- 나머지 ticket은 waiting 유지

실행:

```bash
docker compose up -d redis

WAITING_ROOM_REDIS_TEST_ENABLED=true \
WAITING_ROOM_REDIS_HOST=127.0.0.1 \
WAITING_ROOM_REDIS_PORT=16380 \
./gradlew test \
  --tests org.example.ticket.reservation.waitingroom.repository.redis.RedisWaitingRoomStoreIntegrationTest
```

이 테스트의 목적은 "Redis를 썼으니 분산 환경일 것"이라는 추정을 없애는 것이다.

정확한 주장:

> 서로 다른 Store instance가 같은 Redis에서 동시에 promotion을 실행해도 Lua가 admission quota와 FIFO transition을 원자적으로 조정한다.

실제 두 JVM의 network/deployment 동작을 검증하는 테스트는 아니다.

---

## 6. Seat Cache multi-instance boundary test

현재 single-flight 상태:

```java
ConcurrentMap<Long, CompletableFuture<List<SeatResponse>>> inFlightLoads
```

이 Map은 `SeatMapCacheReader` instance에 속한다.

따라서 같은 JVM Reader 하나에서는:

```text
20 concurrent cold misses
-> DB rebuild 1
```

이지만 서로 다른 application instance를 모사한 Reader 두 개에서는:

```text
Reader A -> owner -> DB rebuild
Reader B -> owner -> DB rebuild
```

가 가능하다.

이번 branch는 이 한계를 실패가 아니라 **현재 보장 범위**로 테스트한다.

실행:

```bash
./gradlew test \
  --tests org.example.ticket.reservation.booking.cache.SeatMapCacheReaderTest
```

추가된 테스트는 두 Reader가 동시에 DB 구간까지 들어간 것을 latch로 확인하고:

```text
databaseReader.readSplit() == 2 calls
Reader A singleflight_owner == 1
Reader B singleflight_owner == 1
```

을 검증한다.

### 이 결과만으로 distributed lock을 추가하지 않는다

다음 질문은 별도다.

> instance 수만큼 cold rebuild가 발생하는 비용이 실제 DB capacity에서 문제가 되는가?

현재 deterministic test는 architecture boundary만 증명한다.

실제 2-app 부하에서 Hikari pending, DB projection count, p95가 문제가 될 때만 distributed coordination을 검토한다.

---

## 7. 테스트 순서

### A. 빠른 코드 검증

```bash
./gradlew test \
  --tests org.example.ticket.reservation.booking.cache.SeatMapCacheReaderTest
```

### B. Redis 동시성 검증

```bash
docker compose up -d redis

WAITING_ROOM_REDIS_TEST_ENABLED=true \
WAITING_ROOM_REDIS_HOST=127.0.0.1 \
WAITING_ROOM_REDIS_PORT=16380 \
./gradlew test \
  --tests org.example.ticket.reservation.waitingroom.repository.redis.RedisWaitingRoomStoreIntegrationTest
```

### C. Waiting Room A/B

필요 도구:

- Docker
- jq
- k6
- curl
- awk
- redis-cli
- python3

```bash
bash scripts/test/run_waiting_room_promotion_metadata_ab.sh
```

---

## 8. 포트폴리오에 사용할 수 있는 문장 — 결과 확정 전

결과가 나오기 전에는 아래까지만 주장한다.

> Waiting Room의 설정 admission rate와 실제 처리율 차이를 확인한 뒤 promotion 경로를 expiry scan, candidate 조회, candidate metadata 조회, batch transition으로 분해 계측했다. 또한 서로 다른 application instance를 모사한 동시 promotion test로 shared Redis admission quota의 원자성을 검증했다.

pipeline 성과 숫자는 A/B 실행 전에는 쓰지 않는다.

---

## 9. 결과 기록표

A/B 실행 후 자동 생성된 `RESULTS.md` 값을 이 표에 옮긴다.

| Metric | Legacy | Pipeline | 판단 |
|---|---:|---:|---|
| candidate metadata avg | TBD | TBD | |
| candidate metadata max | TBD | TBD | |
| batch transition avg | TBD | TBD | |
| scheduler max | TBD | TBD | |
| admission rate median | TBD | TBD | |
| queue wait p95 median | TBD | TBD | |
| Tomcat busy max median | TBD | TBD | |
| Hikari pending max median | TBD | TBD | |

---

## 10. 이 branch에서 하지 않는 것

- Redis Sentinel / Cluster 구축
- 실제 2 JVM Waiting Room deployment 실험
- distributed Seat Cache single-flight 구현
- Cache architecture 재설계
- Frontend/SSE E2E 추가

이 branch의 목적은 **현재 backend 설계의 병목과 보장 범위를 측정 가능한 형태로 닫는 것**이다.
