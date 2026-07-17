#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUNNER="${ROOT_DIR}/scripts/test/run_performance_cache_k6.sh"

BASE_URL="${BASE_URL:-http://127.0.0.1:10080}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-16380}"
PERFORMANCE_ID="${PERFORMANCE_ID:-2}"
BURST_LEVELS="${BURST_LEVELS:-100,500,1000}"
BURST_DELAY_SECONDS="${BURST_DELAY_SECONDS:-5}"
STEADY_RATE="${STEADY_RATE:-100}"
STEADY_DURATION="${STEADY_DURATION:-60s}"
STEADY_PRE_ALLOCATED_VUS="${STEADY_PRE_ALLOCATED_VUS:-150}"
STEADY_MAX_VUS="${STEADY_MAX_VUS:-200}"
CACHE_TTL_SECONDS="${CACHE_TTL_SECONDS:-600}"
ALLOW_TTL_EXPIRY="${ALLOW_TTL_EXPIRY:-false}"
STEADY_REPETITIONS="${STEADY_REPETITIONS:-3}"
WORKLOADS="${WORKLOADS:-burst,steady}"
RUN_ID="${RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
RESULT_DIR="${RESULT_DIR:-${ROOT_DIR}/build/k6-results/performance-cache/local-matrix-${RUN_ID}}"
STEADY_INDEX_FILE="${RESULT_DIR}/steady-results.tsv"
STEADY_AGGREGATE_FILE="${RESULT_DIR}/steady-aggregate.tsv"
PLAN_FILE="${RESULT_DIR}/test-plan.txt"

if [[ ! "${PERFORMANCE_ID}" =~ ^[1-9][0-9]*$ ]]; then
  echo "PERFORMANCE_ID는 양의 정수여야 합니다: ${PERFORMANCE_ID}" >&2
  exit 1
fi
if [[ ! "${STEADY_REPETITIONS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "STEADY_REPETITIONS는 양의 정수여야 합니다: ${STEADY_REPETITIONS}" >&2
  exit 1
fi
if [[ ! "${CACHE_TTL_SECONDS}" =~ ^[1-9][0-9]*$ ]] || [[ ! "${ALLOW_TTL_EXPIRY}" =~ ^(true|false)$ ]]; then
  echo "CACHE_TTL_SECONDS는 양의 정수, ALLOW_TTL_EXPIRY는 true 또는 false여야 합니다." >&2
  exit 1
fi

IFS=',' read -r -a burst_levels <<< "${BURST_LEVELS}"
for burst_level in "${burst_levels[@]}"; do
  if [[ ! "${burst_level}" =~ ^[1-9][0-9]*$ ]]; then
    echo "BURST_LEVELS에는 쉼표로 구분한 양의 정수만 사용할 수 있습니다: ${BURST_LEVELS}" >&2
    exit 1
  fi
done

case "${WORKLOADS}" in
  burst|steady|burst,steady|steady,burst) ;;
  *)
  echo "WORKLOADS는 burst, steady 또는 burst,steady여야 합니다: ${WORKLOADS}" >&2
  exit 1
  ;;
esac

duration_to_seconds() {
  awk -v duration="$1" '
    BEGIN {
      value = duration
      if (value ~ /^[1-9][0-9]*ms$/) { sub(/ms$/, "", value); seconds = value / 1000 }
      else if (value ~ /^[1-9][0-9]*s$/) { sub(/s$/, "", value); seconds = value }
      else if (value ~ /^[1-9][0-9]*m$/) { sub(/m$/, "", value); seconds = value * 60 }
      else if (value ~ /^[1-9][0-9]*h$/) { sub(/h$/, "", value); seconds = value * 3600 }
      else exit 1
      printf "%.3f", seconds
    }
  '
}

