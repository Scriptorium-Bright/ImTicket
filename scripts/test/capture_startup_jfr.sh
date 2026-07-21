#!/usr/bin/env bash
set -euo pipefail

# application process가 시작되는 순간부터 JFR을 기록한다. duration이 끝나면 JVM이
# 실행 중인 상태에서도 recording을 파일로 닫고, 마지막에 JFR 없이 app을 다시 올린다.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
READY_TIMEOUT_SECONDS="${READY_TIMEOUT_SECONDS:-240}"
JFR_DURATION_SECONDS="${JFR_DURATION_SECONDS:-180}"
BUILD_APP_IMAGE="${BUILD_APP_IMAGE:-true}"
RESULT_ROOT="${RESULT_ROOT:-${ROOT_DIR}/build/k6-results/startup-jfr}"
RUN_ID="${RUN_ID:-boot-$(date -u +%Y%m%dT%H%M%SZ)}"
RUN_DIR="${RESULT_ROOT}/${RUN_ID}"
JFR_HOST_DIR="${ROOT_DIR}/uploads/jfr"
JFR_HOST_FILE="${JFR_HOST_DIR}/${RUN_ID}.jfr"
JFR_CONTAINER_FILE="/app/uploads/jfr/${RUN_ID}.jfr"
ORIGINAL_JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-}"
RECORDING_JAVA_TOOL_OPTIONS="${ORIGINAL_JAVA_TOOL_OPTIONS} -XX:StartFlightRecording=name=boot,settings=profile,duration=${JFR_DURATION_SECONDS}s,filename=${JFR_CONTAINER_FILE},maxsize=128m"
app_recreated=false
app_restored=false

if [[ ! "${READY_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "READY_TIMEOUT_SECONDS는 양의 정수여야 합니다." >&2
  exit 1
fi
if [[ ! "${JFR_DURATION_SECONDS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "JFR_DURATION_SECONDS는 양의 정수여야 합니다." >&2
  exit 1
fi
if [[ ! "${BUILD_APP_IMAGE}" =~ ^(true|false)$ ]]; then
  echo "BUILD_APP_IMAGE은 true 또는 false여야 합니다." >&2
  exit 1
fi
for command in docker date; do
  if ! command -v "${command}" > /dev/null 2>&1; then
    echo "필수 명령을 찾지 못했습니다: ${command}" >&2
    exit 1
  fi
done

mkdir -p "${RUN_DIR}" "${JFR_HOST_DIR}"

restore_application() {
  if [[ "${app_recreated}" == "true" && "${app_restored}" != "true" ]]; then
    TICKET_STARTUP_JFR_ENABLED=false \
      JAVA_TOOL_OPTIONS="${ORIGINAL_JAVA_TOOL_OPTIONS}" \
      docker compose -f "${ROOT_DIR}/docker-compose.yml" up -d --no-deps --force-recreate app > /dev/null || true
  fi
}
trap restore_application EXIT INT TERM

if [[ "${BUILD_APP_IMAGE}" == "true" ]]; then
  docker compose -f "${ROOT_DIR}/docker-compose.yml" build app
fi

rm -f "${JFR_HOST_FILE}"
started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
started_at_epoch="$(date +%s)"
TICKET_STARTUP_JFR_ENABLED=true \
  JAVA_TOOL_OPTIONS="${RECORDING_JAVA_TOOL_OPTIONS}" \
  docker compose -f "${ROOT_DIR}/docker-compose.yml" up -d --no-deps --force-recreate app
app_recreated=true

for attempt in $(seq 1 "${READY_TIMEOUT_SECONDS}"); do
  health_status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' imticket-app 2>/dev/null || true)"
  if [[ "${health_status}" == "healthy" ]]; then
    ready_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    break
  fi
  sleep 1
done

if [[ -z "${ready_at:-}" ]]; then
  docker compose -f "${ROOT_DIR}/docker-compose.yml" logs --no-log-prefix app > "${RUN_DIR}/app-startup.log" || true
  echo "${READY_TIMEOUT_SECONDS}초 안에 app health가 healthy가 되지 않았습니다." >&2
  exit 1
fi

docker compose -f "${ROOT_DIR}/docker-compose.yml" logs --no-log-prefix app > "${RUN_DIR}/app-startup.log"
docker inspect imticket-app > "${RUN_DIR}/container-inspect.json"

for attempt in $(seq 1 "$((JFR_DURATION_SECONDS + 30))"); do
  if [[ -s "${JFR_HOST_FILE}" ]]; then
    break
  fi
  sleep 1
done

if [[ ! -s "${JFR_HOST_FILE}" ]]; then
  elapsed_seconds="$(( $(date +%s) - started_at_epoch ))"
  echo "${elapsed_seconds}초 동안 JFR 파일을 찾지 못했습니다: ${JFR_HOST_FILE}" >&2
  exit 1
fi

cp "${JFR_HOST_FILE}" "${RUN_DIR}/boot.jfr"
printf 'run_id=%s\nstarted_at=%s\nready_at=%s\nready_timeout_seconds=%s\njfr_duration_seconds=%s\njfr_file=%s\n' \
  "${RUN_ID}" "${started_at}" "${ready_at}" "${READY_TIMEOUT_SECONDS}" "${JFR_DURATION_SECONDS}" "${JFR_HOST_FILE}" \
  > "${RUN_DIR}/capture-summary.txt"

if command -v jfr > /dev/null 2>&1; then
  jfr summary "${RUN_DIR}/boot.jfr" > "${RUN_DIR}/boot-jfr-summary.txt"
fi

TICKET_STARTUP_JFR_ENABLED=false \
  JAVA_TOOL_OPTIONS="${ORIGINAL_JAVA_TOOL_OPTIONS}" \
  docker compose -f "${ROOT_DIR}/docker-compose.yml" up -d --no-deps --force-recreate app
app_restored=true

printf 'startup_jfr_capture=%s\nresult_dir=%s\n' "passed" "${RUN_DIR}"
