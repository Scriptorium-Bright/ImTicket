#!/usr/bin/env bash
set -euo pipefail

# 전달받은 자연 경합 테스트를 실행하는 동안 애플리케이션과 MySQL의 락·풀 지표를 수집한다.
#
# BASE_URL=... MYSQL_PASSWORD=... \
# scripts/test/observe_pessimistic_lock_run.sh \
#   scripts/test/run_pessimistic_lock_natural_k6.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASE_URL="${BASE_URL:-http://127.0.0.1:10080}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-10047}"
MYSQL_USER="${MYSQL_USER:-capstone}"
MYSQL_DATABASE="${MYSQL_DATABASE:-capstone}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-${MYSQL_LOCK_TEST_PASSWORD:-}}"
SAMPLE_INTERVAL_SECONDS="${SAMPLE_INTERVAL_SECONDS:-0.2}"
RESULT_DIR="${RESULT_DIR:-${ROOT_DIR}/build/k6-results}"

if (( $# == 0 )); then
  echo "실행할 테스트 명령을 마지막 인자로 전달해야 합니다." >&2
  echo "예: scripts/test/observe_pessimistic_lock_run.sh scripts/test/run_pessimistic_lock_natural_k6.sh" >&2
  exit 1
fi
if [[ -z "${MYSQL_PASSWORD}" ]]; then
  echo "MYSQL_PASSWORD 또는 MYSQL_LOCK_TEST_PASSWORD를 설정해야 합니다." >&2
  exit 1
fi
if [[ ! "${SAMPLE_INTERVAL_SECONDS}" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
  echo "SAMPLE_INTERVAL_SECONDS는 숫자여야 합니다." >&2
  exit 1
fi

RUN_ID="pessimistic-natural-observe-$(date -u +%Y%m%dT%H%M%SZ)"
RUN_DIR="${RESULT_DIR}/${RUN_ID}"
APP_METRICS_FILE="${RUN_DIR}/app-metrics.tsv"
MYSQL_WAITS_FILE="${RUN_DIR}/mysql-lock-waits.tsv"
MYSQL_SNAPSHOT_FILE="${RUN_DIR}/mysql-lock-wait-snapshot.log"
COMMAND_LOG_FILE="${RUN_DIR}/test-command.log"
SUMMARY_FILE="${RUN_DIR}/observation-summary.txt"
sampler_pid=""
MYSQL_LOCK_WAIT_SOURCE=""

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
    --execute="$1" 2>/dev/null
}

detect_mysql_lock_wait_source() {
  local count
  count="$(mysql_query 'SELECT COUNT(*) FROM performance_schema.data_lock_waits;' || true)"
  if [[ "${count}" =~ ^[0-9]+$ ]]; then
    MYSQL_LOCK_WAIT_SOURCE="performance_schema"
    return
  fi

  count="$(mysql_query 'SELECT COUNT(*) FROM information_schema.innodb_lock_waits;' || true)"
  if [[ "${count}" =~ ^[0-9]+$ ]]; then
    MYSQL_LOCK_WAIT_SOURCE="information_schema"
    return
  fi

  MYSQL_LOCK_WAIT_SOURCE="unavailable"
}

mysql_lock_wait_count() {
  local count
  if [[ "${MYSQL_LOCK_WAIT_SOURCE}" == "performance_schema" ]]; then
    count="$(mysql_query 'SELECT COUNT(*) FROM performance_schema.data_lock_waits;' || true)"
  elif [[ "${MYSQL_LOCK_WAIT_SOURCE}" == "information_schema" ]]; then
    count="$(mysql_query 'SELECT COUNT(*) FROM information_schema.innodb_lock_waits;' || true)"
  else
    count="NA"
  fi
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
    printf 'lock_wait_source=%s\n\n' "${MYSQL_LOCK_WAIT_SOURCE}"
    if [[ "${MYSQL_LOCK_WAIT_SOURCE}" == "performance_schema" ]]; then
      mysql_query="
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
      "
    else
      mysql_query="
        SELECT
          requesting_trx_id,
          blocking_trx_id,
          requested_lock_id,
          blocking_lock_id
        FROM information_schema.innodb_lock_waits;
      "
    fi
    MYSQL_PWD="${MYSQL_PASSWORD}" mysql \
      --host="${MYSQL_HOST}" \
      --port="${MYSQL_PORT}" \
      --user="${MYSQL_USER}" \
      --protocol=tcp \
      --table \
      "${MYSQL_DATABASE}" \
      --execute="${mysql_query}" || true
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
      busy=NA; current=NA; active=NA; pending=NA; maximum=NA; acquire=NA; timeouts=NA
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
      if (!seen || value > max) { max = value; seen = 1 }
    }
    END { if (seen) print max; else print "NA" }
  ' "${file}"
}

preflight_payload="$(curl -fsS --connect-timeout 1 --max-time 3 "${BASE_URL}/actuator/prometheus" 2>/dev/null || true)"
if [[ -z "${preflight_payload}" ]]; then
  echo "${BASE_URL}/actuator/prometheus에서 지표를 읽지 못했습니다." >&2
  exit 1
fi
detect_mysql_lock_wait_source
if [[ "${MYSQL_LOCK_WAIT_SOURCE}" == "unavailable" ]]; then
  echo "MySQL lock wait 관측 테이블을 조회할 수 없습니다." >&2
  echo "performance_schema.data_lock_waits 또는 information_schema.innodb_lock_waits의 버전·권한을 확인하세요." >&2
  exit 1
fi
printf 'mysql_lock_wait_source=%s\n' "${MYSQL_LOCK_WAIT_SOURCE}"

printf 'observation_run_dir=%s\n' "${RUN_DIR}"
printf 'live_app_metrics=tail -f %s\n' "${APP_METRICS_FILE}"
printf 'live_mysql_lock_waits=tail -f %s\n' "${MYSQL_WAITS_FILE}"
printf 'test_command='
printf '%q ' "$@"
printf '\n'

sample_metrics &
sampler_pid=$!

set +e
"$@" 2>&1 | tee "${COMMAND_LOG_FILE}"
command_status=${PIPESTATUS[0]}
set -e

cleanup
sampler_pid=""

tomcat_busy_peak="$(peak_column "${APP_METRICS_FILE}" 2)"
tomcat_current_peak="$(peak_column "${APP_METRICS_FILE}" 3)"
hikari_active_peak="$(peak_column "${APP_METRICS_FILE}" 4)"
hikari_pending_peak="$(peak_column "${APP_METRICS_FILE}" 5)"
hikari_max_peak="$(peak_column "${APP_METRICS_FILE}" 6)"
lock_wait_peak="$(peak_column "${MYSQL_WAITS_FILE}" 2)"

{
  printf 'run_id=%s\n' "${RUN_ID}"
  printf 'test_exit_code=%s\n' "${command_status}"
  printf 'tomcat_busy_peak=%s\n' "${tomcat_busy_peak}"
  printf 'tomcat_current_peak=%s\n' "${tomcat_current_peak}"
  printf 'hikari_active_peak=%s\n' "${hikari_active_peak}"
  printf 'hikari_pending_peak=%s\n' "${hikari_pending_peak}"
  printf 'hikari_max_peak=%s\n' "${hikari_max_peak}"
  printf 'mysql_data_lock_waits_peak=%s\n' "${lock_wait_peak}"
  printf 'app_metrics=%s\n' "${APP_METRICS_FILE}"
  printf 'mysql_lock_waits=%s\n' "${MYSQL_WAITS_FILE}"
  printf 'mysql_snapshot=%s\n' "${MYSQL_SNAPSHOT_FILE}"
  printf 'test_log=%s\n' "${COMMAND_LOG_FILE}"
} > "${SUMMARY_FILE}"

sed -n '1,200p' "${SUMMARY_FILE}"
printf '\n테스트 로그 마지막 80줄:\n'
tail -n 80 "${COMMAND_LOG_FILE}"

if [[ "${command_status}" -ne 0 ]]; then
  exit "${command_status}"
fi
