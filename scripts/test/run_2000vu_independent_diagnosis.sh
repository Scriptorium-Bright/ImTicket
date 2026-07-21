#!/usr/bin/env bash
set -euo pipefail

# 하나의 독립 run만 수행한다. R1/R2/R3은 이 스크립트를 각각 새로 호출한다.
# app만 force-recreate하고, MySQL은 유지한 채 새 fixture를 만들기 때문에 DB 초기화/버퍼풀
# 차이를 새 변수로 만들지 않는다. 실행 결과는 RESULT_ROOT 아래 한 디렉터리에만 기록한다.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUN_LABEL="${1:-}"
CONCURRENCY="${CONCURRENCY:-2000}"
LOCK_STRATEGY="${LOCK_STRATEGY:-reentrant}"
LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS="${LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS:-1000}"
HIKARI_POOL_SIZE="${HIKARI_POOL_SIZE:-30}"
ADMISSION_PER_SEAT_PERMITS="${ADMISSION_PER_SEAT_PERMITS:-1}"
BASE_URL="${BASE_URL:-http://127.0.0.1:10080}"
MANAGEMENT_SERVER_PORT="${MANAGEMENT_SERVER_PORT:-}"
MANAGEMENT_BASE_URL="${MANAGEMENT_BASE_URL:-${BASE_URL}}"
PROMETHEUS_CONFIG_FILE="${PROMETHEUS_CONFIG_FILE:-./prometheus.yml}"
RECREATE_PROMETHEUS="${RECREATE_PROMETHEUS:-false}"
BUILD_APP_IMAGE="${BUILD_APP_IMAGE:-true}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-10047}"
MYSQL_USER="${MYSQL_USER:-capstone}"
MYSQL_DATABASE="${MYSQL_DATABASE:-capstone}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-${MYSQL_LOCK_TEST_PASSWORD:-}}"
JWT_SECRET="${JWT_SECRET:-}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-15s}"
MAX_DURATION="${MAX_DURATION:-2m}"
APP_READY_TIMEOUT_SECONDS="${APP_READY_TIMEOUT_SECONDS:-240}"
RESERVATION_PATH_WARMUP="${RESERVATION_PATH_WARMUP:-true}"
SAMPLE_INTERVAL_SECONDS="${SAMPLE_INTERVAL_SECONDS:-1}"
IDLE_SECONDS="${IDLE_SECONDS:-10}"
TAIL_SECONDS="${TAIL_SECONDS:-30}"
RESULT_ROOT="${RESULT_ROOT:-${ROOT_DIR}/build/k6-results/2000vu-independent}"
K6_EXECUTION_MODE="${K6_EXECUTION_MODE:-host}"
K6_DOCKER_IMAGE="${K6_DOCKER_IMAGE:-grafana/k6:1.6.1}"
K6_DOCKER_CPUS="${K6_DOCKER_CPUS:-1}"
K6_DOCKER_NETWORK="${K6_DOCKER_NETWORK:-imticket_imticket-network}"
K6_DOCKER_BASE_URL="${K6_DOCKER_BASE_URL:-http://imticket-app:10080}"
K6_DOCKER_CONTAINER_NAME="imticket-k6-diagnosis-${RUN_LABEL:-pending}"

if [[ ! "${RUN_LABEL}" =~ ^(dry-run|r[1-3]|cpu-control|observability-control)$ ]]; then
  echo "첫 인자는 dry-run, r1, r2, r3, cpu-control, observability-control 중 하나여야 합니다." >&2
  exit 1
fi
if [[ ! "${CONCURRENCY}" =~ ^[1-9][0-9]*$ ]]; then
  echo "CONCURRENCY는 양의 정수여야 합니다." >&2
  exit 1
fi
if [[ "${RUN_LABEL}" != "dry-run" && "${CONCURRENCY}" != "2000" ]]; then
  echo "실측 run은 CONCURRENCY=2000으로 고정합니다. actual=${CONCURRENCY}" >&2
  exit 1
fi
if [[ "${LOCK_STRATEGY}" != "reentrant" ]]; then
  echo "이 진단의 고정 전략은 reentrant입니다. actual=${LOCK_STRATEGY}" >&2
  exit 1
