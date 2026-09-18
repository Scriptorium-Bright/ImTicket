#!/usr/bin/env bash
set -euo pipefail

# 인기 공연의 쓰기 비용과 좌석 조회 갱신 비용을 분리·결합해 측정한다.
# 현재 application cache 구현의 before/after 부하와 관측 명령을 실행한다.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
K6_SCRIPT="${SCRIPT_DIR}/146-popular-write-refresh-load.js"
source "${SCRIPT_DIR}/load_env_defaults.sh"
load_imticket_env "${ROOT_DIR}/.env"

BASE_URL="${BASE_URL:-http://127.0.0.1:10080}"
MANAGEMENT_BASE_URL="${MANAGEMENT_BASE_URL:-http://127.0.0.1:10081}"
PT_ID="${PT_ID:-900000001}"
JWT_SECRET="${JWT_SECRET:-${SPRING_JWT_SECRET:-}}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-16380}"
MYSQL_DATABASE="${MYSQL_DATABASE:-capstone}"
MEMBER_ID_BASE="${MEMBER_ID_BASE:-900009980}"
MEMBER_POOL_SIZE="${MEMBER_POOL_SIZE:-21}"
BENCHMARK_MEMBER_ID_BASE="${BENCHMARK_MEMBER_ID_BASE:-900000000}"
WRITE_DURATION="${WRITE_DURATION:-20s}"
MIXED_DURATION="${MIXED_DURATION:-20s}"
WRITE_RATE_LOW="${WRITE_RATE_LOW:-10}"
WRITE_RATE_HIGH="${WRITE_RATE_HIGH:-50}"
MIXED_WRITE_RATE="${MIXED_WRITE_RATE:-50}"
MIXED_READ_RATE="${MIXED_READ_RATE:-100}"
READ_BURST_VUS="${READ_BURST_VUS:-2000}"
PRE_ALLOCATED_VUS="${PRE_ALLOCATED_VUS:-100}"
MAX_VUS="${MAX_VUS:-500}"
READ_PRE_ALLOCATED_VUS="${READ_PRE_ALLOCATED_VUS:-200}"
READ_MAX_VUS="${READ_MAX_VUS:-2000}"
BURST_DELAY_SECONDS="${BURST_DELAY_SECONDS:-2}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-15s}"
SEAT_COUNT_LIMIT="${SEAT_COUNT_LIMIT:-1200}"
CASE_SELECTION="${CASE_SELECTION:-all}"
RESULT_ROOT="${RESULT_ROOT:-${ROOT_DIR}/build/k6-results/146.6.3-popular-write-refresh}"
RUN_ID="${RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
RUN_DIR="${RESULT_ROOT}/${RUN_ID}"

CURRENT_PREFIX=""
CURRENT_RUN_DIR=""
IOSTAT_PID=""
VMSTAT_PID=""

require_positive_integer() {
  local name="$1"
  local value="$2"
  if ! [[ "${value}" =~ ^[1-9][0-9]*$ ]]; then
    echo "${name}은 양의 정수여야 합니다: ${value}" >&2
    exit 1
  fi
}

require_duration() {
  local name="$1"
  local value="$2"
  if ! [[ "${value}" =~ ^[1-9][0-9]*(ms|s|m|h)$ ]]; then
    echo "${name}은 예: 20s, 1m 형식이어야 합니다: ${value}" >&2
    exit 1
  fi
}

require_positive_integer "PT_ID" "${PT_ID}"
require_positive_integer "MEMBER_ID_BASE" "${MEMBER_ID_BASE}"
require_positive_integer "MEMBER_POOL_SIZE" "${MEMBER_POOL_SIZE}"
require_positive_integer "WRITE_RATE_LOW" "${WRITE_RATE_LOW}"
require_positive_integer "WRITE_RATE_HIGH" "${WRITE_RATE_HIGH}"
require_positive_integer "MIXED_WRITE_RATE" "${MIXED_WRITE_RATE}"
require_positive_integer "MIXED_READ_RATE" "${MIXED_READ_RATE}"
require_positive_integer "READ_BURST_VUS" "${READ_BURST_VUS}"
require_positive_integer "PRE_ALLOCATED_VUS" "${PRE_ALLOCATED_VUS}"
require_positive_integer "MAX_VUS" "${MAX_VUS}"
require_positive_integer "READ_PRE_ALLOCATED_VUS" "${READ_PRE_ALLOCATED_VUS}"
require_positive_integer "READ_MAX_VUS" "${READ_MAX_VUS}"
require_positive_integer "BURST_DELAY_SECONDS" "${BURST_DELAY_SECONDS}"
require_positive_integer "SEAT_COUNT_LIMIT" "${SEAT_COUNT_LIMIT}"
require_duration "WRITE_DURATION" "${WRITE_DURATION}"
require_duration "MIXED_DURATION" "${MIXED_DURATION}"

