# 예매 부하·fixture·진단 실행 가이드

이 디렉터리는 예매 경로를 검증하는 k6 시나리오, 셸 실행기, SQL fixture와 재현·관측 스크립트를 한곳에 둔다. 실행 결과 로그와 summary는 소스와 섞지 않고 `build/k6-results/`에 저장한다.

인기 공연 좌석 캐시 활성·비활성 비교와 OCI 재현 명령은 [POPULAR_CACHE_TEST_COMMANDS.md](POPULAR_CACHE_TEST_COMMANDS.md)에 모아 두었다. 각 테스트의 가설·관측·설계 판단은 [인기 공연 캐시 활성·비활성 실험의 의미](../../docs/implements/seat-availability/146.6.3-popular-cache-toggle-test-meaning.md)에서 읽는다.

### 로컬 `.env` 자동 로드

주요 테스트 스크립트는 실행 시 저장소 루트의 `.env`를 자동으로 읽는다. 따라서 `MYSQL_PASSWORD`, `MYSQL_LOCK_TEST_PASSWORD`, `JWT_SECRET`을 매번 명령어 앞에 붙이지 않아도 된다. 이미 셸 환경이나 명령어에서 non-empty 값으로 지정한 값은 `.env`보다 우선한다. `.env`는 셸 코드로 실행하지 않고 `KEY=VALUE`만 읽으므로 JDBC URL의 `&`도 안전하게 보존한다.

현재 로컬 진단용 JWT는 `.env`의 `JWT_SECRET`과 앱의 `SPRING_JWT_SECRET`을 같은 임시값으로 맞춰 둔다. 운영·공유 환경에서는 이 값을 사용하지 않는다.

진단 collector의 MySQL lock metric은 `performance_schema.data_lock_waits` 권한이 필요하므로 앱 접속 계정(`capstone`)과 분리한다. 기본적으로 `.env`의 `MYSQL_ROOT_PASSWORD`를 `MYSQL_METRICS_USER=root`로 사용하며, 별도 계정이 있으면 `MYSQL_METRICS_USER`와 `MYSQL_METRICS_PASSWORD`로 덮어쓸 수 있다.

## 실행 순서와 명령어

### 지금 실행할 범위

현재는 1.2.1.1의 비관 락 문제를 눈으로 확인하는 단계다. 아래 순서만 실행한다.

1. **MySQL failure mode 검증**: row lock wait·`1205`·`1213`이 MySQL에서 실제로 발생하는지 확인한다. 임시 table만 사용하므로 약 1~2초면 끝나는 것이 정상이며, 부하 테스트가 아니다.
2. **비관 락 대기 전파 진단**: 외부 transaction이 실제 `Seat` row를 12초 보유하는 동안 50 VU를 보내 Hikari active/pending, MySQL `data_lock_waits`, k6 p95/p99을 수집한다. 이것이 지금 가장 먼저 볼 실행이다.
3. **비관 락 baseline**: 새 fixture에서 외부 row lock 없이 같은 VU를 보내, 정상적인 1건 성공·나머지 409과 latency를 대조한다.

4. **자연 경합 관찰**: 외부 락을 만들지 않고 같은 좌석을 1,000 VU가 요청하는 동안 Prometheus와 MySQL `data_lock_waits`를 함께 수집한다. 이 실행에서는 lock wait가 0일 수도 있으며, 이는 트랜잭션이 짧아 대기가 관측 샘플 사이에 끝났다는 뜻이다.

다른 다섯 전략 비교와 VU 증분은 1.4·1.4.1의 평가 기준을 정한 뒤에 실행한다. 지금은 실행하지 않는다.

### 1. 코드 수준 lock 전략 검증

애플리케이션을 기동하지 않고 annotation 적용, AOP 전략 해석, context 전달, `SeatService`의 DB query 선택을 검증한다.

