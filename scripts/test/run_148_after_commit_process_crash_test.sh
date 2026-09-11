#!/usr/bin/env bash
set -euo pipefail

# MySQL commit 직후 Redis 무효화가 대기하는 동안 reservation 애플리케이션을 종료한다.
# 애플리케이션 소스의 중단 지점은 사용하지 않으며, 테스트용 k6와 외부 실행 순서만 변경한다.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
K6_SCRIPT="${SCRIPT_DIR}/148-after-commit-process-crash-load.js"
source "${SCRIPT_DIR}/load_env_defaults.sh"
load_imticket_env "${ROOT_DIR}/.env"

BASE_URL="${BASE_URL:-http://127.0.0.1:10080}"
PT_ID="${PT_ID:-900000001}"
SEAT_ID="${SEAT_ID:-900000001}"
MEMBER_ID="${MEMBER_ID:-900009980}"
BENCHMARK_MEMBER_ID_BASE="${BENCHMARK_MEMBER_ID_BASE:-900000000}"
JWT_SECRET="${JWT_SECRET:-${SPRING_JWT_SECRET:-}}"
IDEMPOTENCY_KEY="${IDEMPOTENCY_KEY:-e1480001-0000-4000-8000-000000000000}"
MYSQL_DATABASE="${MYSQL_DATABASE:-capstone}"
TEST_TTL="${TEST_TTL:-60s}"
REDIS_TIMEOUT="${REDIS_TIMEOUT:-20s}"
REDIS_PAUSE_MS="${REDIS_PAUSE_MS:-30000}"
COMMIT_TIMEOUT_SECONDS="${COMMIT_TIMEOUT_SECONDS:-20}"
RECOVERY_TIMEOUT_SECONDS="${RECOVERY_TIMEOUT_SECONDS:-90}"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-0.1}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-45s}"
K6_MAX_DURATION="${K6_MAX_DURATION:-50s}"
RESULT_ROOT="${RESULT_ROOT:-${ROOT_DIR}/build/k6-results/148.3-after-commit-process-crash}"
RUN_ID="${RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
RUN_DIR="${RUN_DIR:-${RESULT_ROOT}/${RUN_ID}}"
K6_BIN="${K6_BIN:-/opt/homebrew/bin/k6}"

K6_PID=""
RESTORE_STARTED=0
TEST_RESULT="not_started"
COMMIT_OBSERVED_EPOCH=""
STALE_OBSERVED_EPOCH=""
RECOVERY_OBSERVED_EPOCH=""

TIMELINE_FILE="${RUN_DIR}/timeline.tsv"
K6_LOG="${RUN_DIR}/k6.log"
K6_SUMMARY="${RUN_DIR}/k6-summary.json"
SNAPSHOT_KEY="reservation:seat-map:{${PT_ID}}:snapshot:v2"
VERSION_KEY="reservation:seat-map:{${PT_ID}}:version"
LEGACY_SNAPSHOT_KEY="reservation:seat-map:{${PT_ID}}:snapshot"

require_positive_integer() {
  local name="$1"
  local value="$2"
  if ! [[ "${value}" =~ ^[1-9][0-9]*$ ]]; then
    echo "${name}은 양의 정수여야 합니다: ${value}" >&2
    exit 1
  fi
}

require_non_negative_integer() {
  local name="$1"
  local value="$2"
  if ! [[ "${value}" =~ ^[0-9]+$ ]]; then
    echo "${name}은 0 이상의 정수여야 합니다: ${value}" >&2
    exit 1
  fi
}

require_duration() {
  local name="$1"
  local value="$2"
  if ! [[ "${value}" =~ ^[1-9][0-9]*(ms|s|m|h)$ ]]; then
    echo "${name}은 예: 60s, 1m 형식이어야 합니다: ${value}" >&2
    exit 1
  fi
}

