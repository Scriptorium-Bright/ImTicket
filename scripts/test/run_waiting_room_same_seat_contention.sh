#!/usr/bin/env bash
set -euo pipefail

# C 동일 좌석 경합 시험
#
# Waiting Room join부터 entry pass 검증, 좌석 조회, pre-reserve까지 실행한다.
# 같은 좌석에 서로 다른 회원이 동시에 요청하고, 성공 1건·409 N-1건을 판정한다.
# 좌석별 admission permit은 테스트 목적에 맞게 충분히 열어 잠금 정합성과
# 애플리케이션의 좌석 상태 판정을 분리한다.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/load_env_defaults.sh"
load_imticket_env "${ROOT_DIR}/.env"

BASE_URL="${BASE_URL:-http://127.0.0.1:10082}"
MANAGEMENT_BASE_URL="${MANAGEMENT_BASE_URL:-http://127.0.0.1:10081}"
PT_ID="${PT_ID:-900000001}"
JWT_SECRET="${JWT_SECRET:-${SPRING_JWT_SECRET:-}}"
WAITING_ROOM_PASS_SECRET="${RESERVATION_WAITING_ROOM_PASS_SECRET:-local-e2e-waiting-room-pass-secret-2026}"
CONCURRENCIES="${CONCURRENCIES:-10 100}"
MAX_ACTIVE_SESSIONS="${MAX_ACTIVE_SESSIONS:-100}"
ADMIT_PER_INTERVAL="${ADMIT_PER_INTERVAL:-100}"
ADMISSION_PER_SEAT_PERMITS="${ADMISSION_PER_SEAT_PERMITS:-100}"
LOCK_STRATEGY="${LOCK_STRATEGY:-reentrant}"
LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS="${LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS:-60000}"
MEMBER_ID_BASE="${MEMBER_ID_BASE:-900000000}"
SEAT_ID="${SEAT_ID:-900000001}"
STATUS_POLLS="${STATUS_POLLS:-60}"
STATUS_POLL_INTERVAL_MS="${STATUS_POLL_INTERVAL_MS:-1000}"
STATUS_POLL_JITTER_RATIO="${STATUS_POLL_JITTER_RATIO:-0.1}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-120s}"
MAX_DURATION="${MAX_DURATION:-5m}"
METRICS_INTERVAL_SECONDS="${METRICS_INTERVAL_SECONDS:-0.2}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-10047}"
MYSQL_USER="${MYSQL_USER:-capstone}"
MYSQL_DATABASE="${MYSQL_DATABASE:-capstone}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-${MYSQL_LOCK_TEST_PASSWORD:-}}"
MYSQL_USE_DOCKER="${MYSQL_USE_DOCKER:-auto}"
REDIS_USE_DOCKER="${REDIS_USE_DOCKER:-true}"
RESET_FIXTURE="${RESET_FIXTURE:-true}"
RECONFIGURE_SERVICES="${RECONFIGURE_SERVICES:-true}"
BUILD_IMAGES="${BUILD_IMAGES:-true}"
RESULT_ROOT="${RESULT_ROOT:-${ROOT_DIR}/build/k6-results/146.15-waiting-room-contention}"
RUN_GROUP="${RUN_GROUP:-$(date -u +%Y%m%dT%H%M%SZ)}"
GROUP_DIR="${RESULT_ROOT}/${RUN_GROUP}"
MANIFEST_FILE="${GROUP_DIR}/run-manifest.tsv"
MATRIX_FILE="${GROUP_DIR}/contention-matrix.tsv"

require_positive_integer() {
  local name="$1"
  local value="$2"
  if ! [[ "${value}" =~ ^[1-9][0-9]*$ ]]; then
    echo "${name}은 양의 정수여야 합니다: ${value}" >&2
    exit 1
  fi
}

require_boolean() {
  local name="$1"
  local value="$2"
  if [[ "${value}" != "true" && "${value}" != "false" ]]; then
    echo "${name}은 true 또는 false여야 합니다: ${value}" >&2
    exit 1
  fi
}