```bash
./gradlew test \
  --tests org.example.ticket.reservation.lock.ReservationLockAnnotationTest \
  --tests org.example.ticket.reservation.lock.ReservationLockAspectTest \
  --tests org.example.ticket.reservation.lock.ReservationLockStrategyTest \
  --tests org.example.ticket.reservation.service.SeatServiceLockStrategyContextTest \
  --tests org.example.ticket.reservation.repository.SeatRepositoryLockPolicyTest
```

### 2. MySQL lock failure mode 자체 검증

이 테스트는 임시 InnoDB table을 만들었다가 삭제한다. 같은 row의 lock wait, session lock wait timeout(`1205`), 두 row를 반대 순서로 획득했을 때의 deadlock victim(`1213`)을 검증한다. 성공해도 wrapper가 `[LOCK-EVIDENCE]`로 실제 대기 시간과 error code를 콘솔에 출력한다.

```bash
MYSQL_LOCK_TEST_PASSWORD='로컬 테스트 DB 비밀번호' \
MYSQL_LOCK_TEST_URL='jdbc:mysql://127.0.0.1:10047/capstone?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul' \
scripts/test/run_mysql_pessimistic_lock_semantics_test.sh
```

### 3. 동일 VU lock 전략 비교 준비

`@ReservationLock(strategy = CONFIGURED)` 상태이므로 아래처럼 앱을 **전략 하나씩 재기동**한다. fixture는 실행마다 새로 만들고, 출력된 `performance_time_id`와 `grade_1_seat_id`를 각각 `PT_ID`, `SEAT_ID`에 넣는다. 한 번의 burst가 좌석을 `LOCKED`로 바꾸므로 같은 fixture를 재사용하지 않는다.

```bash
# 터미널 A: 첫 전략으로 앱 기동
LOCK_STRATEGY=pessimistic \
SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=30 \
./gradlew bootRun
```

```bash
# 터미널 B: 매 실행 전 hot-seat fixture 생성
MYSQL_HOST=127.0.0.1 \
MYSQL_PORT=10047 \
MYSQL_PASSWORD='로컬 테스트 DB 비밀번호' \
scripts/test/seed_pessimistic_lock_fixture.sh
```

```bash
# 터미널 B: 한 전략의 기준선 실행
GRADE=1 \
TRAFFIC_PROFILE=minimum \
CONCURRENCY=500 \
PT_ID='fixture의 performance_time_id' \
SEAT_ID='fixture의 grade_1_seat_id' \
BASE_URL=http://127.0.0.1:10080 \
JWT_SECRET='서버와 같은 테스트용 secret' \
scripts/test/run_pessimistic_lock_k6.sh
```

다음 전략은 앱을 종료한 뒤 `LOCK_STRATEGY=synchronized`, `reentrant`, `optimistic`, `mysql-named`, `single-thread` 중 하나로 바꿔 기동하고, 다시 fixture 생성부터 반복한다. 아직 이 결과의 우열이나 선택 기준은 정하지 않는다.

### 4. 비관 락 대기 → Hikari pool 전파 진단

이 실행은 반드시 `LOCK_STRATEGY=pessimistic`과 Hikari 30으로 기동한 앱에서 수행한다. MySQL이 대상 row를 12초 보유하는 동안 50 VU를 보내고, `data_lock_waits`, Hikari active/pending, Tomcat, k6 로그를 함께 남긴다. 시작 직후 출력되는 `diagnosis_run_dir`의 TSV는 다른 터미널에서 `tail -f`로 실시간 확인할 수 있다.

```bash
MYSQL_HOST=127.0.0.1 \
MYSQL_PORT=10047 \
MYSQL_PASSWORD='로컬 테스트 DB 비밀번호' \
PT_ID='fixture의 performance_time_id' \
SEAT_ID='fixture의 grade_1_seat_id' \
JWT_SECRET='서버와 같은 테스트용 secret' \
BASE_URL=http://127.0.0.1:10080 \
HIKARI_POOL_SIZE=30 \
CONCURRENCY=50 \
HOLD_SECONDS=12 \
scripts/test/run_pessimistic_lock_diagnosis.sh
```

## 현재 기준 k6 시나리오

