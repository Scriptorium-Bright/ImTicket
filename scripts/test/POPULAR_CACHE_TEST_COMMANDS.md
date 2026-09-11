# 인기 공연 좌석 캐시 비교 실행 명령

이 문서는 인기 공연 한 회차의 좌석 현황 조회를 대상으로 캐시 활성·비활성 조건과 커밋 이후 캐시 무효화 유실 조건을 검증하는 실행 순서를 정리한다. 애플리케이션, MySQL, Redis, k6를 같은 실행 구간에 두고 결과를 build/k6-results/에 저장한다.

각 케이스가 검증한 가설과 설계 판단은 [인기 공연 캐시 활성·비활성 실험의 의미](../../docs/implements/seat-availability/146.6.3-popular-cache-toggle-test-meaning.md)에서 확인한다.

| 스크립트 | 역할 |
| --- | --- |
| run_146_popular_write_refresh_test.sh | 인기 공연 상태 변경·좌석 조회·무효화 burst의 주 실행기 |
| 146-popular-write-refresh-load.js | 상태 변경과 좌석 현황 조회를 발생시키는 k6 시나리오 |
| run_148_after_commit_process_crash_test.sh | MySQL commit 이후 Redis 무효화 대기 중 애플리케이션 종료 실행기 |
| 148-after-commit-process-crash-load.js | 프로세스 종료 시험용 단일 pre-reserve k6 시나리오 |
| run_waiting_room_load.sh | 대기실 또는 애플리케이션 직접 조회 부하 실행기 |
| collect_popular_lifecycle_metrics.sh | Actuator·MySQL·InnoDB·좌석 상태 시간축 수집기 |
| load_env_defaults.sh | .env 값을 Bash 방식으로 읽는 공통 로더 |

## 1. 측정 범위

좌석 현황 조회 API는 GET /api/seats/{performanceTimeId}이다. 캐시 비활성 경로는 MySQL projection을 매 요청 수행한다. 캐시 활성 경로는 Redis snapshot을 조회하고, miss가 발생하면 MySQL 결과로 snapshot을 재구축한다. 좌석 상태 변경이 commit되면 대상 회차의 snapshot을 무효화한다.

이번 비교는 대기실을 우회한 애플리케이션 경계 측정이다. 대기실 입장·승격 지연은 별도 시나리오로 측정한다.

| 케이스 | 기본 입력 | 측정 목적 |
| --- | --- | --- |
| write-10 | 좌석 상태 변경 10/s, 20초 | 쓰기 기준선과 캐시 무효화 발행 비용 |
| write-50 | 좌석 상태 변경 50/s, 20초 | 높은 상태 변경률에서의 쓰기 지연 |
| mixed-50-100 | 쓰기 50/s + 조회 100/s, 20초 | 상태 변경과 snapshot 재구축의 결합 비용 |
| invalidation-burst | 무효화 직후 조회 2,000건 | cold miss, single-flight, 응답 직렬화 비용 |
| warm-hit 직접 조회 | snapshot warm-up 후 조회 1,000건 | 캐시 hit 경로와 MySQL 직접 조회 경로 비교 |

기본 대상 회차는 PT_ID=900000001이며 2,000개 좌석을 사용한다. OCI에서 대상 회차가 다르면 PT_ID와 SEAT_COUNT_LIMIT을 함께 바꾼다.

## 2. 사전 조건

저장소 루트에서 실행한다.

~~~bash
cd /path/to/ImTicket
docker compose up -d mysql redis
~~~

주요 실행기는 저장소 루트의 .env를 Bash 방식으로 읽는다. JWT_SECRET, SPRING_JWT_SECRET, MySQL 비밀번호를 문서에 직접 기록하지 않는다.

애플리케이션은 관리 포트가 healthy가 될 때까지 기다린다.

~~~bash
until curl -fsS --connect-timeout 1 --max-time 2 \
  http://127.0.0.1:10081/actuator/health >/dev/null; do
  sleep 1
done
~~~

로컬 기본 포트는 다음과 같다.

| 대상 | 로컬 주소 |
| --- | --- |
| 애플리케이션 | http://127.0.0.1:10080 |
| Actuator | http://127.0.0.1:10081 |
| MySQL | 127.0.0.1:10047 |
| Redis | 127.0.0.1:16380 |

