#!/usr/bin/env bash
set -euo pipefail

# 92.x~147 성능 개선 종료 시험
#
# 현재 코드로 2,000명 전체 흐름을 3회 실행한다.
#
# Client
#   → Nginx Gateway
#   → Waiting Room Service
#   → entry pass
#   → Reservation Application
#   → seat map / pre-reserve
#
# 이 실행기는 종료 SLO를 자동 판정한다. 브라우저의 Ticket SSE·새로고침·재연결은
# 실행 완료 후 수동으로 한 번 확인한다.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/load_env_defaults.sh"
load_imticket_env "${ROOT_DIR}/.env"

BASE_URL="${BASE_URL:-http://127.0.0.1:10082}"
MANAGEMENT_BASE_URL="${MANAGEMENT_BASE_URL:-http://127.0.0.1:10081}"
WAITING_ROOM_MANAGEMENT_BASE_URL="${WAITING_ROOM_MANAGEMENT_BASE_URL:-http://127.0.0.1:10084}"
PT_ID="${PT_ID:-900000001}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-16380}"
JWT_SECRET="${JWT_SECRET:-${SPRING_JWT_SECRET:-}}"
WAITING_ROOM_PASS_SECRET="${WAITING_ROOM_PASS_SECRET:-${RESERVATION_WAITING_ROOM_PASS_SECRET:-local-e2e-waiting-room-pass-secret-2026}}"

CONCURRENCY="${CONCURRENCY:-2000}"
RUNS="${RUNS:-3}"
MAX_ACTIVE_SESSIONS="${MAX_ACTIVE_SESSIONS:-30}"
ADMIT_PER_INTERVAL="${ADMIT_PER_INTERVAL:-25}"
MEMBER_ID_BASE="${MEMBER_ID_BASE:-900000000}"
MEMBER_COUNT="${MEMBER_COUNT:-2000}"
SEAT_ID_START="${SEAT_ID_START:-900000001}"
STATUS_POLLS="${STATUS_POLLS:-150}"
STATUS_POLL_INTERVAL_MS="${STATUS_POLL_INTERVAL_MS:-1000}"
STATUS_POLL_JITTER_RATIO="${STATUS_POLL_JITTER_RATIO:-0.1}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-30s}"
MAX_DURATION="${MAX_DURATION:-10m}"
START_DELAY_SECONDS="${START_DELAY_SECONDS:-2}"
METRICS_INTERVAL_SECONDS="${METRICS_INTERVAL_SECONDS:-0.2}"

MYSQL_USE_DOCKER="${MYSQL_USE_DOCKER:-auto}"
REDIS_USE_DOCKER="${REDIS_USE_DOCKER:-auto}"
RESET_FIXTURE="${RESET_FIXTURE:-true}"
RECONFIGURE_SERVICES="${RECONFIGURE_SERVICES:-true}"
BUILD_IMAGES="${BUILD_IMAGES:-true}"
STOP_ON_SLO_FAILURE="${STOP_ON_SLO_FAILURE:-false}"
PROMOTION_METADATA_PIPELINE_ENABLED="${PROMOTION_METADATA_PIPELINE_ENABLED:-false}"

RESULT_ROOT="${RESULT_ROOT:-${ROOT_DIR}/build/k6-results/147-final-performance-closure}"
RUN_GROUP="${RUN_GROUP:-$(date -u +%Y%m%dT%H%M%SZ)}"
GROUP_DIR="${RESULT_ROOT}/${RUN_GROUP}"
MANIFEST_FILE="${GROUP_DIR}/run-manifest.tsv"
MATRIX_FILE="${GROUP_DIR}/closure-matrix.tsv"

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
require_positive_integer "CONCURRENCY" "${CONCURRENCY}"
require_positive_integer "RUNS" "${RUNS}"
require_positive_integer "MAX_ACTIVE_SESSIONS" "${MAX_ACTIVE_SESSIONS}"
require_positive_integer "ADMIT_PER_INTERVAL" "${ADMIT_PER_INTERVAL}"
require_positive_integer "MEMBER_ID_BASE" "${MEMBER_ID_BASE}"
require_positive_integer "MEMBER_COUNT" "${MEMBER_COUNT}"
require_positive_integer "SEAT_ID_START" "${SEAT_ID_START}"
require_positive_integer "STATUS_POLLS" "${STATUS_POLLS}"
require_positive_integer "STATUS_POLL_INTERVAL_MS" "${STATUS_POLL_INTERVAL_MS}"
if ! [[ "${START_DELAY_SECONDS}" =~ ^[0-9]+$ ]]; then
  echo "START_DELAY_SECONDS는 0 이상의 정수여야 합니다: ${START_DELAY_SECONDS}" >&2
  exit 1
