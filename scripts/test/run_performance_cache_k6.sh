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
STEADY_RATE="${STEADY_RATE:-100}"
STEADY_DURATION="${STEADY_DURATION:-60s}"
STEADY_PRE_ALLOCATED_VUS="${STEADY_PRE_ALLOCATED_VUS:-150}"
STEADY_MAX_VUS="${STEADY_MAX_VUS:-200}"
CACHE_TTL_SECONDS="${CACHE_TTL_SECONDS:-600}"
ALLOW_TTL_EXPIRY="${ALLOW_TTL_EXPIRY:-false}"
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
STRICT_STAMPEDE_ASSERTIONS="${STRICT_STAMPEDE_ASSERTIONS:-false}"
RESULT_INDEX_FILE="${RESULT_INDEX_FILE:-}"
RUN_LABEL="${RUN_LABEL:-${ACTION}}"

case "${ACTION}" in
  comparison|direct|cache|stampede-cold|stampede-warm|steady-direct|steady-cache|steady-comparison) ;;
  *)
    echo "ACTION은 comparison, direct, cache, stampede-cold, stampede-warm, steady-direct, steady-cache, steady-comparison 중 하나여야 합니다." >&2
    exit 1
    ;;
esac

case "${BASE_URL}" in
  http://140.245.76.87:*|http://localhost:*|http://127.0.0.1:*|https://140.245.76.87:*|https://localhost:*|https://127.0.0.1:*) ;;
  *)
    if [[ "${ALLOW_REMOTE_LOAD}" != "true" ]]; then
      echo "원격 대상 부하는 ALLOW_REMOTE_LOAD=true를 명시해야 합니다: ${BASE_URL}" >&2
      exit 1
    fi
    ;;
esac
if [[ ! "${ALLOW_LARGE_LOAD}" =~ ^(true|false)$ ]] \
  || [[ ! "${STRICT_STAMPEDE_ASSERTIONS}" =~ ^(true|false)$ ]] \
  || [[ ! "${ALLOW_TTL_EXPIRY}" =~ ^(true|false)$ ]]; then
  echo "ALLOW_LARGE_LOAD, STRICT_STAMPEDE_ASSERTIONS, ALLOW_TTL_EXPIRY는 true 또는 false여야 합니다." >&2
  exit 1
fi

for value_name in USERS CONCURRENCY STAMPEDE_CONCURRENCY STEADY_RATE STEADY_PRE_ALLOCATED_VUS STEADY_MAX_VUS CACHE_TTL_SECONDS; do
  value="${!value_name}"
  if [[ ! "${value}" =~ ^[1-9][0-9]*$ ]]; then
    echo "${value_name}는 양의 정수여야 합니다: ${value}" >&2
    exit 1
  fi
done

if [[ ! "${STEADY_DURATION}" =~ ^[1-9][0-9]*(ms|s|m|h)$ ]]; then
  echo "STEADY_DURATION은 예: 60s, 2m처럼 단일 시간 단위여야 합니다: ${STEADY_DURATION}" >&2
  exit 1
fi
steady_duration_seconds="$(awk -v duration="${STEADY_DURATION}" '
  BEGIN {
    value = duration
    if (value ~ /ms$/) { sub(/ms$/, "", value); seconds = value / 1000 }
    else if (value ~ /s$/) { sub(/s$/, "", value); seconds = value }
    else if (value ~ /m$/) { sub(/m$/, "", value); seconds = value * 60 }
    else if (value ~ /h$/) { sub(/h$/, "", value); seconds = value * 3600 }
    printf "%.3f", seconds
  }
')"
if [[ "${ACTION}" == "steady-cache" || "${ACTION}" == "steady-comparison" ]] \
  && [[ "${ALLOW_TTL_EXPIRY}" != "true" ]] \
  && awk -v duration="${steady_duration_seconds}" -v ttl="${CACHE_TTL_SECONDS}" 'BEGIN { exit !(duration >= ttl) }'; then
  echo "warm steady 비교는 CACHE_TTL_SECONDS(${CACHE_TTL_SECONDS})보다 짧아야 합니다. TTL 만료를 의도적으로 검증하려면 ALLOW_TTL_EXPIRY=true를 명시하세요." >&2
  exit 1