`POST /api/reservation/pre-reserve`는 UUID 형식의 `Idempotency-Key`가 필수다. 현재 예매 스크립트는 **VU의 요청 의도마다 서로 다른 key**를 생성한다. 그래야 같은 좌석을 향한 서로 다른 사용자 의도가 idempotency claim 하나로 합쳐지지 않고 실제 seat lock 경합까지 도달한다. 한 요청을 네트워크 오류 때문에 재전송하는 로직을 추가할 때만 최초 key를 보존해 재사용한다.

| 스크립트 | 상태 | 이유 |
|---|---|---|
| `01-ticket-open-run.js` | 현재 기준 | 같은 한 좌석으로 단발 burst를 만들고 성공·충돌·내부 오류를 분리한다. |
| `02-seat-check-spike.js` | 실행 금지 | 현재 존재하지 않는 `/empty-seat` 경로와 8080 포트를 사용한다. |
| `04-sms-auth-spike.js` | 별도 검토 | 비관적 락 기준선과 무관하다. |
| 과거 load 시나리오 JS | 과거 실험 보관 | 현재 예매 경로의 합격 기준에는 포함하지 않는다. |

## Pessimistic Lock 기준선

예약 테스트는 앱을 재시작할 때 `LOCK_STRATEGY`를 하나씩 바꾸고 동일한 fixture와 k6 입력을 재사용한다. 가능한 값은 `pessimistic`, `synchronized`, `reentrant`, `optimistic`, `mysql-named`, `single-thread`다. `synchronized`, `reentrant`, `single-thread`는 단일 JVM에서만 유효하다.

fixture 생성 결과에서 `performance_time_id`와 등급별 `seat_id`를 확인한다.

```bash
MYSQL_PASSWORD='로컬 테스트 DB 비밀번호' \
scripts/test/seed_pessimistic_lock_fixture.sh
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
  scripts/test/01-ticket-open-run.js
```

강제 timeout 실행은 별도 터미널에서 먼저 row lock을 보유한 뒤 시작한다.

```bash
MYSQL_PASSWORD='로컬 테스트 DB 비밀번호' \
SEAT_ID=... \
HOLD_SECONDS=6 \
scripts/test/hold_pessimistic_seat_lock.sh
```

```bash
MYSQL_PASSWORD='로컬 테스트 DB 비밀번호' \
MODE=forced-timeout \
GRADE=1 \
PT_ID=... \
SEAT_ID=... \
BASE_URL=http://127.0.0.1:10080 \
JWT_SECRET='서버와 같은 테스트용 secret' \
scripts/test/run_pessimistic_lock_k6.sh
```

## 등급별 증분 부하

최대 VU를 한 번에 실행하지 않고, fixture를 매 단계 새로 준비한 뒤 아래 순서로 `N`을 늘린다.

| 등급 | `CONCURRENCY` 단계 |
|---|---|
| G1 | 500 → 1,000 → 2,000 → 5,000 |
| G2 | 5,000 → 10,000 → 20,000 → 30,000 |
| G3 | 20,000 → 40,000 → 60,000 → 100,000 |
| G4 | 80,000 → 120,000 → 200,000 → 300,000 |

`CONCURRENCY`를 생략하면 등급의 기본 minimum/maximum을 사용하므로, 증분 단계에서는 반드시 명시한다.

```bash
GRADE=1 \
TRAFFIC_PROFILE=minimum \
CONCURRENCY=1000 \
PT_ID=... \
SEAT_ID=... \
JWT_SECRET='서버와 같은 테스트용 secret' \
BASE_URL=http://127.0.0.1:10080 \
scripts/test/run_pessimistic_lock_k6.sh
```

각 단계는 같은 조건으로 3회 반복한다. 성공 1건, 예상 409 `N-1`건, unexpected 5xx/timeout 0건을 먼저 확인하고 p95/p99·Tomcat·Hikari·MySQL lock·CPU/RSS/GC를 기록한다. timeout, 중복 성공, partial hold 또는 Hikari pending 지속이 발생하면 해당 `N`을 포화 경계로 남기고 다음 단계로 증가하지 않는다. 5,000 VU 초과는 `ALLOW_LARGE_LOAD=true`가 필요하며 G3/G4는 로컬 부하 발생기 용량을 초과할 수 있으므로 실행 불가 자체를 결과로 기록한다.