require_positive_integer "PT_ID" "${PT_ID}"
require_positive_integer "MAX_ACTIVE_SESSIONS" "${MAX_ACTIVE_SESSIONS}"
require_positive_integer "ADMIT_PER_INTERVAL" "${ADMIT_PER_INTERVAL}"
require_positive_integer "ADMISSION_PER_SEAT_PERMITS" "${ADMISSION_PER_SEAT_PERMITS}"
require_positive_integer "LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS" "${LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS}"
require_positive_integer "MEMBER_ID_BASE" "${MEMBER_ID_BASE}"
require_positive_integer "SEAT_ID" "${SEAT_ID}"
require_positive_integer "STATUS_POLLS" "${STATUS_POLLS}"
require_positive_integer "STATUS_POLL_INTERVAL_MS" "${STATUS_POLL_INTERVAL_MS}"
require_positive_integer "MYSQL_PORT" "${MYSQL_PORT}"
if ! [[ "${METRICS_INTERVAL_SECONDS}" =~ ^[0-9]+([.][0-9]+)?$ ]] || [[ "$(awk -v value="${METRICS_INTERVAL_SECONDS}" 'BEGIN { print (value > 0) ? "true" : "false" }')" != "true" ]]; then
  echo "METRICS_INTERVAL_SECONDS는 0보다 큰 숫자여야 합니다: ${METRICS_INTERVAL_SECONDS}" >&2
  exit 1
fi
require_boolean "RESET_FIXTURE" "${RESET_FIXTURE}"
require_boolean "RECONFIGURE_SERVICES" "${RECONFIGURE_SERVICES}"
require_boolean "BUILD_IMAGES" "${BUILD_IMAGES}"

if [[ -z "${JWT_SECRET}" ]]; then
  echo "JWT_SECRET 또는 SPRING_JWT_SECRET이 필요합니다." >&2
  exit 1
fi
if [[ -z "${MYSQL_PASSWORD}" ]]; then
  echo "MYSQL_PASSWORD 또는 MYSQL_LOCK_TEST_PASSWORD가 필요합니다." >&2
  exit 1
fi
if [[ "${LOCK_STRATEGY}" != "reentrant" && "${LOCK_STRATEGY}" != "pessimistic" ]]; then
  echo "LOCK_STRATEGY는 reentrant 또는 pessimistic이어야 합니다: ${LOCK_STRATEGY}" >&2
  exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "jq가 필요합니다." >&2
  exit 1
fi
if ! command -v k6 >/dev/null 2>&1; then
  echo "k6가 필요합니다." >&2
  exit 1
fi
if ! command -v docker >/dev/null 2>&1; then
  echo "docker가 필요합니다." >&2
  exit 1
fi

declare -a concurrency_values=()
max_concurrency=0
for concurrency in ${CONCURRENCIES}; do
  require_positive_integer "CONCURRENCY" "${concurrency}"
  if (( concurrency > MAX_ACTIVE_SESSIONS )); then
    echo "CONCURRENCY=${concurrency}가 MAX_ACTIVE_SESSIONS=${MAX_ACTIVE_SESSIONS}보다 큽니다." >&2
    exit 1
  fi
  if (( concurrency > max_concurrency )); then
    max_concurrency="${concurrency}"
  fi
  concurrency_values+=("${concurrency}")
done
if (( ${#concurrency_values[@]} == 0 )); then
  echo "CONCURRENCIES가 비어 있습니다." >&2
  exit 1
fi
if (( ADMISSION_PER_SEAT_PERMITS < max_concurrency )); then
  echo "ADMISSION_PER_SEAT_PERMITS=${ADMISSION_PER_SEAT_PERMITS}가 최대 동시성=${max_concurrency}보다 작습니다." >&2
  exit 1
fi

case "${MYSQL_USE_DOCKER}" in
  true|1|yes)
    MYSQL_TRANSPORT="docker"
    ;;
  false|0|no)
    MYSQL_TRANSPORT="local"
    ;;
  auto)
    if [[ -n "$(docker compose ps -q mysql 2>/dev/null)" ]]; then
      MYSQL_TRANSPORT="docker"
    else
      MYSQL_TRANSPORT="local"
    fi
    ;;
  *)
    echo "MYSQL_USE_DOCKER는 auto, true, false 중 하나여야 합니다." >&2
    exit 1
    ;;
esac

mysql_query() {
  local sql="$1"
  if [[ "${MYSQL_TRANSPORT}" == "docker" ]]; then
    docker compose exec -T \
      -e "MYSQL_PWD=${MYSQL_PASSWORD}" \
      mysql mysql \
      --user="${MYSQL_USER}" \
      --database="${MYSQL_DATABASE}" \
      --batch \
      --skip-column-names \
      -e "${sql}"
  else
    MYSQL_PWD="${MYSQL_PASSWORD}" mysql \
      --host="${MYSQL_HOST}" \
      --port="${MYSQL_PORT}" \
      --user="${MYSQL_USER}" \
      --protocol=tcp \
      --database="${MYSQL_DATABASE}" \
      --batch \
      --skip-column-names \
      -e "${sql}"
  fi
}