if [[ -z "${JWT_SECRET}" ]]; then
  echo "JWT_SECRET 또는 SPRING_JWT_SECRET이 필요합니다." >&2
  exit 1
fi
if ! command -v k6 >/dev/null 2>&1; then
  echo "k6가 필요합니다." >&2
  exit 1
fi
if ! command -v curl >/dev/null 2>&1; then
  echo "curl이 필요합니다." >&2
  exit 1
fi
if ! command -v redis-cli >/dev/null 2>&1; then
  echo "redis-cli가 필요합니다." >&2
  exit 1
fi
if ! command -v docker >/dev/null 2>&1; then
  echo "docker가 필요합니다." >&2
  exit 1
fi

mysql_root_query() {
  local sql="$1"
  docker compose exec -T mysql sh -c \
    'mysql --user=root --password="$MYSQL_ROOT_PASSWORD" --database="$MYSQL_DATABASE" --batch --skip-column-names' \
    <<< "${sql}"
}

mysql_status_snapshot() {
  local output_file="$1"
  mysql_root_query "
    SELECT VARIABLE_NAME, VARIABLE_VALUE
    FROM performance_schema.global_status
    WHERE VARIABLE_NAME IN (
      'Innodb_buffer_pool_read_requests',
      'Innodb_buffer_pool_reads',
      'Innodb_data_reads',
      'Innodb_data_writes',
      'Innodb_data_written',
      'Innodb_log_write_requests',
      'Innodb_log_writes',
      'Innodb_os_log_fsyncs',
      'Innodb_log_waits',
      'Innodb_buffer_pool_pages_dirty',
      'Innodb_row_lock_current_waits',
      'Innodb_row_lock_waits',
      'Innodb_row_lock_time',
      'Innodb_row_lock_time_max',
      'Threads_running'
    )
    ORDER BY VARIABLE_NAME;
  " > "${output_file}"
}

mysql_status_delta() {
  local before_file="$1"
  local after_file="$2"
  local output_file="$3"
  awk -F '\t' '
    FNR == NR { before[$1] = $2; next }
    {
      old = before[$1] + 0
      current = $2 + 0
      printf "%s\t%s\t%s\n", $1, current - old, $2
    }
  ' "${before_file}" "${after_file}" > "${output_file}"
}

capture_app_metrics() {
  local output_file="$1"
  curl -fsS --connect-timeout 2 --max-time 5 \
    "${MANAGEMENT_BASE_URL}/actuator/prometheus" > "${output_file}" || true
}

capture_redis_metrics() {
  local output_file="$1"
  {
    echo '# INFO commandstats'
    redis-cli -h "${REDIS_HOST}" -p "${REDIS_PORT}" INFO commandstats
    echo '# INFO stats'
    redis-cli -h "${REDIS_HOST}" -p "${REDIS_PORT}" INFO stats
  } > "${output_file}" || true
}

capture_container_stats() {
  local output_file="$1"
  docker stats --no-stream --format '{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}\t{{.BlockIO}}' \
    imticket-app imticket-mysql imticket-redis > "${output_file}" 2>&1 || true
}

capture_fixture_state() {
  local output_file="$1"
  mysql_root_query "
    SELECT seat_status, version, COUNT(*)
    FROM Seat
    WHERE performance_time_id=${PT_ID}
    GROUP BY seat_status, version
    ORDER BY seat_status, version;
  " > "${output_file}"
}