## 결과 해석

- baseline에서는 성공이 정확히 한 건이어야 한다.
- 나머지는 오류 코드가 `SEAT_ALREADY_RESERVED`인 409 응답이어야 정상적인 business conflict다.
- forced-timeout의 500/503은 k6 응답만으로 lock timeout이라고 단정하지 않는다.
- 같은 시각의 서버 예외 로그, MySQL `data_lock_waits`, Hikari pending을 함께 확인한다.
- 같은 한 row의 경합은 lock wait/timeout 시나리오다. deadlock은 좌석 여러 개를 반대 순서로 잠그는 MySQL 통합 테스트에서 별도로 검증한다.

## 비관 락 대기 전파 진단

아래 실행기는 외부 MySQL transaction으로 대상 좌석 row를 일정 시간 보유한 뒤, 같은 좌석으로 `Hikari pool size`보다 많은 요청을 보낸다. k6 결과만 보지 않고 Tomcat·Hikari·MySQL `data_lock_waits`를 0.2초 간격으로 같은 실행 디렉터리에 저장한다. 실행 전 fixture를 새로 만들고 애플리케이션을 `LOCK_STRATEGY=pessimistic`으로 기동한다.

먼저 앱의 실제 Hikari max를 진단 입력과 같게 고정해 기동한다. 실행기는 `/actuator/prometheus`의 `hikaricp_connections_max`가 `HIKARI_POOL_SIZE`와 다르면 시작하지 않는다.

```bash
LOCK_STRATEGY=pessimistic \
SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=30 \
./gradlew bootRun
```

```bash
MYSQL_PASSWORD='로컬 테스트 DB 비밀번호' \
PT_ID=... \
SEAT_ID=... \
JWT_SECRET='서버와 같은 테스트용 secret' \
BASE_URL=http://127.0.0.1:10080 \
HIKARI_POOL_SIZE=30 \
CONCURRENCY=50 \
HOLD_SECONDS=12 \
scripts/test/run_pessimistic_lock_diagnosis.sh
```

성공 조건은 `data_lock_waits >= 1`, `Hikari active peak >= pool size`, `Hikari pending peak >= 1`이다. 완료되면 `lock-wait-evidence.txt`에 k6 요청 수·p95/p99·성공/충돌/내부 오류, Hikari·Tomcat·MySQL peak와 peak 시각의 raw sample, MySQL lock wait snapshot 앞부분을 한 번에 출력한다. 결과는 `build/k6-results/pessimistic-diagnosis-*/` 아래의 TSV, MySQL snapshot, k6 log로 남는다. 이 실행은 같은 좌석의 lock wait와 pool 전파를 보여주는 용도이며, deadlock을 만들지는 않는다. Prometheus와 MySQL을 0.2초 주기로 조회하는 비용은 진단 관측 비용이므로, 기준 성능 수치에는 이 실행이 아닌 별도 baseline 실행을 사용한다.

### 5. 자연 예매 경합 테스트와 관찰용 래퍼

`run_pessimistic_lock_natural_k6.sh`는 외부 MySQL 락 점유 없이 실제 Reservation API에 같은 좌석을 동시에 요청한다. `observe_pessimistic_lock_run.sh`는 이 테스트가 실행되는 동안 Tomcat·Hikari·MySQL `data_lock_waits`를 수집한다.

앱은 `LOCK_STRATEGY=pessimistic`으로 기동하고, 실행 전 좌석 fixture를 새로 만든다.

```bash
LOCK_STRATEGY=pessimistic \
SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=30 \
./gradlew bootRun
```

```bash
MYSQL_HOST=127.0.0.1 \
MYSQL_PORT=10047 \
MYSQL_PASSWORD='로컬 테스트 DB 비밀번호' \
scripts/test/seed_pessimistic_lock_fixture.sh
```

