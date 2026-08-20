#!/usr/bin/env bash
set -euo pipefail

# A1 동기 join 기준선을 같은 조건으로 3회 실행한다.
# 실제 1회 측정과 Prometheus·Redis 수집은 기존 run_waiting_room_load.sh에 위임한다.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/load_env_defaults.sh"
load_imticket_env "${ROOT_DIR}/.env"

BASE_URL="${BASE_URL:-http://127.0.0.1:10083}"
MANAGEMENT_BASE_URL="${MANAGEMENT_BASE_URL:-http://127.0.0.1:10084}"
PT_ID="${PT_ID:-900000001}"
JWT_SECRET="${JWT_SECRET:-${SPRING_JWT_SECRET:-}}"
CONCURRENCY="${CONCURRENCY:-2000}"
BASE_MEMBER_ID="${BASE_MEMBER_ID:-990000001}"
MEMBER_COUNT="${MEMBER_COUNT:-2000}"
MYSQL_USE_DOCKER="${MYSQL_USE_DOCKER:-auto}"
REDIS_USE_DOCKER="${REDIS_USE_DOCKER:-auto}"
RESET_FIXTURE="${RESET_FIXTURE:-true}"
RESULT_ROOT="${RESULT_ROOT:-${ROOT_DIR}/build/k6-results/146.8.3-join-before}"
RUN_GROUP="${RUN_GROUP:-$(date -u +%Y%m%dT%H%M%SZ)}"
GROUP_DIR="${RESULT_ROOT}/${RUN_GROUP}"
MANIFEST_FILE="${GROUP_DIR}/run-manifest.tsv"

if [[ -z "${JWT_SECRET}" ]]; then
  echo "JWT_SECRET 또는 SPRING_JWT_SECRET이 필요합니다." >&2
  exit 1
fi
if ! [[ "${PT_ID}" =~ ^[1-9][0-9]*$ ]]; then
  echo "PT_ID는 양의 정수여야 합니다." >&2
  exit 1
fi
if [[ "${CONCURRENCY}" != "2000" ]]; then
  echo "Before 측정의 CONCURRENCY는 2000으로 고정합니다. actual=${CONCURRENCY}" >&2
  exit 1
fi
if ! [[ "${BASE_MEMBER_ID}" =~ ^[1-9][0-9]*$ ]]; then
  echo "BASE_MEMBER_ID는 양의 정수여야 합니다." >&2
  exit 1
fi
if ! [[ "${MEMBER_COUNT}" =~ ^[1-9][0-9]*$ ]]; then
  echo "MEMBER_COUNT는 양의 정수여야 합니다." >&2
  exit 1
fi
case "${RESET_FIXTURE}" in
  true|false) ;;
  *)
    echo "RESET_FIXTURE는 true 또는 false여야 합니다." >&2
    exit 1
    ;;
esac

mkdir -p "${GROUP_DIR}"
printf 'run_index\trun_name\tmember_id_base\tbase_url\tmanagement_base_url\tpt_id\tconcurrency\tmode\tflow\tstatus_polls\trun_dir\n' \
  > "${MANIFEST_FILE}"

for run_index in 1 2 3; do
  run_name="a1-sync-join-2000-${RUN_GROUP}-r${run_index}"
  run_dir="${GROUP_DIR}/${run_name}"
  run_member_id_base=$((BASE_MEMBER_ID + (run_index - 1) * 10000))

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\tjoin\twaiting-room\t0\t%s\n' \
    "${run_index}" \
    "${run_name}" \
    "${run_member_id_base}" \
    "${BASE_URL}" \
    "${MANAGEMENT_BASE_URL}" \
    "${PT_ID}" \
    "${CONCURRENCY}" \
    "${run_dir}" \
    >> "${MANIFEST_FILE}"

  echo "[A1] run ${run_index}/3: ${run_dir}"
  if [[ "${RESET_FIXTURE}" == "true" ]]; then
    PT_ID="${PT_ID}" \
    MEMBER_ID_START="${run_member_id_base}" \
    MEMBER_COUNT="${MEMBER_COUNT}" \
    MYSQL_USE_DOCKER="${MYSQL_USE_DOCKER}" \
    REDIS_USE_DOCKER="${REDIS_USE_DOCKER}" \
    bash "${SCRIPT_DIR}/reset_waiting_room_fixture.sh"
  fi

  BASE_URL="${BASE_URL}" \
  MANAGEMENT_BASE_URL="${MANAGEMENT_BASE_URL}" \
  PT_ID="${PT_ID}" \
  JWT_SECRET="${JWT_SECRET}" \
  MODE=join \
  FLOW=waiting-room \
  CONCURRENCY=2000 \
  MEMBER_ID_BASE="${run_member_id_base}" \
  STATUS_POLLS=0 \
  RUN_NAME="${run_name}" \
  RUN_DIR="${run_dir}" \
  "${SCRIPT_DIR}/run_waiting_room_load.sh"

  contract_value="$(jq -r '.metrics.waiting_room_contract_success.value // empty' "${run_dir}/k6-summary.json")"
  if [[ "${contract_value}" != "1" && "${contract_value}" != "1.0" ]]; then
    echo "join contract가 100%가 아니어서 A1 실행을 중단합니다: run=${run_name}, value=${contract_value:-missing}" >&2
    echo "application의 async join 설정과 대상 회차 설정을 확인한 뒤 같은 RUN_GROUP으로 재실행하십시오." >&2
    exit 1
  fi
done

printf 'before_runs=3\nmanifest=%s\nresult_root=%s\n' "${MANIFEST_FILE}" "${GROUP_DIR}"
