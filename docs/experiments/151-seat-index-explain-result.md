# Seat Index EXPLAIN Result — 60,000 Seats

작성일: 2026-09-19

Branch: `experiment/waiting-room-cache-validation`

GitHub Actions run: `35361449327`

Artifact: `seat-index-explain`

## 1. 목적

Seat Cache의 Redis 도입 근거를 설명하기 전에, 현재 MySQL Seat read-model query가 index 설계만으로 충분히 개선될 수 있는지 확인했다.

비교 대상:

```text
Current
idx_seat_perf_status
(performance_time_id, seat_status)

Candidate
idx_seat_perf
(performance_time_id)
```

대상 query:

```text
WHERE performance_time_id = ?
ORDER BY id
```

dataset:

- MySQL 8.0 / InnoDB
- 1개 performance time
- 60,000 seats
- current/candidate mirror table에 동일 데이터 적재

이 실험은 실제 운영 DB 절대 성능 benchmark가 아니라 **query shape와 index ordering 적합성 확인**이 목적이다.

## 2. 실행계획 결과

### Layout projection

Current:

```text
Index lookup:
idx_seat_perf_status
performance_time_id 조건 사용

그 뒤:
Sort: id

actual:
index lookup 0.480..53.6 ms
sort 포함 root 65.6..69.8 ms
rows 60,000
```

Candidate:

```text
Index lookup:
idx_seat_perf

별도 Sort 없음

actual:
0.412..51.5 ms
rows 60,000
```

### Availability projection

Current:

```text
Index lookup:
idx_seat_perf_status

그 뒤:
Sort: id

actual:
index lookup 0.314..39.5 ms
sort 포함 root 49.8..53.1 ms
rows 60,000
```

Candidate:

```text
Index lookup:
idx_seat_perf

별도 Sort 없음

actual:
0.278..38.0 ms
rows 60,000
```

## 3. 비교

| Query | Current `(performance_time_id, seat_status)` | Candidate `(performance_time_id)` | 관측 |
|---|---:|---:|---|
| Layout root end | 69.8 ms | 51.5 ms | 약 26.2% 감소 |
| Availability root end | 53.1 ms | 38.0 ms | 약 28.4% 감소 |
| Layout filesort | 발생 | 없음 | Candidate 우세 |
| Availability filesort | 발생 | 없음 | Candidate 우세 |
| 실제 반환 rows | 60,000 | 60,000 | 동일 |

절대 시간은 GitHub Actions runner 1회 측정이므로 포트폴리오에서 확정 성능 수치로 사용하지 않는다.

더 중요한 결과는 **filesort 유무와 index ordering**이다.

## 4. 왜 filesort가 달라졌는가

Current index:

```text
(performance_time_id, seat_status)
```

한 performance time 안에서 index ordering은 개념적으로:

```text
performance_time_id
  -> seat_status
      -> PK(id)
```

순서다.

query는:

```sql
WHERE performance_time_id = ?
ORDER BY id
```

이므로 같은 performance time 안에서도 `seat_status` 그룹이 id 사이에 끼어 있어 전체 id 순서를 그대로 제공하지 못한다.

Candidate:

```text
(performance_time_id)
```

InnoDB secondary index leaf에는 PK가 포함되므로 같은 performance time 안에서:

```text
performance_time_id
  -> PK(id)
```

순서로 스캔할 수 있다.

따라서 별도 filesort가 사라졌다.

## 5. Repository usage 점검

현재 `SeatRepository`의 주요 hot path를 다시 확인했다.

- performance time + seatIds
- performance time 전체 layout projection
- performance time 전체 availability projection
- performance time + seatIds availability projection
- reservation에 연결된 seat IDs
- seat IDs row lock

현재 repository에는 다음 query가 없다.

```text
WHERE performance_time_id = ?
  AND seat_status = ?
```

즉 현재 코드 기준으로 `seat_status`를 composite index 두 번째 컬럼에 둘 직접적인 read-path 근거가 확인되지 않았다.

반대로 `seat_status`는 예약 lifecycle에서 자주 변경된다.

## 6. 실험 브랜치 결정

이 branch에서는 후보를 실제 코드와 migration candidate에 반영했다.

Entity:

```text
idx_seat_perf
(performance_time_id)
```

Migration candidate:

```text
scripts/db/migrations/
V20260919_01__replace_seat_perf_status_index.sql
```

migration은:

1. `idx_seat_perf`를 먼저 생성
2. 기존 `idx_seat_perf_status` 제거

순서로 작성했다.

실험 branch 밖으로 merge하기 전 실제 로컬 schema의 `SHOW INDEX FROM Seat`를 확인해야 한다.

## 7. 이것만으로 Cache가 불필요해지는가?

아니다.

이번 실험은 오히려 두 문제를 분리했다.

### 단일 query 최적화

```text
query-aligned index
-> filesort 제거
-> 단일 projection 비용 감소 가능
```

### 반복 query 구조

동시에 300명이 같은 회차 전체 좌석을 요청하면 index가 좋아져도:

```text
300 requests
-> 같은 전체 seat set을 최대 300번 projection
```

하는 구조는 그대로다.

기존 controlled cold experiment에서는:

```text
full-seat DB projection
301 -> 1
```

이 확인됐다.

따라서 최종 판단은:

> 먼저 query/index를 맞추되, index tuning으로 제거할 수 없는 반복 projection은 Redis read model과 same-JVM single-flight로 줄인다.

이다.

## 8. 포트폴리오용 표현

> 좌석 조회에 Redis를 바로 적용하지 않고 MySQL 실행계획부터 확인했습니다. 기존 `(회차, 좌석상태)` 인덱스는 회차 필터에는 사용됐지만 `ORDER BY id`에서 추가 정렬이 발생했고, 현재 조회 패턴에 맞춘 `(회차)` 인덱스에서는 정렬이 제거됐습니다. 단일 조회 경로를 먼저 정리한 뒤에도 동시 사용자가 동일 회차 전체 좌석을 반복 조회하는 구조는 남아, Cache-Aside와 single-flight로 전체 좌석 DB projection을 301회에서 1회로 줄였습니다.

주의:

- 69.8ms -> 51.5ms를 확정적인 26% 성능 개선으로 전면에 내세우지 않는다.
- CI 1회 실행이므로 실행계획 차이의 보조 관측값으로만 사용한다.
- 대표 정량 성과는 controlled Cache experiment의 `301 -> 1`을 사용한다.

## 9. 후속

- [x] 60,000-seat current/candidate EXPLAIN
- [x] filesort 차이 확인
- [x] experiment branch의 Entity index 변경
- [x] migration candidate 작성
- [x] 기존 same-JVM single-flight 검증
- [x] multi-reader single-flight 한계 검증
- [ ] merge 전 실제 로컬 Seat schema `SHOW INDEX` 확인
- [ ] 필요 시 최신 controlled Cache OFF/ON 한 회 재현

새로운 Cache mechanism은 추가하지 않는다.