```bash
BASE_URL=http://127.0.0.1:10080 \
PT_ID='fixture의 performance_time_id' \
SEAT_ID='fixture의 grade_1_seat_id' \
JWT_SECRET='서버와 같은 테스트용 secret' \
MYSQL_HOST=127.0.0.1 \
MYSQL_PORT=10047 \
MYSQL_PASSWORD='로컬 테스트 DB 비밀번호' \
CONCURRENCY=1000 \
scripts/test/observe_pessimistic_lock_run.sh \
  scripts/test/run_pessimistic_lock_natural_k6.sh
```

실행 중 출력된 `observation_run_dir` 아래의 `app-metrics.tsv`, `mysql-lock-waits.tsv`를 `tail -f`로 볼 수 있다. 완료 후 `observation-summary.txt`에서 peak를 확인한다. 같은 한 좌석에서는 정상적으로 성공 1건과 `SEAT_ALREADY_RESERVED` 충돌이 대부분이며, 이 테스트만으로 deadlock을 기대하지 않는다. deadlock은 두 좌석을 반대 순서로 잠그는 별도 JDBC 테스트로 재현한다. 현재 예매 경로는 좌석 ID를 정렬하므로 이 실패 모드를 방지하려는 코드 규칙을 가진다.

### 2,000~5,000 VU lock overhead matrix

비관락과 ReentrantLock을 같은 조건으로 비교하고 JFR·Prometheus·Grafana·MySQL·Docker metric을 case별로 남기려면 다음 matrix runner를 사용한다. 기본 VU는 2,000 → 3,000 → 4,000 → 5,000이며, 전략별로 새 app과 새 fixture를 만든다. admission permit은 최대 VU와 같게 두어 실제 lock 경로까지 요청을 보낸다.

```bash
DRY_RUN=true scripts/test/run_lock_overhead_matrix.sh lock-overhead
```

```bash
ADMISSION_PER_SEAT_PERMITS=5000 \
JFR_ENABLED=true \
CAPTURE_THREAD_DUMPS=false \
scripts/test/run_lock_overhead_matrix.sh lock-overhead
```

결과는 `build/k6-results/lock-overhead-matrix/<matrix-id>/matrix-summary.tsv`와 case별 JFR/분석 파일에 남는다. 2,000 VU의 원인 확인만 먼저 할 때는 `VUS_LIST=2000 LOCK_STRATEGIES=reentrant CAPTURE_THREAD_DUMPS=true`로 한 case만 실행한다.

### 30-seat multi-hot-seat matrix

4개 좌석 집중 경합과 분리해 30개 좌석에 요청을 분산한다. 기본 VU는 1,000 → 1,500 → 2,000이며 pessimistic/ReentrantLock을 각각 실행한다.

```bash
DRY_RUN=true scripts/test/run_multi_hot_seat_matrix.sh multi-hot-seat
```

```bash
SEAT_POOL_SIZE=30 \
VUS_LIST=1000,1500,2000 \
ADMISSION_PER_SEAT_PERMITS=2000 \
JFR_ENABLED=true \
scripts/test/run_multi_hot_seat_matrix.sh multi-hot-seat
```

30-seat fixture는 `seed_multi_hot_seat_fixture.sh`가 매 run 새로 만들며, 결과는 `build/k6-results/multi-hot-seat-matrix/`에 저장한다. 자세한 Grafana·JFR 판정 순서는 [132 문서](../../docs/132-2000vu-lock-overhead-jfr-matrix.md)를 따른다.

### 여러 좌석으로 분산되는 자연 경합

`01-ticket-open-run.js`가 한 좌석에 모든 VU를 집중시키는 반면, `03-multi-seat-distributed-run.js`는 VU 번호를 좌석 풀에 매핑한다. 따라서 여러 사용자가 여러 좌석을 나눠 요청하면서도 같은 좌석을 선택한 요청끼리는 실제 `@ReservationLock` 경로에서 경합한다. 기본값은 한 요청에 한 좌석이며, `SEATS_PER_REQUEST=2`로 바꾸면 한 트랜잭션에서 여러 좌석을 잠그는 경로도 확인할 수 있다. 좌석 ID는 매 요청에서 정렬되므로 정상적인 multi-seat 경합과 반대 순서 deadlock 재현은 구분한다.

