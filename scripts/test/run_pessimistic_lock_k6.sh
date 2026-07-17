#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODE="${MODE:-baseline}"
GRADE="${GRADE:-1}"
TRAFFIC_PROFILE="${TRAFFIC_PROFILE:-minimum}"
PT_ID="${PT_ID:-}"
SEAT_ID="${SEAT_ID:-}"
BASE_URL="${BASE_URL:-http://140.245.76.87:10080}"
JWT_SECRET="${JWT_SECRET:-}"
CONCURRENCY="${CONCURRENCY:-}"
HOLD_SECONDS="${HOLD_SECONDS:-8}"
BURST_DELAY_SECONDS="${BURST_DELAY_SECONDS:-5}"
START_AT_EPOCH_MS="${START_AT_EPOCH_MS:-}"
MAX_DURATION="${MAX_DURATION:-10m}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-15s}"
DISTRIBUTED="${DISTRIBUTED:-false}"
ALLOW_LARGE_LOAD="${ALLOW_LARGE_LOAD:-false}"
K6_EXECUTION_SEGMENT="${K6_EXECUTION_SEGMENT:-}"
K6_EXECUTION_SEGMENT_SEQUENCE="${K6_EXECUTION_SEGMENT_SEQUENCE:-}"
MYSQL_HOST="${MYSQL_HOST:-140.245.76.87}"
MYSQL_PORT="${MYSQL_PORT:-10047}"
MYSQL_USER="${MYSQL_USER:-capstone}"
MYSQL_DATABASE="${MYSQL_DATABASE:-capstone}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-${MYSQL_LOCK_TEST_PASSWORD:-}}"
K6_BIN="${K6_BIN:-k6}"
RESULT_DIR="${RESULT_DIR:-${ROOT_DIR}/build/k6-results}"

if [[ ! "${GRADE}" =~ ^[1234]$ ]]; then
  echo "GRADE는 1, 2, 3, 4 중 하나여야 합니다." >&2
  exit 1
fi
if [[ ! "${TRAFFIC_PROFILE}" =~ ^(minimum|maximum)$ ]]; then
  echo "TRAFFIC_PROFILE은 minimum 또는 maximum이어야 합니다." >&2
  exit 1
fi
if [[ ! "${DISTRIBUTED}" =~ ^(true|false)$ ]]; then
  echo "DISTRIBUTED는 true 또는 false여야 합니다." >&2
  exit 1
fi
if [[ ! "${ALLOW_LARGE_LOAD}" =~ ^(true|false)$ ]]; then
  echo "ALLOW_LARGE_LOAD는 true 또는 false여야 합니다." >&2
  exit 1
fi
if [[ ! "${PT_ID}" =~ ^[0-9]+$ ]] || [[ ! "${SEAT_ID}" =~ ^[0-9]+$ ]]; then
  echo "PT_ID와 SEAT_ID는 양의 정수여야 합니다." >&2
  exit 1
fi
if [[ -z "${JWT_SECRET}" ]]; then
  echo "JWT_SECRET을 설정해야 합니다." >&2
  exit 1
fi
if [[ "${MODE}" == "forced-timeout" && -z "${MYSQL_PASSWORD}" ]]; then
  echo "forced-timeout에는 MYSQL_PASSWORD 또는 MYSQL_LOCK_TEST_PASSWORD가 필요합니다." >&2
  exit 1
fi
if [[ "${DISTRIBUTED}" == "true" && -z "${START_AT_EPOCH_MS}" ]]; then
  echo "DISTRIBUTED=true에는 공통 START_AT_EPOCH_MS가 필요합니다." >&2
  exit 1
fi
if [[ "${DISTRIBUTED}" == "true" && ( -z "${K6_EXECUTION_SEGMENT}" || -z "${K6_EXECUTION_SEGMENT_SEQUENCE}" ) ]]; then
  echo "DISTRIBUTED=true에는 K6_EXECUTION_SEGMENT와 K6_EXECUTION_SEGMENT_SEQUENCE가 필요합니다." >&2
  exit 1
fi

if [[ -n "${CONCURRENCY}" ]]; then
  requested_concurrency="${CONCURRENCY}"
else
  case "${GRADE}:${TRAFFIC_PROFILE}" in
    1:minimum) requested_concurrency=500 ;;
    1:maximum) requested_concurrency=5000 ;;
    2:minimum) requested_concurrency=5000 ;;
    2:maximum) requested_concurrency=30000 ;;
    3:minimum) requested_concurrency=20000 ;;
    3:maximum) requested_concurrency=100000 ;;
    4:minimum) requested_concurrency=80000 ;;
    4:maximum) requested_concurrency=300000 ;;
  esac
