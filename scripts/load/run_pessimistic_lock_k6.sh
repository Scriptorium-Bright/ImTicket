#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODE="${MODE:-baseline}"
GRADE="${GRADE:-1}"
PT_ID="${PT_ID:-}"
SEAT_ID="${SEAT_ID:-}"
BASE_URL="${BASE_URL:-http://127.0.0.1:10080}"
JWT_SECRET="${JWT_SECRET:-}"
CONCURRENCY="${CONCURRENCY:-}"
HOLD_SECONDS="${HOLD_SECONDS:-8}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-10047}"
MYSQL_USER="${MYSQL_USER:-capstone}"
MYSQL_DATABASE="${MYSQL_DATABASE:-capstone}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-${MYSQL_LOCK_TEST_PASSWORD:-}}"
K6_BIN="${K6_BIN:-k6}"
RESULT_DIR="${RESULT_DIR:-${ROOT_DIR}/build/k6-results}"

if [[ ! "${GRADE}" =~ ^[123]$ ]]; then
  echo "GRADE는 1, 2, 3 중 하나여야 합니다." >&2
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

mkdir -p "${RESULT_DIR}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
summary_file="${RESULT_DIR}/pessimistic-grade-${GRADE}-${MODE}-${timestamp}.json"
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
  SEAT_ID="${SEAT_ID}" \
  HOLD_SECONDS="${HOLD_SECONDS}" \
    "${ROOT_DIR}/scripts/load/hold_pessimistic_seat_lock.sh" \
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
  -e "PT_ID=${PT_ID}"
  -e "SEAT_ID=${SEAT_ID}"
  -e "BASE_URL=${BASE_URL}"
  -e "JWT_SECRET=${JWT_SECRET}"
  --summary-export "${summary_file}"
)

if [[ -n "${CONCURRENCY}" ]]; then
  k6_args+=( -e "CONCURRENCY=${CONCURRENCY}" )
fi

set +e
"${K6_BIN}" "${k6_args[@]}" "${ROOT_DIR}/k6-scripts/01-ticket-open-run.js"
k6_status=$?
set -e

if [[ -n "${holder_pid}" ]]; then
  wait "${holder_pid}" || true
  holder_pid=""
fi

echo "Summary: ${summary_file}"
exit "${k6_status}"