verify_test_members() {
  local expected actual
  expected="${MEMBER_POOL_SIZE}"
  actual="$(mysql_root_query "SELECT COUNT(*) FROM Member WHERE id BETWEEN ${MEMBER_ID_BASE} AND ${MEMBER_ID_BASE} + ${MEMBER_POOL_SIZE} - 1;")"
  if [[ "${actual}" != "${expected}" ]]; then
    echo "전용 테스트 회원 수가 맞지 않습니다. expected=${expected}, actual=${actual}" >&2
    exit 1
  fi
}

cleanup_test_state() {
  local prefix="$1"
  [[ -n "${prefix}" ]] || return 0

  mysql_root_query "
    START TRANSACTION;
    DROP TEMPORARY TABLE IF EXISTS popular_write_cleanup_ids;
    CREATE TEMPORARY TABLE popular_write_cleanup_ids (
      id BIGINT NOT NULL PRIMARY KEY
    ) ENGINE=InnoDB;
    INSERT INTO popular_write_cleanup_ids (id)
    SELECT reservation_id
    FROM reservation_idempotency
    WHERE idempotency_key LIKE '${prefix}-%'
      AND reservation_id IS NOT NULL;
    UPDATE Seat s
    JOIN ReservedSeat rs ON rs.seat_id = s.id
    JOIN popular_write_cleanup_ids cleanup_ids ON cleanup_ids.id = rs.reservation_id
    SET s.seat_status = 'AVAILABLE', s.version = 0
    WHERE s.performance_time_id = ${PT_ID};
    DELETE rs
    FROM ReservedSeat rs
    JOIN popular_write_cleanup_ids cleanup_ids ON cleanup_ids.id = rs.reservation_id;
    DELETE ri
    FROM reservation_idempotency ri
    WHERE ri.idempotency_key LIKE '${prefix}-%';
    DELETE r
    FROM Reservation r
    JOIN popular_write_cleanup_ids cleanup_ids ON cleanup_ids.id = r.id;
    DROP TEMPORARY TABLE popular_write_cleanup_ids;
    COMMIT;
  "
  clear_seat_map_cache
}

clear_seat_map_cache() {
  redis-cli -h "${REDIS_HOST}" -p "${REDIS_PORT}" \
    DEL \
      "reservation:seat-map:{${PT_ID}}:layout:generation" \
      "reservation:seat-map:{${PT_ID}}:layout:v1" \
      "reservation:seat-map:{${PT_ID}}:availability:generation" \
      "reservation:seat-map:{${PT_ID}}:availability:v1" \
      "reservation:seat-map:{${PT_ID}}:version" \
      "reservation:seat-map:{${PT_ID}}:snapshot:v2" \
      "reservation:seat-map:{${PT_ID}}:snapshot" >/dev/null || true
}

stop_collectors() {
  if [[ -n "${IOSTAT_PID}" ]]; then
    kill "${IOSTAT_PID}" 2>/dev/null || true
    wait "${IOSTAT_PID}" 2>/dev/null || true
    IOSTAT_PID=""
  fi
  if [[ -n "${VMSTAT_PID}" ]]; then
    kill "${VMSTAT_PID}" 2>/dev/null || true
    wait "${VMSTAT_PID}" 2>/dev/null || true
    VMSTAT_PID=""
  fi
}

cleanup_on_exit() {
  stop_collectors
  if [[ -n "${CURRENT_PREFIX}" ]]; then
    cleanup_test_state "${CURRENT_PREFIX}" || true
    CURRENT_PREFIX=""
  fi
}
trap cleanup_on_exit EXIT INT TERM

new_prefix() {
  printf 'c146%04x' "$(( $(date +%s) % 65536 ))"
}

prepare_case() {
  local prefix="$1"
  cleanup_test_state "${prefix}"
  clear_seat_map_cache
  curl -fsS --connect-timeout 2 --max-time 20 \
    "${BASE_URL}/api/seats/${PT_ID}" > "${CURRENT_RUN_DIR}/warmup-seat-map.json"
  if ! jq -e '.success == true and (.data | type == "array")' \
    "${CURRENT_RUN_DIR}/warmup-seat-map.json" >/dev/null; then
    echo "seat map warm-up 응답을 확인하지 못했습니다." >&2
    return 1
  fi
  capture_fixture_state "${CURRENT_RUN_DIR}/fixture-state-before.tsv"
}

