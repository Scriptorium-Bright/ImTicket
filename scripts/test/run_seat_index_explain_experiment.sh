#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_FILE="${SCRIPT_DIR}/seat_index_explain_experiment.sql"

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-rootpass}"
MYSQL_DATABASE="${MYSQL_DATABASE:-imticket_index_test}"
SEAT_COUNT="${SEAT_COUNT:-60000}"
PERFORMANCE_TIME_ID="${PERFORMANCE_TIME_ID:-900000001}"
OUTPUT_DIR="${OUTPUT_DIR:-build/db-results/seat-index-explain}"

mkdir -p "${OUTPUT_DIR}"

for command in mysql python3; do
  if ! command -v "${command}" >/dev/null 2>&1; then
    echo "${command} is required." >&2
    exit 1
  fi
done

TMP_SQL="$(mktemp)"
trap 'rm -f "${TMP_SQL}"' EXIT

{
  echo "SET @seat_count := ${SEAT_COUNT};"
  echo "SET @performance_time_id := ${PERFORMANCE_TIME_ID};"
  cat "${SQL_FILE}"
} > "${TMP_SQL}"

echo "Seat index EXPLAIN experiment"
echo "- MySQL: ${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DATABASE}"
echo "- seats: ${SEAT_COUNT}"
echo "- performance_time_id: ${PERFORMANCE_TIME_ID}"

MYSQL_PWD="${MYSQL_PASSWORD}" mysql \
  --host="${MYSQL_HOST}" \
  --port="${MYSQL_PORT}" \
  --user="${MYSQL_USER}" \
  --database="${MYSQL_DATABASE}" \
  --default-character-set=utf8mb4 \
  --batch --raw \
  < "${TMP_SQL}" | tee "${OUTPUT_DIR}/raw.txt"

python3 "${SCRIPT_DIR}/summarize_seat_index_explain.py" \
  --input "${OUTPUT_DIR}/raw.txt" \
  --output "${OUTPUT_DIR}/RESULTS.md"

echo
echo "result=${OUTPUT_DIR}/RESULTS.md"
