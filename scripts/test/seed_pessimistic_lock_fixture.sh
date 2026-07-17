#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MYSQL_HOST="${MYSQL_HOST:-140.245.76.87}"
MYSQL_PORT="${MYSQL_PORT:-10047}"
MYSQL_USER="${MYSQL_USER:-capstone}"
MYSQL_DATABASE="${MYSQL_DATABASE:-capstone}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-${MYSQL_LOCK_TEST_PASSWORD:-}}"

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
  "${MYSQL_DATABASE}" < "${SCRIPT_DIR}/seed_pessimistic_lock_fixture.sql"
