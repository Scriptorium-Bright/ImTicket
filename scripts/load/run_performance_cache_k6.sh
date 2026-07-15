#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ACTION="${ACTION:-comparison}"
BASE_URL="${BASE_URL:-http://140.245.76.87:10080}"
BASE_URL="${BASE_URL%/}"
PERFORMANCE_IDS="${PERFORMANCE_IDS:-${PERFORMANCE_ID:-}}"
USERS="${USERS:-5000}"
CONCURRENCY="${CONCURRENCY:-5000}"
STAMPEDE_CONCURRENCY="${STAMPEDE_CONCURRENCY:-500}"
BURST_DELAY_SECONDS="${BURST_DELAY_SECONDS:-10}"
MAX_DURATION="${MAX_DURATION:-10m}"
K6_BIN="${K6_BIN:-k6}"
CURL_BIN="${CURL_BIN:-curl}"
REDIS_CLI="${REDIS_CLI:-redis-cli}"
REDIS_HOST="${REDIS_HOST:-140.245.76.87}"
REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_PASSWORD="${REDIS_PASSWORD:-}"
RESULT_DIR="${RESULT_DIR:-${ROOT_DIR}/build/k6-results/performance-cache}"
ALLOW_REMOTE_LOAD="${ALLOW_REMOTE_LOAD:-false}"
ALLOW_LARGE_LOAD="${ALLOW_LARGE_LOAD:-false}"

case "${ACTION}" in
  comparison|direct|cache|stampede-cold|stampede-warm) ;;
  *)
    echo "ACTION은 comparison, direct, cache, stampede-cold, stampede-warm 중 하나여야 합니다." >&2
    exit 1
    ;;
esac

case "${BASE_URL}" in
  http://140.245.76.87:*|http://localhost:*|https://140.245.76.87:*|https://localhost:*) ;;
  *)
    if [[ "${ALLOW_REMOTE_LOAD}" != "true" ]]; then
      echo "원격 대상 부하는 ALLOW_REMOTE_LOAD=true를 명시해야 합니다: ${BASE_URL}" >&2
      exit 1
    fi
    ;;
esac
if [[ ! "${ALLOW_LARGE_LOAD}" =~ ^(true|false)$ ]]; then
  echo "ALLOW_LARGE_LOAD는 true 또는 false여야 합니다." >&2
  exit 1
fi

for value_name in USERS CONCURRENCY STAMPEDE_CONCURRENCY; do
  value="${!value_name}"
  if [[ ! "${value}" =~ ^[1-9][0-9]*$ ]]; then
    echo "${value_name}는 양의 정수여야 합니다: ${value}" >&2
    exit 1
  fi
done

if [[ -z "${PERFORMANCE_IDS}" ]]; then
  echo "PERFORMANCE_IDS가 필요합니다. 먼저 seed_performance_cache_fixture.sh를 실행하세요." >&2
  exit 1
fi

IFS=',' read -r -a performance_ids <<< "${PERFORMANCE_IDS}"
for performance_id in "${performance_ids[@]}"; do
  if [[ ! "${performance_id}" =~ ^[1-9][0-9]*$ ]]; then
    echo "PERFORMANCE_IDS에는 양의 정수만 사용할 수 있습니다: ${performance_id}" >&2
    exit 1
  fi
done

if [[ "${ACTION}" == "comparison" || "${ACTION}" == "direct" || "${ACTION}" == "cache" ]]; then
  if [[ "${#performance_ids[@]}" -ne 100 ]]; then
    echo "100개 공연 비교에는 PERFORMANCE_IDS가 정확히 100개 필요합니다: ${#performance_ids[@]}" >&2
    exit 1
  fi
  if (( CONCURRENCY > USERS )); then
    echo "CONCURRENCY는 USERS보다 클 수 없습니다." >&2
    exit 1
  fi
fi

requested_concurrency="${STAMPEDE_CONCURRENCY}"
if [[ "${ACTION}" == "comparison" || "${ACTION}" == "direct" || "${ACTION}" == "cache" ]]; then
  requested_concurrency="${CONCURRENCY}"
fi
if (( requested_concurrency > 5000 )) && [[ "${ALLOW_LARGE_LOAD}" != "true" ]]; then
  echo "${requested_concurrency} VU 실행에는 ALLOW_LARGE_LOAD=true를 명시해야 합니다." >&2
  exit 1
fi

mkdir -p "${RESULT_DIR}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
run_dir="${RESULT_DIR}/${timestamp}-${ACTION}"
mkdir -p "${run_dir}"

redis_cmd() {
  if [[ -n "${REDIS_PASSWORD}" ]]; then
    REDISCLI_AUTH="${REDIS_PASSWORD}" "${REDIS_CLI}" \
      -h "${REDIS_HOST}" -p "${REDIS_PORT}" "$@"
  else
    "${REDIS_CLI}" -h "${REDIS_HOST}" -p "${REDIS_PORT}" "$@"
  fi
}

