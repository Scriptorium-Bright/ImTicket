#!/usr/bin/env bash
set -euo pipefail

# admission rate 후보마다 동일한 좌석·회원 상태를 복원한다.
# 대상 회차와 테스트 회원 범위만 사용하며, 전체 DB/Redis flush는 수행하지 않는다.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/load_env_defaults.sh"
load_imticket_env "${ROOT_DIR}/.env"

PT_ID="${PT_ID:-900000001}"
MEMBER_ID_START="${MEMBER_ID_START:-900000001}"
MEMBER_COUNT="${MEMBER_COUNT:-2000}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-10047}"
MYSQL_USER="${MYSQL_USER:-capstone}"
MYSQL_DATABASE="${MYSQL_DATABASE:-capstone}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-${MYSQL_LOCK_TEST_PASSWORD:-}}"
MYSQL_USE_DOCKER="${MYSQL_USE_DOCKER:-auto}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-16380}"
REDIS_USE_DOCKER="${REDIS_USE_DOCKER:-auto}"

require_positive_integer() {
  local name="$1"
  local value="$2"
  if ! [[ "${value}" =~ ^[1-9][0-9]*$ ]]; then
    echo "${name}은 양의 정수여야 합니다: ${value}" >&2
    exit 1
  fi
}

require_positive_integer "PT_ID" "${PT_ID}"
require_positive_integer "MEMBER_ID_START" "${MEMBER_ID_START}"
require_positive_integer "MEMBER_COUNT" "${MEMBER_COUNT}"

if [[ -z "${MYSQL_PASSWORD}" ]]; then
  echo "MYSQL_PASSWORD 또는 MYSQL_LOCK_TEST_PASSWORD가 필요합니다." >&2
  exit 1
fi

MYSQL_SQL_FILE="$(mktemp)"
trap 'rm -f "${MYSQL_SQL_FILE}"' EXIT

cat > "${MYSQL_SQL_FILE}" <<SQL
START TRANSACTION;

DROP TEMPORARY TABLE IF EXISTS tmp_waiting_room_reservation_ids;
CREATE TEMPORARY TABLE tmp_waiting_room_reservation_ids (
    reservation_id BIGINT PRIMARY KEY
);

INSERT INTO tmp_waiting_room_reservation_ids (reservation_id)
SELECT DISTINCT rs.reservation_id
FROM ReservedSeat rs
JOIN Seat s ON s.id = rs.seat_id
WHERE s.performance_time_id = ${PT_ID};

DELETE el
FROM EntryLog el
JOIN tmp_waiting_room_reservation_ids ids ON ids.reservation_id = el.reservation_id;

DELETE po
FROM payment_order po
JOIN tmp_waiting_room_reservation_ids ids ON ids.reservation_id = po.reservation_id;

DELETE ri
FROM reservation_idempotency ri
LEFT JOIN tmp_waiting_room_reservation_ids ids ON ids.reservation_id = ri.reservation_id
WHERE ids.reservation_id IS NOT NULL
   OR ri.member_id BETWEEN ${MEMBER_ID_START} AND ${MEMBER_ID_START} + ${MEMBER_COUNT} - 1;

DELETE rs
FROM ReservedSeat rs
JOIN tmp_waiting_room_reservation_ids ids ON ids.reservation_id = rs.reservation_id;

DELETE r
FROM Reservation r
JOIN tmp_waiting_room_reservation_ids ids ON ids.reservation_id = r.id;

UPDATE Seat
SET seat_status = 'AVAILABLE',
    is_reservation = 0,
    version = 0
WHERE performance_time_id = ${PT_ID};

COMMIT;

SELECT CONCAT(
    'fixture_available=', COALESCE(SUM(seat_status = 'AVAILABLE'), 0),
    ',fixture_locked=', COALESCE(SUM(seat_status = 'LOCKED'), 0)
)
FROM Seat
WHERE performance_time_id = ${PT_ID};
SQL

run_mysql_local() {
  MYSQL_PWD="${MYSQL_PASSWORD}" mysql \
    --host="${MYSQL_HOST}" \
    --port="${MYSQL_PORT}" \
    --user="${MYSQL_USER}" \
    --protocol=tcp \
    --database="${MYSQL_DATABASE}" \
    --batch \
    --skip-column-names \
    < "${MYSQL_SQL_FILE}"
}

run_mysql_docker() {
  docker compose exec -T \
    -e "MYSQL_PWD=${MYSQL_PASSWORD}" \
    mysql mysql \
    --user="${MYSQL_USER}" \
    --database="${MYSQL_DATABASE}" \
    --batch \
    --skip-column-names \
    < "${MYSQL_SQL_FILE}"
}

case "${MYSQL_USE_DOCKER}" in
  true|1|yes)
    run_mysql_docker
    ;;
  false|0|no)
    run_mysql_local
    ;;
  auto)
    if command -v docker >/dev/null 2>&1 && [[ -n "$(docker compose ps -q mysql 2>/dev/null)" ]]; then
      run_mysql_docker
    else
      run_mysql_local
    fi
    ;;
  *)
    echo "MYSQL_USE_DOCKER는 auto, true, false 중 하나여야 합니다." >&2
    exit 1
    ;;
