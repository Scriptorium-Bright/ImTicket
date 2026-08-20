#!/usr/bin/env bash
set -euo pipefail

# Waiting Room API 부하와 같은 구간의 Tomcat·Hikari·CPU·Redis commandstats를 함께 남긴다.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
K6_SCRIPT="${SCRIPT_DIR}/146-waiting-room-load.js"

BASE_URL="${BASE_URL:-http://127.0.0.1:10080}"
MANAGEMENT_BASE_URL="${MANAGEMENT_BASE_URL:-http://127.0.0.1:10081}"
PT_ID="${PT_ID:?PT_ID가 필요합니다.}"
JWT_SECRET="${JWT_SECRET:?JWT_SECRET이 필요합니다.}"
MODE="${MODE:-join}"
FLOW="${FLOW:-waiting-room}"
CONCURRENCY="${CONCURRENCY:-100}"
MEMBER_ID_BASE="${MEMBER_ID_BASE:-900000000}"
WALLET_ID_BASE="${WALLET_ID_BASE:-0}"
STATUS_POLLS="${STATUS_POLLS:-5}"
STATUS_POLL_INTERVAL_MS="${STATUS_POLL_INTERVAL_MS:-1000}"
STATUS_POLL_JITTER_RATIO="${STATUS_POLL_JITTER_RATIO:-0.1}"
SEAT_IDS="${SEAT_IDS:-}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-10s}"
MAX_DURATION="${MAX_DURATION:-5m}"
METRICS_INTERVAL_SECONDS="${METRICS_INTERVAL_SECONDS:-0.2}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-16380}"
RUN_NAME="${RUN_NAME:-146.6-waiting-room-${MODE}-${CONCURRENCY}-$(date +%Y%m%dT%H%M%S)}"
RUN_DIR="${RUN_DIR:-${REPO_ROOT}/build/k6-results/${RUN_NAME}}"

if ! [[ "${CONCURRENCY}" =~ ^[1-9][0-9]*$ ]]; then
  echo "CONCURRENCY는 양의 정수여야 합니다." >&2
  exit 1
fi
if ! [[ "${PT_ID}" =~ ^[1-9][0-9]*$ ]]; then
  echo "PT_ID는 양의 정수여야 합니다." >&2
  exit 1
fi
if ! command -v k6 >/dev/null 2>&1; then
  echo "k6가 필요합니다." >&2
  exit 1
fi

mkdir -p "${RUN_DIR}"

curl -fsS --max-time 2 "${MANAGEMENT_BASE_URL}/actuator/prometheus" \
  -o "${RUN_DIR}/app-metrics-before.prom" || true

metric_value() {
  local metric_name="$1"
  local payload="$2"

  printf '%s\n' "${payload}" | awk -v metric_name="${metric_name}" '
    index($1, metric_name) == 1 { print $NF; exit }
  '
}

collect_metrics() {
  local metric_payload sample timestamp

  printf 'sample\tepoch_seconds\ttomcat_busy\ttomcat_current\ttomcat_max\thikari_active\thikari_pending\thikari_max\tprocess_cpu\tsystem_cpu\tjvm_live_threads\n' \
    > "${RUN_DIR}/app-metrics.tsv"
  sample=0
  while true; do
    sample=$((sample + 1))
    timestamp="$(date +%s)"
    metric_payload="$(curl -fsS --connect-timeout 1 --max-time 1 "${MANAGEMENT_BASE_URL}/actuator/prometheus" 2>/dev/null || true)"
    if [[ -n "${metric_payload}" ]]; then
      printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "${sample}" \
        "${timestamp}" \
        "$(metric_value 'tomcat_threads_busy_threads' "${metric_payload}")" \
        "$(metric_value 'tomcat_threads_current_threads' "${metric_payload}")" \
        "$(metric_value 'tomcat_threads_config_max_threads' "${metric_payload}")" \
        "$(metric_value 'hikaricp_connections_active' "${metric_payload}")" \
        "$(metric_value 'hikaricp_connections_pending' "${metric_payload}")" \
        "$(metric_value 'hikaricp_connections_max' "${metric_payload}")" \
        "$(metric_value 'process_cpu_usage' "${metric_payload}")" \
        "$(metric_value 'system_cpu_usage' "${metric_payload}")" \
        "$(metric_value 'jvm_threads_live_threads' "${metric_payload}")" \
        >> "${RUN_DIR}/app-metrics.tsv"
    fi
    sleep "${METRICS_INTERVAL_SECONDS}"
  done
}

redis-cli -h "${REDIS_HOST}" -p "${REDIS_PORT}" INFO commandstats \
  > "${RUN_DIR}/redis-commandstats-before.txt" || true
collect_metrics &
COLLECTOR_PID=$!

cleanup() {
  kill "${COLLECTOR_PID}" 2>/dev/null || true
  wait "${COLLECTOR_PID}" 2>/dev/null || true
}
trap cleanup EXIT

k6 run \
  -e "BASE_URL=${BASE_URL}" \
  -e "PT_ID=${PT_ID}" \
  -e "JWT_SECRET=${JWT_SECRET}" \
  -e "MODE=${MODE}" \
  -e "FLOW=${FLOW}" \
  -e "CONCURRENCY=${CONCURRENCY}" \
  -e "MEMBER_ID_BASE=${MEMBER_ID_BASE}" \
  -e "WALLET_ID_BASE=${WALLET_ID_BASE}" \
  -e "STATUS_POLLS=${STATUS_POLLS}" \
  -e "STATUS_POLL_INTERVAL_MS=${STATUS_POLL_INTERVAL_MS}" \
  -e "STATUS_POLL_JITTER_RATIO=${STATUS_POLL_JITTER_RATIO}" \
  -e "SEAT_IDS=${SEAT_IDS}" \
  -e "REQUEST_TIMEOUT=${REQUEST_TIMEOUT}" \
  -e "MAX_DURATION=${MAX_DURATION}" \
  --summary-export "${RUN_DIR}/k6-summary.json" \
  "${K6_SCRIPT}" \
  > "${RUN_DIR}/k6.log" 2>&1

redis-cli -h "${REDIS_HOST}" -p "${REDIS_PORT}" INFO commandstats \
  > "${RUN_DIR}/redis-commandstats-after.txt" || true
curl -fsS --max-time 2 "${MANAGEMENT_BASE_URL}/actuator/prometheus" \
  -o "${RUN_DIR}/app-metrics-after.prom" || true

echo "run_dir=${RUN_DIR}"