대상 fixture의 좌석 수와 상태를 먼저 확인한다.

~~~bash
docker compose exec -T mysql sh -c \
  'mysql --user=root --password="$MYSQL_ROOT_PASSWORD" --database="$MYSQL_DATABASE" --batch --skip-column-names -e \
  "SELECT seat_status, COUNT(*) FROM Seat WHERE performance_time_id=900000001 GROUP BY seat_status ORDER BY seat_status;"'
~~~

쓰기 케이스는 실행 가능한 AVAILABLE 좌석 수가 전체 요청 수보다 많아야 한다. 다른 회차를 사용할 때 모든 명령의 PT_ID와 캐시 대상 회차 목록을 같은 값으로 지정한다.

## 3. 캐시 비활성 기준 실행

캐시와 대기실을 명시적으로 끄고 애플리케이션을 재기동한다. 애플리케이션 코드와 Hikari 설정은 활성 실행과 동일하게 유지한다.

~~~bash
env \
  RESERVATION_SEAT_MAP_CACHE_ENABLED=false \
  RESERVATION_SEAT_MAP_CACHE_ENABLED_PERFORMANCE_TIME_IDS= \
  RESERVATION_WAITING_ROOM_ENABLED=false \
  RESERVATION_WAITING_ROOM_ENABLED_PERFORMANCE_TIME_IDS= \
  LOCK_STRATEGY=reentrant \
  SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=30 \
  docker compose up -d --force-recreate app
~~~

health 확인 후 네 케이스를 실행한다.

~~~bash
RESULT_ROOT=build/k6-results/146.6.3-popular-cache-toggle \
RUN_ID=cache-off-$(date -u +%Y%m%dT%H%M%SZ) \
CASE_SELECTION=all \
WRITE_DURATION=20s \
MIXED_DURATION=20s \
WRITE_RATE_LOW=10 \
WRITE_RATE_HIGH=50 \
MIXED_WRITE_RATE=50 \
MIXED_READ_RATE=100 \
READ_BURST_VUS=2000 \
REQUEST_TIMEOUT=15s \
bash scripts/test/run_146_popular_write_refresh_test.sh
~~~

혼합 부하만 다시 실행할 때는 다음 명령을 사용한다.

~~~bash
RESULT_ROOT=build/k6-results/146.6.3-popular-cache-toggle \
RUN_ID=mixed-cache-off-$(date -u +%Y%m%dT%H%M%SZ) \
CASE_SELECTION=mixed-50-100 \
MIXED_WRITE_RATE=50 \
MIXED_READ_RATE=100 \
MIXED_DURATION=20s \
bash scripts/test/run_146_popular_write_refresh_test.sh
~~~

RUN_ID에 날짜를 붙여 실행한 경우 결과 확인 명령의 20260903T-cache-off 부분을 실제 RUN_ID로 바꾼다. 같은 RUN_ID를 재사용하면 기존 결과가 덮어써질 수 있다.

## 4. 캐시 활성 비교 실행

대상 회차만 캐시를 사용하도록 설정한다. 전역 활성화와 대상 회차 목록이 모두 필요하다.

~~~bash
env \
  RESERVATION_SEAT_MAP_CACHE_ENABLED=true \
  RESERVATION_SEAT_MAP_CACHE_ENABLED_PERFORMANCE_TIME_IDS=900000001 \
  RESERVATION_WAITING_ROOM_ENABLED=false \
  RESERVATION_WAITING_ROOM_ENABLED_PERFORMANCE_TIME_IDS= \
  LOCK_STRATEGY=reentrant \
  SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=30 \
  docker compose up -d --force-recreate app
~~~

health 확인 후 비활성 실행과 같은 입력으로 다시 실행한다.

~~~bash
RESULT_ROOT=build/k6-results/146.6.3-popular-cache-toggle \
RUN_ID=cache-on-$(date -u +%Y%m%dT%H%M%SZ) \
CASE_SELECTION=all \
WRITE_DURATION=20s \
MIXED_DURATION=20s \
WRITE_RATE_LOW=10 \
WRITE_RATE_HIGH=50 \
MIXED_WRITE_RATE=50 \
MIXED_READ_RATE=100 \
READ_BURST_VUS=2000 \
REQUEST_TIMEOUT=15s \
bash scripts/test/run_146_popular_write_refresh_test.sh
~~~