write_steady_aggregate() {
  [[ -f "${STEADY_INDEX_FILE}" ]] || return 0

  awk -F '\t' '
    NR == 1 { next }
    {
      access = $2
      runs[access]++
      expected[access] += $3
      started[access] += $4
      successes[access] += $5
      dropped[access] += $6
      failedRate[access] += $7
      avg[access] += $8
      p95[access] += $9
      p99[access] += $10
      if ($11 > maxVus[access]) maxVus[access] = $11
      direct[access] += $12
      hits[access] += $13
      misses[access] += $14
      coalesced[access] += $15
      writes[access] += $16
    }
    BEGIN {
      print "access\truns\tnominal_target_requests\tstarted_requests\tsuccesses\tdropped_iterations\tmean_http_failure_rate\tmean_avg_ms\tmean_run_p95_ms\tmean_run_p99_ms\tpeak_vus\tdirect_bypass\tcache_hit\tcache_miss\tcache_coalesced\tcache_write"
    }
    END {
      for (i = 1; i <= 2; i++) {
        access = i == 1 ? "direct" : "cache"
        if (!(access in runs)) continue
        printf "%s\t%d\t%.0f\t%.0f\t%.0f\t%.0f\t%.9f\t%.3f\t%.3f\t%.3f\t%.0f\t%.0f\t%.0f\t%.0f\t%.0f\t%.0f\n", \
          access, runs[access], expected[access], started[access], successes[access], dropped[access], \
          failedRate[access] / runs[access], avg[access] / runs[access], p95[access] / runs[access], \
          p99[access] / runs[access], maxVus[access], direct[access], hits[access], misses[access], \
          coalesced[access], writes[access]
      }
    }
  ' "${STEADY_INDEX_FILE}" > "${STEADY_AGGREGATE_FILE}"
}

run_burst() {
  local temperature="$1"
  local concurrency="$2"

  ACTION="stampede-${temperature}" \
  BASE_URL="${BASE_URL}" \
  REDIS_HOST="${REDIS_HOST}" \
  REDIS_PORT="${REDIS_PORT}" \
  PERFORMANCE_ID="${PERFORMANCE_ID}" \
  STAMPEDE_CONCURRENCY="${concurrency}" \
  BURST_DELAY_SECONDS="${BURST_DELAY_SECONDS}" \
  STRICT_STAMPEDE_ASSERTIONS=true \
  RESULT_DIR="${RESULT_DIR}" \
  "${RUNNER}"
}

run_steady() {
  local access="$1"
  local repetition="$2"

  ACTION="steady-${access}" \
  BASE_URL="${BASE_URL}" \
  REDIS_HOST="${REDIS_HOST}" \
  REDIS_PORT="${REDIS_PORT}" \
  PERFORMANCE_IDS="${PERFORMANCE_ID}" \
  STEADY_RATE="${STEADY_RATE}" \
  STEADY_DURATION="${STEADY_DURATION}" \
  STEADY_PRE_ALLOCATED_VUS="${STEADY_PRE_ALLOCATED_VUS}" \
  STEADY_MAX_VUS="${STEADY_MAX_VUS}" \
  CACHE_TTL_SECONDS="${CACHE_TTL_SECONDS}" \
  ALLOW_TTL_EXPIRY="${ALLOW_TTL_EXPIRY}" \
  RUN_LABEL="steady-${repetition}-${access}" \
  RESULT_INDEX_FILE="${STEADY_INDEX_FILE}" \
  RESULT_DIR="${RESULT_DIR}" \
  "${RUNNER}"
}

mkdir -p "${RESULT_DIR}"
steady_seconds="$(duration_to_seconds "${STEADY_DURATION}")" || {
  echo "STEADY_DURATION은 예: 60s, 10m처럼 단일 시간 단위여야 합니다: ${STEADY_DURATION}" >&2
  exit 1
}
if [[ ",${WORKLOADS}," == *",steady,"* ]] \
  && [[ "${ALLOW_TTL_EXPIRY}" != "true" ]] \
  && awk -v duration="${steady_seconds}" -v ttl="${CACHE_TTL_SECONDS}" 'BEGIN { exit !(duration >= ttl) }'; then
  echo "warm steady 비교는 CACHE_TTL_SECONDS(${CACHE_TTL_SECONDS})보다 짧아야 합니다. TTL 만료 검증은 ALLOW_TTL_EXPIRY=true로 분리하세요." >&2
  exit 1
