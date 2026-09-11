#!/usr/bin/env bash
set -euo pipefail

# 148.1 좌석 현황 Read Model 검증 실행기
#
# 기본 동작은 기존 146.6.1 W0/W1 원시 결과를 148.1 기준으로 검증하는 것이다.
# 실제 재실행은 ACTION=run-w0, ACTION=run-w1 또는 ACTION=run-all을 명시한다.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

ACTION="${ACTION:-verify-existing}"
PT_ID="${PT_ID:-900000001}"
CONCURRENCY="${CONCURRENCY:-2000}"
RUNS="${RUNS:-3}"
BASE_URL="${BASE_URL:-http://127.0.0.1:10080}"
MANAGEMENT_BASE_URL="${MANAGEMENT_BASE_URL:-http://127.0.0.1:10081}"
JWT_SECRET="${JWT_SECRET:-${SPRING_JWT_SECRET:-}}"
MEMBER_ID_BASE="${MEMBER_ID_BASE:-900000000}"
STATUS_POLLS="${STATUS_POLLS:-5}"
STATUS_POLL_INTERVAL_MS="${STATUS_POLL_INTERVAL_MS:-1000}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-30s}"
MAX_DURATION="${MAX_DURATION:-10m}"
MAX_ACTIVE_SESSIONS="${MAX_ACTIVE_SESSIONS:-2000}"
ADMIT_PER_INTERVAL="${ADMIT_PER_INTERVAL:-2000}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-16380}"
RESET_FIXTURE="${RESET_FIXTURE:-true}"
RECONFIGURE_SERVICES="${RECONFIGURE_SERVICES:-false}"
BUILD_IMAGES="${BUILD_IMAGES:-false}"
WARMUP="${WARMUP:-true}"
RUN_GROUP="${RUN_GROUP:-$(date -u +%Y%m%dT%H%M%SZ)}"

REFERENCE_ROOT="${REFERENCE_ROOT:-${ROOT_DIR}/build/k6-results}"
EXISTING_BASELINE_PREFIX="${EXISTING_BASELINE_PREFIX:-146.6.1-baseline-seat-map-2000-run}"
EXISTING_CACHE_PREFIX="${EXISTING_CACHE_PREFIX:-146.6.1-cache-hit-seat-map-2000-run}"
RESULT_ROOT="${RESULT_ROOT:-${ROOT_DIR}/build/k6-results/148.1-seat-availability-read-model/${RUN_GROUP}}"
MANIFEST_FILE="${RESULT_ROOT}/run-manifest.tsv"
COMPARISON_FILE="${RESULT_ROOT}/comparison.tsv"

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
require_positive_integer "MEMBER_ID_BASE" "${MEMBER_ID_BASE}"
require_positive_integer "STATUS_POLLS" "${STATUS_POLLS}"
require_positive_integer "STATUS_POLL_INTERVAL_MS" "${STATUS_POLL_INTERVAL_MS}"
require_positive_integer "MAX_ACTIVE_SESSIONS" "${MAX_ACTIVE_SESSIONS}"
require_positive_integer "ADMIT_PER_INTERVAL" "${ADMIT_PER_INTERVAL}"
require_boolean "RESET_FIXTURE" "${RESET_FIXTURE}"
require_boolean "RECONFIGURE_SERVICES" "${RECONFIGURE_SERVICES}"
require_boolean "BUILD_IMAGES" "${BUILD_IMAGES}"
require_boolean "WARMUP" "${WARMUP}"

if [[ "${CONCURRENCY}" != "2000" || "${RUNS}" != "3" ]]; then
  echo "148.1 고정 조건은 CONCURRENCY=2000, RUNS=3입니다." >&2
  exit 1
fi

metric_value() {
  local summary_file="$1"
  local metric_name="$2"
  local field="$3"
  jq -r --arg metric_name "${metric_name}" --arg field "${field}" \
    '.metrics[$metric_name][$field] // 0' "${summary_file}"
}

metric_count() {
  local summary_file="$1"
  local metric_name="$2"
  jq -r --arg metric_name "${metric_name}" \
    '.metrics[$metric_name].count // 0' "${summary_file}"
}

max_tsv_value() {
  local file="$1"
  local field_number="$2"
  awk -F '\t' -v field_number="${field_number}" '
    NR > 1 && $field_number ~ /^[0-9]+([.][0-9]+)?$/ {
      if (!found || ($field_number + 0) > max) max = $field_number
      found = 1
    }
    END { if (!found) print "0"; else print max }
  ' "${file}"
}

