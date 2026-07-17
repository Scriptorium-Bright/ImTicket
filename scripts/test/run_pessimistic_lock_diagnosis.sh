#!/usr/bin/env bash
set -euo pipefail

# 같은 좌석의 비관적 락 대기가 Hikari pool과 HTTP 요청까지 전파되는지 기록한다.
# 실행 전 대상 좌석은 AVAILABLE 상태여야 하며, 애플리케이션은 LOCK_STRATEGY=pessimistic으로 기동한다.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASE_URL="${BASE_URL:-http://127.0.0.1:10080}"
PT_ID="${PT_ID:-}"
SEAT_ID="${SEAT_ID:-}"
JWT_SECRET="${JWT_SECRET:-}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-10047}"
MYSQL_USER="${MYSQL_USER:-capstone}"
MYSQL_DATABASE="${MYSQL_DATABASE:-capstone}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-${MYSQL_LOCK_TEST_PASSWORD:-}}"
HIKARI_POOL_SIZE="${HIKARI_POOL_SIZE:-30}"
CONCURRENCY="${CONCURRENCY:-50}"
HOLD_SECONDS="${HOLD_SECONDS:-12}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-30s}"
SAMPLE_INTERVAL_SECONDS="${SAMPLE_INTERVAL_SECONDS:-0.2}"
RESULT_DIR="${RESULT_DIR:-${ROOT_DIR}/build/k6-results}"

if [[ ! "${PT_ID}" =~ ^[1-9][0-9]*$ ]] || [[ ! "${SEAT_ID}" =~ ^[1-9][0-9]*$ ]]; then
  echo "PT_ID와 SEAT_ID는 양의 정수여야 합니다." >&2
  exit 1
fi
if [[ -z "${JWT_SECRET}" ]]; then
  echo "JWT_SECRET을 설정해야 합니다." >&2
  exit 1
fi
if [[ -z "${MYSQL_PASSWORD}" ]]; then
  echo "MYSQL_PASSWORD 또는 MYSQL_LOCK_TEST_PASSWORD를 설정해야 합니다." >&2
  exit 1
fi
if [[ ! "${HIKARI_POOL_SIZE}" =~ ^[1-9][0-9]*$ ]] || [[ ! "${CONCURRENCY}" =~ ^[1-9][0-9]*$ ]]; then
  echo "HIKARI_POOL_SIZE와 CONCURRENCY는 양의 정수여야 합니다." >&2
  exit 1
fi
if (( CONCURRENCY <= HIKARI_POOL_SIZE )); then
  echo "CONCURRENCY는 HIKARI_POOL_SIZE보다 커야 Hikari pending을 관측할 수 있습니다." >&2
  exit 1
fi
if [[ ! "${HOLD_SECONDS}" =~ ^[1-9][0-9]*$ ]] || (( HOLD_SECONDS < 5 )); then
  echo "HOLD_SECONDS는 lock wait와 Hikari pending을 안정적으로 관측할 수 있도록 5초 이상의 정수여야 합니다." >&2
  exit 1
fi

RUN_ID="pessimistic-diagnosis-$(date -u +%Y%m%dT%H%M%SZ)"
RUN_DIR="${RESULT_DIR}/${RUN_ID}"
APP_METRICS_FILE="${RUN_DIR}/app-metrics.tsv"
MYSQL_WAITS_FILE="${RUN_DIR}/mysql-lock-waits.tsv"
MYSQL_SNAPSHOT_FILE="${RUN_DIR}/mysql-lock-wait-snapshot.log"
K6_LOG_FILE="${RUN_DIR}/k6-console.log"
SUMMARY_FILE="${RUN_DIR}/diagnosis-summary.txt"
EVIDENCE_FILE="${RUN_DIR}/lock-wait-evidence.txt"
sampler_pid=""

mkdir -p "${RUN_DIR}"
printf 'timestamp\ttomcat_busy\ttomcat_current\thikari_active\thikari_pending\thikari_max\tacquire_max_seconds\tconnection_timeouts\n' > "${APP_METRICS_FILE}"
printf 'timestamp\tdata_lock_waits\n' > "${MYSQL_WAITS_FILE}"

