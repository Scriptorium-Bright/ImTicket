#!/usr/bin/env bash
set -euo pipefail

SEAT_ID="${SEAT_ID:-}"
PT_ID="${PT_ID:-}"
HOLD_SECONDS="${HOLD_SECONDS:-6}"
MYSQL_HOST="${MYSQL_HOST:-140.245.76.87}"
MYSQL_PORT="${MYSQL_PORT:-10047}"
MYSQL_USER="${MYSQL_USER:-capstone}"
MYSQL_DATABASE="${MYSQL_DATABASE:-capstone}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-${MYSQL_LOCK_TEST_PASSWORD:-}}"

if [[ ! "${SEAT_ID}" =~ ^[0-9]+$ ]]; then
  echo "SEAT_ID는 양의 정수여야 합니다." >&2
  exit 1
fi
if [[ -n "${PT_ID}" ]] && [[ ! "${PT_ID}" =~ ^[0-9]+$ ]]; then
  echo "PT_ID는 비어 있거나 양의 정수여야 합니다." >&2
  exit 1
fi
if [[ ! "${HOLD_SECONDS}" =~ ^[0-9]+$ ]]; then
  echo "HOLD_SECONDS는 0 이상의 정수여야 합니다." >&2
  exit 1
fi
if [[ -z "${MYSQL_PASSWORD}" ]]; then
  echo "MYSQL_PASSWORD 또는 MYSQL_LOCK_TEST_PASSWORD를 설정해야 합니다." >&2
  exit 1
fi

LOCK_NAME="imticket:pessimistic-lock-holder:${SEAT_ID}"

if [[ -n "${PT_ID}" ]]; then
  seat_exists="$(MYSQL_PWD="${MYSQL_PASSWORD}" mysql \
    --host="${MYSQL_HOST}" \
    --port="${MYSQL_PORT}" \
    --user="${MYSQL_USER}" \
    --protocol=tcp \
    --batch \
    --skip-column-names \
    "${MYSQL_DATABASE}" \
    --execute="SELECT COUNT(*) FROM Seat WHERE id = ${SEAT_ID} AND performance_time_id = ${PT_ID};")"
else
  seat_exists="$(MYSQL_PWD="${MYSQL_PASSWORD}" mysql \
    --host="${MYSQL_HOST}" \
    --port="${MYSQL_PORT}" \
    --user="${MYSQL_USER}" \
    --protocol=tcp \
    --batch \
    --skip-column-names \
    "${MYSQL_DATABASE}" \
    --execute="SELECT COUNT(*) FROM Seat WHERE id = ${SEAT_ID};")"
fi

if [[ "${seat_exists}" != "1" ]]; then
  echo "강제 lock 대상 좌석을 정확히 하나 찾지 못했습니다. seatId=${SEAT_ID}, performanceTimeId=${PT_ID:-unspecified}" >&2
  exit 1
fi

MYSQL_PWD="${MYSQL_PASSWORD}" mysql \
  --host="${MYSQL_HOST}" \
  --port="${MYSQL_PORT}" \
  --user="${MYSQL_USER}" \
  --protocol=tcp \
  --batch \
  "${MYSQL_DATABASE}" \
  --execute="
    START TRANSACTION;
    SELECT id FROM Seat WHERE id = ${SEAT_ID} FOR UPDATE;
    SELECT GET_LOCK('${LOCK_NAME}', 0);
    DO SLEEP(${HOLD_SECONDS});
    SELECT RELEASE_LOCK('${LOCK_NAME}');
    COMMIT;
  "