mysql_status_value() {
  local name="$1"
  mysql_query "SHOW GLOBAL STATUS LIKE '${name}'" 2>/dev/null | awk 'NR == 1 { print $2; exit }'
}

check_fixture_seat() {
  local seat_count
  seat_count="$(mysql_query "SELECT COUNT(*) FROM Seat WHERE performance_time_id=${PT_ID} AND id=${SEAT_ID};")"
  if [[ "${seat_count}" != "1" ]]; then
    echo "C 대상 좌석을 찾지 못했습니다: performance_time_id=${PT_ID}, seat_id=${SEAT_ID}, count=${seat_count}" >&2
    exit 1
  fi
}

collect_mysql_metrics() {
  local run_dir="$1"
  local stop_file="$2"
  local metrics_file="${run_dir}/mysql-metrics.tsv"
  printf 'timestamp\tinnodb_row_lock_current_waits\tinnodb_row_lock_time\tinnodb_row_lock_time_max\tinnodb_deadlocks\tthreads_running\n' \
    > "${metrics_file}"
  while [[ ! -f "${stop_file}" ]]; do
    local current_waits lock_time lock_time_max deadlocks threads_running
    current_waits="$(mysql_status_value 'Innodb_row_lock_current_waits')"
    lock_time="$(mysql_status_value 'Innodb_row_lock_time')"
    lock_time_max="$(mysql_status_value 'Innodb_row_lock_time_max')"
    deadlocks="$(mysql_status_value 'Innodb_deadlocks')"
    threads_running="$(mysql_status_value 'Threads_running')"
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
      "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
      "${current_waits:-0}" \
      "${lock_time:-0}" \
      "${lock_time_max:-0}" \
      "${deadlocks:-0}" \
      "${threads_running:-0}" \
      >> "${metrics_file}"
    sleep "${METRICS_INTERVAL_SECONDS}"
  done
}

metric_count() {
  local summary_file="$1"
  local metric_name="$2"
  jq -r --arg metric_name "${metric_name}" '.metrics[$metric_name].count // 0' "${summary_file}"
}

metric_value() {
  local summary_file="$1"
  local metric_name="$2"
  jq -r --arg metric_name "${metric_name}" '.metrics[$metric_name].value // 0' "${summary_file}"
}

metric_percentile() {
  local summary_file="$1"
  local metric_name="$2"
  local percentile="$3"
  jq -r --arg metric_name "${metric_name}" --arg percentile "${percentile}" \
    '.metrics[$metric_name][$percentile] // 0' "${summary_file}"
}

max_app_metric() {
  local file="$1"
  local column="$2"
  awk -F '\t' -v column="${column}" \
    'NR > 1 && $column != "" { value = $column + 0; if (!found || value > max) { max = value; found = 1 } } END { if (!found) print "missing"; else print max }' \
    "${file}"
}

mysql_metric_max() {
  local file="$1"
  local column="$2"
  awk -F '\t' -v column="${column}" \
    'NR > 1 && $column != "" { value = $column + 0; if (!found || value > max) { max = value; found = 1 } } END { if (!found) print "missing"; else print max }' \
    "${file}"
}

mysql_metric_first() {
  local file="$1"
  local column="$2"
  awk -F '\t' -v column="${column}" 'NR == 2 { print $column; exit }' "${file}"
}

mysql_metric_last() {
  local file="$1"
  local column="$2"
  awk -F '\t' -v column="${column}" 'NR > 1 { value = $column } END { print value }' "${file}"
}

mkdir -p "${GROUP_DIR}"
printf 'run_index\trun_name\tconcurrency\tmember_id_base\tseat_id\tbase_url\tmanagement_base_url\tpt_id\tlock_strategy\tadmission_per_seat_permits\trun_dir\n' \
  > "${MANIFEST_FILE}"
printf 'concurrency\tcontract_success\tjoin_success\tseat_map_success\tpre_reserve_expected\tpre_reserve_success\tpre_reserve_conflict\tpre_reserve_admission_rejected\tunexpected_response\tpre_reserve_success_p95_ms\tpre_reserve_success_p99_ms\tpre_reserve_conflict_p95_ms\tpre_reserve_conflict_p99_ms\ttomcat_busy_max\thikari_pending_max\tmysql_lock_wait_max\tmysql_deadlocks\n' \
  > "${MATRIX_FILE}"