esac

REDIS_PATTERN="reservation:waiting-room:{${PT_ID}}:*"

redis_scan_local() {
  redis-cli -h "${REDIS_HOST}" -p "${REDIS_PORT}" --scan --pattern "${REDIS_PATTERN}"
}

redis_delete_local() {
  redis-cli -h "${REDIS_HOST}" -p "${REDIS_PORT}" "$@" < /dev/null
}

redis_scan_docker() {
  docker compose exec -T redis redis-cli --scan --pattern "${REDIS_PATTERN}"
}

redis_delete_docker() {
  docker compose exec -T redis redis-cli "$@" < /dev/null
}

redis_delete_key_list_local() {
  local key
  local -a keys=()
  while IFS= read -r key; do
    [[ -z "${key}" ]] && continue
    keys+=("${key}")
    if (( ${#keys[@]} >= 100 )); then
      redis_delete_local DEL "${keys[@]}" >/dev/null
      keys=()
    fi
  done <<< "${1}"
  if (( ${#keys[@]} > 0 )); then
    redis_delete_local DEL "${keys[@]}" >/dev/null
  fi
}

redis_delete_key_list_docker() {
  local key
  local -a keys=()
  while IFS= read -r key; do
    [[ -z "${key}" ]] && continue
    keys+=("${key}")
    if (( ${#keys[@]} >= 100 )); then
      redis_delete_docker DEL "${keys[@]}" >/dev/null
      keys=()
    fi
  done <<< "${1}"
  if (( ${#keys[@]} > 0 )); then
    redis_delete_docker DEL "${keys[@]}" >/dev/null
  fi
}

case "${REDIS_USE_DOCKER}" in
  true|1|yes)
    REDIS_KEYS="$(redis_scan_docker)"
    if [[ -n "${REDIS_KEYS}" ]]; then
      redis_delete_key_list_docker "${REDIS_KEYS}"
    fi
    ;;
  false|0|no)
    REDIS_KEYS="$(redis_scan_local)"
    if [[ -n "${REDIS_KEYS}" ]]; then
      redis_delete_key_list_local "${REDIS_KEYS}"
    fi
    ;;
  auto)
    if command -v redis-cli >/dev/null 2>&1 && redis-cli -h "${REDIS_HOST}" -p "${REDIS_PORT}" PING >/dev/null 2>&1; then
      REDIS_KEYS="$(redis_scan_local)"
      if [[ -n "${REDIS_KEYS}" ]]; then
        redis_delete_key_list_local "${REDIS_KEYS}"
      fi
    else
      REDIS_KEYS="$(redis_scan_docker)"
      if [[ -n "${REDIS_KEYS}" ]]; then
        redis_delete_key_list_docker "${REDIS_KEYS}"
      fi
    fi
    ;;
  *)
    echo "REDIS_USE_DOCKER는 auto, true, false 중 하나여야 합니다." >&2
    exit 1
    ;;
esac

if command -v redis-cli >/dev/null 2>&1 && redis-cli -h "${REDIS_HOST}" -p "${REDIS_PORT}" PING >/dev/null 2>&1; then
  REMAINING_KEYS="$(redis_scan_local)"
  redis_exec() {
    redis-cli -h "${REDIS_HOST}" -p "${REDIS_PORT}" "$@"
  }
else
  REMAINING_KEYS="$(redis_scan_docker)"
  redis_exec() {
    docker compose exec -T redis redis-cli "$@"
  }
fi

REMAINING_DATA_KEYS=""
if [[ -n "${REMAINING_KEYS}" ]]; then
  while IFS= read -r key; do
    [[ -z "${key}" ]] && continue
    case "${key}" in
      "reservation:waiting-room:{${PT_ID}}:active"|"reservation:waiting-room:{${PT_ID}}:waiting"|"reservation:waiting-room:{${PT_ID}}:deadline")
        [[ "$(redis_exec ZCARD "${key}")" == "0" ]] && continue
        ;;
      "reservation:waiting-room:{${PT_ID}}:admission")
        [[ "$(redis_exec HGET "${key}" count)" == "0" ]] && continue
        ;;
      "reservation:waiting-room:{${PT_ID}}:join-handoff")
        stream_length="$(redis_exec XLEN "${key}")"
        pending_count="$(redis_exec XPENDING "${key}" waiting-room-join-workers | awk 'NR == 1 { print $1 }')"
        [[ "${stream_length}" == "1" && "${pending_count}" == "0" ]] && continue
        ;;
    esac
    REMAINING_DATA_KEYS+="${key}"$'\n'
  done <<< "${REMAINING_KEYS}"
fi
if [[ -n "${REMAINING_DATA_KEYS}" ]]; then
  echo "Redis namespace reset이 완료되지 않았습니다." >&2
  printf '%s\n' "${REMAINING_DATA_KEYS}" | sed -n '1,20p' >&2
  exit 1
fi

echo "waiting_room_fixture_reset=complete"
echo "performance_time_id=${PT_ID}"
echo "redis_pattern=${REDIS_PATTERN}"