require_positive_integer "PT_ID" "${PT_ID}"
require_positive_integer "SEAT_ID" "${SEAT_ID}"
require_positive_integer "MEMBER_ID" "${MEMBER_ID}"
require_positive_integer "BENCHMARK_MEMBER_ID_BASE" "${BENCHMARK_MEMBER_ID_BASE}"
require_positive_integer "COMMIT_TIMEOUT_SECONDS" "${COMMIT_TIMEOUT_SECONDS}"
require_positive_integer "RECOVERY_TIMEOUT_SECONDS" "${RECOVERY_TIMEOUT_SECONDS}"
require_non_negative_integer "REDIS_PAUSE_MS" "${REDIS_PAUSE_MS}"
require_duration "TEST_TTL" "${TEST_TTL}"
require_duration "REDIS_TIMEOUT" "${REDIS_TIMEOUT}"
require_duration "REQUEST_TIMEOUT" "${REQUEST_TIMEOUT}"
require_duration "K6_MAX_DURATION" "${K6_MAX_DURATION}"

if [[ -z "${JWT_SECRET}" ]]; then
  echo "JWT_SECRET 또는 SPRING_JWT_SECRET이 필요합니다." >&2
  exit 1
fi
if ! [[ "${IDEMPOTENCY_KEY}" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$ ]]; then
  echo "IDEMPOTENCY_KEY는 canonical UUID version 4 형식이어야 합니다: ${IDEMPOTENCY_KEY}" >&2
  exit 1
fi
if (( REDIS_PAUSE_MS < 5000 )); then
  echo "REDIS_PAUSE_MS는 5000 이상으로 설정해야 합니다." >&2
  exit 1
fi
if ! command -v docker >/dev/null 2>&1; then
  echo "docker가 필요합니다." >&2
  exit 1
fi
if ! command -v curl >/dev/null 2>&1; then
  echo "curl이 필요합니다." >&2
  exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "jq가 필요합니다." >&2
  exit 1
fi
if [[ ! -x "${K6_BIN}" ]]; then
  K6_BIN="$(command -v k6 || true)"
fi
if [[ -z "${K6_BIN}" || ! -x "${K6_BIN}" ]]; then
  echo "k6가 필요합니다." >&2
  exit 1
fi

mkdir -p "${RUN_DIR}"
printf 'epoch_seconds\tobserved_at_utc\tevent\tdb_state\tapi_state\tredis_version\tredis_ttl_ms\n' \
  > "${TIMELINE_FILE}"

mysql_root_query() {
  local sql="$1"
  docker compose exec -T mysql sh -c \
    'mysql --user=root --password="$MYSQL_ROOT_PASSWORD" --database="$MYSQL_DATABASE" --batch --skip-column-names --silent' \
    <<< "${sql}"
}

redis_cli() {
  docker compose exec -T redis redis-cli "$@" < /dev/null
}

epoch_seconds() {
  date +%s
}

observed_at_utc() {
  date -u '+%Y-%m-%dT%H:%M:%SZ'
}

seat_state() {
  mysql_root_query "
    SELECT CONCAT(seat_status, '/', version)
    FROM Seat
    WHERE id=${SEAT_ID} AND performance_time_id=${PT_ID};
  " | tr -d '\r' | tail -n 1
}

mysql_fixture_state() {
  mysql_root_query "
    SELECT CONCAT(
      s.seat_status, '/', s.version, ';',
      COALESCE(ri.status, 'NONE'), '/',
      COALESCE(CAST(ri.reservation_id AS CHAR), 'NULL')
    )
    FROM Seat s
    LEFT JOIN reservation_idempotency ri
      ON ri.idempotency_key='${IDEMPOTENCY_KEY}'
     AND ri.member_id=${MEMBER_ID}
    WHERE s.id=${SEAT_ID} AND s.performance_time_id=${PT_ID};
  " | tr -d '\r' | tail -n 1
}

redis_value() {
  local key="$1"
  redis_cli --raw GET "${key}" | tr -d '\r' | tail -n 1
}

redis_version() {
  local value
  value="$(redis_value "${VERSION_KEY}" || true)"
  if [[ -z "${value}" ]]; then
    printf '0\n'
  else
    printf '%s\n' "${value}"
  fi
}

redis_ttl_ms() {
  redis_cli PTTL "${SNAPSHOT_KEY}" | tr -d '\r' | tail -n 1
}

