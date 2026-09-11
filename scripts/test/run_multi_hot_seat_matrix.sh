#!/usr/bin/env bash
set -euo pipefail

# 30-seat distributed hot-seat matrix.
# 공통 lock collector를 사용하되 4-seat lock matrix와 결과 root를 분리한다.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/scripts/test/load_env_defaults.sh"
load_imticket_env "${ROOT_DIR}/.env"

MATRIX_LABEL="${1:-multi-hot-seat}"
VUS_LIST="${VUS_LIST:-1000,1500,2000}"
LOCK_STRATEGIES="${LOCK_STRATEGIES:-pessimistic,reentrant}"
SEAT_POOL_SIZE="${SEAT_POOL_SIZE:-30}"
SEATS_PER_REQUEST="${SEATS_PER_REQUEST:-1}"
ADMISSION_PER_SEAT_PERMITS="${ADMISSION_PER_SEAT_PERMITS:-2000}"
RESULT_ROOT="${RESULT_ROOT:-${ROOT_DIR}/build/k6-results/multi-hot-seat-matrix}"

exec env \
  VUS_LIST="${VUS_LIST}" \
  LOCK_STRATEGIES="${LOCK_STRATEGIES}" \
  SEAT_POOL_SIZE="${SEAT_POOL_SIZE}" \
  SEATS_PER_REQUEST="${SEATS_PER_REQUEST}" \
  ADMISSION_PER_SEAT_PERMITS="${ADMISSION_PER_SEAT_PERMITS}" \
  RESULT_ROOT="${RESULT_ROOT}" \
  "${ROOT_DIR}/scripts/test/run_lock_overhead_matrix.sh" "${MATRIX_LABEL}"
