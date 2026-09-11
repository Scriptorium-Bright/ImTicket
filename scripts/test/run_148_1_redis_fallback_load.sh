#!/usr/bin/env bash
set -euo pipefail

# 148.1 W4 Redis 장애 fallback 보호 예산 검증
#
# Redis 장애 중에는 Waiting Room access guard도 Redis를 사용하므로
# protected-flow가 아닌 direct seat-map 경로를 사용한다. 애플리케이션은
# waiting-room disabled, seat-map cache enabled 상태로 별도 기동해야 한다.
#
# 기본값은 Redis 상태를 변경하지 않는다. MANAGE_REDIS=true를 명시한 경우에만
# docker compose stop/start redis를 실행한다.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

BASE_URL="${BASE_URL:-http://127.0.0.1:10080}"
MANAGEMENT_BASE_URL="${MANAGEMENT_BASE_URL:-http://127.0.0.1:10081}"
PT_ID="${PT_ID:-900000001}"
JWT_SECRET="${JWT_SECRET:-${SPRING_JWT_SECRET:-}}"
CONCURRENCY="${CONCURRENCY:-2000}"
MEMBER_ID_BASE="${MEMBER_ID_BASE:-900000000}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-30s}"
MAX_DURATION="${MAX_DURATION:-10m}"
REDIS_SERVICE="${REDIS_SERVICE:-redis}"
MANAGE_REDIS="${MANAGE_REDIS:-false}"
PRECONDITION_CONFIRMED="${PRECONDITION_CONFIRMED:-false}"
RUN_GROUP="${RUN_GROUP:-$(date -u +%Y%m%dT%H%M%SZ)}"
RUN_NAME="w4-redis-fallback-${RUN_GROUP}"
RUN_DIR="${RUN_DIR:-${ROOT_DIR}/build/k6-results/148.1-seat-availability-read-model/${RUN_NAME}}"

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
require_positive_integer "MEMBER_ID_BASE" "${MEMBER_ID_BASE}"
require_boolean "MANAGE_REDIS" "${MANAGE_REDIS}"
require_boolean "PRECONDITION_CONFIRMED" "${PRECONDITION_CONFIRMED}"

if [[ -z "${JWT_SECRET}" ]]; then
  echo "JWT_SECRET 또는 SPRING_JWT_SECRET이 필요합니다." >&2
  exit 1
fi
if [[ "${PRECONDITION_CONFIRMED}" != "true" ]]; then
  echo "다음 조건을 확인한 뒤 PRECONDITION_CONFIRMED=true를 지정하십시오." >&2
  echo "- application: waiting-room disabled, seat-map cache enabled for PT_ID=${PT_ID}" >&2
  echo "- load path: direct seat-map, no entry-pass validation" >&2
  echo "- Redis failure: service stopped or connection failure is observable" >&2
  exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "jq가 필요합니다." >&2
  exit 1
fi

wait_for_application_health() {
  local attempt
  for ((attempt = 1; attempt <= 240; attempt += 1)); do
    if curl -fsS --connect-timeout 1 --max-time 2 \
      "${MANAGEMENT_BASE_URL}/actuator/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "애플리케이션 health check가 제한 시간 안에 통과하지 못했습니다: ${MANAGEMENT_BASE_URL}/actuator/health" >&2
  exit 1
}

mkdir -p "${RUN_DIR}"
cat > "${RUN_DIR}/run-manifest.tsv" <<EOF
case	pt_id	concurrency	base_url	management_base_url	redis_failure	manage_redis	mode	flow	result_dir
W4	${PT_ID}	${CONCURRENCY}	${BASE_URL}	${MANAGEMENT_BASE_URL}	confirmed	${MANAGE_REDIS}	seat-map	direct	${RUN_DIR}
EOF

restart_redis() {
  if [[ "${MANAGE_REDIS}" == "true" ]]; then
    docker compose start "${REDIS_SERVICE}" >/dev/null
  fi
}
trap restart_redis EXIT

if [[ "${MANAGE_REDIS}" == "true" ]]; then
  wait_for_application_health
  docker compose stop "${REDIS_SERVICE}"
fi

BASE_URL="${BASE_URL}" \
MANAGEMENT_BASE_URL="${MANAGEMENT_BASE_URL}" \
PT_ID="${PT_ID}" \
JWT_SECRET="${JWT_SECRET}" \
MODE=seat-map \
FLOW=direct \
CONCURRENCY="${CONCURRENCY}" \
MEMBER_ID_BASE="${MEMBER_ID_BASE}" \
STATUS_POLLS=0 \
SEAT_MAP_FALLBACK_REJECTION_EXPECTED=true \
REQUEST_TIMEOUT="${REQUEST_TIMEOUT}" \
MAX_DURATION="${MAX_DURATION}" \
RUN_NAME="${RUN_NAME}" \
RUN_DIR="${RUN_DIR}" \
bash "${SCRIPT_DIR}/run_waiting_room_load.sh"

summary_file="${RUN_DIR}/k6-summary.json"
contract="$(jq -r '.metrics.waiting_room_contract_success.value // 0' "${summary_file}")"
seat_map_success="$(jq -r '.metrics.waiting_room_seat_map_success.count // 0' "${summary_file}")"
seat_map_fallback_rejected="$(jq -r '.metrics.waiting_room_seat_map_fallback_rejected.count // 0' "${summary_file}")"
seat_map_p95="$(jq -r '.metrics.waiting_room_protected_duration["p(95)"] // 0' "${summary_file}")"
seat_map_p99="$(jq -r '.metrics.waiting_room_protected_duration["p(99)"] // 0' "${summary_file}")"

printf 'contract=%s\nseat_map_success=%s\nseat_map_fallback_rejected=%s\nseat_map_p95_ms=%s\nseat_map_p99_ms=%s\n' \
  "${contract}" "${seat_map_success}" "${seat_map_fallback_rejected}" "${seat_map_p95}" "${seat_map_p99}"
printf 'result_dir=%s\n' "${RUN_DIR}"
