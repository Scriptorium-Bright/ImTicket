#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/scripts/test/load_env_defaults.sh"
load_imticket_env "${ROOT_DIR}/.env"

BASE_URL="${K6_BASE_URL:-${BASE_URL:-http://127.0.0.1:10080}}"
PT_ID="${PT_ID:-}"
SEAT_IDS="${SEAT_IDS:-}"
SEAT_POOL_SIZE="${SEAT_POOL_SIZE:-4}"
SEATS_PER_REQUEST="${SEATS_PER_REQUEST:-1}"
CONCURRENCY="${CONCURRENCY:-100}"
JWT_SECRET="${JWT_SECRET:-}"
BURST_DELAY_SECONDS="${BURST_DELAY_SECONDS:-2}"
MAX_DURATION="${MAX_DURATION:-2m}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-10s}"
K6_BIN="${K6_BIN:-k6}"
RESULT_DIR="${RESULT_DIR:-${ROOT_DIR}/build/k6-results}"

if [[ ! "${PT_ID}" =~ ^[1-9][0-9]*$ ]]; then
  echo "PT_ID는 양의 정수여야 합니다." >&2
  exit 1
fi
if [[ ! "${SEAT_POOL_SIZE}" =~ ^[1-9][0-9]*$ ]] || [[ ! "${SEATS_PER_REQUEST}" =~ ^[1-9][0-9]*$ ]]; then
  echo "SEAT_POOL_SIZE와 SEATS_PER_REQUEST는 양의 정수여야 합니다." >&2
  exit 1
fi
if [[ ! "${CONCURRENCY}" =~ ^[1-9][0-9]*$ ]]; then
  echo "CONCURRENCY는 양의 정수여야 합니다." >&2
  exit 1
fi
if [[ -z "${JWT_SECRET}" ]]; then
  echo "JWT_SECRET을 설정해야 합니다." >&2
  exit 1
fi

mkdir -p "${RESULT_DIR}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
summary_file="${RESULT_DIR}/multi-seat-distributed-${timestamp}.json"

k6_args=(
  run
  -e "BASE_URL=${BASE_URL}"
  -e "PT_ID=${PT_ID}"
  -e "SEAT_POOL_SIZE=${SEAT_POOL_SIZE}"
  -e "SEATS_PER_REQUEST=${SEATS_PER_REQUEST}"
  -e "CONCURRENCY=${CONCURRENCY}"
  -e "JWT_SECRET=${JWT_SECRET}"
  -e "BURST_DELAY_SECONDS=${BURST_DELAY_SECONDS}"
  -e "MAX_DURATION=${MAX_DURATION}"
  -e "REQUEST_TIMEOUT=${REQUEST_TIMEOUT}"
  --summary-export "${summary_file}"
)

if [[ -n "${SEAT_IDS}" ]]; then
  k6_args+=( -e "SEAT_IDS=${SEAT_IDS}" )
fi

set +e
"${K6_BIN}" "${k6_args[@]}" "${ROOT_DIR}/scripts/test/03-multi-seat-distributed-run.js"
status=$?
set -e

echo "Summary: ${summary_file}"
exit "${status}"