fi
if (( STEADY_MAX_VUS < STEADY_PRE_ALLOCATED_VUS )); then
  echo "STEADY_MAX_VUS는 STEADY_PRE_ALLOCATED_VUS 이상이어야 합니다." >&2
  exit 1
fi

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
if [[ "${ACTION}" == steady-* ]]; then
  requested_concurrency="${STEADY_MAX_VUS}"
fi
if (( requested_concurrency > 5000 )) && [[ "${ALLOW_LARGE_LOAD}" != "true" ]]; then
  echo "${requested_concurrency} VU 실행에는 ALLOW_LARGE_LOAD=true를 명시해야 합니다." >&2
  exit 1
fi

if [[ -n "${RESULT_INDEX_FILE}" ]] && ! command -v jq >/dev/null 2>&1; then
  echo "RESULT_INDEX_FILE을 사용하려면 jq가 필요합니다." >&2
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

metric_total() {
  local file="$1"
  local metric="$2"
  local access="${3:-}"
  local result="${4:-}"

  awk -v metric="${metric}" -v access="${access}" -v result="${result}" '
    index($1, metric) == 1 && ($1 == metric || index($1, metric "{") == 1) {
      if (access != "" && index($1, "access=\"" access "\"") == 0) next
      if (result != "" && index($1, "result=\"" result "\"") == 0) next
      total += $2
    }
    END { printf "%.0f\n", total + 0 }
  ' "${file}"
}

metric_delta() {
  local before="$1"
  local after="$2"
  awk -v before="${before}" -v after="${after}" 'BEGIN { printf "%.0f\n", after - before }'
}

write_cache_metric_delta() {
  local label="$1"
  local before_file="$2"
  local after_file="$3"
  local output_file="${run_dir}/${label}-cache-metrics.txt"
  local direct_before direct_after cache_hit_before cache_hit_after
  local cache_miss_before cache_miss_after cache_coalesced_before cache_coalesced_after
  local cache_error_before cache_error_after writes_before writes_after
  local direct_delta cache_hit_delta cache_miss_delta cache_coalesced_delta cache_error_delta writes_delta

  direct_before="$(metric_total "${before_file}" imticket_performance_details_requests_total direct bypass)"
  direct_after="$(metric_total "${after_file}" imticket_performance_details_requests_total direct bypass)"
  cache_hit_before="$(metric_total "${before_file}" imticket_performance_details_requests_total cache hit)"
  cache_hit_after="$(metric_total "${after_file}" imticket_performance_details_requests_total cache hit)"
  cache_miss_before="$(metric_total "${before_file}" imticket_performance_details_requests_total cache miss)"
  cache_miss_after="$(metric_total "${after_file}" imticket_performance_details_requests_total cache miss)"
  cache_coalesced_before="$(metric_total "${before_file}" imticket_performance_details_requests_total cache coalesced)"
  cache_coalesced_after="$(metric_total "${after_file}" imticket_performance_details_requests_total cache coalesced)"
  cache_error_before="$(metric_total "${before_file}" imticket_performance_details_requests_total cache error)"
  cache_error_after="$(metric_total "${after_file}" imticket_performance_details_requests_total cache error)"
  writes_before="$(metric_total "${before_file}" imticket_performance_details_cache_writes_total)"
  writes_after="$(metric_total "${after_file}" imticket_performance_details_cache_writes_total)"

  direct_delta="$(metric_delta "${direct_before}" "${direct_after}")"
  cache_hit_delta="$(metric_delta "${cache_hit_before}" "${cache_hit_after}")"
  cache_miss_delta="$(metric_delta "${cache_miss_before}" "${cache_miss_after}")"
  cache_coalesced_delta="$(metric_delta "${cache_coalesced_before}" "${cache_coalesced_after}")"
  cache_error_delta="$(metric_delta "${cache_error_before}" "${cache_error_after}")"
  writes_delta="$(metric_delta "${writes_before}" "${writes_after}")"

  {
    printf 'direct_bypass_delta=%s\n' "${direct_delta}"
    printf 'cache_hit_delta=%s\n' "${cache_hit_delta}"
    printf 'cache_miss_delta=%s\n' "${cache_miss_delta}"
    printf 'cache_coalesced_delta=%s\n' "${cache_coalesced_delta}"
    printf 'cache_error_delta=%s\n' "${cache_error_delta}"
    printf 'cache_write_delta=%s\n' "${writes_delta}"
  } > "${output_file}"

  echo "Cache metric delta: ${output_file}"
  sed 's/^/  /' "${output_file}"
}