if [[ "${RECONFIGURE_SERVICES}" == "true" ]]; then
  compose_up_args=(up -d)
  if [[ "${BUILD_IMAGES}" == "true" ]]; then
    compose_up_args+=(--build)
  fi
  env \
    SPRING_JWT_SECRET="${JWT_SECRET}" \
    LOCK_STRATEGY="${LOCK_STRATEGY}" \
    LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS="${LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS}" \
    RESERVATION_ADMISSION_PER_SEAT_PERMITS="${ADMISSION_PER_SEAT_PERMITS}" \
    RESERVATION_WAITING_ROOM_ENABLED=true \
    RESERVATION_WAITING_ROOM_ASYNC_JOIN_ENABLED=false \
    RESERVATION_WAITING_ROOM_ENABLED_PERFORMANCE_TIME_IDS="${PT_ID}" \
    RESERVATION_WAITING_ROOM_MAX_ACTIVE_SESSIONS="${MAX_ACTIVE_SESSIONS}" \
    RESERVATION_WAITING_ROOM_ADMIT_PER_INTERVAL="${ADMIT_PER_INTERVAL}" \
    RESERVATION_WAITING_ROOM_PASS_SECRET="${WAITING_ROOM_PASS_SECRET}" \
    docker compose --profile waiting-room-ingress-experiment "${compose_up_args[@]}" app waiting-room-service waiting-room-gateway
fi

check_fixture_seat