start_collectors() {
  iostat -d -w 1 > "${CURRENT_RUN_DIR}/iostat.log" 2>&1 &
  IOSTAT_PID=$!
  vm_stat 1 > "${CURRENT_RUN_DIR}/vm-stat.log" 2>&1 &
  VMSTAT_PID=$!
}

run_k6() {
  local mode="$1"
  local write_rate="$2"
  local read_rate="$3"
  local duration_value="$4"
  local burst_vus="$5"
  local prefix="$6"

  BASE_URL="${BASE_URL}" \
  PT_ID="${PT_ID}" \
  JWT_SECRET="${JWT_SECRET}" \
  MODE="${mode}" \
  WRITE_RATE="${write_rate}" \
  READ_RATE="${read_rate}" \
  READ_BURST_VUS="${burst_vus}" \
  DURATION="${duration_value}" \
  PRE_ALLOCATED_VUS="${PRE_ALLOCATED_VUS}" \
  MAX_VUS="${MAX_VUS}" \
  READ_PRE_ALLOCATED_VUS="${READ_PRE_ALLOCATED_VUS}" \
  READ_MAX_VUS="${READ_MAX_VUS}" \
  BURST_DELAY_SECONDS="${BURST_DELAY_SECONDS}" \
  MEMBER_ID_BASE="${MEMBER_ID_BASE}" \
  MEMBER_POOL_SIZE="${MEMBER_POOL_SIZE}" \
  BENCHMARK_MEMBER_ID_BASE="${BENCHMARK_MEMBER_ID_BASE}" \
  IDEMPOTENCY_PREFIX="${prefix}" \
  REQUEST_TIMEOUT="${REQUEST_TIMEOUT}" \
  SEAT_COUNT_LIMIT="${SEAT_COUNT_LIMIT}" \
  k6 run \
    --summary-export "${CURRENT_RUN_DIR}/k6-summary.json" \
    "${K6_SCRIPT}" > "${CURRENT_RUN_DIR}/k6.log" 2>&1
}

write_case_manifest() {
  local mode="$1"
  local write_rate="$2"
  local read_rate="$3"
  local duration_value="$4"
  local burst_vus="$5"
  cat > "${CURRENT_RUN_DIR}/run-manifest.txt" <<EOF
mode=${mode}
performance_time_id=${PT_ID}
write_rate_per_second=${write_rate}
read_rate_per_second=${read_rate}
read_burst_vus=${burst_vus}
duration=${duration_value}
member_id_base=${MEMBER_ID_BASE}
member_pool_size=${MEMBER_POOL_SIZE}
application_code_changed=false
EOF
}

print_summary() {
  local summary_file="${CURRENT_RUN_DIR}/k6-summary.json"
  [[ -f "${summary_file}" ]] || return 0
  jq '{http_req_duration: .metrics.http_req_duration, http_req_waiting: .metrics.http_req_waiting, http_req_receiving: .metrics.http_req_receiving, http_req_blocked: .metrics.http_req_blocked, http_req_failed: .metrics.http_req_failed, dropped_iterations: .metrics.dropped_iterations, popular_write_attempts: .metrics.popular_write_attempts, popular_write_success: .metrics.popular_write_success, popular_write_conflict: .metrics.popular_write_conflict, popular_write_rate_limited: .metrics.popular_write_rate_limited, popular_write_unexpected: .metrics.popular_write_unexpected, popular_write_transport_failure: .metrics.popular_write_transport_failure, popular_read_attempts: .metrics.popular_read_attempts, popular_read_success: .metrics.popular_read_success, popular_read_unexpected: .metrics.popular_read_unexpected, popular_read_expected: .metrics.popular_read_expected, popular_read_transport_failure: .metrics.popular_read_transport_failure, popular_read_duration: .metrics.popular_read_duration, popular_read_response_bytes: .metrics.popular_read_response_bytes, popular_write_duration: .metrics.popular_write_duration}' \
    "${summary_file}" > "${CURRENT_RUN_DIR}/summary-selected.json" || true
}