metric_delta_from_file() {
  local file="$1"
  local key="$2"
  awk -F= -v key="${key}" '$1 == key { print $2; found=1 } END { if (!found) print "0" }' "${file}"
}

append_steady_result() {
  local access="$1"
  local summary_file="$2"
  local metrics_file="$3"
  local expected_requests="$4"
  local started successes dropped failed_rate avg p95 p99 max_vus
  local direct hits misses coalesced writes

  [[ -z "${RESULT_INDEX_FILE}" ]] && return 0

  started="$(jq -r '.metrics.performance_requests.count // 0' "${summary_file}")"
  successes="$(jq -r '.metrics.performance_successes.count // 0' "${summary_file}")"
  dropped="$(jq -r '.metrics.dropped_iterations.count // 0' "${summary_file}")"
  failed_rate="$(jq -r '.metrics.http_req_failed.value // 0' "${summary_file}")"
  avg="$(jq -r '.metrics.http_req_duration.avg // 0' "${summary_file}")"
  p95="$(jq -r '.metrics.http_req_duration["p(95)"] // 0' "${summary_file}")"
  p99="$(jq -r '.metrics.http_req_duration["p(99)"] // 0' "${summary_file}")"
  max_vus="$(jq -r '.metrics.vus_max.max // .metrics.vus_max.value // 0' "${summary_file}")"
  direct="$(metric_delta_from_file "${metrics_file}" direct_bypass_delta)"
  hits="$(metric_delta_from_file "${metrics_file}" cache_hit_delta)"
  misses="$(metric_delta_from_file "${metrics_file}" cache_miss_delta)"
  coalesced="$(metric_delta_from_file "${metrics_file}" cache_coalesced_delta)"
  writes="$(metric_delta_from_file "${metrics_file}" cache_write_delta)"

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "${RUN_LABEL}" "${access}" "${expected_requests}" "${started}" "${successes}" "${dropped}" \
    "${failed_rate}" "${avg}" "${p95}" "${p99}" "${max_vus}" "${direct}" "${hits}" \
    "${misses}" "${coalesced}" "${writes}" "${run_dir}" >> "${RESULT_INDEX_FILE}"
}

