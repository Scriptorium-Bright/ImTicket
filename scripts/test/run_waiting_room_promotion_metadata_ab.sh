#!/usr/bin/env bash
set -euo pipefail

# Waiting Room promotion metadata A/B experiment.
# legacy   : candidate마다 ticket Hash HGETALL
# pipeline : candidate memberId HGET을 Redis pipeline 한 번으로 flush

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

RESULT_ROOT="${RESULT_ROOT:-${ROOT_DIR}/build/k6-results/149-promotion-metadata-ab}"
RUN_GROUP="${RUN_GROUP:-$(date -u +%Y%m%dT%H%M%SZ)}"
WAITING_ROOM_MANAGEMENT_BASE_URL="${WAITING_ROOM_MANAGEMENT_BASE_URL:-http://127.0.0.1:10084}"
AB_ORDER="${AB_ORDER:-legacy-first}"
BUILD_EXPERIMENT_IMAGES="${BUILD_EXPERIMENT_IMAGES:-true}"

LEGACY_GROUP="${RUN_GROUP}-legacy"
PIPELINE_GROUP="${RUN_GROUP}-pipeline"
LEGACY_DIR="${RESULT_ROOT}/${LEGACY_GROUP}"
PIPELINE_DIR="${RESULT_ROOT}/${PIPELINE_GROUP}"

mkdir -p "${RESULT_ROOT}"

if [[ "${BUILD_EXPERIMENT_IMAGES}" == "true" ]]; then
  echo "== build A/B experiment images once =="
  env \
    SPRING_JWT_SECRET="${JWT_SECRET:-${SPRING_JWT_SECRET:-}}" \
    docker compose --profile waiting-room-ingress-experiment build app waiting-room-service
else
  echo "== reuse existing A/B experiment images =="
fi

run_variant() {
  local label="$1"
  local enabled="$2"
  local group="$3"
  local group_dir="${RESULT_ROOT}/${group}"

  echo "== promotion metadata variant: ${label} =="

  set +e
  RESULT_ROOT="${RESULT_ROOT}" \
  RUN_GROUP="${group}" \
  PROMOTION_METADATA_PIPELINE_ENABLED="${enabled}" \
  RECONFIGURE_SERVICES=true \
  BUILD_IMAGES=false \
  STOP_ON_SLO_FAILURE=false \
    bash "${SCRIPT_DIR}/run_waiting_room_final_performance_closure.sh"
  local closure_status=$?
  set -e

  mkdir -p "${group_dir}"
  printf '%s\n' "${closure_status}" > "${group_dir}/closure-exit-code.txt"

  curl -fsS --connect-timeout 1 --max-time 5 \
    "${WAITING_ROOM_MANAGEMENT_BASE_URL}/actuator/prometheus" \
    > "${group_dir}/waiting-room-prometheus-final.txt" || true

  echo "variant=${label} closure_exit=${closure_status} dir=${group_dir}"
}

case "${AB_ORDER}" in
  legacy-first)
    run_variant legacy false "${LEGACY_GROUP}"
    run_variant pipeline true "${PIPELINE_GROUP}"
    ;;
  pipeline-first)
    run_variant pipeline true "${PIPELINE_GROUP}"
    run_variant legacy false "${LEGACY_GROUP}"
    ;;
  *)
    echo "AB_ORDER는 legacy-first 또는 pipeline-first여야 합니다: ${AB_ORDER}" >&2
    exit 1
    ;;
esac

python3 "${SCRIPT_DIR}/summarize_waiting_room_promotion_ab.py" \
  --legacy-dir "${LEGACY_DIR}" \
  --pipeline-dir "${PIPELINE_DIR}" \
  --output-dir "${RESULT_ROOT}/${RUN_GROUP}-summary"

echo
echo "A/B summary:"
echo "  ${RESULT_ROOT}/${RUN_GROUP}-summary/RESULTS.md"
echo "  ${RESULT_ROOT}/${RUN_GROUP}-summary/promotion-metadata-ab.tsv"