기존 20~200 VU 기록은 admission 도입 전 `pessimistic` 조건의 결과다. 현재 단일 JVM 보호 경로(`reentrant` + seat admission permit 1)에서는 429가 정상적인 즉시 거절이므로, 단순 runner를 그대로 사용해 409만 정상으로 처리하면 안 된다. 300~3,000 VU 실험은 `run_multi_hot_seat_diagnosis.sh`만 사용한다. 이 runner는 429·transport·관측 손실을 분리하고, 새 app container·새 4-seat fixture·사후 DB 검증·management port 관측을 run 디렉터리에 함께 남긴다.

먼저 20 VU dry-run으로 수집 계약을 확인한다.

```bash
MYSQL_PASSWORD='로컬 테스트 DB 비밀번호' \
JWT_SECRET='서버와 같은 테스트용 secret' \
CONCURRENCY=20 \
scripts/test/run_multi_hot_seat_diagnosis.sh dry-run
```

dry-run의 `contract_status=pass`, observation loss=0, 성공 4건, 사후 `ReservedSeat` 4건을 확인한 뒤에만 300 → 600 → 1,000 → 2,000 → 3,000 VU로 올린다. runner는 `request_contract_status`(응답·정합성)와 `observation_contract_status`(표본 완결성)를 분리한다. 관측 손실은 요청 실패로 합산하지 않지만, 관측이 degraded인 run으로 resource peak의 부재를 주장하지 않는다. 3,000 VU는 새 app container·새 fixture로 세 번 독립 실행한다. capacity run은 `CAPTURE_THREAD_DUMPS=false`가 기본이다. `SIGQUIT` dump는 stdout에 큰 로그를 동기 출력해 응답시간과 management 관측을 흔들 수 있으므로, 필요하면 별도의 관측 control에서만 `true`로 실행한다.

```bash
MYSQL_PASSWORD='로컬 테스트 DB 비밀번호' \
JWT_SECRET='서버와 같은 테스트용 secret' \
CONCURRENCY=300 \
scripts/test/run_multi_hot_seat_diagnosis.sh vu300
```

결과는 `build/k6-results/multi-hot-seat/<run-id>/`에 남는다. 5xx 또는 transport가 있으면 요청 계약 실패로 표시하고, 같은 조건을 두 번 더 독립 재현해 다음 VU 진행 여부를 판단한다. 관측 손실은 별도로 기록하며, 필요하면 더 높은 VU에서 요청 경계와 관측 경계를 각각 확인한다. 2026-07-18의 3,000 VU 결과와 해석은 [114 문서](../../docs/114-multi-hot-seat-3000vu-results.md)에 남겼다.

먼저 새 fixture를 만든 뒤 낮은 VU부터 실행한다. 네 좌석 fixture를 사용할 때 `CONCURRENCY=20`이면 좌석별 약 5개 VU가 경쟁한다.

```bash
fixture="$(MYSQL_HOST=127.0.0.1 MYSQL_PORT=10047 MYSQL_PASSWORD='cider123' \
  scripts/test/seed_pessimistic_lock_fixture.sh | tail -n 1)"
pt_id="$(echo "$fixture" | awk '{print $2}')"
s1="$(echo "$fixture" | awk '{print $3}')"
s2="$(echo "$fixture" | awk '{print $4}')"
s3="$(echo "$fixture" | awk '{print $5}')"
s4="$(echo "$fixture" | awk '{print $6}')"

LOCK_STRATEGY=pessimistic \
SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=30 \
docker compose up -d --force-recreate app

BASE_URL=http://127.0.0.1:10080 \
MYSQL_HOST=127.0.0.1 MYSQL_PORT=10047 MYSQL_PASSWORD='cider123' \
LOCK_STRATEGY=pessimistic \
RESULT_DIR="build/k6-results/multi-seat/concurrency-20" \
scripts/test/observe_pessimistic_lock_run.sh \
  env BASE_URL=http://127.0.0.1:10080 \
  PT_ID="$pt_id" SEAT_IDS="$s1,$s2,$s3,$s4" \
  SEAT_POOL_SIZE=4 SEATS_PER_REQUEST=1 CONCURRENCY=20 \
  JWT_SECRET='change-me-jwt-secret-change-me-jwt-secret-change-me-jwt-secret' \
  scripts/test/run_multi_seat_distributed_k6.sh
```