for run_index in "${!concurrency_values[@]}"; do
  concurrency="${concurrency_values[run_index]}"
  run_number=$((run_index + 1))
  run_name="c-same-seat-contention-${concurrency}-${RUN_GROUP}"
  run_dir="${GROUP_DIR}/${run_name}"
  stop_file="${run_dir}/.stop-mysql-collector"
  mkdir -p "${run_dir}"

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "${run_number}" \
    "${run_name}" \
    "${concurrency}" \
    "${MEMBER_ID_BASE}" \
    "${SEAT_ID}" \
    "${BASE_URL}" \
    "${MANAGEMENT_BASE_URL}" \
    "${PT_ID}" \
    "${LOCK_STRATEGY}" \
    "${ADMISSION_PER_SEAT_PERMITS}" \
    "${run_dir}" \
    >> "${MANIFEST_FILE}"

  echo "[C] run ${run_number}/${#concurrency_values[@]}: concurrency=${concurrency}, seat_id=${SEAT_ID}"
  if [[ "${RESET_FIXTURE}" == "true" ]]; then
    PT_ID="${PT_ID}" \
    MEMBER_ID_START=$((MEMBER_ID_BASE + 1)) \
    MEMBER_COUNT="${concurrency}" \
    MYSQL_USE_DOCKER="${MYSQL_USE_DOCKER}" \
    REDIS_USE_DOCKER="${REDIS_USE_DOCKER}" \
    bash "${SCRIPT_DIR}/reset_waiting_room_fixture.sh"
  fi

  : > "${stop_file}"
  rm -f "${stop_file}"
  collect_mysql_metrics "${run_dir}" "${stop_file}" &
  mysql_collector_pid=$!

  set +e
  BASE_URL="${BASE_URL}" \
  MANAGEMENT_BASE_URL="${MANAGEMENT_BASE_URL}" \
  PT_ID="${PT_ID}" \
  JWT_SECRET="${JWT_SECRET}" \
  MODE=full-flow \
  FLOW=waiting-room \
  CONCURRENCY="${concurrency}" \
  MEMBER_ID_BASE="${MEMBER_ID_BASE}" \
  STATUS_POLLS="${STATUS_POLLS}" \
  STATUS_POLL_INTERVAL_MS="${STATUS_POLL_INTERVAL_MS}" \
  STATUS_POLL_JITTER_RATIO="${STATUS_POLL_JITTER_RATIO}" \
  SEAT_IDS="${SEAT_ID}" \
  REQUEST_TIMEOUT="${REQUEST_TIMEOUT}" \
  MAX_DURATION="${MAX_DURATION}" \
  METRICS_INTERVAL_SECONDS="${METRICS_INTERVAL_SECONDS}" \
  RUN_NAME="${run_name}" \
  RUN_DIR="${run_dir}" \
  bash "${SCRIPT_DIR}/run_waiting_room_load.sh"
  run_status=$?
  set -e

  touch "${stop_file}"
  wait "${mysql_collector_pid}" 2>/dev/null || true

  if (( run_status != 0 )); then
    echo "C 실행기 내부 부하 시험 실패: run=${run_name}, status=${run_status}" >&2
    exit "${run_status}"
  fi

  summary_file="${run_dir}/k6-summary.json"
  contract_success="$(metric_value "${summary_file}" 'waiting_room_contract_success')"
  join_success="$(metric_count "${summary_file}" 'waiting_room_join_success')"
  seat_map_success="$(metric_count "${summary_file}" 'waiting_room_seat_map_success')"
  pre_reserve_expected="$(metric_count "${summary_file}" 'waiting_room_pre_reserve_expected')"
  pre_reserve_success="$(metric_count "${summary_file}" 'waiting_room_pre_reserve_success')"
  pre_reserve_conflict="$(metric_count "${summary_file}" 'waiting_room_pre_reserve_conflict')"
  pre_reserve_admission_rejected="$(metric_count "${summary_file}" 'waiting_room_pre_reserve_admission_rejected')"
  unexpected_response="$(metric_count "${summary_file}" 'waiting_room_unexpected_response')"
  success_p95="$(metric_percentile "${summary_file}" 'waiting_room_pre_reserve_success_duration' 'p(95)')"
  success_p99="$(metric_percentile "${summary_file}" 'waiting_room_pre_reserve_success_duration' 'p(99)')"
  conflict_p95="$(metric_percentile "${summary_file}" 'waiting_room_pre_reserve_conflict_duration' 'p(95)')"
  conflict_p99="$(metric_percentile "${summary_file}" 'waiting_room_pre_reserve_conflict_duration' 'p(99)')"
  tomcat_busy_max="$(max_app_metric "${run_dir}/app-metrics.tsv" 3)"
  hikari_pending_max="$(max_app_metric "${run_dir}/app-metrics.tsv" 7)"
  lock_wait_max="$(mysql_metric_max "${run_dir}/mysql-metrics.tsv" 2)"
  deadlock_before="$(mysql_metric_first "${run_dir}/mysql-metrics.tsv" 5)"
  deadlock_after="$(mysql_metric_last "${run_dir}/mysql-metrics.tsv" 5)"
  deadlocks_delta=$(( ${deadlock_after:-0} - ${deadlock_before:-0} ))

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "${concurrency}" \
    "${contract_success}" \
    "${join_success}" \
    "${seat_map_success}" \
    "${pre_reserve_expected}" \
    "${pre_reserve_success}" \
    "${pre_reserve_conflict}" \
    "${pre_reserve_admission_rejected}" \
    "${unexpected_response}" \
    "${success_p95}" \
    "${success_p99}" \
    "${conflict_p95}" \
    "${conflict_p99}" \
    "${tomcat_busy_max}" \
    "${hikari_pending_max}" \
    "${lock_wait_max}" \
    "${deadlocks_delta}" \
    >> "${MATRIX_FILE}"

  if [[ "${contract_success}" != "1" && "${contract_success}" != "1.0" ]] \
    || [[ "${join_success}" != "${concurrency}" ]] \
    || [[ "${seat_map_success}" != "${concurrency}" ]] \
    || [[ "${pre_reserve_expected}" != "${concurrency}" ]] \
    || [[ "${pre_reserve_success}" != "1" ]] \
    || [[ "${pre_reserve_conflict}" != "$((concurrency - 1))" ]] \
    || [[ "${pre_reserve_admission_rejected}" != "0" ]] \
    || [[ "${unexpected_response}" != "0" ]] \
    || [[ "${deadlocks_delta}" != "0" ]]; then
    echo "C 계약 실패: run=${run_name} contract=${contract_success} join=${join_success} seat_map=${seat_map_success} expected=${pre_reserve_expected} success=${pre_reserve_success} conflict=${pre_reserve_conflict} admission_rejected=${pre_reserve_admission_rejected} unexpected=${unexpected_response} deadlocks=${deadlocks_delta}" >&2
    exit 1
  fi
done

printf 'c_runs=%s\nmanifest=%s\ncontention_matrix=%s\n' \
  "${#concurrency_values[@]}" \
  "${MANIFEST_FILE}" \
  "${MATRIX_FILE}"