validate_run() {
  local run_dir="$1"
  local label="$2"
  local cache_mode="$3"
  local expected_projection="$4"
  local summary_file="${run_dir}/k6-summary.json"
  local app_metrics_file="${run_dir}/app-metrics.tsv"
  local contract join_success seat_map_success protected_p50 protected_p95 protected_p99 bytes
  local tomcat_busy hikari_pending result

  for required_file in "${summary_file}" "${app_metrics_file}" "${run_dir}/k6.log"; do
    if [[ ! -f "${required_file}" ]]; then
      echo "결과 파일이 없습니다: ${required_file}" >&2
      return 1
    fi
  done

  contract="$(metric_value "${summary_file}" waiting_room_contract_success value)"
  join_success="$(metric_count "${summary_file}" waiting_room_join_success)"
  seat_map_success="$(metric_count "${summary_file}" waiting_room_seat_map_success)"
  protected_p50="$(metric_value "${summary_file}" waiting_room_protected_duration med)"
  protected_p95="$(metric_value "${summary_file}" waiting_room_protected_duration 'p(95)')"
  protected_p99="$(metric_value "${summary_file}" waiting_room_protected_duration 'p(99)')"
  bytes="$(metric_value "${summary_file}" data_received count)"
  tomcat_busy="$(max_tsv_value "${app_metrics_file}" 3)"
  hikari_pending="$(max_tsv_value "${app_metrics_file}" 7)"

  result="PASS"
  if [[ "${contract}" != "1" && "${contract}" != "1.0" ]] \
    || [[ "${join_success}" != "${CONCURRENCY}" ]] \
    || [[ "${seat_map_success}" != "${CONCURRENCY}" ]]; then
    result="FAIL"
  fi

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "${label}" "${cache_mode}" "${contract}" "${join_success}" "${seat_map_success}" \
    "${protected_p50}" "${protected_p95}" "${protected_p99}" "${bytes}" \
    "${tomcat_busy}" "${hikari_pending}" "${result}" >> "${COMPARISON_FILE}"

  printf '%s\n' "${label}: ${result} contract=${contract} join=${join_success} seat_map=${seat_map_success} p95_ms=${protected_p95} hikari_pending_max=${hikari_pending}"
  [[ "${result}" == "PASS" ]]
}

write_manifest_header() {
  mkdir -p "${RESULT_ROOT}"
  printf 'case\tcache_mode\tpt_id\tconcurrency\truns\tmax_active_sessions\tadmit_per_interval\tbase_url\tmanagement_base_url\tresult_dir\tsource\n' \
    > "${MANIFEST_FILE}"
  printf 'case\tcache_mode\tcontract\tjoin_success\tseat_map_success\tp50_ms\tp95_ms\tp99_ms\tdata_received_bytes\ttomcat_busy_max\thikari_pending_max\tresult\n' \
    > "${COMPARISON_FILE}"
}

record_manifest() {
  local case_name="$1"
  local cache_mode="$2"
  local result_dir="$3"
  local source="$4"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "${case_name}" "${cache_mode}" "${PT_ID}" "${CONCURRENCY}" "${RUNS}" \
    "${MAX_ACTIVE_SESSIONS}" "${ADMIT_PER_INTERVAL}" "${BASE_URL}" \
    "${MANAGEMENT_BASE_URL}" "${result_dir}" "${source}" >> "${MANIFEST_FILE}"
}