## 5. warm-hit 직접 조회 비교

run_waiting_room_load.sh는 직접 좌석 현황 조회를 지원한다. 이 실행은 대기실 API를 호출하지 않는다. load_env_defaults.sh가 Bash 문법을 사용하므로 zsh에서 직접 source하지 않고 Bash로 호출한다.

캐시 활성 상태에서 snapshot을 초기화하고 한 번 재구축한다.

~~~bash
redis-cli -h 127.0.0.1 -p 16380 \
  DEL \
  'reservation:seat-map:{900000001}:version' \
  'reservation:seat-map:{900000001}:snapshot:v2' \
  'reservation:seat-map:{900000001}:snapshot'
curl -fsS --connect-timeout 2 --max-time 20 \
  http://127.0.0.1:10080/api/seats/900000001 >/dev/null
~~~

캐시 활성 warm-hit 부하:

~~~bash
bash -lc '
source scripts/test/load_env_defaults.sh
load_imticket_env .env
MODE=seat-map FLOW=direct PT_ID=900000001 \
JWT_SECRET="$JWT_SECRET" \
BASE_URL=http://127.0.0.1:10080 \
MANAGEMENT_BASE_URL=http://127.0.0.1:10081 \
CONCURRENCY=1000 STATUS_POLLS=0 REQUEST_TIMEOUT=15s MAX_DURATION=2m \
RUN_NAME=cache-warm-on-1000 \
RUN_DIR=build/k6-results/146.6.3-popular-cache-toggle/warm-on-1000 \
bash scripts/test/run_waiting_room_load.sh
'
~~~

캐시 비활성 조건을 적용한 뒤 health 확인 후 같은 직접 조회 명령을 `RUN_NAME=cache-warm-off-1000-rerun`, `RUN_DIR=.../warm-off-1000-rerun`으로 실행한다. 두 결과에서 `http_req_failed`, 좌석 조회 p95/p99, `hikaricp_connections_pending`, Tomcat busy thread를 함께 확인한다.

재기동 직후 부하를 시작하면 애플리케이션 준비 과정의 연결 reset이 측정에 포함될 수 있다. 반드시 Actuator health와 GET /api/seats/{PT_ID}의 단일 200 응답을 확인한 뒤 k6를 시작한다.

## 6. 2분 인기 공연 상태 변경 측정

좌석 변경과 결제 완료·예약 확정·만료 해제를 분리해 측정할 때는 collector를 별도 터미널에서 실행한다. collector는 Actuator, MySQL 상태, InnoDB 상태, 좌석 상태를 popular-lifecycle-timeline.tsv에 기록한다.

터미널 A:

~~~bash
OUT_DIR=build/k6-results/146.6.3-popular-lifecycle/$(date -u +%Y%m%dT%H%M%SZ) \
STOP_FILE=/tmp/imticket-popular-lifecycle.stop \
PT_ID=900000001 \
MANAGEMENT_BASE_URL=http://127.0.0.1:10081 \
INTERVAL_SECONDS=1 \
bash scripts/test/collect_popular_lifecycle_metrics.sh
~~~

터미널 B에서 실제 k6 상태 변경·결제 시나리오를 실행한 뒤 collector를 종료한다.

~~~bash
touch /tmp/imticket-popular-lifecycle.stop
~~~

재실행 전에 STOP_FILE을 삭제하고 OUT_DIR을 새로 지정한다. 종료 후 popular-lifecycle-timeline.tsv와 k6 결과를 같은 시간축으로 비교한다.

## 7. 결과 확인

케이스별 핵심 지표:

