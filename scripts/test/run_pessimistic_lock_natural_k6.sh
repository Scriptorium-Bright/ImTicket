#!/usr/bin/env bash
set -euo pipefail

# 외부에서 좌석 row를 잠그지 않고 실제 Reservation API에 같은 좌석을 요청한다.
# 기존 run_pessimistic_lock_k6.sh의 baseline 경로만 사용한다.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [[ "${MODE:-baseline}" != "baseline" ]]; then
  echo "이 실행기는 MODE=baseline만 허용합니다. 강제 락 실험은 run_pessimistic_lock_k6.sh를 사용하세요." >&2
  exit 1
fi

BASE_URL="${K6_BASE_URL:-${BASE_URL:-http://127.0.0.1:10080}}" \
MODE=baseline \
GRADE="${GRADE:-1}" \
TRAFFIC_PROFILE="${TRAFFIC_PROFILE:-minimum}" \
CONCURRENCY="${CONCURRENCY:-1000}" \
exec "${ROOT_DIR}/scripts/test/run_pessimistic_lock_k6.sh"
