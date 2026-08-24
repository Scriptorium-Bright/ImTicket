#!/usr/bin/env bash
set -euo pipefail

# B 보호 구간 용량 실험
#
# 서로 다른 좌석을 사용하는 admitted cohort를 10·20·30·40·50으로 실행한다.
# Waiting Room Service는 실험 상한 50명·입장 처리율 50명으로 재기동해
# 대기열 정책이 Reservation Application 용량 측정을 제한하지 않도록 한다.
# 운영값 확정과 좌석 충돌 실험에는 사용하지 않는다.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/load_env_defaults.sh"
load_imticket_env "${ROOT_DIR}/.env"

BASE_URL="${BASE_URL:-http://127.0.0.1:10082}"
MANAGEMENT_BASE_URL="${MANAGEMENT_BASE_URL:-http://127.0.0.1:10081}"
PT_ID="${PT_ID:-900000001}"
JWT_SECRET="${JWT_SECRET:-${SPRING_JWT_SECRET:-}}"
CONCURRENCIES="${CONCURRENCIES:-10 20 30 40 50}"
MAX_ACTIVE_SESSIONS="${MAX_ACTIVE_SESSIONS:-50}"
ADMIT_PER_INTERVAL="${ADMIT_PER_INTERVAL:-50}"
MEMBER_ID_BASE="${MEMBER_ID_BASE:-900000000}"
MEMBER_COUNT="${MEMBER_COUNT:-50}"
SEAT_ID_START="${SEAT_ID_START:-900000001}"
SEAT_IDS="${SEAT_IDS:-}"
STATUS_POLLS="${STATUS_POLLS:-60}"
STATUS_POLL_INTERVAL_MS="${STATUS_POLL_INTERVAL_MS:-1000}"
STATUS_POLL_JITTER_RATIO="${STATUS_POLL_JITTER_RATIO:-0.1}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-30s}"
MAX_DURATION="${MAX_DURATION:-5m}"
MYSQL_USE_DOCKER="${MYSQL_USE_DOCKER:-true}"
REDIS_USE_DOCKER="${REDIS_USE_DOCKER:-true}"
RESET_FIXTURE="${RESET_FIXTURE:-true}"
RECONFIGURE_WAITING_ROOM_SERVICE="${RECONFIGURE_WAITING_ROOM_SERVICE:-true}"
WAITING_ROOM_PASS_SECRET="${RESERVATION_WAITING_ROOM_PASS_SECRET:-local-e2e-waiting-room-pass-secret-2026}"
RESULT_ROOT="${RESULT_ROOT:-${ROOT_DIR}/build/k6-results/146.10-protected-zone-capacity}"
RUN_GROUP="${RUN_GROUP:-$(date -u +%Y%m%dT%H%M%SZ)}"
GROUP_DIR="${RESULT_ROOT}/${RUN_GROUP}"
MANIFEST_FILE="${GROUP_DIR}/run-manifest.tsv"

require_positive_integer() {
  local name="$1"
  local value="$2"
  if ! [[ "${value}" =~ ^[1-9][0-9]*$ ]]; then
    echo "${name}은 양의 정수여야 합니다: ${value}" >&2
    exit 1
  fi
}

require_positive_integer "PT_ID" "${PT_ID}"
require_positive_integer "MAX_ACTIVE_SESSIONS" "${MAX_ACTIVE_SESSIONS}"
require_positive_integer "ADMIT_PER_INTERVAL" "${ADMIT_PER_INTERVAL}"
require_positive_integer "MEMBER_ID_BASE" "${MEMBER_ID_BASE}"
require_positive_integer "MEMBER_COUNT" "${MEMBER_COUNT}"
require_positive_integer "SEAT_ID_START" "${SEAT_ID_START}"
if [[ -z "${JWT_SECRET}" ]]; then
  echo "JWT_SECRET 또는 SPRING_JWT_SECRET이 필요합니다." >&2
  exit 1
fi
case "${RECONFIGURE_WAITING_ROOM_SERVICE}" in
  true|false) ;;
  *) echo "RECONFIGURE_WAITING_ROOM_SERVICE는 true 또는 false여야 합니다." >&2; exit 1 ;;
esac
case "${RESET_FIXTURE}" in
  true|false) ;;
  *) echo "RESET_FIXTURE는 true 또는 false여야 합니다." >&2; exit 1 ;;
esac