~~~bash
for mode in cache-off cache-on; do
  for case_name in write-10 write-50 mixed-50-100 invalidation-burst; do
    result_dir=build/k6-results/146.6.3-popular-cache-toggle/20260903T-$mode/$case_name
    printf '%s/%s\t' "$mode" "$case_name"
    jq -r '[
      (.metrics.http_req_duration["p(95)"] // 0),
      (.metrics.http_req_duration["p(99)"] // 0),
      (.metrics.http_req_failed.value // 0),
      (.metrics.dropped_iterations.count // 0),
      (.metrics.popular_read_attempts.count // 0),
      (.metrics.popular_read_success.count // 0),
      (.metrics.popular_read_transport_failure.count // 0)
    ] | @tsv' "$result_dir/k6-summary.json"
  done
done
~~~

좌석 cache 경로별 이벤트는 app-metrics-before.prom와 app-metrics-after.prom의 차이를 계산한다.

~~~bash
rg 'imticket_seat_map_cache_events_total' \
  build/k6-results/146.6.3-popular-cache-toggle/20260903T-cache-on/mixed-50-100/app-metrics-before.prom \
  build/k6-results/146.6.3-popular-cache-toggle/20260903T-cache-on/mixed-50-100/app-metrics-after.prom
~~~

MySQL에서는 다음 지표의 실행 구간 증분을 확인한다.

~~~bash
awk -F '\t' '$1 ~ /Innodb_(data_writes|data_written|log_write_requests|log_writes|os_log_fsyncs|log_waits|row_lock_waits)/ {print}' \
  build/k6-results/146.6.3-popular-cache-toggle/20260903T-cache-on/mixed-50-100/mysql-delta.tsv
~~~

## 8. AFTER_COMMIT 무효화 유실 재현

`AFTER_COMMIT`은 MySQL commit 이후 애플리케이션 프로세스 안에서 실행된다. Redis 명령 대기 중 애플리케이션을 종료하면 MySQL 상태와 Redis 조회 snapshot의 반영 시점을 분리해 확인할 수 있다. 애플리케이션 소스는 변경하지 않으며, 전용 k6 시나리오와 외부 실행기가 실행 환경을 제어한다.

기본값은 `PT_ID=900000001`, `SEAT_ID=900000001`, 테스트 TTL `60s`, Redis pause `30s`다. 실행기는 대상 fixture를 정리하고 테스트용 캐시 설정으로 애플리케이션을 기동한 뒤, 종료 후 DB fixture·Redis pause·애플리케이션 설정을 원복한다.

```bash
bash scripts/test/run_148_after_commit_process_crash_test.sh
```

OCI에서 주소·포트·대상 회차를 바꿀 때는 환경 변수로 전달한다.

```bash
BASE_URL=http://127.0.0.1:10080 \
PT_ID=900000001 \
SEAT_ID=900000001 \
TEST_TTL=60s \
REDIS_PAUSE_MS=30000 \
RESULT_ROOT=build/k6-results/148.3-after-commit-process-crash \
bash scripts/test/run_148_after_commit_process_crash_test.sh
```

핵심 산출물은 실행 시 출력된 `RUN_DIR` 아래에 저장된다.

```bash
cat build/k6-results/148.3-after-commit-process-crash/<RUN_ID>/timeline.tsv
cat build/k6-results/148.3-after-commit-process-crash/<RUN_ID>/result.txt
jq '.metrics.crash_write_transport_failure, .metrics.crash_write_duration' \
  build/k6-results/148.3-after-commit-process-crash/<RUN_ID>/k6-summary.json
```

`timeline.tsv`에서 `mysql_locked_commit_observed`, `application_killed`, `post_restart_api_observed`, `recovery_api_observed`를 순서대로 확인한다. 재기동 직후 API가 `AVAILABLE`이고 MySQL이 `LOCKED`이면 stale snapshot 경로가 재현된 것이다. TTL 만료 뒤 API가 `LOCKED`로 전환되면 자동 재구축 경계까지 확인한 것이다.

## 9. 이번 로컬 실행 결과

2026-09-03 로컬 Docker 환경에서 같은 애플리케이션 이미지, Hikari 최대 30, 대기실 비활성, PT_ID=900000001 조건으로 1회씩 실행했다.

| 케이스 | 캐시 비활성 | 캐시 활성 |
| --- | ---: | ---: |
| mixed-50-100 전체 p95 | 7,513 ms | 1,107 ms |
| mixed-50-100 좌석 조회 p95 | 7,261 ms | 996 ms |
| mixed-50-100 MySQL projection | 1,701회 | 229회 |
| mixed-50-100 조회 성공 | 1,700회 | 2,001회 |
| mixed-50-100 dropped iterations | 463회 | 0회 |
| invalidation-burst 조회 p95 | 14,912 ms | 15,947 ms |
| invalidation-burst 조회 성공 | 1,035회 | 101회 |
| warm-hit 직접 조회 1,000건 p95 | 15,038 ms | 10,067 ms |
| warm-hit 직접 조회 성공 | 397회 | 1,000회 |

혼합 부하에서는 캐시 활성으로 MySQL projection 횟수와 요청 지연이 함께 감소했다. 쓰기가 계속 snapshot을 무효화하는 조건에서도 single-flight 참여 요청이 발생했다.

write-10과 write-50은 좌석 조회를 발생시키지 않으므로 캐시 활성·비활성의 우열 판단에는 사용하지 않는다. 이 케이스는 쓰기와 무효화 발행의 기준선으로 사용한다.

무효화 직후 2,000건 burst에서는 캐시 활성 조건의 MySQL projection이 4회까지 줄었다. 응답 성공률은 101/2,000으로 낮아졌고 single-flight timeout 35회가 기록됐다. 현재 구현에서는 Redis snapshot 조회·역직렬화·대형 응답 전송이 burst 경로의 별도 병목으로 관찰된다. 이 케이스는 캐시 활성의 일반적인 hit 성능 판단에 warm-hit 결과와 함께 사용한다.

각 결과는 다음 디렉터리에 있다.

~~~text
build/k6-results/146.6.3-popular-cache-toggle/20260903T-cache-off/
build/k6-results/146.6.3-popular-cache-toggle/20260903T-cache-on/
build/k6-results/146.6.3-popular-cache-toggle/warm-on-1000/
build/k6-results/146.6.3-popular-cache-toggle/warm-off-1000-rerun/
~~~

## 10. OCI 재현 시 변경 사항

OCI에서 실행할 때는 주소와 포트만 해당 배포 환경에 맞춘다.

~~~bash
BASE_URL=http://127.0.0.1:10080
MANAGEMENT_BASE_URL=http://127.0.0.1:10081
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
~~~

부하 발생기를 별도 인스턴스에 둘 때 BASE_URL은 OCI private IP 또는 내부 DNS를 사용한다. MANAGEMENT_BASE_URL은 관측 호스트에서만 접근하도록 보안 그룹과 바인딩 주소를 제한한다.

현재 run_146_popular_write_refresh_test.sh는 macOS의 vm_stat를 메모리 관측에 사용한다. OCI Linux에서는 별도 터미널에서 다음 명령을 실행하고 결과 파일을 테스트 디렉터리에 보관한다.

~~~bash
iostat -dx 1 > build/k6-results/oci-iostat.log
vmstat 1 > build/k6-results/oci-vmstat.log
~~~

Linux의 iostat는 sysstat 패키지, vmstat는 procps 패키지를 준비한다. 컨테이너 내부의 MySQL·Redis 지표는 현재 실행기와 동일하게 docker compose exec와 redis-cli로 수집한다.

OCI에서 새 이미지가 필요하면 애플리케이션 재기동 시 build를 명시한다.

~~~bash
env \
  RESERVATION_SEAT_MAP_CACHE_ENABLED=true \
  RESERVATION_SEAT_MAP_CACHE_ENABLED_PERFORMANCE_TIME_IDS=900000001 \
  docker compose up -d --build --force-recreate app
~~~

## 11. 실행 후 복원

명령어 앞에 지정한 환경 변수는 해당 명령에만 적용된다. 테스트 후 운영 또는 로컬 기본 설정을 .env에 맞춰 재기동한다.

~~~bash
docker compose up -d --force-recreate app
until curl -fsS --connect-timeout 1 --max-time 2 \
  http://127.0.0.1:10081/actuator/health >/dev/null; do
  sleep 1
done
~~~

좌석 fixture가 테스트 후 AVAILABLE 상태로 복구됐는지도 확인한다.

~~~bash
docker compose exec -T mysql sh -c \
  'mysql --user=root --password="$MYSQL_ROOT_PASSWORD" --database="$MYSQL_DATABASE" --batch --skip-column-names -e \
  "SELECT seat_status, COUNT(*) FROM Seat WHERE performance_time_id=900000001 GROUP BY seat_status ORDER BY seat_status;"'
~~~
