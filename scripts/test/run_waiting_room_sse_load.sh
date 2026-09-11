#!/usr/bin/env bash
set -euo pipefail

# SSE connection 수와 protected reservation 경로를 같은 시간축으로 수집한다.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/load_env_defaults.sh"
load_imticket_env "${REPO_ROOT}/.env"
WORKLOAD_SCRIPT="${SCRIPT_DIR}/146-waiting-room-sse-load.mjs"

BASE_URL="${BASE_URL:-http://127.0.0.1:10080}"
MANAGEMENT_BASE_URL="${MANAGEMENT_BASE_URL:-http://127.0.0.1:10081}"
PT_ID="${PT_ID:?PT_ID가 필요합니다.}"
JWT_SECRET="${JWT_SECRET:?JWT_SECRET이 필요합니다.}"
COHORT="${COHORT:-2000}"
MEMBER_ID_BASE="${MEMBER_ID_BASE:-900000000}"
WALLET_ID_BASE="${WALLET_ID_BASE:-0}"
SEAT_ID_START="${SEAT_ID_START:-900000001}"
SEAT_IDS="${SEAT_IDS:-}"
START_DELAY_MS="${START_DELAY_MS:-2000}"
REQUEST_TIMEOUT_MS="${REQUEST_TIMEOUT_MS:-30000}"
MAX_WAIT_MS="${MAX_WAIT_MS:-300000}"
METRICS_INTERVAL_SECONDS="${METRICS_INTERVAL_SECONDS:-0.2}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-16380}"
RUN_NAME="${RUN_NAME:-146.6.6-sse-${COHORT}-$(date +%Y%m%dT%H%M%S)}"
RUN_DIR="${RUN_DIR:-${REPO_ROOT}/build/k6-results/${RUN_NAME}}"

if ! [[ "${COHORT}" =~ ^[1-9][0-9]*$ ]]; then
  echo "COHORT는 양의 정수여야 합니다." >&2
  exit 1
fi
if ! command -v node >/dev/null 2>&1; then
  echo "node가 필요합니다." >&2
  exit 1
fi
if ! [[ "${SEAT_ID_START}" =~ ^[1-9][0-9]*$ ]]; then
  echo "SEAT_ID_START는 양의 정수여야 합니다." >&2
  exit 1
fi

build_seat_ids() {
  local offset
  local seat_ids=""
  for ((offset = 0; offset < COHORT; offset += 1)); do
    if [[ -n "${seat_ids}" ]]; then
      seat_ids+=","
    fi
    seat_ids+=$((SEAT_ID_START + offset))
  done
  printf '%s' "${seat_ids}"
}

if [[ -z "${SEAT_IDS}" ]]; then
  SEAT_IDS="$(build_seat_ids)"
fi

mkdir -p "${RUN_DIR}"

metric_value() {
  local metric_name="$1"
  local payload="$2"
  printf '%s\n' "${payload}" | awk -v metric_name="${metric_name}" '
    index($1, metric_name) == 1 && !found { print $NF; found = 1 }
  '
}

collect_metrics() {
  local metric_payload sample timestamp
  printf 'sample\tepoch_seconds\ttomcat_busy\ttomcat_current\ttomcat_max\thikari_active\thikari_pending\thikari_max\tsse_active_connections\tsse_registry_size\n' \
    > "${RUN_DIR}/app-metrics.tsv"
  sample=0
  while true; do
    sample=$((sample + 1))
    timestamp="$(date +%s)"
    metric_payload="$(curl -fsS --connect-timeout 1 --max-time 1 "${MANAGEMENT_BASE_URL}/actuator/prometheus" 2>/dev/null || true)"
    if [[ -n "${metric_payload}" ]]; then
      printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "${sample}" \
        "${timestamp}" \
        "$(metric_value 'tomcat_threads_busy_threads' "${metric_payload}")" \
        "$(metric_value 'tomcat_threads_current_threads' "${metric_payload}")" \
        "$(metric_value 'tomcat_threads_config_max_threads' "${metric_payload}")" \
        "$(metric_value 'hikaricp_connections_active' "${metric_payload}")" \
        "$(metric_value 'hikaricp_connections_pending' "${metric_payload}")" \
        "$(metric_value 'hikaricp_connections_max' "${metric_payload}")" \
        "$(metric_value 'imticket_waiting_room_sse_active_connections' "${metric_payload}")" \
        "$(metric_value 'imticket_waiting_room_sse_registry_size' "${metric_payload}")" \
        >> "${RUN_DIR}/app-metrics.tsv"
    fi
    sleep "${METRICS_INTERVAL_SECONDS}"
  done
}

curl -fsS --max-time 2 "${MANAGEMENT_BASE_URL}/actuator/prometheus" > "${RUN_DIR}/app-metrics-before.prom" || true
redis-cli -h "${REDIS_HOST}" -p "${REDIS_PORT}" INFO commandstats > "${RUN_DIR}/redis-commandstats-before.txt" || true
collect_metrics &
COLLECTOR_PID=$!

cleanup() {
  local status=$?
  kill "${COLLECTOR_PID}" 2>/dev/null || true
  wait "${COLLECTOR_PID}" 2>/dev/null || true
  return "${status}"
}
trap cleanup EXIT

set +e
BASE_URL="${BASE_URL}" \
PT_ID="${PT_ID}" \
JWT_SECRET="${JWT_SECRET}" \
COHORT="${COHORT}" \
MEMBER_ID_BASE="${MEMBER_ID_BASE}" \
WALLET_ID_BASE="${WALLET_ID_BASE}" \
SEAT_IDS="${SEAT_IDS}" \
START_DELAY_MS="${START_DELAY_MS}" \
REQUEST_TIMEOUT_MS="${REQUEST_TIMEOUT_MS}" \
MAX_WAIT_MS="${MAX_WAIT_MS}" \
RESULT_PATH="${RUN_DIR}/sse-summary.json" \
node "${WORKLOAD_SCRIPT}" > "${RUN_DIR}/sse-load.log" 2>&1
WORKLOAD_EXIT=$?
set -e

redis-cli -h "${REDIS_HOST}" -p "${REDIS_PORT}" INFO commandstats > "${RUN_DIR}/redis-commandstats-after.txt" || true
curl -fsS --max-time 2 "${MANAGEMENT_BASE_URL}/actuator/prometheus" > "${RUN_DIR}/app-metrics-after.prom" || true

echo "run_dir=${RUN_DIR}"
exit "${WORKLOAD_EXIT}"