snapshot_metrics() {
  local label="$1"
  "${CURL_BIN}" --fail --silent --show-error \
    --output "${run_dir}/${label}-actuator.prom" \
    "${BASE_URL}/actuator/prometheus"
}

preflight_direct() {
  local performance_id
  for performance_id in "$@"; do
    "${CURL_BIN}" --fail --silent --show-error --output /dev/null \
      "${BASE_URL}/api/performance/intro/${performance_id}?cache=false"
  done
}

delete_cache_keys() {
  local performance_id
  local -a keys=()
  for performance_id in "$@"; do
    keys+=("performance:details:${performance_id}")
  done
  redis_cmd DEL "${keys[@]}" >/dev/null
}

warm_cache_keys() {
  local performance_id
  local ttl
  for performance_id in "$@"; do
    "${CURL_BIN}" --fail --silent --show-error --output /dev/null \
      "${BASE_URL}/api/performance/intro/${performance_id}?cache=true"
    if [[ "$(redis_cmd EXISTS "performance:details:${performance_id}")" != "1" ]]; then
      echo "캐시 warm-up을 확인하지 못했습니다: performance:details:${performance_id}" >&2
      exit 1
    fi
    ttl="$(redis_cmd TTL "performance:details:${performance_id}")"
    if [[ ! "${ttl}" =~ ^[1-9][0-9]*$ ]]; then
      echo "캐시 TTL을 확인하지 못했습니다: performance:details:${performance_id}, ttl=${ttl}" >&2
      exit 1
    fi
  done
}

run_distribution() {
  local access="$1"
  local cache="$2"
  local summary_file="${run_dir}/${access}-summary.json"
  local k6_status

  snapshot_metrics "${access}-before"
  set +e
  "${K6_BIN}" run \
    -e "TEST_TYPE=distribution" \
    -e "CACHE=${cache}" \
    -e "BASE_URL=${BASE_URL}" \
    -e "PERFORMANCE_IDS=${PERFORMANCE_IDS}" \
    -e "USERS=${USERS}" \
    -e "CONCURRENCY=${CONCURRENCY}" \
    -e "BURST_DELAY_SECONDS=${BURST_DELAY_SECONDS}" \
    -e "MAX_DURATION=${MAX_DURATION}" \
    --summary-export "${summary_file}" \
    "${ROOT_DIR}/k6-scripts/06-performance-cache-load.js"
  k6_status=$?
  set -e
  snapshot_metrics "${access}-after"
  echo "Summary: ${summary_file}"
  return "${k6_status}"
}

run_stampede() {
  local temperature="$1"
  local performance_id="${performance_ids[0]}"
  local summary_file="${run_dir}/stampede-${temperature}-summary.json"
  local k6_status

  snapshot_metrics "stampede-${temperature}-before"
  set +e
  "${K6_BIN}" run \
    -e "TEST_TYPE=stampede" \
    -e "CACHE=true" \
    -e "BASE_URL=${BASE_URL}" \
    -e "PERFORMANCE_ID=${performance_id}" \
    -e "CONCURRENCY=${STAMPEDE_CONCURRENCY}" \
    -e "BURST_DELAY_SECONDS=${BURST_DELAY_SECONDS}" \
    -e "MAX_DURATION=${MAX_DURATION}" \
    --summary-export "${summary_file}" \
    "${ROOT_DIR}/k6-scripts/06-performance-cache-load.js"
  k6_status=$?
  set -e
  snapshot_metrics "stampede-${temperature}-after"

  if [[ "$(redis_cmd EXISTS "performance:details:${performance_id}")" != "1" ]]; then
    echo "테스트 후 캐시 key가 생성되지 않았습니다: performance:details:${performance_id}" >&2
    return 1
  fi
  echo "Summary: ${summary_file}"
  return "${k6_status}"
}

case "${ACTION}" in
  comparison)
    preflight_direct "${performance_ids[@]}"
    run_distribution direct false
    delete_cache_keys "${performance_ids[@]}"
    warm_cache_keys "${performance_ids[@]}"
    run_distribution cache true
    ;;
  direct)
    preflight_direct "${performance_ids[@]}"
    run_distribution direct false
    ;;
  cache)
    delete_cache_keys "${performance_ids[@]}"
    warm_cache_keys "${performance_ids[@]}"
    run_distribution cache true
    ;;
  stampede-cold)
    preflight_direct "${performance_ids[0]}"
    delete_cache_keys "${performance_ids[0]}"
    if [[ "$(redis_cmd EXISTS "performance:details:${performance_ids[0]}")" != "0" ]]; then
      echo "cold cache 준비에 실패했습니다." >&2
      exit 1
    fi
    run_stampede cold
    ;;
  stampede-warm)
    delete_cache_keys "${performance_ids[0]}"
    warm_cache_keys "${performance_ids[0]}"
    run_stampede warm
    ;;
esac

echo "Metrics and summaries: ${run_dir}"
