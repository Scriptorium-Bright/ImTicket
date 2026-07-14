# k6 부하 테스트 기준

## 현재 판단

| 스크립트 | 상태 | 이유 |
|---|---|---|
| `01-ticket-open-run.js` | 현재 기준 | 같은 한 좌석으로 단발 burst를 만들고 결과를 성공/충돌/내부 오류로 분리한다. |
| `02-seat-check-spike.js` | 실행 금지 | 현재 존재하지 않는 `/empty-seat` 경로와 8080 포트를 사용한다. 좌석 캐시는 현재 범위에서도 제외됐다. |
| `04-sms-auth-spike.js` | 별도 검토 | 비관적 락 기준선과 무관하다. |
| `05-cache-stampede.js` | 수정 필요 | 현재 존재하지 않는 공연 상세 경로와 8080 포트를 사용한다. |
| `06-performance-cache-load.js` | 현재 기준 | 공연 100개 direct/cache 비교와 단일 key cold/warm burst를 분리한다. |
| 루트 `load-test-reservation.js` | 실행 금지 | 무작위 좌석으로 hot-row 경합이 희석된다. |

## 등급 1~4 해석

예약 테스트는 VU당 같은 좌석에 요청을 한 번 보낸다. 따라서 아래 VU는 각 등급의 실제 가상 사용자 범위다.

| 등급 | minimum profile | maximum profile |
|---|---:|---:|
| 1 | 500 VU | 5,000 VU |
| 2 | 5,000 VU | 30,000 VU |
| 3 | 20,000 VU | 100,000 VU |
| 4 | 80,000 VU | 300,000 VU |

`TRAFFIC_PROFILE=minimum|maximum`으로 범위를 선택하고 중간값은 `CONCURRENCY`로 지정한다. G3/G4는 부하 발생기를 분리하고 공통 `START_AT_EPOCH_MS`와 execution segment를 사용한다. 대상 애플리케이션은 단일 서버로 유지할 수 있다.

## Pessimistic Lock 기준선

예약 락 비교는 앱을 재시작할 때 `LOCK_STRATEGY`를 하나씩 바꾸고 동일한 fixture와 k6 입력을 재사용한다. 가능한 값은 `pessimistic`, `synchronized`, `reentrant`, `optimistic`, `mysql-named`, `single-thread`다. `synchronized`, `reentrant`, `single-thread`는 단일 JVM에서만 유효하다.

fixture 생성 결과에서 `performance_time_id`와 등급별 `seat_id`를 확인한다.

```bash
MYSQL_PASSWORD='로컬 테스트 DB 비밀번호' \
scripts/load/seed_pessimistic_lock_fixture.sh
```

기준선 실행:

```bash
k6 run \
  -e GRADE=1 \
  -e TRAFFIC_PROFILE=minimum \
  -e PT_ID=... \
  -e SEAT_ID=... \
  -e JWT_SECRET='서버와 같은 테스트용 secret' \
  --summary-export build/k6-results/pessimistic-grade-1.json \
  k6-scripts/01-ticket-open-run.js
```

강제 timeout 실행은 별도 터미널에서 먼저 row lock을 보유한 뒤 시작한다.

```bash
MYSQL_PASSWORD='로컬 테스트 DB 비밀번호' \
SEAT_ID=... \
HOLD_SECONDS=6 \
scripts/load/hold_pessimistic_seat_lock.sh
```

```bash
MYSQL_PASSWORD='로컬 테스트 DB 비밀번호' \
MODE=forced-timeout \
GRADE=1 \
PT_ID=... \
SEAT_ID=... \
BASE_URL=http://127.0.0.1:10080 \
JWT_SECRET='서버와 같은 테스트용 secret' \
scripts/load/run_pessimistic_lock_k6.sh
```

## 결과 해석

- baseline에서는 성공이 정확히 한 건이어야 한다.
- 나머지는 오류 코드가 `SEAT_ALREADY_RESERVED`인 409 응답이어야 정상적인 business conflict다.
- forced-timeout의 500/503은 k6 응답만으로 lock timeout이라고 단정하지 않는다.
- forced-timeout에서 내부 오류가 반드시 나야 한다는 threshold는 두지 않는다. 실제 timeout 정책이 적용되는지 자체가 측정 대상이다.
- 같은 시각의 서버 예외 로그, MySQL `data_lock_waits`, Hikari pending을 함께 확인한다.
- 같은 한 row의 경합은 lock wait/timeout 시나리오다. deadlock은 좌석 여러 개를 반대 순서로 잠그는 MySQL 통합 테스트에서 별도로 검증한다.

## 공연 상세 캐시

공연 100개 fixture를 만든 뒤 출력된 `PERFORMANCE_IDS`를 사용한다.

```bash
MYSQL_PASSWORD='로컬 테스트 DB 비밀번호' \
scripts/load/seed_performance_cache_fixture.sh
```

기본 비교는 5,000 VU가 각각 한 번씩 조회하며, 100개 공연에 50회씩 균등하게 요청한다.

```bash
ACTION=comparison \
PERFORMANCE_IDS='fixture가 출력한 ID 목록' \
scripts/load/run_performance_cache_k6.sh
```

단일 key의 cold-cache burst와 warm-cache 대조군은 분리해 실행한다. 캐시 전체를 비우지 않고 대상 key만 삭제한다.

```bash
ACTION=stampede-cold \
PERFORMANCE_ID='fixture 공연 ID 하나' \
STAMPEDE_CONCURRENCY=5000 \
scripts/load/run_performance_cache_k6.sh

ACTION=stampede-warm \
PERFORMANCE_ID='같은 공연 ID' \
STAMPEDE_CONCURRENCY=5000 \
scripts/load/run_performance_cache_k6.sh
```

5,000 VU burst가 기본값이다. VU당 1회 요청이며 실제 시작 분산은 `request_start_lag`로 확인한다. 결과는 `build/k6-results/performance-cache`에 k6 summary와 실행 전후 Actuator 원본 지표로 저장된다.