같은 fixture를 재사용하지 말고 `CONCURRENCY=50`, `100`, `200` 순서로 매번 새 fixture를 만든다. 각 실행에서 성공 수는 좌석 풀 크기 이하, 나머지는 `SEAT_ALREADY_RESERVED` 409가 정상이다. `unexpected_response`, 내부 오류, HTTP 실패율, Hikari pending, MySQL `data_lock_waits`, p95/p99를 함께 기록한다.

### 6. 부하 발생기와 앱 프로세스 분리

20,000 VU에서 k6 자체 또는 호스트 OS가 병목인지 분리하려면 Docker k6 실행기를 사용한다. 앱·MySQL·관찰 래퍼는 호스트에서 실행하고, k6만 컨테이너에서 실행한다. macOS Docker Desktop에서는 컨테이너에서 호스트 앱으로 접근할 때 `127.0.0.1` 대신 `host.docker.internal`을 사용한다.

```bash
K6_BIN="${PWD}/scripts/test/run_k6_docker.sh" \
BASE_URL=http://127.0.0.1:10080 \
K6_BASE_URL=http://host.docker.internal:10080 \
GRADE=2 \
TRAFFIC_PROFILE=minimum \
CONCURRENCY=20000 \
ALLOW_LARGE_LOAD=true \
PT_ID=... \
SEAT_ID=... \
JWT_SECRET='서버와 같은 테스트용 secret' \
MYSQL_HOST=127.0.0.1 \
MYSQL_PORT=10047 \
MYSQL_PASSWORD='cider123' \
scripts/test/observe_pessimistic_lock_run.sh \
  scripts/test/run_pessimistic_lock_natural_k6.sh
```

이 방식도 물리적으로는 같은 노트북 CPU·메모리를 공유하므로 완전한 분리는 아니다. 같은 결과가 반복되면 애플리케이션/DB 경계의 포화 가능성이 커지고, 별도 OCI 인스턴스를 k6 전용 부하 발생기로 사용해야 한다.

```bash
MYSQL_LOCK_TEST_PASSWORD='로컬 테스트 DB 비밀번호' \
scripts/test/run_mysql_pessimistic_lock_semantics_test.sh
```

## 인기 공연 쓰기·좌석 조회 갱신 부하

`146-popular-write-refresh-load.js`는 인기 공연 한 회차의 좌석 상태 변경과 좌석 현황 조회를 별도 시나리오로 발생시킨다. `run_146_popular_write_refresh_test.sh`는 실행 전 snapshot warm-up, MySQL·Redis·Prometheus·컨테이너·macOS 관측, 실행 후 테스트 데이터 복구를 담당한다. 애플리케이션 코드는 부하 발생기 실행 중 변경하지 않는다.

기본 실행은 다음 네 케이스를 순서대로 수행한다.

| 케이스 | 부하 | 확인 목적 |
| --- | --- | --- |
| `write-10` | 상태 변경 10/s, 20초 | 인기 공연 쓰기 기준선 |
| `write-50` | 상태 변경 50/s, 20초 | 높은 상태 변경률의 p95·redo·flush 관찰 |
| `mixed-50-100` | 쓰기 50/s + 조회 100/s, 20초 | 상태 변경과 snapshot 재구축의 결합 비용 |
| `invalidation-burst` | invalidation 직후 조회 2,000건 | cold burst의 fallback·tail latency와 사용자 응답 |

```bash
RESULT_ROOT=build/k6-results/146.6.3-popular-write-refresh \
bash scripts/test/run_146_popular_write_refresh_test.sh
```

한 케이스만 실행하거나 입력률을 조정할 수 있다.