assert_stampede_invariant() {
  local temperature="$1"
  local metrics_file="$2"
  local miss writes hits coalesced errors expected_followers

  miss="$(awk -F= '$1 == "cache_miss_delta" { print $2 }' "${metrics_file}")"
  writes="$(awk -F= '$1 == "cache_write_delta" { print $2 }' "${metrics_file}")"
  hits="$(awk -F= '$1 == "cache_hit_delta" { print $2 }' "${metrics_file}")"
  coalesced="$(awk -F= '$1 == "cache_coalesced_delta" { print $2 }' "${metrics_file}")"
  errors="$(awk -F= '$1 == "cache_error_delta" { print $2 }' "${metrics_file}")"
  expected_followers=$(( STAMPEDE_CONCURRENCY - 1 ))

  if [[ "${temperature}" == "cold" ]]; then
    if [[ "${miss}" == "1" && "${writes}" == "1" && "${errors}" == "0" ]] \
      && (( hits + coalesced == expected_followers )); then
      echo "Single-flight invariant: cold cache origin load/write=1, follower results=${expected_followers}, errors=0"
      return 0
    fi
    echo "Single-flight invariant failed: cold cache miss=${miss}, write=${writes}, hit=${hits}, coalesced=${coalesced}, error=${errors}" >&2
  else
    if [[ "${miss}" == "0" && "${writes}" == "0" && "${hits}" == "${STAMPEDE_CONCURRENCY}" && "${coalesced}" == "0" && "${errors}" == "0" ]]; then
      echo "Warm-cache invariant: origin load/write=0, cache hit=${hits}, errors=0"
      return 0
    fi
    echo "Warm-cache invariant failed: miss=${miss}, write=${writes}, hit=${hits}, coalesced=${coalesced}, error=${errors}" >&2
  fi

  [[ "${STRICT_STAMPEDE_ASSERTIONS}" != "true" ]]
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
    "${ROOT_DIR}/scripts/test/06-performance-cache-load.js"
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
    "${ROOT_DIR}/scripts/test/06-performance-cache-load.js"
  k6_status=$?
  set -e
  snapshot_metrics "stampede-${temperature}-after"

  local metrics_file="${run_dir}/stampede-${temperature}-cache-metrics.txt"
  write_cache_metric_delta \
    "stampede-${temperature}" \
    "${run_dir}/stampede-${temperature}-before-actuator.prom" \
    "${run_dir}/stampede-${temperature}-after-actuator.prom"
  assert_stampede_invariant "${temperature}" "${metrics_file}"

  if [[ "$(redis_cmd EXISTS "performance:details:${performance_id}")" != "1" ]]; then
    echo "테스트 후 캐시 key가 생성되지 않았습니다: performance:details:${performance_id}" >&2
    return 1
  fi
  echo "Summary: ${summary_file}"
  return "${k6_status}"
}

run_steady() {
  local access="$1"
  local cache="$2"
  local summary_file="${run_dir}/steady-${access}-summary.json"
  local k6_status

  snapshot_metrics "steady-${access}-before"
  set +e
  "${K6_BIN}" run \
    -e "TEST_TYPE=steady" \
    -e "CACHE=${cache}" \
    -e "BASE_URL=${BASE_URL}" \
    -e "PERFORMANCE_IDS=${PERFORMANCE_IDS}" \
    -e "STEADY_RATE=${STEADY_RATE}" \
    -e "STEADY_DURATION=${STEADY_DURATION}" \
    -e "STEADY_PRE_ALLOCATED_VUS=${STEADY_PRE_ALLOCATED_VUS}" \
    -e "STEADY_MAX_VUS=${STEADY_MAX_VUS}" \
    --summary-export "${summary_file}" \
    "${ROOT_DIR}/scripts/test/06-performance-cache-load.js"
  k6_status=$?
  set -e
  snapshot_metrics "steady-${access}-after"
  write_cache_metric_delta \
    "steady-${access}" \
    "${run_dir}/steady-${access}-before-actuator.prom" \
    "${run_dir}/steady-${access}-after-actuator.prom"
  append_steady_result \
    "${access}" \
    "${summary_file}" \
    "${run_dir}/steady-${access}-cache-metrics.txt" \
    "$(awk -v rate="${STEADY_RATE}" -v duration="${STEADY_DURATION}" '
      BEGIN {
        value = duration
        if (value ~ /ms$/) { sub(/ms$/, "", value); seconds = value / 1000 }
        else if (value ~ /s$/) { sub(/s$/, "", value); seconds = value }
        else if (value ~ /m$/) { sub(/m$/, "", value); seconds = value * 60 }
        else if (value ~ /h$/) { sub(/h$/, "", value); seconds = value * 3600 }
        printf "%.0f", rate * seconds
      }
    ')"

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
  steady-direct)
    preflight_direct "${performance_ids[@]}"
    run_steady direct false
    ;;
  steady-cache)
    delete_cache_keys "${performance_ids[@]}"
    warm_cache_keys "${performance_ids[@]}"
    run_steady cache true
    ;;
  steady-comparison)
    preflight_direct "${performance_ids[@]}"
    run_steady direct false
    delete_cache_keys "${performance_ids[@]}"
    warm_cache_keys "${performance_ids[@]}"
    run_steady cache true
    ;;
esac

echo "Metrics and summaries: ${run_dir}"
