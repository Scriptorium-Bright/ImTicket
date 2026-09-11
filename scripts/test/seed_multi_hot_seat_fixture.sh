#!/usr/bin/env bash
set -euo pipefail

# SEAT_COUNT개의 사용 가능한 좌석을 만들고
# wallet_address performance_time_id seat_id... 형식으로 출력한다.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/load_env_defaults.sh"
load_imticket_env "${ROOT_DIR}/.env"

SEAT_COUNT="${SEAT_COUNT:-30}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-10047}"
MYSQL_USER="${MYSQL_USER:-capstone}"
MYSQL_DATABASE="${MYSQL_DATABASE:-capstone}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-${MYSQL_LOCK_TEST_PASSWORD:-}}"

if [[ ! "${SEAT_COUNT}" =~ ^[1-9][0-9]*$ ]]; then
  echo "SEAT_COUNT는 양의 정수여야 합니다." >&2
  exit 1
fi
if (( SEAT_COUNT > 1000 )); then
  echo "SEAT_COUNT는 1000 이하만 허용합니다." >&2
  exit 1
fi
if [[ -z "${MYSQL_PASSWORD}" ]]; then
  echo "MYSQL_PASSWORD 또는 MYSQL_LOCK_TEST_PASSWORD를 설정해야 합니다." >&2
  exit 1
fi

MYSQL_PWD="${MYSQL_PASSWORD}" mysql \
  --host="${MYSQL_HOST}" \
  --port="${MYSQL_PORT}" \
  --user="${MYSQL_USER}" \
  --protocol=tcp \
  --batch \
  --init-command="SET @seat_count = ${SEAT_COUNT}" \
  "${MYSQL_DATABASE}" < "${SCRIPT_DIR}/seed_multi_hot_seat_fixture.sql"