```bash
CASE_SELECTION=mixed-50-100 \
MIXED_WRITE_RATE=50 \
MIXED_READ_RATE=100 \
MIXED_DURATION=20s \
bash scripts/test/run_146_popular_write_refresh_test.sh
```

기본 대상은 `PT_ID=900000001`, Docker app `10080`, management `10081`, MySQL `10047`, Redis `16380`이다. `.env`의 `JWT_SECRET` 또는 `SPRING_JWT_SECRET`을 읽고, 전용 회원 `900009980`부터 21명을 사용한다. 다른 fixture를 사용할 때 `PT_ID`, `MEMBER_ID_BASE`, `MEMBER_POOL_SIZE`, `SEAT_COUNT_LIMIT`을 함께 지정한다.

케이스별 결과 디렉터리에는 `k6-summary.json`, `summary-selected.json`, `mysql-delta.tsv`, Redis·Prometheus 전후 스냅샷, OS·컨테이너 관측 파일, `fixture-state-before.tsv`·`fixture-state-after.tsv`가 저장된다. `case-status.txt`의 `k6_exit=0`은 실행 프로세스의 정상 종료를 뜻한다. 혼합·burst에서 fallback 상한을 초과해 반환된 HTTP 503은 `popular_read_unexpected`와 애플리케이션의 `fallback_rejected` 증분으로 판정한다.

MySQL status counter는 실행 구간의 누적 증분이다. `Innodb_data_writes`와 `Innodb_data_written`에는 page cleaner의 백그라운드 flush가 포함된다. `Innodb_log_waits`와 row lock wait가 0이어도 transaction p95 상승 원인을 단정하지 않고, `iostat`, transaction 구간, 저장장치 지연을 함께 확인한다.

## 커밋 이후 캐시 무효화 유실 재현

`run_148_after_commit_process_crash_test.sh`는 Redis `CLIENT PAUSE ALL`로 `AFTER_COMMIT` 무효화 호출을 대기시킨 뒤 MySQL에서 `LOCKED` commit을 관측하고 `imticket-app` 컨테이너를 종료한다. 재기동 직후 Redis의 이전 `AVAILABLE` snapshot과 좌석 API 응답을 확인하고, TTL 만료 뒤 MySQL 기반 `LOCKED` snapshot 재구축까지 기록한다. 전용 k6 시나리오는 pre-reserve 1회 요청과 프로세스 종료에 따른 전송 실패를 기록한다.

```bash
RESULT_ROOT=build/k6-results/148.3-after-commit-process-crash \
TEST_TTL=60s \
REDIS_PAUSE_MS=30000 \
bash scripts/test/run_148_after_commit_process_crash_test.sh
```

실행 결과의 `timeline.tsv`, `k6-summary.json`, `result.txt`를 [인기 공연 캐시 실행 명령](POPULAR_CACHE_TEST_COMMANDS.md)과 [148.3 결과 문서](../../docs/implements/seat-availability/148.3-after-commit-cache-invalidation-failure-test.md)에서 확인한다.

## fixture·진단 파일

- `seed_pessimistic_lock_fixture.{sh,sql}`: hot-seat 경합용 데이터
- `seed_multi_hot_seat_fixture.{sh,sql}`: 30-seat distributed hot-seat 경합용 데이터
- `seed_mysql_benchmark_data.{sh,sql}`: 대량 DB·만료 데이터
- `seed_large_data.{sh,sql}`, `seed_data.sh`: 로컬 수동 시드
- `frontend-seed.js`: 프런트 API를 이용한 통합 mock seed (`frontend/public` poster 참조)
- `reproduce_*`, `observe_*`: MySQL lock·Redis stream 상태 재현과 관측
- `run-cluster.sh`: 두 JVM과 ShedLock 동작 확인

`server_a.log`, `server_b.log`, `seed_output.txt`는 과거 실행 산출물이며 재현 스크립트와 함께 보관할 때만 참고한다. 새로운 결과는 `build/k6-results/` 또는 별도의 `RESULT_DIR`로 남긴다.