fi
steady_per_run="$(awk -v rate="${STEADY_RATE}" -v seconds="${steady_seconds}" 'BEGIN { printf "%.0f", rate * seconds }')"
burst_total=0
if [[ ",${WORKLOADS}," == *",burst,"* ]]; then
  burst_total="$(awk -v levels="${BURST_LEVELS}" 'BEGIN { split(levels, values, ","); for (i in values) total += values[i] * 2; printf "%.0f", total }')"
fi
steady_total=0
if [[ ",${WORKLOADS}," == *",steady,"* ]]; then
  steady_total=$(( steady_per_run * STEADY_REPETITIONS * 2 ))
  printf 'label\taccess\tnominal_target_requests\tstarted_requests\tsuccesses\tdropped_iterations\thttp_failure_rate\tavg_ms\tp95_ms\tp99_ms\tmax_vus\tdirect_bypass\tcache_hit\tcache_miss\tcache_coalesced\tcache_write\trun_dir\n' \
    > "${STEADY_INDEX_FILE}"
fi
{
  printf 'workloads=%s\n' "${WORKLOADS}"
  printf 'performance_id=%s\n' "${PERFORMANCE_ID}"
  printf 'burst_levels=%s\n' "${BURST_LEVELS}"
  printf 'nominal_target_burst_requests=%s\n' "${burst_total}"
  printf 'steady_rate_per_second=%s\n' "${STEADY_RATE}"
  printf 'steady_duration=%s\n' "${STEADY_DURATION}"
  printf 'steady_repetitions=%s\n' "${STEADY_REPETITIONS}"
  printf 'cache_ttl_seconds=%s\n' "${CACHE_TTL_SECONDS}"
  printf 'allow_ttl_expiry=%s\n' "${ALLOW_TTL_EXPIRY}"
  printf 'nominal_target_steady_requests_per_run=%s\n' "${steady_per_run}"
  printf 'nominal_target_steady_requests_total=%s\n' "${steady_total}"
  printf 'nominal_target_requests_total=%s\n' "$(( burst_total + steady_total ))"
  printf 'assertion=steady requires HTTP failure 0 and dropped_iterations 0\n'
} > "${PLAN_FILE}"
trap write_steady_aggregate EXIT

printf 'Local cache matrix: workloads=%s, performance=%s, burst=%s, steady=%s/s for %s × %s\n' \
  "${WORKLOADS}" "${PERFORMANCE_ID}" "${BURST_LEVELS}" "${STEADY_RATE}" "${STEADY_DURATION}" "${STEADY_REPETITIONS}"
printf 'Nominal target requests: burst=%s, steady=%s, total=%s (%s)\n' \
  "${burst_total}" "${steady_total}" "$(( burst_total + steady_total ))" "${PLAN_FILE}"

if [[ ",${WORKLOADS}," == *",burst,"* ]]; then
  for burst_level in "${burst_levels[@]}"; do
    run_burst cold "${burst_level}"
    run_burst warm "${burst_level}"
  done
fi

if [[ ",${WORKLOADS}," == *",steady,"* ]]; then
  for ((run = 1; run <= STEADY_REPETITIONS; run++)); do
    printf 'Steady repetition %s/%s\n' "${run}" "${STEADY_REPETITIONS}"
    if (( run % 2 == 1 )); then
      run_steady direct "${run}"
      run_steady cache "${run}"
    else
      run_steady cache "${run}"
      run_steady direct "${run}"
    fi
  done
fi

write_steady_aggregate
printf 'Local cache matrix complete: %s\n' "${RESULT_DIR}"