if ! command -v jq >/dev/null 2>&1; then
  echo "jq가 필요합니다." >&2
  exit 1
fi

if ! [[ "${MEMBER_COUNT}" -ge "${MAX_ACTIVE_SESSIONS}" ]]; then
  echo "MEMBER_COUNT는 MAX_ACTIVE_SESSIONS 이상이어야 합니다." >&2
  exit 1
fi

declare -a concurrency_values=()
for concurrency in ${CONCURRENCIES}; do
  require_positive_integer "CONCURRENCY" "${concurrency}"
  if (( concurrency > MAX_ACTIVE_SESSIONS )); then
    echo "CONCURRENCY=${concurrency}가 MAX_ACTIVE_SESSIONS=${MAX_ACTIVE_SESSIONS}보다 큽니다." >&2
    exit 1
  fi
  concurrency_values+=("${concurrency}")
done
if (( ${#concurrency_values[@]} == 0 )); then
  echo "CONCURRENCIES가 비어 있습니다." >&2
  exit 1
fi

if [[ -z "${SEAT_IDS}" ]]; then
  SEAT_IDS=""
  for ((offset = 0; offset < MAX_ACTIVE_SESSIONS; offset += 1)); do
    if [[ -n "${SEAT_IDS}" ]]; then
      SEAT_IDS+=","
    fi
    SEAT_IDS+=$((SEAT_ID_START + offset))
  done
fi

IFS=',' read -r -a seat_id_values <<< "${SEAT_IDS}"
if (( ${#seat_id_values[@]} < MAX_ACTIVE_SESSIONS )); then
  echo "SEAT_IDS가 부족합니다. required=${MAX_ACTIVE_SESSIONS}, actual=${#seat_id_values[@]}" >&2
  exit 1
fi
for seat_id in "${seat_id_values[@]}"; do
  require_positive_integer "SEAT_ID" "${seat_id}"
done

mkdir -p "${GROUP_DIR}"
printf 'run_index\trun_name\tconcurrency\tmember_id_base\tseat_ids\tbase_url\tmanagement_base_url\tpt_id\tmode\tflow\trun_dir\n' \
  > "${MANIFEST_FILE}"
printf 'concurrency\tseat_map_p95_ms\tseat_map_p99_ms\tpre_reserve_p95_ms\tpre_reserve_p99_ms\ttomcat_busy_max\thikari_pending_max\tjoin_success\tpre_reserve_success\n' \
  > "${GROUP_DIR}/capacity-matrix.tsv"

if [[ "${RECONFIGURE_WAITING_ROOM_SERVICE}" == "true" ]]; then
  env \
    RESERVATION_WAITING_ROOM_ENABLED=true \
    RESERVATION_WAITING_ROOM_ENABLED_PERFORMANCE_TIME_IDS="${PT_ID}" \
    RESERVATION_WAITING_ROOM_MAX_ACTIVE_SESSIONS="${MAX_ACTIVE_SESSIONS}" \
    RESERVATION_WAITING_ROOM_ADMIT_PER_INTERVAL="${ADMIT_PER_INTERVAL}" \
    RESERVATION_WAITING_ROOM_PASS_SECRET="${WAITING_ROOM_PASS_SECRET}" \
    docker compose --profile waiting-room-ingress-experiment up -d --build --force-recreate waiting-room-service waiting-room-gateway
fi

for run_index in "${!concurrency_values[@]}"; do
  concurrency="${concurrency_values[run_index]}"
  run_number=$((run_index + 1))
  run_name="b-protected-zone-capacity-${concurrency}-${RUN_GROUP}"
  run_dir="${GROUP_DIR}/${run_name}"
  # fixture reset이 같은 회원 범위를 매 회차 복원하므로 순차 실행에서는 재사용한다.
  run_member_id_base="${MEMBER_ID_BASE}"
  run_seat_ids="$(IFS=','; printf '%s' "${seat_id_values[*]:0:${concurrency}}")"

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\tfull-flow\twaiting-room\t%s\n' \
    "${run_number}" \
    "${run_name}" \
    "${concurrency}" \
    "${run_member_id_base}" \
    "${run_seat_ids}" \
    "${BASE_URL}" \
    "${MANAGEMENT_BASE_URL}" \
    "${PT_ID}" \
    "${run_dir}" \
    >> "${MANIFEST_FILE}"

  echo "[B] run ${run_number}/${#concurrency_values[@]}: concurrency=${concurrency}"
  if [[ "${RESET_FIXTURE}" == "true" ]]; then
    PT_ID="${PT_ID}" \
    MEMBER_ID_START=$((run_member_id_base + 1)) \
    MEMBER_COUNT="${concurrency}" \
    MYSQL_USE_DOCKER="${MYSQL_USE_DOCKER}" \
    REDIS_USE_DOCKER="${REDIS_USE_DOCKER}" \
    bash "${SCRIPT_DIR}/reset_waiting_room_fixture.sh"
  fi

  BASE_URL="${BASE_URL}" \
  MANAGEMENT_BASE_URL="${MANAGEMENT_BASE_URL}" \
  PT_ID="${PT_ID}" \
  JWT_SECRET="${JWT_SECRET}" \
  MODE=full-flow \
  FLOW=waiting-room \
  CONCURRENCY="${concurrency}" \
  MEMBER_ID_BASE="${run_member_id_base}" \
  STATUS_POLLS="${STATUS_POLLS}" \
  STATUS_POLL_INTERVAL_MS="${STATUS_POLL_INTERVAL_MS}" \
  STATUS_POLL_JITTER_RATIO="${STATUS_POLL_JITTER_RATIO}" \
  SEAT_IDS="${run_seat_ids}" \
  REQUEST_TIMEOUT="${REQUEST_TIMEOUT}" \
  MAX_DURATION="${MAX_DURATION}" \
  RUN_NAME="${run_name}" \
  RUN_DIR="${run_dir}" \
  bash "${SCRIPT_DIR}/run_waiting_room_load.sh"

  contract_value="$(jq -r '.metrics.waiting_room_contract_success.value // 0' "${run_dir}/k6-summary.json")"
  join_count="$(jq -r '.metrics.waiting_room_join_success.count // 0' "${run_dir}/k6-summary.json")"
  seat_map_count="$(jq -r '.metrics.waiting_room_seat_map_success.count // 0' "${run_dir}/k6-summary.json")"
  pre_reserve_success_count="$(jq -r '.metrics.waiting_room_pre_reserve_success.count // 0' "${run_dir}/k6-summary.json")"
  if [[ "${contract_value}" != "1" && "${contract_value}" != "1.0" ]] \
    || [[ "${join_count}" != "${concurrency}" ]] \
    || [[ "${seat_map_count}" != "${concurrency}" ]] \
    || [[ "${pre_reserve_success_count}" != "${concurrency}" ]]; then
    echo "B 계약 실패: run=${run_name} contract=${contract_value} join=${join_count} seat_map=${seat_map_count} pre_reserve_success=${pre_reserve_success_count}" >&2
    exit 1
  fi

  tomcat_busy_max="$(awk -F '\t' 'BEGIN { max = -1 } NR > 1 && $3 != "" && $3 > max { max = $3 } END { if (max < 0) print "missing"; else print max }' "${run_dir}/app-metrics.tsv")"
  hikari_pending_max="$(awk -F '\t' 'BEGIN { max = -1 } NR > 1 && $7 != "" && $7 > max { max = $7 } END { if (max < 0) print "missing"; else print max }' "${run_dir}/app-metrics.tsv")"
  seat_map_p95="$(jq -r '.metrics.waiting_room_seat_map_duration["p(95)"] // 0' "${run_dir}/k6-summary.json")"
  seat_map_p99="$(jq -r '.metrics.waiting_room_seat_map_duration["p(99)"] // 0' "${run_dir}/k6-summary.json")"
  pre_reserve_p95="$(jq -r '.metrics.waiting_room_pre_reserve_duration["p(95)"] // 0' "${run_dir}/k6-summary.json")"
  pre_reserve_p99="$(jq -r '.metrics.waiting_room_pre_reserve_duration["p(99)"] // 0' "${run_dir}/k6-summary.json")"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "${concurrency}" \
    "${seat_map_p95}" \
    "${seat_map_p99}" \
    "${pre_reserve_p95}" \
    "${pre_reserve_p99}" \
    "${tomcat_busy_max}" \
    "${hikari_pending_max}" \
    "${join_count}" \
    "${pre_reserve_success_count}" \
    >> "${GROUP_DIR}/capacity-matrix.tsv"
done

printf 'b_runs=%s\nmanifest=%s\ncapacity_matrix=%s\n' \
  "${#concurrency_values[@]}" \
  "${MANIFEST_FILE}" \
  "${GROUP_DIR}/capacity-matrix.tsv"