fi
if [[ ! "${requested_concurrency}" =~ ^[1-9][0-9]*$ ]]; then
  echo "CONCURRENCY는 양의 정수여야 합니다." >&2
  exit 1
fi
if (( requested_concurrency > 5000 )) && [[ "${ALLOW_LARGE_LOAD}" != "true" ]]; then
  echo "${requested_concurrency} VU 실행에는 ALLOW_LARGE_LOAD=true를 명시해야 합니다." >&2
  exit 1
fi

mkdir -p "${RESULT_DIR}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
summary_file="${RESULT_DIR}/pessimistic-grade-${GRADE}-${TRAFFIC_PROFILE}-${MODE}-${timestamp}.json"
holder_pid=""

cleanup() {
  if [[ -n "${holder_pid}" ]] && kill -0 "${holder_pid}" 2>/dev/null; then
    kill "${holder_pid}" 2>/dev/null || true
    wait "${holder_pid}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

if [[ "${MODE}" == "forced-timeout" ]]; then
  MYSQL_PASSWORD="${MYSQL_PASSWORD}" \
  MYSQL_HOST="${MYSQL_HOST}" \
  MYSQL_PORT="${MYSQL_PORT}" \
  MYSQL_USER="${MYSQL_USER}" \
  MYSQL_DATABASE="${MYSQL_DATABASE}" \
  PT_ID="${PT_ID}" \
  SEAT_ID="${SEAT_ID}" \
  HOLD_SECONDS="${HOLD_SECONDS}" \
    "${ROOT_DIR}/scripts/test/hold_pessimistic_seat_lock.sh" \
    > "${RESULT_DIR}/lock-holder-${timestamp}.log" 2>&1 &
  holder_pid=$!

  lock_name="imticket:pessimistic-lock-holder:${SEAT_ID}"
  lock_ready=false
  for _ in $(seq 1 50); do
    if [[ "$(MYSQL_PWD="${MYSQL_PASSWORD}" mysql \
      --host="${MYSQL_HOST}" \
      --port="${MYSQL_PORT}" \
      --user="${MYSQL_USER}" \
      --protocol=tcp \
      --batch \
      --skip-column-names \
      "${MYSQL_DATABASE}" \
      --execute="SELECT IS_USED_LOCK('${lock_name}') IS NOT NULL;")" == "1" ]]; then
      lock_ready=true
      break
    fi
    sleep 0.1
  done

  if [[ "${lock_ready}" != "true" ]]; then
    echo "강제 row lock 준비를 확인하지 못했습니다." >&2
    exit 1
  fi
fi

k6_args=(
  run
  -e "MODE=${MODE}"
  -e "GRADE=${GRADE}"
  -e "TRAFFIC_PROFILE=${TRAFFIC_PROFILE}"
  -e "PT_ID=${PT_ID}"
  -e "SEAT_ID=${SEAT_ID}"
  -e "BASE_URL=${BASE_URL}"
  -e "JWT_SECRET=${JWT_SECRET}"
  -e "BURST_DELAY_SECONDS=${BURST_DELAY_SECONDS}"
  -e "MAX_DURATION=${MAX_DURATION}"
  -e "REQUEST_TIMEOUT=${REQUEST_TIMEOUT}"
  -e "DISTRIBUTED=${DISTRIBUTED}"
  --summary-export "${summary_file}"
)

if [[ -n "${START_AT_EPOCH_MS}" ]]; then
  k6_args+=( -e "START_AT_EPOCH_MS=${START_AT_EPOCH_MS}" )
fi
if [[ -n "${K6_EXECUTION_SEGMENT}" ]]; then
  k6_args+=( --execution-segment "${K6_EXECUTION_SEGMENT}" )
fi
if [[ -n "${K6_EXECUTION_SEGMENT_SEQUENCE}" ]]; then
  k6_args+=( --execution-segment-sequence "${K6_EXECUTION_SEGMENT_SEQUENCE}" )
fi

if [[ -n "${CONCURRENCY}" ]]; then
  k6_args+=( -e "CONCURRENCY=${CONCURRENCY}" )
fi

set +e
"${K6_BIN}" "${k6_args[@]}" "${ROOT_DIR}/scripts/test/01-ticket-open-run.js"
k6_status=$?
set -e

if [[ -n "${holder_pid}" ]]; then
  wait "${holder_pid}" || true
  holder_pid=""
fi

echo "Summary: ${summary_file}"
exit "${k6_status}"