cleanup() {
  if [[ -n "${sampler_pid}" ]] && kill -0 "${sampler_pid}" 2>/dev/null; then
    kill "${sampler_pid}" 2>/dev/null || true
    wait "${sampler_pid}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

prometheus_value() {
  local metric_name="$1"
  awk -v name="${metric_name}" '
    ($1 == name || index($1, name "{") == 1) {
      value = $2 + 0
      if (!found || value > max) { max = value; found = 1 }
    }
    END { if (found) print max; else print "NA" }
  '
}

is_number() {
  [[ "$1" =~ ^[0-9]+([.][0-9]+)?$ ]]
}

is_at_least() {
  local actual="$1"
  local expected="$2"
  awk -v actual="${actual}" -v expected="${expected}" \
    'BEGIN { exit !(actual + 0 >= expected + 0) }'
}

mysql_lock_wait_count() {
  local count
  count="$(MYSQL_PWD="${MYSQL_PASSWORD}" mysql \
    --host="${MYSQL_HOST}" \
    --port="${MYSQL_PORT}" \
    --user="${MYSQL_USER}" \
    --protocol=tcp \
    --batch \
    --skip-column-names \
    --connect-timeout=1 \
    "${MYSQL_DATABASE}" \
    --execute='SELECT COUNT(*) FROM performance_schema.data_lock_waits;' \
    2>/dev/null || true)"
  count="${count//$'\n'/}"
  if [[ "${count}" =~ ^[0-9]+$ ]]; then
    printf '%s\n' "${count}"
  else
    printf 'NA\n'
  fi
}

capture_mysql_lock_wait_snapshot() {
  {
    printf 'captured_at=%s\n\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    MYSQL_PWD="${MYSQL_PASSWORD}" mysql \
      --host="${MYSQL_HOST}" \
      --port="${MYSQL_PORT}" \
      --user="${MYSQL_USER}" \
      --protocol=tcp \
      --table \
      "${MYSQL_DATABASE}" \
      --execute="
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
      " || true
    printf '\n== INNODB STATUS ==\n'
    MYSQL_PWD="${MYSQL_PASSWORD}" mysql \
      --host="${MYSQL_HOST}" \
      --port="${MYSQL_PORT}" \
      --user="${MYSQL_USER}" \
      --protocol=tcp \
      --execute='SHOW ENGINE INNODB STATUS\G' || true
  } > "${MYSQL_SNAPSHOT_FILE}" 2>&1
}

sample_metrics() {
  local snapshot_captured=false
  while :; do
    local now payload busy current active pending maximum acquire timeouts lock_waits
    now="$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)"
    payload="$(curl -fsS --connect-timeout 1 --max-time 2 "${BASE_URL}/actuator/prometheus" 2>/dev/null || true)"

    if [[ -z "${payload}" ]]; then
      busy=NA
      current=NA
      active=NA
      pending=NA
      maximum=NA
      acquire=NA
      timeouts=NA
    else
      busy="$(printf '%s\n' "${payload}" | prometheus_value tomcat_threads_busy_threads)"
      current="$(printf '%s\n' "${payload}" | prometheus_value tomcat_threads_current_threads)"
      active="$(printf '%s\n' "${payload}" | prometheus_value hikaricp_connections_active)"
      pending="$(printf '%s\n' "${payload}" | prometheus_value hikaricp_connections_pending)"
      maximum="$(printf '%s\n' "${payload}" | prometheus_value hikaricp_connections_max)"
      acquire="$(printf '%s\n' "${payload}" | prometheus_value hikaricp_connections_acquire_seconds_max)"
      timeouts="$(printf '%s\n' "${payload}" | prometheus_value hikaricp_connections_timeout_total)"
    fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
      "${now}" "${busy}" "${current}" "${active}" "${pending}" "${maximum}" "${acquire}" "${timeouts}" \
      >> "${APP_METRICS_FILE}"

    lock_waits="$(mysql_lock_wait_count)"
    printf '%s\t%s\n' "${now}" "${lock_waits}" >> "${MYSQL_WAITS_FILE}"
    if [[ "${snapshot_captured}" == false ]] && [[ "${lock_waits}" =~ ^[1-9][0-9]*$ ]]; then
      capture_mysql_lock_wait_snapshot
      snapshot_captured=true
    fi

    sleep "${SAMPLE_INTERVAL_SECONDS}"
  done
}

peak_column() {
  local file="$1"
  local column="$2"
  awk -F '\t' -v column="${column}" '
    NR > 1 && $column != "NA" && $column != "" {
      value = $column + 0
      if (!seen || value > max) {
        max = value
        seen = 1
      }
    }
    END {
      if (seen) print max
      else print "NA"
    }
  ' "${file}"
}

peak_sample() {
  local file="$1"
  local column="$2"
  awk -F '\t' -v column="${column}" '
    NR > 1 && $column != "NA" && $column != "" {
      value = $column + 0
      if (!seen || value > max) {
        max = value
        line = $0
        seen = 1
      }
    }
    END {
      if (seen) print line
      else print "NA"
    }
  ' "${file}"
}

append_k6_metrics() {
  local summary_file="$1"
  if [[ -z "${summary_file}" ]] || [[ ! -f "${summary_file}" ]]; then
    printf 'k6_summary=NA (summary file not found)\n'
    return
  fi
  if ! command -v jq >/dev/null 2>&1; then
    printf 'k6_summary=%s\n' "${summary_file}"
    printf 'k6_metrics=NA (jq is not installed)\n'
    return
  fi

  jq -r '
    .metrics as $m |
    "k6_summary=" + input_filename,
    "k6_http_reqs=" + (($m.http_reqs.count // "NA") | tostring),
    "k6_http_req_duration_avg_ms=" + (($m.http_req_duration.avg // "NA") | tostring),
    "k6_http_req_duration_p95_ms=" + (($m.http_req_duration["p(95)"] // "NA") | tostring),
    "k6_http_req_duration_p99_ms=" + (($m.http_req_duration["p(99)"] // "NA") | tostring),
    "k6_reservation_success=" + (($m.reservation_success.count // "NA") | tostring),
    "k6_reservation_conflict=" + (($m.reservation_conflict.count // "NA") | tostring),
    "k6_reservation_internal_error=" + (($m.reservation_internal_error.count // "NA") | tostring),
    "k6_unexpected_response=" + (($m.unexpected_response.count // "NA") | tostring),
    "k6_http_req_failed_rate=" + (($m.http_req_failed.value // "NA") | tostring)
  ' "${summary_file}"
}

preflight_payload="$(curl -fsS --connect-timeout 1 --max-time 3 "${BASE_URL}/actuator/prometheus" 2>/dev/null || true)"
if [[ -z "${preflight_payload}" ]]; then
  echo "${BASE_URL}/actuator/prometheus에서 사전 지표를 읽지 못했습니다." >&2
  exit 1
fi

observed_hikari_max="$(printf '%s\n' "${preflight_payload}" | prometheus_value hikaricp_connections_max)"
if ! is_number "${observed_hikari_max}"; then
  echo "hikaricp_connections_max 지표를 찾지 못했습니다. Prometheus와 Hikari metrics 노출을 확인하세요." >&2
  exit 1
fi
if ! is_at_least "${observed_hikari_max}" "${HIKARI_POOL_SIZE}" \
  || ! is_at_least "${HIKARI_POOL_SIZE}" "${observed_hikari_max}"; then
  echo "실행 중인 Hikari max(${observed_hikari_max})와 HIKARI_POOL_SIZE(${HIKARI_POOL_SIZE})가 다릅니다." >&2
  echo "앱을 SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=${HIKARI_POOL_SIZE}로 재기동한 뒤 실행하세요." >&2
  exit 1
fi

if [[ "$(mysql_lock_wait_count)" == "NA" ]]; then
  echo "performance_schema.data_lock_waits를 조회할 수 없습니다. MySQL 권한과 performance_schema를 확인하세요." >&2
  exit 1
fi

printf 'diagnosis_run_dir=%s\n' "${RUN_DIR}"
printf 'live_app_metrics=tail -f %s\n' "${APP_METRICS_FILE}"
printf 'live_mysql_lock_waits=tail -f %s\n' "${MYSQL_WAITS_FILE}"

sample_metrics &
sampler_pid=$!

set +e
MODE=forced-timeout \
GRADE=1 \
TRAFFIC_PROFILE=minimum \
CONCURRENCY="${CONCURRENCY}" \
PT_ID="${PT_ID}" \
SEAT_ID="${SEAT_ID}" \
BASE_URL="${BASE_URL}" \
JWT_SECRET="${JWT_SECRET}" \
MYSQL_HOST="${MYSQL_HOST}" \
MYSQL_PORT="${MYSQL_PORT}" \
MYSQL_USER="${MYSQL_USER}" \
MYSQL_DATABASE="${MYSQL_DATABASE}" \
MYSQL_PASSWORD="${MYSQL_PASSWORD}" \
HOLD_SECONDS="${HOLD_SECONDS}" \
REQUEST_TIMEOUT="${REQUEST_TIMEOUT}" \
MAX_DURATION=2m \
RESULT_DIR="${RUN_DIR}/k6" \
  "${ROOT_DIR}/scripts/test/run_pessimistic_lock_k6.sh" > "${K6_LOG_FILE}" 2>&1
k6_status=$?
set -e

cleanup
sampler_pid=""

active_peak="$(peak_column "${APP_METRICS_FILE}" 4)"
pending_peak="$(peak_column "${APP_METRICS_FILE}" 5)"
maximum_peak="$(peak_column "${APP_METRICS_FILE}" 6)"
tomcat_busy_peak="$(peak_column "${APP_METRICS_FILE}" 2)"
lock_wait_peak="$(peak_column "${MYSQL_WAITS_FILE}" 2)"
hikari_pending_peak_sample="$(peak_sample "${APP_METRICS_FILE}" 5)"
mysql_lock_wait_peak_sample="$(peak_sample "${MYSQL_WAITS_FILE}" 2)"
k6_summary_file="$(find "${RUN_DIR}/k6" -maxdepth 1 -type f -name '*.json' -print -quit 2>/dev/null || true)"

{
  printf 'run_id=%s\n' "${RUN_ID}"
  printf 'k6_exit_code=%s\n' "${k6_status}"
  printf 'configured_hikari_pool_size=%s\n' "${HIKARI_POOL_SIZE}"
  printf 'configured_concurrency=%s\n' "${CONCURRENCY}"
  printf 'configured_lock_hold_seconds=%s\n' "${HOLD_SECONDS}"
  printf 'tomcat_busy_peak=%s\n' "${tomcat_busy_peak}"
  printf 'hikari_active_peak=%s\n' "${active_peak}"
  printf 'hikari_pending_peak=%s\n' "${pending_peak}"
  printf 'hikari_max_observed=%s\n' "${maximum_peak}"
  printf 'mysql_data_lock_waits_peak=%s\n' "${lock_wait_peak}"
  printf 'hikari_pending_peak_sample=%s\n' "${hikari_pending_peak_sample}"
  printf 'mysql_lock_wait_peak_sample=%s\n' "${mysql_lock_wait_peak_sample}"
  printf 'app_metrics=%s\n' "${APP_METRICS_FILE}"
  printf 'mysql_lock_waits=%s\n' "${MYSQL_WAITS_FILE}"
  printf 'mysql_snapshot=%s\n' "${MYSQL_SNAPSHOT_FILE}"
  printf 'k6_log=%s\n' "${K6_LOG_FILE}"
  append_k6_metrics "${k6_summary_file}"
} > "${SUMMARY_FILE}"

{
  printf '== 비관 락 대기 진단 증거 ==\n'
  printf '가설: 같은 row의 SELECT ... FOR UPDATE 대기가 DB connection을 점유해 Hikari pending까지 전파된다.\n\n'
  sed -n '1,200p' "${SUMMARY_FILE}"
  printf '\nMySQL lock wait snapshot 첫 부분:\n'
  if [[ -s "${MYSQL_SNAPSHOT_FILE}" ]]; then
    sed -n '1,80p' "${MYSQL_SNAPSHOT_FILE}"
  else
    printf 'snapshot 없음\n'
  fi
} > "${EVIDENCE_FILE}"

sed -n '1,240p' "${EVIDENCE_FILE}"

evidence_ok=true
if ! is_number "${lock_wait_peak}" || ! is_at_least "${lock_wait_peak}" 1; then
  echo "실패: MySQL data_lock_waits 관측값이 없습니다." >&2
  evidence_ok=false
fi
if ! is_number "${active_peak}" || ! is_at_least "${active_peak}" "${HIKARI_POOL_SIZE}"; then
  echo "실패: Hikari active peak가 설정 pool size(${HIKARI_POOL_SIZE})에 도달하지 않았습니다." >&2
  evidence_ok=false
fi
if ! is_number "${pending_peak}" || ! is_at_least "${pending_peak}" 1; then
  echo "실패: Hikari pending이 관측되지 않았습니다." >&2
  evidence_ok=false
fi
if [[ ! -s "${MYSQL_SNAPSHOT_FILE}" ]]; then
  echo "실패: MySQL lock wait snapshot을 저장하지 못했습니다." >&2
  evidence_ok=false
fi

if [[ "${evidence_ok}" != true ]]; then
  exit 1
fi

echo "진단 성공: lock wait → Hikari active/pending 전파 증거를 ${EVIDENCE_FILE}에 저장했습니다."
if (( k6_status != 0 )); then
  echo "참고: k6 threshold 실패는 강제 대기 실험의 결과일 수 있으므로 raw summary를 함께 해석하세요." >&2
fi
