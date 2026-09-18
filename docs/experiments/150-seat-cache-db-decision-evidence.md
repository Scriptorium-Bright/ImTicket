# Seat Cache DB Decision Evidence

Branch: `experiment/waiting-room-cache-validation`

이 문서는 Seat Cache를 "DB가 느려서 Redis를 넣었다"로 설명하지 않기 위해,
Redis 선택 이전의 DB query/index 검토와 이후 Cache 효과를 하나의 의사결정 흐름으로 묶는다.

## 핵심 질문

> 좌석 조회 병목은 SQL 한 번이 비효율적이어서 생긴 것인가,
> 아니면 동일 회차 전체 좌석 projection을 동시 사용자가 반복해서 생성하는 구조 때문에 생긴 것인가?

이 질문을 아래 두 실험으로 분리한다.

1. 단일 query의 실행계획과 index 적합성
2. 동시 요청에서 동일 projection 반복 생성 비용과 Cache 효과

---

## 1. 실제 Seat read-model query shape

현재 split cache rebuild에서 사용하는 projection은 두 종류다.

### Layout

```sql
SELECT
    id,
    seat_floor,
    seat_section,
    seat_row,
    seat_number,
    seat_type,
    seat_price,
    is_reservation
FROM Seat
WHERE performance_time_id = ?
ORDER BY id;
```

### Availability

```sql
SELECT
    id,
    seat_status,
    version
FROM Seat
WHERE performance_time_id = ?
ORDER BY id;
```

현재 entity index:

```text
idx_seat_perf_status
(performance_time_id, seat_status)
```

두 query 모두 `seat_status`로 filtering하지 않는다.

또 `seat_status`는 예약 lifecycle에서:

```text
AVAILABLE -> LOCKED -> RESERVED
LOCKED -> AVAILABLE
```

처럼 변경 빈도가 높은 컬럼이다.

따라서 질문은 다음과 같다.

> 조회 pattern과 직접 맞지 않는 변경 빈도 높은 컬럼을 secondary index에 유지하는 것이 실제로 이득인가?

---

## 2. Index EXPLAIN 실험

실행 결과: [151-seat-index-explain-result.md](151-seat-index-explain-result.md)

실험 파일:

```text
scripts/test/seat_index_explain_experiment.sql
scripts/test/run_seat_index_explain_experiment.sh
scripts/test/summarize_seat_index_explain.py
```

기본 dataset:

```text
1 performance time
60,000 seats
MySQL 8.0 / InnoDB
```

비교:

```text
Current
(performance_time_id, seat_status)

Candidate
(performance_time_id)
```

후보를 `(performance_time_id, id)`부터 만들지 않은 이유는 InnoDB secondary index가 PK를 내부적으로 포함하므로,
먼저 가장 작은 index shape인 `performance_time_id` 단독을 검증하기 위해서다.

수집:

- optimizer가 선택한 key
- rows examined per scan
- filesort 여부
- EXPLAIN ANALYZE actual time
- 원본 JSON/TREE plan

실행:

```bash
MYSQL_HOST=127.0.0.1 \
MYSQL_PORT=3306 \
MYSQL_USER=root \
MYSQL_PASSWORD=rootpass \
MYSQL_DATABASE=imticket_index_test \
SEAT_COUNT=60000 \
bash scripts/test/run_seat_index_explain_experiment.sh
```

GitHub Actions에서도 같은 실험을 수행하고 raw plan을 artifact로 남긴다.

### 해석 규칙

#### A. 현재 index가 충분히 적합

```text
Current plan
- 정상 index access
- 치명적 full scan 없음
- sort/read cost 허용 범위
```

결론:

> 단일 Seat query 자체가 병목의 전부는 아니다.

#### B. 후보 index가 더 적합

예:

```text
Current  -> filesort / 더 많은 read cost
Candidate -> query order에 더 적합
```

결론:

> query-aligned index로 단일 조회 비용은 줄일 수 있지만,
> 동시 사용자마다 동일 전체 좌석 projection을 반복 생성하는 구조는 남는다.

#### C. index만으로 충분

이 경우 Cache 선택 근거를 다시 평가한다.

실험 결과를 Cache 정당화에 맞추지 않는다.

---

## 3. Cache OFF / ON 증거

새로운 부하 framework를 만들지 않고 기존 검증 자산을 재사용한다.

주 실행기:

```text
scripts/test/run_148_1_seat_availability_read_model.sh
```

기존 controlled cold 실험에서는 다음 결과가 있었다.

```text
300 concurrent users
full-seat DB query: 301 -> 1
```

또 기존 mixed workload에서는:

```text
DB projection: 1,701 -> 229
seat read p95: 7.261s -> 0.996s
```

이 값은 과거 실험 결과이므로 최신 branch에서 다시 실행할 경우 새 결과를 우선한다.

### 최신 재현

```bash
ACTION=run-all \
CONCURRENCY=2000 \
RUNS=3 \
RECONFIGURE_SERVICES=true \
BUILD_IMAGES=true \
bash scripts/test/run_148_1_seat_availability_read_model.sh
```

300-user controlled cold를 포트폴리오 대표 기준으로 다시 만들 경우
동일 회차, 동일 seat count, 동일 Hikari/Tomcat 설정을 고정해야 한다.

비교 지표:

- DB projection count
- seat-map p95 / p99
- Hikari active / pending
- Tomcat busy
- success count
- single-flight owner / joined

---

## 4. Logical projected rows

query count를 더 직관적으로 표현할 때 다음 계산을 보조 지표로 사용할 수 있다.

예:

```text
seat count = 30,000
DB full projection = 301

30,000 * 301
= 9,030,000 logical projected rows
```

Cache + single-flight:

```text
30,000 * 1
= 30,000 logical projected rows
```

주의:

이 값은 실제 network bytes나 MySQL engine의 physical rows-read 측정값이 아니다.

정확한 표현:

> 동일 seat count와 full projection 호출 횟수를 곱한 논리적 projection row 수

로 한정한다.

---

## 5. 최종 의사결정 서사

실험이 현재 가설을 지지한다면 포트폴리오에는 다음 흐름으로 압축한다.

```text
Seat query shape 검토
        |
EXPLAIN ANALYZE / Index 비교
        |
단일 query 자체의 비용과 index 한계 확인
        |
동시 조회에서 동일 회차 전체 좌석 반복 projection
        |
Cache-Aside
        |
Concurrent cold miss
        |
Same-JVM Single-flight
        |
Rebuild / state update race
        |
Generation Guard + Seat Version
        |
Static Layout / Dynamic Availability split
```

핵심 문장:

> DB 실행계획과 index를 먼저 검토한 뒤, 단일 SQL보다 동일 회차 전체 좌석을 동시 요청마다 반복 생성하는 구조가 더 큰 비용이라고 판단해 Redis read model을 선택했다.

---

## 6. 현재 Cache 보장 범위

이미 테스트로 확인된 범위:

- 같은 Reader/JVM에서 concurrent cold miss 20건 -> DB rebuild 1회
- 서로 다른 Reader instance에서는 각각 owner가 될 수 있어 DB rebuild 2회 가능
- generation guard가 stale full rebuild overwrite를 거절
- seat version이 늦은 partial state update를 거절
- AFTER_COMMIT Redis 반영 실패 시 stale read 가능
- 실제 좌석 선점 correctness는 MySQL에서 다시 판단

현재 의도적으로 추가하지 않는 것:

- distributed single-flight
- Cache용 distributed lock
- Redis HA/Sentinel/Cluster
- Cache update용 outbox

이유:

현재 측정 근거 없이 분산 coordination을 추가하면 복잡도와 새로운 failure mode만 증가한다.

---

## 7. 결과 기록

### Index experiment

| Query | Current index | Candidate index | 판단 |
|---|---|---|---|
| layout | filesort 발생, root end 72.3ms | filesort 없음, root end 51.0ms | 후보 인덱스가 query ordering에 더 적합 |
| availability | filesort 발생, root end 53.1ms | filesort 없음, root end 37.8ms | 후보 인덱스가 query ordering에 더 적합 |

### Cache effect

| Metric | Cache OFF | Cache ON | 판단 |
|---|---:|---:|---|
| DB projection | TBD | TBD | |
| logical projected rows | TBD | TBD | |
| p95 | TBD | TBD | |
| Hikari pending max | TBD | TBD | |
| success | TBD | TBD | |

---

## 8. Done 조건

다음을 만족하면 Seat Cache 사례는 종료한다.

- [x] 60,000-seat query의 current/candidate EXPLAIN을 보관
- [x] index 변경 여부를 결과 기준으로 결정
- [ ] Cache OFF/ON 대표 결과 하나를 최신 코드 기준으로 선택
- [ ] Redis 선택 이유를 "느린 DB"가 아니라 "반복 projection 제거"로 설명
- [x] same-JVM single-flight 보장 범위 테스트
- [x] multi-reader 한계 테스트
- [x] generation/version consistency guard
- [x] AFTER_COMMIT stale failure 재현

이 조건 이후에는 새로운 Cache architecture를 추가하지 않는다.
