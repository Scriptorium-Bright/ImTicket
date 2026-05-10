#!/usr/bin/env bash
set -euo pipefail

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-10047}"
MYSQL_USER="${MYSQL_USER:-capstone}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-${MYSQL_ROOT_PASSWORD:-}}"
MYSQL_DATABASE="${MYSQL_DATABASE:-capstone}"

if [[ -z "${MYSQL_PASSWORD}" ]]; then
  echo "Set MYSQL_PASSWORD or MYSQL_ROOT_PASSWORD." >&2
  exit 1
fi

mysql_exec() {
  MYSQL_PWD="${MYSQL_PASSWORD}" mysql \
    -h "${MYSQL_HOST}" \
    -P "${MYSQL_PORT}" \
    -u "${MYSQL_USER}" \
    --protocol=tcp \
    "$@"
}

echo "== MySQL processlist =="
mysql_exec -e "SHOW FULL PROCESSLIST;"

echo
echo "== InnoDB status =="
mysql_exec -e "SHOW ENGINE INNODB STATUS\\G"

echo
echo "== performance_schema.data_locks =="
mysql_exec "${MYSQL_DATABASE}" -e "
SELECT
  ENGINE_TRANSACTION_ID,
  OBJECT_SCHEMA,
  OBJECT_NAME,
  INDEX_NAME,
  LOCK_TYPE,
  LOCK_MODE,
  LOCK_STATUS,
  LOCK_DATA
FROM performance_schema.data_locks
WHERE OBJECT_SCHEMA = DATABASE()
ORDER BY ENGINE_TRANSACTION_ID, OBJECT_NAME, INDEX_NAME;
" || true

echo
echo "== performance_schema.data_lock_waits =="
mysql_exec "${MYSQL_DATABASE}" -e "
SELECT
  REQUESTING_ENGINE_TRANSACTION_ID,
  BLOCKING_ENGINE_TRANSACTION_ID,
  REQUESTING_THREAD_ID,
  BLOCKING_THREAD_ID
FROM performance_schema.data_lock_waits;
" || true