fi
if [[ ! "${HIKARI_POOL_SIZE}" =~ ^[1-9][0-9]*$ ]] \
  || [[ ! "${LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS}" =~ ^[1-9][0-9]*$ ]] \
  || [[ ! "${ADMISSION_PER_SEAT_PERMITS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "HIKARI_POOL_SIZE, LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS, ADMISSION_PER_SEAT_PERMITS는 양의 정수여야 합니다." >&2
  exit 1
fi
if [[ -z "${MYSQL_PASSWORD}" || -z "${JWT_SECRET}" ]]; then
  echo "MYSQL_PASSWORD와 JWT_SECRET을 설정해야 합니다." >&2
  exit 1
fi
if [[ -n "${MANAGEMENT_SERVER_PORT}" && ! "${MANAGEMENT_SERVER_PORT}" =~ ^[1-9][0-9]*$ ]]; then
  echo "MANAGEMENT_SERVER_PORT는 비어 있거나 양의 정수여야 합니다." >&2
  exit 1
fi
if [[ ! "${RECREATE_PROMETHEUS}" =~ ^(true|false)$ ]]; then
  echo "RECREATE_PROMETHEUS는 true 또는 false여야 합니다." >&2
  exit 1
fi
if [[ ! "${BUILD_APP_IMAGE}" =~ ^(true|false)$ ]]; then
  echo "BUILD_APP_IMAGE는 true 또는 false여야 합니다." >&2
  exit 1
fi
if [[ ! "${SAMPLE_INTERVAL_SECONDS}" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
  echo "SAMPLE_INTERVAL_SECONDS는 양의 숫자여야 합니다." >&2
  exit 1
fi
if [[ ! "${APP_READY_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "APP_READY_TIMEOUT_SECONDS는 양의 정수여야 합니다." >&2
  exit 1
fi
if [[ ! "${RESERVATION_PATH_WARMUP}" =~ ^(true|false)$ ]]; then
  echo "RESERVATION_PATH_WARMUP은 true 또는 false여야 합니다." >&2
  exit 1
fi
if [[ ! "${K6_EXECUTION_MODE}" =~ ^(host|docker)$ ]]; then
  echo "K6_EXECUTION_MODE는 host 또는 docker여야 합니다." >&2
  exit 1
fi
if [[ "${K6_EXECUTION_MODE}" == "docker" ]] \
  && [[ ! "${K6_DOCKER_CPUS}" =~ ^[1-9][0-9]*([.][0-9]+)?$ ]]; then
  echo "K6_DOCKER_CPUS는 양의 숫자여야 합니다." >&2
  exit 1
fi

RUN_ID="${RUN_LABEL}-$(date -u +%Y%m%dT%H%M%SZ)"
RUN_DIR="${RESULT_ROOT}/${RUN_ID}"
MANIFEST_FILE="${RUN_DIR}/manifest.txt"
APP_METRICS_FILE="${RUN_DIR}/app-metrics.tsv"
MYSQL_METRICS_FILE="${RUN_DIR}/mysql-metrics.tsv"
DOCKER_STATS_FILE="${RUN_DIR}/docker-stats.tsv"
OBSERVATION_LOSS_FILE="${RUN_DIR}/observation-loss.tsv"
THREAD_DUMP_EVENTS_FILE="${RUN_DIR}/thread-dump-events.tsv"
APP_LOG_FILE="${RUN_DIR}/app-container.log"
MYSQL_LOG_FILE="${RUN_DIR}/mysql-container.log"
MYSQL_LOCK_SNAPSHOT_FILE="${RUN_DIR}/mysql-lock-wait-snapshot.log"
FIXTURE_FILE="${RUN_DIR}/fixture.tsv"
K6_CONSOLE_FILE="${RUN_DIR}/k6-console.log"
K6_SUMMARY_FILE="${RUN_DIR}/k6-summary.json"
SUMMARY_FILE="${RUN_DIR}/run-summary.txt"
POST_RUN_DB_FILE="${RUN_DIR}/post-run-fixture-state.tsv"
POST_RUN_INSPECT_FILE="${RUN_DIR}/post-run-container-state.txt"

app_metrics_collector_pid=""
docker_stats_collector_pid=""
app_log_pid=""
k6_pid=""
app_container=""
mysql_container=""
redis_container=""

mkdir -p "${RUN_DIR}"
printf 'timestamp\tsource\treason\n' > "${OBSERVATION_LOSS_FILE}"
printf 'timestamp\tlabel\tcontainer_id\tsignal_result\n' > "${THREAD_DUMP_EVENTS_FILE}"
printf 'timestamp\ttomcat_busy\ttomcat_current\ttomcat_config_max\thikari_active\thikari_pending\thikari_max\thikari_acquire_max_seconds\thikari_connection_timeouts\tjvm_threads_live\tjvm_threads_peak\tjvm_gc_pause_count\tjvm_gc_pause_seconds_sum\tprocess_cpu_usage\tsystem_cpu_usage\tjvm_heap_used_bytes\tjvm_heap_max_bytes\tprocess_rss_bytes\n' > "${APP_METRICS_FILE}"
printf 'timestamp\tdata_lock_waits\tthreads_connected\tthreads_running\tmax_used_connections\tinnodb_row_lock_current_waits\n' > "${MYSQL_METRICS_FILE}"
printf 'timestamp\tcontainer_id\tcontainer_name\tcpu_percent\tmemory_usage_limit\tnet_io\tblock_io\tpids\n' > "${DOCKER_STATS_FILE}"

timestamp() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

record_loss() {
  printf '%s\t%s\t%s\n' "$(timestamp)" "$1" "$2" >> "${OBSERVATION_LOSS_FILE}"
}

cleanup() {
  local pid
  for pid in "${app_metrics_collector_pid}" "${docker_stats_collector_pid}" "${app_log_pid}"; do
    if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
      kill "${pid}" 2>/dev/null || true
      wait "${pid}" 2>/dev/null || true
    fi
  done
}
trap cleanup EXIT INT TERM

metric_max() {
  local payload="$1"
  local metric_name="$2"
  awk -v name="${metric_name}" '
    ($1 == name || index($1, name "{") == 1) {
      value = $NF + 0
      if (!found || value > max) { max = value; found = 1 }
    }
    END { if (found) print max; else print "NA" }
  ' <<< "${payload}"
}

metric_sum_with_label() {
  local payload="$1"
  local metric_name="$2"
  local label_fragment="$3"
  awk -v name="${metric_name}" -v label="${label_fragment}" '
    index($1, name "{") == 1 && index($1, label) > 0 { sum += $NF; found = 1 }
    END { if (found) print sum; else print "NA" }
  ' <<< "${payload}"
}

parse_prometheus_snapshot() {
  awk '
    BEGIN {
      busy=current=tomcat_max=active=pending=maximum=acquire=timeouts="NA"
      jvm_live=jvm_peak=gc_count=gc_sum=process_cpu=system_cpu="NA"
      heap_used=heap_max=0
      heap_used_seen=heap_max_seen=0
      process_rss="NA"
    }
    /^tomcat_threads_busy_threads/ { busy = $NF }
    /^tomcat_threads_current_threads/ { current = $NF }
    /^tomcat_threads_config_max_threads/ { tomcat_max = $NF }
    /^hikaricp_connections_active/ { active = $NF }
    /^hikaricp_connections_pending/ { pending = $NF }
    /^hikaricp_connections_max/ { maximum = $NF }
    /^hikaricp_connections_acquire_seconds_max/ { acquire = $NF }
    /^hikaricp_connections_timeout_total/ { timeouts = $NF }
    /^jvm_threads_live_threads/ { jvm_live = $NF }
    /^jvm_threads_peak_threads/ { jvm_peak = $NF }
    /^jvm_gc_pause_seconds_count/ { gc_count += $NF; gc_count_seen = 1 }
    /^jvm_gc_pause_seconds_sum/ { gc_sum += $NF; gc_sum_seen = 1 }
    /^process_cpu_usage/ { process_cpu = $NF }
    /^system_cpu_usage/ { system_cpu = $NF }
    /^jvm_memory_used_bytes/ && $0 ~ /area="heap"/ {
      heap_used += $NF; heap_used_seen = 1
    }
    /^jvm_memory_max_bytes/ && $0 ~ /area="heap"/ && $NF >= 0 {
      heap_max += $NF; heap_max_seen = 1
    }
    /^process_resident_memory_bytes/ { process_rss = $NF }
    END {
      if (!gc_count_seen) gc_count = "NA"
      if (!gc_sum_seen) gc_sum = "NA"
      if (!heap_used_seen) heap_used = "NA"
      if (!heap_max_seen) heap_max = "NA"
      print busy "\t" current "\t" tomcat_max "\t" active "\t" pending "\t" maximum "\t" acquire "\t" timeouts "\t" jvm_live "\t" jvm_peak "\t" gc_count "\t" gc_sum "\t" process_cpu "\t" system_cpu "\t" heap_used "\t" heap_max "\t" process_rss
    }
  '
}

mysql_query() {
  MYSQL_PWD="${MYSQL_PASSWORD}" mysql \
    --host="${MYSQL_HOST}" \
    --port="${MYSQL_PORT}" \
    --user="${MYSQL_USER}" \
    --protocol=tcp \
    --batch \
    --skip-column-names \
    --connect-timeout=1 \
    "${MYSQL_DATABASE}" \
    --execute="$1"
}

capture_mysql_lock_snapshot() {
  {
    printf 'captured_at=%s\n\n' "$(timestamp)"
    mysql_query '
      SELECT
        w.REQUESTING_ENGINE_TRANSACTION_ID,
        w.BLOCKING_ENGINE_TRANSACTION_ID,
        rl.OBJECT_SCHEMA AS requesting_schema,
        rl.OBJECT_NAME AS requesting_table,
        rl.LOCK_MODE AS requesting_lock_mode,
        bl.OBJECT_SCHEMA AS blocking_schema,
        bl.OBJECT_NAME AS blocking_table,
        bl.LOCK_MODE AS blocking_lock_mode
      FROM performance_schema.data_lock_waits w
      LEFT JOIN performance_schema.data_locks rl
        ON rl.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
      LEFT JOIN performance_schema.data_locks bl
        ON bl.ENGINE_LOCK_ID = w.BLOCKING_ENGINE_LOCK_ID;
    '
    printf '\n== INNODB STATUS ==\n'
    MYSQL_PWD="${MYSQL_PASSWORD}" mysql \
      --host="${MYSQL_HOST}" --port="${MYSQL_PORT}" --user="${MYSQL_USER}" \
      --protocol=tcp --connect-timeout=1 "${MYSQL_DATABASE}" \
      --execute='SHOW ENGINE INNODB STATUS\G'
  } > "${MYSQL_LOCK_SNAPSHOT_FILE}" 2>&1 || record_loss mysql_lock_snapshot command_failed
}

sample_once() {
  local now payload busy current tomcat_max active pending maximum acquire connection_timeouts
  local jvm_live jvm_peak gc_count gc_sum process_cpu system_cpu heap_used heap_max process_rss prometheus_values
  local mysql_values lock_waits threads_connected threads_running max_used row_lock_waits

  now="$(timestamp)"
  payload="$(curl -fsS --connect-timeout 1 --max-time 1 "${MANAGEMENT_BASE_URL}/actuator/prometheus" 2>/dev/null || true)"
  if [[ -z "${payload}" ]]; then
    record_loss actuator_prometheus no_response
    busy=NA; current=NA; tomcat_max=NA; active=NA; pending=NA; maximum=NA; acquire=NA; connection_timeouts=NA
    jvm_live=NA; jvm_peak=NA; gc_count=NA; gc_sum=NA; process_cpu=NA; system_cpu=NA; heap_used=NA; heap_max=NA; process_rss=NA
  else
    prometheus_values="$(parse_prometheus_snapshot <<< "${payload}")"
    IFS=$'\t' read -r busy current tomcat_max active pending maximum acquire connection_timeouts \
      jvm_live jvm_peak gc_count gc_sum process_cpu system_cpu heap_used heap_max process_rss <<< "${prometheus_values}"
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "${now}" "${busy}" "${current}" "${tomcat_max}" "${active}" "${pending}" "${maximum}" "${acquire}" "${connection_timeouts}" \
    "${jvm_live}" "${jvm_peak}" "${gc_count}" "${gc_sum}" "${process_cpu}" "${system_cpu}" "${heap_used}" "${heap_max}" "${process_rss}" \
    >> "${APP_METRICS_FILE}"

  mysql_values="$(mysql_query "
    SELECT
      (SELECT COUNT(*) FROM performance_schema.data_lock_waits),
      MAX(CASE WHEN VARIABLE_NAME = 'Threads_connected' THEN VARIABLE_VALUE END),
      MAX(CASE WHEN VARIABLE_NAME = 'Threads_running' THEN VARIABLE_VALUE END),
      MAX(CASE WHEN VARIABLE_NAME = 'Max_used_connections' THEN VARIABLE_VALUE END),
      MAX(CASE WHEN VARIABLE_NAME = 'Innodb_row_lock_current_waits' THEN VARIABLE_VALUE END)
    FROM performance_schema.global_status;
  " 2>/dev/null || true)"
  if [[ "${mysql_values}" =~ ^[0-9]+$'\t'([0-9]+|NULL)$'\t'([0-9]+|NULL)$'\t'([0-9]+|NULL)$'\t'([0-9]+|NULL)$ ]]; then
    IFS=$'\t' read -r lock_waits threads_connected threads_running max_used row_lock_waits <<< "${mysql_values}"
  else
    record_loss mysql_metrics query_failed
    lock_waits=NA; threads_connected=NA; threads_running=NA; max_used=NA; row_lock_waits=NA
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
    "${now}" "${lock_waits}" "${threads_connected}" "${threads_running}" "${max_used}" "${row_lock_waits}" \
    >> "${MYSQL_METRICS_FILE}"
  if [[ "${lock_waits}" =~ ^[1-9][0-9]*$ && ! -s "${MYSQL_LOCK_SNAPSHOT_FILE}" ]]; then
    capture_mysql_lock_snapshot
  fi

}

app_metrics_collector_loop() {
  local sample_started sample_finished remaining_seconds
  while :; do
    sample_started="$(date +%s)"
    sample_once
    sample_finished="$(date +%s)"
    remaining_seconds="$(( sample_started + SAMPLE_INTERVAL_SECONDS - sample_finished ))"
    if (( remaining_seconds > 0 )); then
      sleep "${remaining_seconds}"
    fi
  done
}

docker_stats_collector_loop() {
  local sample_timestamp docker_values
  while :; do
    sample_timestamp="$(timestamp)"
    docker_values="$(docker stats --no-stream \
      --format '{{.ID}}\t{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}\t{{.BlockIO}}\t{{.PIDs}}' \
      "${app_container}" "${mysql_container}" "${redis_container}" 2>> "${RUN_DIR}/docker-stats.stderr" || true)"
    if [[ -z "${docker_values}" ]]; then
      record_loss docker_stats no_response
    else
      awk -v now="${sample_timestamp}" 'BEGIN { FS=OFS="\t" } { print now, $0 }' \
        <<< "${docker_values}" >> "${DOCKER_STATS_FILE}"
    fi
    sleep "${SAMPLE_INTERVAL_SECONDS}"
  done
}

wait_until_healthy() {
  local attempt payload observed_hikari
  for attempt in $(seq 1 "${APP_READY_TIMEOUT_SECONDS}"); do
    if curl -fsS --connect-timeout 1 --max-time 2 "${MANAGEMENT_BASE_URL}/actuator/health" > "${RUN_DIR}/health-${attempt}.json" 2>/dev/null; then
      payload="$(curl -fsS --connect-timeout 1 --max-time 2 "${MANAGEMENT_BASE_URL}/actuator/prometheus" 2>/dev/null || true)"
      observed_hikari="$(metric_max "${payload}" hikaricp_connections_max)"
      if [[ "${observed_hikari}" == "${HIKARI_POOL_SIZE}" ]]; then
        printf '%s\n' "${payload}" > "${RUN_DIR}/preflight-prometheus.txt"
        return 0
      fi
    fi
    sleep 1
  done
  echo "${APP_READY_TIMEOUT_SECONDS}초 안에 앱 health 또는 Hikari max=${HIKARI_POOL_SIZE} 확인에 실패했습니다." >&2
  return 1
}

warm_reservation_path() {
  local warmup_log="${RUN_DIR}/reservation-path-warmup.log"
  local warmup_summary="${RUN_DIR}/reservation-path-warmup-summary.json"

  if [[ "${RESERVATION_PATH_WARMUP}" == "false" ]]; then
    printf 'reservation_path_warmup=skipped\n' >> "${MANIFEST_FILE}"
    return 0
  fi

  if [[ "${K6_EXECUTION_MODE}" == "host" ]]; then
    k6 run \
      -e "BASE_URL=${BASE_URL}" \
      -e "JWT_SECRET=${JWT_SECRET}" \
      -e "REQUEST_TIMEOUT=${REQUEST_TIMEOUT}" \
      --summary-export "${warmup_summary}" \
      "${ROOT_DIR}/scripts/test/08-reservation-path-warmup.js" \
      > "${warmup_log}" 2>&1
  else
    docker run --rm \
      --name "${K6_DOCKER_CONTAINER_NAME}-warmup" \
      --network "${K6_DOCKER_NETWORK}" \
      -v "${ROOT_DIR}/scripts/test:/scripts:ro" \
      -v "${RUN_DIR}:/results" \
      "${K6_DOCKER_IMAGE}" run \
      -e "BASE_URL=${K6_DOCKER_BASE_URL}" \
      -e "JWT_SECRET=${JWT_SECRET}" \
      -e "REQUEST_TIMEOUT=${REQUEST_TIMEOUT}" \
      --summary-export /results/reservation-path-warmup-summary.json \
      /scripts/08-reservation-path-warmup.js \
      > "${warmup_log}" 2>&1
  fi

  printf 'reservation_path_warmup=passed\n' >> "${MANIFEST_FILE}"
}

peak_column() {
  local file="$1"
  local column="$2"
  awk -F '\t' -v column="${column}" '
    NR > 1 && $column != "NA" && $column != "" {
      value = $column + 0
      if (!seen || value > max) { max = value; seen = 1 }
    }
    END { if (seen) print max; else print "NA" }
  ' "${file}"
}

line_count_without_header() {
  local file="$1"
  awk 'END { print (NR > 0 ? NR - 1 : 0) }' "${file}"
}

summarize_k6() {
  if [[ ! -s "${K6_SUMMARY_FILE}" ]]; then
    printf 'k6_summary=missing\n'
    return
  fi
  jq -r '
    .metrics as $metrics |
    [
      "k6_http_reqs=" + (($metrics.http_reqs.count // "NA") | tostring),
      "k6_http_req_failed_rate=" + (($metrics.http_req_failed.value // "NA") | tostring),
      "k6_http_req_duration_p95_ms=" + (($metrics.http_req_duration["p(95)"] // "NA") | tostring),
      "k6_http_req_duration_p99_ms=" + (($metrics.http_req_duration["p(99)"] // "NA") | tostring),
      "k6_reservation_attempts=" + (($metrics.diagnostic_reservation_attempts.count // "NA") | tostring),
      "k6_reservation_duration_p95_ms=" + (($metrics.diagnostic_reservation_duration["p(95)"] // "NA") | tostring),
      "k6_request_start_lag_p95_ms=" + (($metrics.diagnostic_request_start_lag["p(95)"] // "NA") | tostring)
    ] | .[]
  ' "${K6_SUMMARY_FILE}"
  jq -r '
    .metrics
    | to_entries[]
    | select(.key | startswith("diagnostic_outcome_bucket") or startswith("diagnostic_reservation_") or startswith("diagnostic_429_") or startswith("diagnostic_transport_") or startswith("diagnostic_unexpected_http"))
    | "k6_metric_" + .key + "=" + ((.value.count // .value.value // "NA") | tostring)
  ' "${K6_SUMMARY_FILE}"
}

printf 'run_id=%s\nrun_label=%s\n' "${RUN_ID}" "${RUN_LABEL}" > "${MANIFEST_FILE}"
{
  printf 'started_at=%s\n' "$(timestamp)"
  printf 'configured_lock_strategy=%s\n' "${LOCK_STRATEGY}"
  printf 'configured_lock_reentrant_wait_timeout_millis=%s\n' "${LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS}"
  printf 'configured_hikari_pool_size=%s\n' "${HIKARI_POOL_SIZE}"
  printf 'configured_admission_per_seat_permits=%s\n' "${ADMISSION_PER_SEAT_PERMITS}"
  printf 'configured_concurrency=%s\n' "${CONCURRENCY}"
  printf 'configured_request_timeout=%s\n' "${REQUEST_TIMEOUT}"
  printf 'configured_app_ready_timeout_seconds=%s\n' "${APP_READY_TIMEOUT_SECONDS}"
  printf 'configured_reservation_path_warmup=%s\n' "${RESERVATION_PATH_WARMUP}"
  printf 'configured_management_base_url=%s\n' "${MANAGEMENT_BASE_URL}"
  printf 'configured_management_server_port=%s\n' "${MANAGEMENT_SERVER_PORT:-same-business-port}"
  printf 'configured_prometheus_config_file=%s\n' "${PROMETHEUS_CONFIG_FILE}"
  printf 'configured_recreate_prometheus=%s\n' "${RECREATE_PROMETHEUS}"
  printf 'configured_build_app_image=%s\n' "${BUILD_APP_IMAGE}"
  printf 'configured_k6_execution_mode=%s\n' "${K6_EXECUTION_MODE}"
  if [[ "${K6_EXECUTION_MODE}" == "docker" ]]; then
    printf 'configured_k6_docker_image=%s\n' "${K6_DOCKER_IMAGE}"
    printf 'configured_k6_docker_cpus=%s\n' "${K6_DOCKER_CPUS}"
    printf 'configured_k6_docker_network=%s\n' "${K6_DOCKER_NETWORK}"
    printf 'configured_k6_docker_base_url=%s\n' "${K6_DOCKER_BASE_URL}"
  fi
  printf 'git_revision=%s\n' "$(git -C "${ROOT_DIR}" rev-parse HEAD)"
  printf '\n[git_status]\n'
  git -C "${ROOT_DIR}" status --short
} >> "${MANIFEST_FILE}"

if [[ "${BUILD_APP_IMAGE}" == "true" ]]; then
  docker compose -f "${ROOT_DIR}/docker-compose.yml" build app \
    > "${RUN_DIR}/compose-build.log" 2>&1
else
  docker image inspect imticket-app --format 'skipped_build_existing_image={{.Id}}' \
    > "${RUN_DIR}/compose-build.log" 2>&1
fi

compose_app_env=(
  "LOCK_STRATEGY=${LOCK_STRATEGY}"
  "LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS=${LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS}"
  "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=${HIKARI_POOL_SIZE}"
  "RESERVATION_ADMISSION_PER_SEAT_PERMITS=${ADMISSION_PER_SEAT_PERMITS}"
)
if [[ -n "${MANAGEMENT_SERVER_PORT}" ]]; then
  compose_app_env+=("MANAGEMENT_SERVER_PORT=${MANAGEMENT_SERVER_PORT}")
fi
env "${compose_app_env[@]}" docker compose -f "${ROOT_DIR}/docker-compose.yml" up -d --force-recreate --no-deps app \
  > "${RUN_DIR}/compose-up.log" 2>&1

app_container="$(docker compose -f "${ROOT_DIR}/docker-compose.yml" ps -q app)"
mysql_container="$(docker compose -f "${ROOT_DIR}/docker-compose.yml" ps -q mysql)"
redis_container="$(docker compose -f "${ROOT_DIR}/docker-compose.yml" ps -q redis)"
if [[ -z "${app_container}" || -z "${mysql_container}" || -z "${redis_container}" ]]; then
  echo "app/mysql/redis container ID를 확인하지 못했습니다." >&2
  exit 1
fi
wait_until_healthy
warm_reservation_path

if [[ "${RECREATE_PROMETHEUS}" == "true" ]]; then
  PROMETHEUS_CONFIG_FILE="${PROMETHEUS_CONFIG_FILE}" \
    docker compose -f "${ROOT_DIR}/docker-compose.yml" up -d --force-recreate --no-deps prometheus \
    > "${RUN_DIR}/prometheus-compose-up.log" 2>&1
fi

{
  printf '\n[container_after_recreate]\n'
  docker inspect --format 'app_id={{.Id}} image={{.Image}} started_at={{.State.StartedAt}} restart_count={{.RestartCount}} oom_killed={{.State.OOMKilled}}' "${app_container}"
  docker inspect --format 'mysql_id={{.Id}} image={{.Image}} started_at={{.State.StartedAt}} restart_count={{.RestartCount}} oom_killed={{.State.OOMKilled}}' "${mysql_container}"
  docker inspect --format 'redis_id={{.Id}} image={{.Image}} started_at={{.State.StartedAt}} restart_count={{.RestartCount}} oom_killed={{.State.OOMKilled}}' "${redis_container}"
} >> "${MANIFEST_FILE}"

fixture_output="$(MYSQL_HOST="${MYSQL_HOST}" MYSQL_PORT="${MYSQL_PORT}" MYSQL_USER="${MYSQL_USER}" \
  MYSQL_DATABASE="${MYSQL_DATABASE}" MYSQL_PASSWORD="${MYSQL_PASSWORD}" \
  "${ROOT_DIR}/scripts/test/seed_pessimistic_lock_fixture.sh")"
printf '%s\n' "${fixture_output}" > "${FIXTURE_FILE}"
fixture_line="$(printf '%s\n' "${fixture_output}" | tail -n 1)"
wallet_address="$(awk '{print $1}' <<< "${fixture_line}")"
performance_time_id="$(awk '{print $2}' <<< "${fixture_line}")"
seat_id="$(awk '{print $3}' <<< "${fixture_line}")"
if [[ "${wallet_address}" != "0xLoadTestUser" ]] \
  || [[ ! "${performance_time_id}" =~ ^[1-9][0-9]*$ ]] \
  || [[ ! "${seat_id}" =~ ^[1-9][0-9]*$ ]]; then
  echo "새 fixture 출력 형식 또는 값이 올바르지 않습니다: ${fixture_line}" >&2
  exit 1
fi
printf '\nfixture_performance_time_id=%s\nfixture_hot_seat_id=%s\n' "${performance_time_id}" "${seat_id}" >> "${MANIFEST_FILE}"

docker logs --timestamps -f "${app_container}" > "${APP_LOG_FILE}" 2>&1 &
app_log_pid=$!
app_metrics_collector_loop &
app_metrics_collector_pid=$!
docker_stats_collector_loop &
docker_stats_collector_pid=$!

# 관측기 자체의 무부하 손실률을 먼저 기록한다. 실패가 있으면 부하를 보내지 않는다.
sleep "${IDLE_SECONDS}"
idle_loss_count="$(line_count_without_header "${OBSERVATION_LOSS_FILE}")"
if (( idle_loss_count > 0 )); then
  echo "idle 구간에서 관측 손실 ${idle_loss_count}건이 발생했습니다. 부하를 시작하지 않습니다." >&2
  exit 1
fi

start_at_epoch_ms="$(( $(date +%s) * 1000 + 12000 ))"
printf 'scheduled_burst_start_at_epoch_ms=%s\n' "${start_at_epoch_ms}" >> "${MANIFEST_FILE}"

set +e
if [[ "${K6_EXECUTION_MODE}" == "host" ]]; then
  k6 run \
    -e "BASE_URL=${BASE_URL}" \
    -e "PT_ID=${performance_time_id}" \
    -e "SEAT_ID=${seat_id}" \
    -e "CONCURRENCY=${CONCURRENCY}" \
    -e "START_AT_EPOCH_MS=${start_at_epoch_ms}" \
    -e "JWT_SECRET=${JWT_SECRET}" \
    -e "REQUEST_TIMEOUT=${REQUEST_TIMEOUT}" \
    -e "MAX_DURATION=${MAX_DURATION}" \
    --summary-export "${K6_SUMMARY_FILE}" \
    "${ROOT_DIR}/scripts/test/07-2000vu-diagnosis.js" \
    > "${K6_CONSOLE_FILE}" 2>&1 &
else
  docker run --rm \
    --name "${K6_DOCKER_CONTAINER_NAME}" \
    --cpus "${K6_DOCKER_CPUS}" \
    --network "${K6_DOCKER_NETWORK}" \
    -v "${ROOT_DIR}/scripts/test:/scripts:ro" \
    -v "${RUN_DIR}:/results" \
    "${K6_DOCKER_IMAGE}" run \
    -e "BASE_URL=${K6_DOCKER_BASE_URL}" \
    -e "PT_ID=${performance_time_id}" \
    -e "SEAT_ID=${seat_id}" \
    -e "CONCURRENCY=${CONCURRENCY}" \
    -e "START_AT_EPOCH_MS=${start_at_epoch_ms}" \
    -e "JWT_SECRET=${JWT_SECRET}" \
    -e "REQUEST_TIMEOUT=${REQUEST_TIMEOUT}" \
    -e "MAX_DURATION=${MAX_DURATION}" \
    --summary-export /results/k6-summary.json \
    /scripts/07-2000vu-diagnosis.js \
    > "${K6_CONSOLE_FILE}" 2>&1 &
fi
k6_pid=$!
set -e

# k6에는 절대 시각을 전달했으므로 dump signal도 같은 burst 기준으로 맞춘다.
sleep 14
if [[ "${K6_EXECUTION_MODE}" == "docker" ]]; then
  docker inspect --format 'k6_container_id={{.Id}} k6_cpu_quota={{.HostConfig.NanoCpus}} k6_network={{range $key, $_ := .NetworkSettings.Networks}}{{$key}}{{end}}' \
    "${K6_DOCKER_CONTAINER_NAME}" >> "${MANIFEST_FILE}" 2>/dev/null \
    || record_loss k6_container_inspect unavailable_at_t_plus_2
fi
for dump_label in t_plus_2_seconds t_plus_3_seconds; do
  if docker kill --signal=QUIT "${app_container}" > /dev/null 2>&1; then
    printf '%s\t%s\t%s\tsent\n' "$(timestamp)" "${dump_label}" "${app_container}" >> "${THREAD_DUMP_EVENTS_FILE}"
  else
    printf '%s\t%s\t%s\tfailed\n' "$(timestamp)" "${dump_label}" "${app_container}" >> "${THREAD_DUMP_EVENTS_FILE}"
    record_loss jvm_thread_dump signal_failed
  fi
  [[ "${dump_label}" == "t_plus_2_seconds" ]] && sleep 1
done

set +e
wait "${k6_pid}"
k6_status=$?
set -e
k6_pid=""
sleep "${TAIL_SECONDS}"

cleanup
app_metrics_collector_pid=""
docker_stats_collector_pid=""
app_log_pid=""

run_started_at="$(sed -n 's/^started_at=//p' "${MANIFEST_FILE}" | head -n 1)"
if ! docker logs --timestamps --since "${run_started_at}" "${mysql_container}" > "${MYSQL_LOG_FILE}" 2>&1; then
  record_loss mysql_container_log command_failed
fi
if ! docker inspect --format 'app_restart_count={{.RestartCount}} app_oom_killed={{.State.OOMKilled}} app_status={{.State.Status}}' "${app_container}" > "${POST_RUN_INSPECT_FILE}"; then
  record_loss docker_inspect_post_run command_failed
fi
if ! mysql_query "
  SELECT s.id, s.seat_status, s.is_reservation, s.version, COUNT(rs.id) AS reserved_seat_rows
  FROM Seat s
  LEFT JOIN ReservedSeat rs ON rs.seat_id = s.id
  WHERE s.id = ${seat_id}
  GROUP BY s.id, s.seat_status, s.is_reservation, s.version;
" > "${POST_RUN_DB_FILE}" 2>&1; then
  record_loss post_run_fixture_verification query_failed
fi

dump_event_count="$(line_count_without_header "${THREAD_DUMP_EVENTS_FILE}")"
dump_header_count="$( (rg -c 'Full thread dump|Java Thread Dump' "${APP_LOG_FILE}" 2>/dev/null || true) | awk 'NF { total += $1 } END { print total + 0 }')"
{
  printf 'run_id=%s\n' "${RUN_ID}"
  printf 'k6_exit_code=%s\n' "${k6_status}"
  printf 'configured_concurrency=%s\n' "${CONCURRENCY}"
  printf 'fixture_performance_time_id=%s\n' "${performance_time_id}"
  printf 'fixture_hot_seat_id=%s\n' "${seat_id}"
  printf 'observation_loss_count=%s\n' "$(line_count_without_header "${OBSERVATION_LOSS_FILE}")"
  printf 'thread_dump_signal_events=%s\n' "${dump_event_count}"
  printf 'thread_dump_log_headers=%s\n' "${dump_header_count}"
  printf 'tomcat_busy_peak=%s\n' "$(peak_column "${APP_METRICS_FILE}" 2)"
  printf 'tomcat_current_peak=%s\n' "$(peak_column "${APP_METRICS_FILE}" 3)"
  printf 'tomcat_config_max_peak=%s\n' "$(peak_column "${APP_METRICS_FILE}" 4)"
  printf 'hikari_active_peak=%s\n' "$(peak_column "${APP_METRICS_FILE}" 5)"
  printf 'hikari_pending_peak=%s\n' "$(peak_column "${APP_METRICS_FILE}" 6)"
  printf 'hikari_acquire_max_seconds_peak=%s\n' "$(peak_column "${APP_METRICS_FILE}" 8)"
  printf 'jvm_threads_live_peak=%s\n' "$(peak_column "${APP_METRICS_FILE}" 10)"
  printf 'jvm_gc_pause_seconds_sum_peak=%s\n' "$(peak_column "${APP_METRICS_FILE}" 13)"
  printf 'process_cpu_peak=%s\n' "$(peak_column "${APP_METRICS_FILE}" 14)"
  printf 'system_cpu_peak=%s\n' "$(peak_column "${APP_METRICS_FILE}" 15)"
  printf 'jvm_heap_used_bytes_peak=%s\n' "$(peak_column "${APP_METRICS_FILE}" 16)"
  printf 'process_rss_bytes_peak=%s\n' "$(peak_column "${APP_METRICS_FILE}" 18)"
  printf 'mysql_data_lock_waits_peak=%s\n' "$(peak_column "${MYSQL_METRICS_FILE}" 2)"
  summarize_k6
  printf 'manifest=%s\napp_metrics=%s\nmysql_metrics=%s\ndocker_stats=%s\nobservation_loss=%s\nthread_dump_events=%s\napp_log=%s\nk6_summary=%s\n' \
    "${MANIFEST_FILE}" "${APP_METRICS_FILE}" "${MYSQL_METRICS_FILE}" "${DOCKER_STATS_FILE}" \
    "${OBSERVATION_LOSS_FILE}" "${THREAD_DUMP_EVENTS_FILE}" "${APP_LOG_FILE}" "${K6_SUMMARY_FILE}"
} > "${SUMMARY_FILE}"

printf 'diagnosis_run_dir=%s\n' "${RUN_DIR}"
sed -n '1,240p' "${SUMMARY_FILE}"

# k6 threshold는 사용하지 않으므로 non-zero는 실행 자체가 완결되지 못한 경우다.
exit "${k6_status}"