fi
if ! [[ "${METRICS_INTERVAL_SECONDS}" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
  echo "METRICS_INTERVAL_SECONDS는 0 이상의 숫자여야 합니다: ${METRICS_INTERVAL_SECONDS}" >&2
  exit 1
fi
require_boolean "RESET_FIXTURE" "${RESET_FIXTURE}"
require_boolean "RECONFIGURE_SERVICES" "${RECONFIGURE_SERVICES}"
require_boolean "BUILD_IMAGES" "${BUILD_IMAGES}"
require_boolean "STOP_ON_SLO_FAILURE" "${STOP_ON_SLO_FAILURE}"
require_boolean "PROMOTION_METADATA_PIPELINE_ENABLED" "${PROMOTION_METADATA_PIPELINE_ENABLED}"

if [[ "${CONCURRENCY}" != "2000" ]]; then
  echo "종료 시험의 CONCURRENCY는 2000으로 고정합니다: ${CONCURRENCY}" >&2
  exit 1
fi
if [[ "${RUNS}" != "3" ]]; then
  echo "종료 시험의 RUNS는 3으로 고정합니다: ${RUNS}" >&2
  exit 1
fi
if (( MEMBER_COUNT < CONCURRENCY )); then
  echo "MEMBER_COUNT는 CONCURRENCY 이상이어야 합니다." >&2
  exit 1
fi
if [[ -z "${JWT_SECRET}" ]]; then
  echo "JWT_SECRET 또는 SPRING_JWT_SECRET이 필요합니다." >&2
  exit 1
fi
for command in docker jq k6 curl awk redis-cli; do
  if ! command -v "${command}" >/dev/null 2>&1; then
    echo "${command}가 필요합니다." >&2
    exit 1
  fi
done

build_seat_ids() {
  local offset
  local seat_ids=""
  for ((offset = 0; offset < CONCURRENCY; offset += 1)); do
    if [[ -n "${seat_ids}" ]]; then
      seat_ids+=",";
    fi
    seat_ids+=$((SEAT_ID_START + offset))
  done
  printf '%s' "${seat_ids}"
}

wait_for_health() {
  local name="$1"
  local url="$2"
  local attempt
  for ((attempt = 1; attempt <= 120; attempt += 1)); do
    if curl -fsS --connect-timeout 1 --max-time 2 "${url}" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "${name} health check가 제한 시간 안에 통과하지 못했습니다: ${url}" >&2
  return 1
}

is_one() {
  [[ "$1" == "1" || "$1" == "1.0" ]]
}

metric_count() {
  local summary_file="$1"
  local metric_name="$2"
  jq -r --arg metric_name "${metric_name}" '.metrics[$metric_name].count // 0' "${summary_file}"
}

metric_value() {
  local summary_file="$1"
  local metric_name="$2"
  local field="$3"
  jq -r --arg metric_name "${metric_name}" --arg field "${field}" \
    '.metrics[$metric_name][$field] // 0' "${summary_file}"
}

numeric_or_zero() {
  local value="$1"
  awk -v value="${value}" 'BEGIN { if (value == "" || value == "null") print 0; else print value }'
}

max_app_metric() {
  local metrics_file="$1"
  local field_number="$2"
  awk -F '\t' -v field_number="${field_number}" '
    NR > 1 && $field_number != "" && $field_number ~ /^[0-9]+([.][0-9]+)?$/ {
      if (!found || ($field_number + 0) > max) {
        max = $field_number
        found = 1
      }
    }
    END { if (!found) print "missing"; else print max }
  ' "${metrics_file}"
}

waiting_room_metric_value() {
  local metric_name="$1"
  local label_fragment="$2"
  local payload="$3"
  printf '%s\n' "${payload}" | awk -v metric_name="${metric_name}" -v label_fragment="${label_fragment}" '
    index($1, metric_name) == 1 && (label_fragment == "" || index($1, label_fragment) > 0) && !found {
      print $NF
      found = 1
    }
  '
}

waiting_room_metric_or_zero() {
  local value="$1"
  if [[ -z "${value}" || "${value}" == "NaN" ]]; then
    printf '0'
  else
    printf '%s' "${value}"
  fi
}

waiting_room_active_count() {
  redis-cli -h "${REDIS_HOST}" -p "${REDIS_PORT}" \
    ZCARD "reservation:waiting-room:{${PT_ID}}:active" 2>/dev/null || printf '0'
}

collect_waiting_room_metrics() {
  local run_dir="$1"
  local stop_file="$2"
  local sample=0
  local timestamp
  local metric_payload
  local scheduler_runs
  local scheduler_duration_count
  local scheduler_duration_sum
  local scheduler_duration_max
  local promotions
  local status_waiting
  local status_admitted
  local active_count
  local sse_active_connections
  local sse_publish
  local sse_consume
  local complete_failures

  printf 'sample\tepoch_seconds\tscheduler_runs\tscheduler_duration_count\tscheduler_duration_sum\tscheduler_duration_max_seconds\tpromotions\tstatus_waiting\tstatus_admitted\tactive_count\tsse_active_connections\tsse_publish\tsse_consume\tcomplete_failures\n' \
    > "${run_dir}/waiting-room-metrics.tsv"

  while [[ ! -f "${stop_file}" ]]; do
    sample=$((sample + 1))
    timestamp="$(date +%s.%N)"
    metric_payload="$(curl -fsS --connect-timeout 1 --max-time 2 "${WAITING_ROOM_MANAGEMENT_BASE_URL}/actuator/prometheus" 2>/dev/null || true)"
    if [[ -n "${metric_payload}" ]]; then
      scheduler_runs="$(waiting_room_metric_or_zero "$(waiting_room_metric_value 'imticket_waiting_room_scheduler_runs_total' '' "${metric_payload}")")"
      scheduler_duration_count="$(waiting_room_metric_or_zero "$(waiting_room_metric_value 'imticket_waiting_room_scheduler_duration_seconds_count' '' "${metric_payload}")")"
      scheduler_duration_sum="$(waiting_room_metric_or_zero "$(waiting_room_metric_value 'imticket_waiting_room_scheduler_duration_seconds_sum' '' "${metric_payload}")")"
      scheduler_duration_max="$(waiting_room_metric_or_zero "$(waiting_room_metric_value 'imticket_waiting_room_scheduler_duration_seconds_max' '' "${metric_payload}")")"
      promotions="$(waiting_room_metric_or_zero "$(waiting_room_metric_value 'imticket_waiting_room_promotions_total' '' "${metric_payload}")")"
      status_waiting="$(waiting_room_metric_or_zero "$(waiting_room_metric_value 'imticket_waiting_room_operations_total' 'operation="status",result="WAITING"' "${metric_payload}")")"
      status_admitted="$(waiting_room_metric_or_zero "$(waiting_room_metric_value 'imticket_waiting_room_operations_total' 'operation="status",result="ADMITTED"' "${metric_payload}")")"
      active_count="$(waiting_room_active_count)"
      sse_active_connections="$(waiting_room_metric_or_zero "$(waiting_room_metric_value 'imticket_waiting_room_sse_active_connections' '' "${metric_payload}")")"
      sse_publish="$(waiting_room_metric_or_zero "$(waiting_room_metric_value 'imticket_waiting_room_sse_pubsub' 'operation="publish"' "${metric_payload}")")"
      sse_consume="$(waiting_room_metric_or_zero "$(waiting_room_metric_value 'imticket_waiting_room_sse_pubsub' 'operation="consume"' "${metric_payload}")")"
      complete_failures="$(waiting_room_metric_or_zero "$(waiting_room_metric_value 'imticket_waiting_room_complete_failures_total' '' "${metric_payload}")")"
      printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "${sample}" \
        "${timestamp}" \
        "${scheduler_runs}" \
        "${scheduler_duration_count}" \
        "${scheduler_duration_sum}" \
        "${scheduler_duration_max}" \
        "${promotions}" \
        "${status_waiting}" \
        "${status_admitted}" \
        "${active_count}" \
        "${sse_active_connections}" \
        "${sse_publish}" \
        "${sse_consume}" \
        "${complete_failures}" \
        >> "${run_dir}/waiting-room-metrics.tsv"
    fi
    sleep "${METRICS_INTERVAL_SECONDS}"
  done
}

max_waiting_room_metric() {
  local metrics_file="$1"
  local field_number="$2"
  awk -F '\t' -v field_number="${field_number}" '
    NR > 1 && $field_number != "" && $field_number ~ /^[0-9]+([.][0-9]+)?$/ {
      if (!found || ($field_number + 0) > max) {
        max = $field_number
        found = 1
      }
    }
    END { if (!found) print "missing"; else print max }
  ' "${metrics_file}"
}

delta_waiting_room_metric() {
  local metrics_file="$1"
  local field_number="$2"
  awk -F '\t' -v field_number="${field_number}" '
    NR > 1 && $field_number != "" && $field_number ~ /^[0-9]+([.][0-9]+)?$/ {
      if (!found) first = $field_number
      last = $field_number
      found = 1
    }
    END { if (!found) print "missing"; else print last - first }
  ' "${metrics_file}"
}

last_waiting_room_metric() {
  local metrics_file="$1"
  local field_number="$2"
  awk -F '\t' -v field_number="${field_number}" '
    NR > 1 && $field_number != "" && $field_number ~ /^[0-9]+([.][0-9]+)?$/ {
      last = $field_number
      found = 1
    }
    END { if (!found) print "missing"; else print last }
  ' "${metrics_file}"
}

waiting_room_admission_rate() {
  local metrics_file="$1"
  awk -F '\t' '
    NR > 1 && $2 ~ /^[0-9]+([.][0-9]+)?$/ && $7 ~ /^[0-9]+([.][0-9]+)?$/ {
      if (!found) {
        first_timestamp = $2
        first_promotions = $7
      }
      last_timestamp = $2
      last_promotions = $7
      found = 1
    }
    END {
      duration = last_timestamp - first_timestamp
      if (!found || duration <= 0) print "missing"
      else printf "%.4f", (last_promotions - first_promotions) / duration
    }
  ' "${metrics_file}"
}

at_least() {
  local value="$1"
  local threshold="$2"
  awk -v value="${value}" -v threshold="${threshold}" 'BEGIN { exit !(value != "missing" && value + 0 >= threshold) }'
}

within_limit() {
  local value="$1"
  local limit="$2"
  awk -v value="${value}" -v limit="${limit}" 'BEGIN { exit !(value <= limit) }'
}

mkdir -p "${GROUP_DIR}"
printf 'run_index\trun_name\tconcurrency\tmax_active_sessions\tadmit_per_interval\tmember_id_base\tseat_id_start\tbase_url\tmanagement_base_url\tpt_id\tmode\tflow\trun_dir\n' \
  > "${MANIFEST_FILE}"
printf 'run_index\tcontract_success\tjoin_success\tseat_map_success\tpre_reserve_success\tpre_reserve_conflict\tpre_reserve_admission_rejected\tunexpected_response\tjoin_p95_ms\tjoin_p99_ms\tqueue_wait_p95_ms\tseat_map_p95_ms\tseat_map_p99_ms\tpre_reserve_p95_ms\tpre_reserve_p99_ms\ttomcat_busy_max\thikari_pending_max\tstatus_admitted\twaiting_scheduler_duration_max_ms\twaiting_scheduler_runs\twaiting_admission_rate\twaiting_active_max\twaiting_active_final\twaiting_complete_failures\tactive_session_p95_ms\tactive_session_p99_ms\n' \
  > "${MATRIX_FILE}"

WAITING_ROOM_METRICS_PID=""
WAITING_ROOM_METRICS_STOP_FILE=""

stop_waiting_room_metrics() {
  if [[ -n "${WAITING_ROOM_METRICS_PID}" ]]; then
    : > "${WAITING_ROOM_METRICS_STOP_FILE}"
    kill "${WAITING_ROOM_METRICS_PID}" 2>/dev/null || true
    wait "${WAITING_ROOM_METRICS_PID}" 2>/dev/null || true
    WAITING_ROOM_METRICS_PID=""
  fi
}

trap stop_waiting_room_metrics EXIT

SEAT_IDS="${SEAT_IDS:-$(build_seat_ids)}"
slo_failures=0

if [[ "${RECONFIGURE_SERVICES}" == "true" ]]; then
  compose_up_args=(up -d --force-recreate)
  if [[ "${BUILD_IMAGES}" == "true" ]]; then
    compose_up_args+=(--build)
  fi
  env \
    SPRING_JWT_SECRET="${JWT_SECRET}" \
    RESERVATION_WAITING_ROOM_ENABLED=true \
    RESERVATION_WAITING_ROOM_ENABLED_PERFORMANCE_TIME_IDS="${PT_ID}" \
    RESERVATION_WAITING_ROOM_MAX_ACTIVE_SESSIONS="${MAX_ACTIVE_SESSIONS}" \
    RESERVATION_WAITING_ROOM_ADMIT_PER_INTERVAL="${ADMIT_PER_INTERVAL}" \
    RESERVATION_WAITING_ROOM_PROMOTION_METADATA_PIPELINE_ENABLED="${PROMOTION_METADATA_PIPELINE_ENABLED}" \
    RESERVATION_WAITING_ROOM_PASS_SECRET="${WAITING_ROOM_PASS_SECRET}" \
    docker compose --profile waiting-room-ingress-experiment "${compose_up_args[@]}" app waiting-room-service waiting-room-gateway

  wait_for_health "Reservation Application" "${MANAGEMENT_BASE_URL}/actuator/health"
  wait_for_health "Waiting Room Service" "${WAITING_ROOM_MANAGEMENT_BASE_URL}/actuator/health"
fi

for run_index in 1 2 3; do
  run_name="d-final-performance-closure-2000-${RUN_GROUP}-r${run_index}"
  run_dir="${GROUP_DIR}/${run_name}"
  # fixture reset이 같은 회원 범위를 복원하므로 세 회차에서 재사용한다.
  run_member_id_base="${MEMBER_ID_BASE}"

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\tfull-flow\twaiting-room\t%s\n' \
    "${run_index}" \
    "${run_name}" \
    "${CONCURRENCY}" \
    "${MAX_ACTIVE_SESSIONS}" \
    "${ADMIT_PER_INTERVAL}" \
    "${run_member_id_base}" \
    "${SEAT_ID_START}" \
    "${BASE_URL}" \
    "${MANAGEMENT_BASE_URL}" \
    "${PT_ID}" \
    "${run_dir}" \
    >> "${MANIFEST_FILE}"

  echo "[D] run ${run_index}/3: ${run_name}"
  if [[ "${RESET_FIXTURE}" == "true" ]]; then
    PT_ID="${PT_ID}" \
    MEMBER_ID_START=$((run_member_id_base + 1)) \
    MEMBER_COUNT="${MEMBER_COUNT}" \
    MYSQL_USE_DOCKER="${MYSQL_USE_DOCKER}" \
    REDIS_USE_DOCKER="${REDIS_USE_DOCKER}" \
    bash "${SCRIPT_DIR}/reset_waiting_room_fixture.sh"
  fi

  mkdir -p "${run_dir}"
  WAITING_ROOM_METRICS_STOP_FILE="${run_dir}/.stop-waiting-room-metrics"
  rm -f "${WAITING_ROOM_METRICS_STOP_FILE}"
  collect_waiting_room_metrics "${run_dir}" "${WAITING_ROOM_METRICS_STOP_FILE}" &
  WAITING_ROOM_METRICS_PID=$!

  set +e
  BASE_URL="${BASE_URL}" \
  MANAGEMENT_BASE_URL="${MANAGEMENT_BASE_URL}" \
  PT_ID="${PT_ID}" \
  JWT_SECRET="${JWT_SECRET}" \
  MODE=full-flow \
  FLOW=waiting-room \
  CONCURRENCY="${CONCURRENCY}" \
  MEMBER_ID_BASE="${run_member_id_base}" \
  STATUS_POLLS="${STATUS_POLLS}" \
  STATUS_POLL_INTERVAL_MS="${STATUS_POLL_INTERVAL_MS}" \
  STATUS_POLL_JITTER_RATIO="${STATUS_POLL_JITTER_RATIO}" \
  SEAT_IDS="${SEAT_IDS}" \
  REQUEST_TIMEOUT="${REQUEST_TIMEOUT}" \
  MAX_DURATION="${MAX_DURATION}" \
  START_DELAY_SECONDS="${START_DELAY_SECONDS}" \
  METRICS_INTERVAL_SECONDS="${METRICS_INTERVAL_SECONDS}" \
  RUN_NAME="${run_name}" \
  RUN_DIR="${run_dir}" \
  bash "${SCRIPT_DIR}/run_waiting_room_load.sh"
  load_exit=$?
  set -e
  stop_waiting_room_metrics
  if (( load_exit != 0 )); then
    echo "Waiting Room 부하 실행 실패: run=${run_name} exit=${load_exit}" >&2
    exit "${load_exit}"
  fi

  summary_file="${run_dir}/k6-summary.json"
  metrics_file="${run_dir}/app-metrics.tsv"
  waiting_room_metrics_file="${run_dir}/waiting-room-metrics.tsv"
  contract_value="$(metric_value "${summary_file}" waiting_room_contract_success value)"
  join_success="$(metric_count "${summary_file}" waiting_room_join_success)"
  seat_map_success="$(metric_count "${summary_file}" waiting_room_seat_map_success)"
  pre_reserve_success="$(metric_count "${summary_file}" waiting_room_pre_reserve_success)"
  pre_reserve_conflict="$(metric_count "${summary_file}" waiting_room_pre_reserve_conflict)"
  pre_reserve_admission_rejected="$(metric_count "${summary_file}" waiting_room_pre_reserve_admission_rejected)"
  unexpected_response="$(metric_count "${summary_file}" waiting_room_unexpected_response)"
  join_p95="$(numeric_or_zero "$(metric_value "${summary_file}" waiting_room_join_duration 'p(95)')")"
  join_p99="$(numeric_or_zero "$(metric_value "${summary_file}" waiting_room_join_duration 'p(99)')")"
  queue_wait_p95="$(numeric_or_zero "$(metric_value "${summary_file}" waiting_room_queue_wait_duration 'p(95)')")"
  seat_map_p95="$(numeric_or_zero "$(metric_value "${summary_file}" waiting_room_seat_map_duration 'p(95)')")"
  seat_map_p99="$(numeric_or_zero "$(metric_value "${summary_file}" waiting_room_seat_map_duration 'p(99)')")"
  pre_reserve_p95="$(numeric_or_zero "$(metric_value "${summary_file}" waiting_room_pre_reserve_duration 'p(95)')")"
  pre_reserve_p99="$(numeric_or_zero "$(metric_value "${summary_file}" waiting_room_pre_reserve_duration 'p(99)')")"
  tomcat_busy_max="$(max_app_metric "${metrics_file}" 3)"
  hikari_pending_max="$(max_app_metric "${metrics_file}" 7)"
  status_admitted="$(metric_count "${summary_file}" waiting_room_status_admitted)"
  waiting_scheduler_duration_max_seconds="$(max_waiting_room_metric "${waiting_room_metrics_file}" 6)"
  waiting_scheduler_duration_max_ms="$(awk -v value="${waiting_scheduler_duration_max_seconds}" 'BEGIN { if (value == "missing") print "missing"; else printf "%.3f", value * 1000 }')"
  waiting_scheduler_runs="$(delta_waiting_room_metric "${waiting_room_metrics_file}" 3)"
  waiting_admission_rate="$(waiting_room_admission_rate "${waiting_room_metrics_file}")"
  waiting_active_max="$(max_waiting_room_metric "${waiting_room_metrics_file}" 10)"
  waiting_active_final="$(last_waiting_room_metric "${waiting_room_metrics_file}" 10)"
  waiting_complete_failures="$(last_waiting_room_metric "${waiting_room_metrics_file}" 14)"
  active_session_p95="$(numeric_or_zero "$(metric_value "${summary_file}" waiting_room_active_session_duration 'p(95)')")"
  active_session_p99="$(numeric_or_zero "$(metric_value "${summary_file}" waiting_room_active_session_duration 'p(99)')")"

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "${run_index}" \
    "${contract_value}" \
    "${join_success}" \
    "${seat_map_success}" \
    "${pre_reserve_success}" \
    "${pre_reserve_conflict}" \
    "${pre_reserve_admission_rejected}" \
    "${unexpected_response}" \
    "${join_p95}" \
    "${join_p99}" \
    "${queue_wait_p95}" \
    "${seat_map_p95}" \
    "${seat_map_p99}" \
    "${pre_reserve_p95}" \
    "${pre_reserve_p99}" \
    "${tomcat_busy_max}" \
    "${hikari_pending_max}" \
    "${status_admitted}" \
    "${waiting_scheduler_duration_max_ms}" \
    "${waiting_scheduler_runs}" \
    "${waiting_admission_rate}" \
    "${waiting_active_max}" \
    "${waiting_active_final}" \
    "${waiting_complete_failures}" \
    "${active_session_p95}" \
    "${active_session_p99}" \
    >> "${MATRIX_FILE}"

  if ! is_one "${contract_value}" \
    || [[ "${join_success}" != "${CONCURRENCY}" ]] \
    || [[ "${seat_map_success}" != "${CONCURRENCY}" ]] \
    || [[ "${pre_reserve_success}" != "${CONCURRENCY}" ]] \
    || [[ "${pre_reserve_conflict}" != "0" ]] \
    || [[ "${pre_reserve_admission_rejected}" != "0" ]] \
    || [[ "${unexpected_response}" != "0" ]] \
    || [[ "${status_admitted}" != "${CONCURRENCY}" ]] \
    || [[ "${tomcat_busy_max}" == "missing" ]] \
    || [[ "${hikari_pending_max}" == "missing" ]] \
    || [[ "${waiting_scheduler_duration_max_ms}" == "missing" ]] \
    || [[ "${waiting_scheduler_runs}" == "missing" ]] \
    || ! at_least "${waiting_admission_rate}" 22 \
    || ! within_limit "${join_p95}" 30000 \
    || ! within_limit "${join_p99}" 30000 \
    || ! within_limit "${queue_wait_p95}" 90000 \
    || ! within_limit "${seat_map_p95}" 1000 \
    || ! within_limit "${seat_map_p99}" 2000 \
    || ! within_limit "${pre_reserve_p95}" 2000 \
    || ! within_limit "${pre_reserve_p99}" 5000 \
    || ! within_limit "${tomcat_busy_max}" 100 \
    || ! within_limit "${hikari_pending_max}" 0; then
    echo "D 종료 SLO 실패: run=${run_name}" >&2
    echo "matrix=${MATRIX_FILE}" >&2
    slo_failures=$((slo_failures + 1))
    if [[ "${STOP_ON_SLO_FAILURE}" == "true" ]]; then
      exit 1
    fi
  fi
done

if (( slo_failures > 0 )); then
  echo "D 종료 SLO 실패 회차 수=${slo_failures}: matrix=${MATRIX_FILE}" >&2
  exit 1
fi

printf 'd_runs=3\nmanifest=%s\nclosure_matrix=%s\n' \
  "${MANIFEST_FILE}" \
  "${MATRIX_FILE}"