run_case() {
  local case_name="$1"
  local mode="$2"
  local write_rate="$3"
  local read_rate="$4"
  local duration_value="$5"
  local burst_vus="$6"
  local prefix
  local k6_status=0

  prefix="$(new_prefix)"
  CURRENT_PREFIX="${prefix}"
  CURRENT_RUN_DIR="${RUN_DIR}/${case_name}"
  mkdir -p "${CURRENT_RUN_DIR}"
  write_case_manifest "${mode}" "${write_rate}" "${read_rate}" "${duration_value}" "${burst_vus}"

  echo "[${case_name}] prepare"
  prepare_case "${prefix}"
  mysql_status_snapshot "${CURRENT_RUN_DIR}/mysql-before.tsv"
  capture_app_metrics "${CURRENT_RUN_DIR}/app-metrics-before.prom"
  capture_redis_metrics "${CURRENT_RUN_DIR}/redis-before.info"
  capture_container_stats "${CURRENT_RUN_DIR}/container-before.tsv"
  start_collectors

  echo "[${case_name}] k6 mode=${mode} write=${write_rate}/s read=${read_rate}/s burst=${burst_vus} duration=${duration_value}"
  set +e
  run_k6 "${mode}" "${write_rate}" "${read_rate}" "${duration_value}" "${burst_vus}" "${prefix}"
  k6_status=$?
  set -e

  stop_collectors
  mysql_status_snapshot "${CURRENT_RUN_DIR}/mysql-after.tsv"
  mysql_status_delta \
    "${CURRENT_RUN_DIR}/mysql-before.tsv" \
    "${CURRENT_RUN_DIR}/mysql-after.tsv" \
    "${CURRENT_RUN_DIR}/mysql-delta.tsv"
  capture_app_metrics "${CURRENT_RUN_DIR}/app-metrics-after.prom"
  capture_redis_metrics "${CURRENT_RUN_DIR}/redis-after.info"
  capture_container_stats "${CURRENT_RUN_DIR}/container-after.tsv"
  print_summary

  cleanup_test_state "${prefix}"
  CURRENT_PREFIX=""
  capture_fixture_state "${CURRENT_RUN_DIR}/fixture-state-after.tsv"
  printf 'k6_exit=%s\n' "${k6_status}" > "${CURRENT_RUN_DIR}/case-status.txt"
  if [[ "${k6_status}" -ne 0 ]]; then
    echo "[${case_name}] k6 실패. 로그: ${CURRENT_RUN_DIR}/k6.log" >&2
    return "${k6_status}"
  fi
  echo "[${case_name}] complete: ${CURRENT_RUN_DIR}"
  return 0
}

case_enabled() {
  local case_name="$1"
  [[ "${CASE_SELECTION}" == "all" || "${CASE_SELECTION}" == "${case_name}" ]]
}

mkdir -p "${RUN_DIR}"
verify_test_members
printf 'run_id=%s\ncase_selection=%s\napplication_code_changed=false\n' \
  "${RUN_ID}" "${CASE_SELECTION}" > "${RUN_DIR}/run-manifest.txt"

case_failures=0
if case_enabled "write-10"; then
  run_case "write-10" write "${WRITE_RATE_LOW}" 0 "${WRITE_DURATION}" 0 || case_failures=$((case_failures + 1))
fi
if case_enabled "write-50"; then
  run_case "write-50" write "${WRITE_RATE_HIGH}" 0 "${WRITE_DURATION}" 0 || case_failures=$((case_failures + 1))
fi
if case_enabled "mixed-50-100"; then
  run_case "mixed-50-100" mixed "${MIXED_WRITE_RATE}" "${MIXED_READ_RATE}" "${MIXED_DURATION}" 0 || case_failures=$((case_failures + 1))
fi
if case_enabled "invalidation-burst"; then
  run_case "invalidation-burst" invalidation-burst 0 0 "${MIXED_DURATION}" "${READ_BURST_VUS}" || case_failures=$((case_failures + 1))
fi

printf 'case_failures=%s\nrun_dir=%s\n' "${case_failures}" "${RUN_DIR}" | tee "${RUN_DIR}/result.txt"
if (( case_failures > 0 )); then
  exit 1
fi