verify_existing_group() {
  local case_name="$1"
  local cache_mode="$2"
  local prefix="$3"
  local expected_projection="$4"
  local run_index run_dir

  for run_index in 1 2 3; do
    run_dir="${REFERENCE_ROOT}/${prefix}${run_index}"
    record_manifest "${case_name}-${run_index}" "${cache_mode}" "${run_dir}" "existing-146.6.1"
    validate_run "${run_dir}" "${case_name}-${run_index}" "${cache_mode}" "${expected_projection}"
  done
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

configure_services() {
  [[ "${RECONFIGURE_SERVICES}" == "true" ]] || return 0
  local -a compose_args=(up -d --force-recreate)
  if [[ "${BUILD_IMAGES}" == "true" ]]; then
    compose_args+=(--build)
  fi

  env \
    SPRING_JWT_SECRET="${JWT_SECRET}" \
    RESERVATION_WAITING_ROOM_ENABLED=true \
    RESERVATION_WAITING_ROOM_ENABLED_PERFORMANCE_TIME_IDS="${PT_ID}" \
    RESERVATION_WAITING_ROOM_MAX_ACTIVE_SESSIONS="${MAX_ACTIVE_SESSIONS}" \
    RESERVATION_WAITING_ROOM_ADMIT_PER_INTERVAL="${ADMIT_PER_INTERVAL}" \
    RESERVATION_SEAT_MAP_CACHE_ENABLED="${CACHE_ENABLED}" \
    RESERVATION_SEAT_MAP_CACHE_ENABLED_PERFORMANCE_TIME_IDS="${CACHE_PERFORMANCE_IDS}" \
    docker compose --profile waiting-room-ingress-experiment "${compose_args[@]}" app waiting-room-service waiting-room-gateway

  wait_for_health "Reservation Application" "${MANAGEMENT_BASE_URL}/actuator/health"
}

clear_snapshot() {
  if command -v redis-cli >/dev/null 2>&1; then
    redis-cli -h "${REDIS_HOST}" -p "${REDIS_PORT}" \
      DEL \
        "reservation:seat-map:{${PT_ID}}:version" \
        "reservation:seat-map:{${PT_ID}}:snapshot:v2" \
        "reservation:seat-map:{${PT_ID}}:snapshot" >/dev/null || true
  fi
}

run_case() {
  local case_name="$1"
  local cache_mode="$2"
  local cache_enabled="$3"
  local cache_performance_ids="$4"
  local case_root="${RESULT_ROOT}/${case_name}"
  local run_index run_name run_dir

  CACHE_ENABLED="${cache_enabled}"
  CACHE_PERFORMANCE_IDS="${cache_performance_ids}"
  configure_services

  for run_index in 1 2 3; do
    run_name="${case_name}-${RUN_GROUP}-r${run_index}"
    run_dir="${case_root}/run${run_index}"
    mkdir -p "${run_dir}"
    record_manifest "${case_name}-${run_index}" "${cache_mode}" "${run_dir}" "new-run"

    if [[ "${RESET_FIXTURE}" == "true" ]]; then
      PT_ID="${PT_ID}" \
      MEMBER_ID_START=$((MEMBER_ID_BASE + 1)) \
      MEMBER_COUNT="${CONCURRENCY}" \
      bash "${SCRIPT_DIR}/reset_waiting_room_fixture.sh"
    fi

    if [[ "${cache_mode}" == "warm-hit" ]]; then
      clear_snapshot
      if [[ "${WARMUP}" == "true" ]]; then
        BASE_URL="${BASE_URL}" MANAGEMENT_BASE_URL="${MANAGEMENT_BASE_URL}" \
        PT_ID="${PT_ID}" JWT_SECRET="${JWT_SECRET}" MODE=seat-map FLOW=waiting-room \
        CONCURRENCY=1 MEMBER_ID_BASE="${MEMBER_ID_BASE}" STATUS_POLLS="${STATUS_POLLS}" \
        STATUS_POLL_INTERVAL_MS="${STATUS_POLL_INTERVAL_MS}" REQUEST_TIMEOUT="${REQUEST_TIMEOUT}" \
        MAX_DURATION="${MAX_DURATION}" RUN_NAME="${run_name}-warmup" \
        RUN_DIR="${run_dir}/warmup" bash "${SCRIPT_DIR}/run_waiting_room_load.sh"
      fi
    fi

    BASE_URL="${BASE_URL}" MANAGEMENT_BASE_URL="${MANAGEMENT_BASE_URL}" \
    PT_ID="${PT_ID}" JWT_SECRET="${JWT_SECRET}" MODE=seat-map FLOW=waiting-room \
    CONCURRENCY="${CONCURRENCY}" MEMBER_ID_BASE="${MEMBER_ID_BASE}" \
    STATUS_POLLS="${STATUS_POLLS}" STATUS_POLL_INTERVAL_MS="${STATUS_POLL_INTERVAL_MS}" \
    REQUEST_TIMEOUT="${REQUEST_TIMEOUT}" MAX_DURATION="${MAX_DURATION}" \
    RUN_NAME="${run_name}" RUN_DIR="${run_dir}" \
    bash "${SCRIPT_DIR}/run_waiting_room_load.sh"

    validate_run "${run_dir}" "${case_name}-${run_index}" "${cache_mode}" ""
  done
}

write_manifest_header

case "${ACTION}" in
  verify-existing)
    verify_existing_group "W0" "disabled" "${EXISTING_BASELINE_PREFIX}" "2000"
    verify_existing_group "W1" "warm-hit" "${EXISTING_CACHE_PREFIX}" "0"
    ;;
  run-w0)
    if [[ -z "${JWT_SECRET}" ]]; then
      echo "실행 모드에는 JWT_SECRET 또는 SPRING_JWT_SECRET이 필요합니다." >&2
      exit 1
    fi
    run_case "W0-cache-disabled" "disabled" "false" ""
    ;;
  run-w1)
    if [[ -z "${JWT_SECRET}" ]]; then
      echo "실행 모드에는 JWT_SECRET 또는 SPRING_JWT_SECRET이 필요합니다." >&2
      exit 1
    fi
    run_case "W1-cache-warm-hit" "warm-hit" "true" "${PT_ID}"
    ;;
  run-all)
    if [[ -z "${JWT_SECRET}" ]]; then
      echo "실행 모드에는 JWT_SECRET 또는 SPRING_JWT_SECRET이 필요합니다." >&2
      exit 1
    fi
    run_case "W0-cache-disabled" "disabled" "false" ""
    run_case "W1-cache-warm-hit" "warm-hit" "true" "${PT_ID}"
    ;;
  *)
    echo "ACTION은 verify-existing, run-w0, run-w1, run-all 중 하나여야 합니다." >&2
    exit 1
    ;;
esac

printf 'result_root=%s\nmanifest=%s\ncomparison=%s\n' \
  "${RESULT_ROOT}" "${MANIFEST_FILE}" "${COMPARISON_FILE}"