snapshot_seat_status() {
  local snapshot_file="$1"
  jq -r --arg seat_id "${SEAT_ID}" '
    [(.entries // .)[]?
      | select(((.seatId // .id) | tostring) == $seat_id)
      | (.seatStatus // .status // "UNKNOWN")]
    | if length == 1 then .[0] else "MISSING" end
  ' "${snapshot_file}"
}

api_seat_status() {
  local response_file="$1"
  jq -r --arg seat_id "${SEAT_ID}" '
    [(.data // [])[]?
      | select(((.seatId // .id) | tostring) == $seat_id)
      | (.seatStatus // .status // "UNKNOWN")]
    | if length == 1 then .[0] else "MISSING" end
  ' "${response_file}"
}

record_event() {
  local event="$1"
  local db_state="${2:-$(mysql_fixture_state 2>/dev/null || printf 'UNKNOWN')}"
  local api_state="${3:-UNKNOWN}"
  local current_version="${4:-}"
  local current_ttl="${5:-}"
  local current_epoch
  current_epoch="$(epoch_seconds)"
  if [[ -z "${current_version}" ]]; then
    current_version="$(redis_version 2>/dev/null || printf 'UNKNOWN')"
  fi
  if [[ -z "${current_ttl}" ]]; then
    current_ttl="$(redis_ttl_ms 2>/dev/null || printf 'UNKNOWN')"
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "${current_epoch}" \
    "$(observed_at_utc)" \
    "${event}" \
    "${db_state}" \
    "${api_state}" \
    "${current_version}" \
    "${current_ttl}" \
    >> "${TIMELINE_FILE}"
}

clear_seat_map_cache() {
  redis_cli DEL "${VERSION_KEY}" "${SNAPSHOT_KEY}" "${LEGACY_SNAPSHOT_KEY}" >/dev/null
}

restore_redis() {
  local attempt
  for attempt in 1 2 3 4 5; do
    if redis_cli CLIENT UNPAUSE >/dev/null 2>&1; then
      redis_cli ACL SETUSER default +INCR +DEL +EVAL +EVALSHA >/dev/null 2>&1 || true
      return 0
    fi
    sleep 1
  done
  echo "Redis CLIENT UNPAUSE에 실패했습니다." >&2
  return 1
}

cleanup_fixture() {
  mysql_root_query "
    START TRANSACTION;
    SET @reservation_id := (
      SELECT reservation_id
      FROM reservation_idempotency
      WHERE idempotency_key='${IDEMPOTENCY_KEY}'
        AND member_id=${MEMBER_ID}
      LIMIT 1
    );
    UPDATE Seat s
    JOIN ReservedSeat rs ON rs.seat_id=s.id
    SET s.seat_status='AVAILABLE', s.is_reservation=0, s.version=0
    WHERE rs.reservation_id=@reservation_id
      AND s.id=${SEAT_ID}
      AND s.performance_time_id=${PT_ID};
    DELETE FROM ReservedSeat WHERE reservation_id=@reservation_id;
    DELETE FROM reservation_idempotency
    WHERE idempotency_key='${IDEMPOTENCY_KEY}' AND member_id=${MEMBER_ID};
    DELETE FROM Reservation WHERE id=@reservation_id;
    COMMIT;
  " >/dev/null
}

restore_app() {
  RESERVATION_SEAT_MAP_CACHE_ENABLED=false \
  RESERVATION_SEAT_MAP_CACHE_ENABLED_PERFORMANCE_TIME_IDS= \
  RESERVATION_SEAT_MAP_CACHE_TTL=5m \
  RESERVATION_WAITING_ROOM_ENABLED=false \
  TICKET_SHEDLOCK_SCHEMA_INITIALIZATION_ENABLED=true \
  SPRING_DATA_REDIS_TIMEOUT=250ms \
  SPRING_DATA_REDIS_CONNECT_TIMEOUT=250ms \
  LOCK_STRATEGY=reentrant \
  docker compose up -d --force-recreate --no-deps app >/dev/null
}

start_test_app() {
  local recreate="${1:-false}"
  local -a up_args=(-d --no-deps app)
  if [[ "${recreate}" == true ]]; then
    up_args=(-d --force-recreate --no-deps app)
  fi

  RESERVATION_SEAT_MAP_CACHE_ENABLED=true \
  RESERVATION_SEAT_MAP_CACHE_ENABLED_PERFORMANCE_TIME_IDS="${PT_ID}" \
  RESERVATION_SEAT_MAP_CACHE_TTL="${TEST_TTL}" \
  RESERVATION_WAITING_ROOM_ENABLED=false \
  TICKET_SHEDLOCK_SCHEMA_INITIALIZATION_ENABLED=false \
  SPRING_DATA_REDIS_TIMEOUT="${REDIS_TIMEOUT}" \
  SPRING_DATA_REDIS_CONNECT_TIMEOUT=250ms \
  LOCK_STRATEGY=reentrant \
  docker compose up "${up_args[@]}" >/dev/null
}

wait_for_app() {
  local attempts="${1:-90}"
  local attempt readiness_file
  readiness_file="${RUN_DIR}/readiness-last.json"
  for ((attempt=1; attempt<=attempts; attempt++)); do
    if curl -sS --connect-timeout 2 --max-time 5 \
      "${BASE_URL}/api/seats/${PT_ID}" > "${readiness_file}" 2>/dev/null \
      && jq -e '.success == true and (.data | type == "array")' "${readiness_file}" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "애플리케이션 readiness 확인에 실패했습니다." >&2
  docker compose ps app >&2 || true
  return 1
}

wait_for_mysql_locked() {
  local deadline current_state
  deadline=$(( $(epoch_seconds) + COMMIT_TIMEOUT_SECONDS ))
  while (( $(epoch_seconds) <= deadline )); do
    current_state="$(seat_state 2>/dev/null || true)"
    if [[ "${current_state}" == LOCKED/* ]]; then
      COMMIT_OBSERVED_EPOCH="$(epoch_seconds)"
      record_event "mysql_locked_commit_observed" "$(mysql_fixture_state)" "UNKNOWN" "PAUSED" "PAUSED"
      return 0
    fi
    sleep "${POLL_INTERVAL_SECONDS}"
  done
  echo "MySQL에서 대상 좌석 LOCKED commit을 확인하지 못했습니다. state=$(seat_state 2>/dev/null || printf UNKNOWN)" >&2
  return 1
}

wait_for_recovery() {
  local deadline current_status response_file
  deadline=$(( $(epoch_seconds) + RECOVERY_TIMEOUT_SECONDS ))
  response_file="${RUN_DIR}/recovery-last-seat-map.json"
  while (( $(epoch_seconds) <= deadline )); do
    if curl -sS --connect-timeout 2 --max-time 10 \
      "${BASE_URL}/api/seats/${PT_ID}" > "${response_file}" 2>/dev/null \
      && jq -e '.success == true and (.data | type == "array")' "${response_file}" >/dev/null 2>&1; then
      current_status="$(api_seat_status "${response_file}")"
      record_event "recovery_poll" "$(mysql_fixture_state 2>/dev/null || printf UNKNOWN)" "${current_status}"
      if [[ "${current_status}" == LOCKED ]]; then
        RECOVERY_OBSERVED_EPOCH="$(epoch_seconds)"
        cp "${response_file}" "${RUN_DIR}/recovered-seat-map.json"
        record_event "recovery_api_observed" "$(mysql_fixture_state 2>/dev/null || printf UNKNOWN)" "${current_status}"
        return 0
      fi
    fi
    sleep 1
  done
  echo "TTL 만료 후 좌석 API가 LOCKED로 전환되지 않았습니다." >&2
  return 1
}

stop_k6() {
  if [[ -n "${K6_PID}" ]] && kill -0 "${K6_PID}" 2>/dev/null; then
    kill "${K6_PID}" 2>/dev/null || true
    wait "${K6_PID}" 2>/dev/null || true
  fi
  K6_PID=""
}

cleanup_on_exit() {
  if (( RESTORE_STARTED == 1 )); then
    return 0
  fi
  RESTORE_STARTED=1
  stop_k6
  restore_redis || true
  cleanup_fixture || true
  clear_seat_map_cache || true
  restore_app || true
}
trap cleanup_on_exit EXIT

write_manifest() {
  {
    printf 'run_id=%s\n' "${RUN_ID}"
    printf 'performance_time_id=%s\n' "${PT_ID}"
    printf 'seat_id=%s\n' "${SEAT_ID}"
    printf 'member_id=%s\n' "${MEMBER_ID}"
    printf 'test_ttl=%s\n' "${TEST_TTL}"
    printf 'redis_timeout=%s\n' "${REDIS_TIMEOUT}"
    printf 'redis_pause_ms=%s\n' "${REDIS_PAUSE_MS}"
    printf 'commit_timeout_seconds=%s\n' "${COMMIT_TIMEOUT_SECONDS}"
    printf 'recovery_timeout_seconds=%s\n' "${RECOVERY_TIMEOUT_SECONDS}"
    printf 'application_code_changed=false\n'
    printf 'k6_code_changed=true\n'
    printf 'external_orchestration=true\n'
  } > "${RUN_DIR}/run-manifest.txt"
}

run_test() {
  local warmup_file warmup_snapshot_file snapshot_status api_file api_status pause_result
  local k6_exit=0 transport_failures

  write_manifest
  restore_redis
  record_event "test_started" "$(mysql_fixture_state 2>/dev/null || printf UNKNOWN)"
  cleanup_fixture
  clear_seat_map_cache

  if [[ "$(seat_state)" != AVAILABLE/0 ]]; then
    echo "테스트 fixture 초기 상태가 AVAILABLE/0이 아닙니다: $(seat_state)" >&2
    return 1
  fi
  if [[ "$(mysql_root_query "SELECT COUNT(*) FROM Member WHERE id=${MEMBER_ID};" | tr -d '\r' | tail -n 1)" != 1 ]]; then
    echo "테스트 회원이 없습니다: ${MEMBER_ID}" >&2
    return 1
  fi

  start_test_app true
  wait_for_app

  clear_seat_map_cache
  warmup_file="${RUN_DIR}/warmup-seat-map.json"
  warmup_snapshot_file="${RUN_DIR}/warmup-snapshot.json"
  curl -fsS --connect-timeout 2 --max-time 20 \
    "${BASE_URL}/api/seats/${PT_ID}" > "${warmup_file}"
  jq -e '.success == true and (.data | type == "array")' "${warmup_file}" >/dev/null
  redis_cli --raw GET "${SNAPSHOT_KEY}" > "${warmup_snapshot_file}"
  snapshot_status="$(snapshot_seat_status "${warmup_snapshot_file}")"
  if [[ "${snapshot_status}" != AVAILABLE ]]; then
    echo "예열 snapshot 대상 좌석이 AVAILABLE이 아닙니다: ${snapshot_status}" >&2
    return 1
  fi
  record_event "snapshot_warmed" "$(mysql_fixture_state)" "$(api_seat_status "${warmup_file}")"

  redis_cli CLIENT UNPAUSE >/dev/null
  pause_result="$(redis_cli CLIENT PAUSE "${REDIS_PAUSE_MS}" ALL)"
  if [[ "${pause_result}" != OK ]]; then
    echo "Redis CLIENT PAUSE 실행에 실패했습니다." >&2
    return 1
  fi
  record_event "redis_paused" "$(mysql_fixture_state)" "UNKNOWN" "PAUSED" "PAUSED"

  BASE_URL="${BASE_URL}" \
  PT_ID="${PT_ID}" \
  SEAT_ID="${SEAT_ID}" \
  MEMBER_ID="${MEMBER_ID}" \
  BENCHMARK_MEMBER_ID_BASE="${BENCHMARK_MEMBER_ID_BASE}" \
  JWT_SECRET="${JWT_SECRET}" \
  IDEMPOTENCY_KEY="${IDEMPOTENCY_KEY}" \
  REQUEST_TIMEOUT="${REQUEST_TIMEOUT}" \
  MAX_DURATION="${K6_MAX_DURATION}" \
  "${K6_BIN}" run \
    -e "BASE_URL=${BASE_URL}" \
    -e "PT_ID=${PT_ID}" \
    -e "SEAT_ID=${SEAT_ID}" \
    -e "MEMBER_ID=${MEMBER_ID}" \
    -e "BENCHMARK_MEMBER_ID_BASE=${BENCHMARK_MEMBER_ID_BASE}" \
    -e "JWT_SECRET=${JWT_SECRET}" \
    -e "IDEMPOTENCY_KEY=${IDEMPOTENCY_KEY}" \
    -e "REQUEST_TIMEOUT=${REQUEST_TIMEOUT}" \
    -e "MAX_DURATION=${K6_MAX_DURATION}" \
    --summary-export "${K6_SUMMARY}" "${K6_SCRIPT}" \
    > "${K6_LOG}" 2>&1 &
  K6_PID=$!
  record_event "k6_started" "$(mysql_fixture_state)" "UNKNOWN" "PAUSED" "PAUSED"

  wait_for_mysql_locked
  docker compose kill -s KILL app >/dev/null
  record_event "application_killed" "$(mysql_fixture_state)" "UNKNOWN" "PAUSED" "PAUSED"

  if wait "${K6_PID}"; then
    k6_exit=0
  else
    k6_exit=$?
  fi
  K6_PID=""
  printf 'k6_exit=%s\n' "${k6_exit}" > "${RUN_DIR}/k6-status.txt"
  if [[ ! -f "${K6_SUMMARY}" ]]; then
    echo "k6 summary가 생성되지 않았습니다." >&2
    return 1
  fi
  transport_failures="$(jq -r '.metrics.crash_write_transport_failure.count // 0' "${K6_SUMMARY}")"
  printf 'crash_write_transport_failure=%s\n' "${transport_failures}" >> "${RUN_DIR}/k6-status.txt"
  record_event "k6_completed" "$(mysql_fixture_state)" "UNKNOWN" "PAUSED" "PAUSED"
  if (( k6_exit != 0 || transport_failures < 1 )); then
    echo "프로세스 종료에 따른 k6 전송 실패를 확인하지 못했습니다. k6_exit=${k6_exit}, transport_failures=${transport_failures}" >&2
    return 1
  fi

  restore_redis
  record_event "redis_unpaused"
  start_test_app false
  wait_for_app
  record_event "application_ready_after_restart"

  api_file="${RUN_DIR}/post-restart-seat-map.json"
  curl -fsS --connect-timeout 2 --max-time 20 \
    "${BASE_URL}/api/seats/${PT_ID}" > "${api_file}"
  api_status="$(api_seat_status "${api_file}")"
  record_event "post_restart_api_observed" "$(mysql_fixture_state)" "${api_status}"
  if [[ "${api_status}" != AVAILABLE ]]; then
    echo "재기동 직후 오래된 AVAILABLE 응답을 확인하지 못했습니다: ${api_status}" >&2
    return 1
  fi
  STALE_OBSERVED_EPOCH="$(epoch_seconds)"
  cp "${api_file}" "${RUN_DIR}/stale-seat-map.json"
  record_event "stale_api_observed" "$(mysql_fixture_state)" "${api_status}"

  wait_for_recovery
  {
    printf 'test_result=passed\n'
    printf 'mysql_locked_commit_epoch=%s\n' "${COMMIT_OBSERVED_EPOCH}"
    printf 'stale_api_observed_epoch=%s\n' "${STALE_OBSERVED_EPOCH}"
    printf 'recovery_api_observed_epoch=%s\n' "${RECOVERY_OBSERVED_EPOCH}"
    printf 'stale_duration_seconds=%s\n' "$(( RECOVERY_OBSERVED_EPOCH - STALE_OBSERVED_EPOCH ))"
    printf 'mysql_final_state=%s\n' "$(mysql_fixture_state)"
    printf 'redis_final_version=%s\n' "$(redis_version)"
    printf 'redis_final_ttl_ms=%s\n' "$(redis_ttl_ms)"
  } > "${RUN_DIR}/result.txt"
  TEST_RESULT="passed"
}

if run_test; then
  echo "프로세스 종료 후 캐시 stale 재현 시험 완료: ${RUN_DIR}"
  exit 0
else
  TEST_RESULT="failed"
  printf 'test_result=failed\n' > "${RUN_DIR}/result.txt"
  echo "프로세스 종료 후 캐시 stale 재현 시험 실패: ${RUN_DIR}" >&2
  exit 1
fi
