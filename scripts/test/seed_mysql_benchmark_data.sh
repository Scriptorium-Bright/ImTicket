#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SQL_FILE="${SCRIPT_DIR}/seed_mysql_benchmark_data.sql"

if [[ -f "${REPO_ROOT}/.env" ]]; then
  while IFS='=' read -r key value; do
    case "${key}" in
      MYSQL_DATABASE | MYSQL_USER | MYSQL_PASSWORD | MYSQL_ROOT_PASSWORD)
        value="${value%$'\r'}"
        value="${value%\"}"
        value="${value#\"}"
        value="${value%\'}"
        value="${value#\'}"
        if [[ -z "${!key:-}" ]]; then
          export "${key}=${value}"
        fi
        ;;
    esac
  done < "${REPO_ROOT}/.env"
fi

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-10047}"
MYSQL_DATABASE="${MYSQL_DATABASE:-capstone}"
MYSQL_USER="${MYSQL_USER:-capstone}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-cider123}"

BENCHMARK_BASE_ID="${BENCHMARK_BASE_ID:-900000000}"
MEMBER_COUNT="${MEMBER_COUNT:-100000}"
SEAT_COUNT="${SEAT_COUNT:-100000}"
RESERVATION_COUNT="${RESERVATION_COUNT:-300000}"
RESERVED_SEAT_PER_RESERVATION="${RESERVED_SEAT_PER_RESERVATION:-2}"
PERFORMANCE_TIME_COUNT="${PERFORMANCE_TIME_COUNT:-10}"
EXPIRED_RATIO="${EXPIRED_RATIO:-50}"
DRY_RUN=0
MYSQL_USE_DOCKER="${MYSQL_USE_DOCKER:-auto}"

if [[ "${1:-}" == "--dry-run" ]]; then
  DRY_RUN=1
fi

require_uint() {
  local name="$1"
  local value="$2"

  if ! [[ "${value}" =~ ^[0-9]+$ ]]; then
    echo "Invalid ${name}: ${value}. Expected unsigned integer." >&2
    exit 1
  fi
}

require_uint "BENCHMARK_BASE_ID" "${BENCHMARK_BASE_ID}"
require_uint "MEMBER_COUNT" "${MEMBER_COUNT}"
require_uint "SEAT_COUNT" "${SEAT_COUNT}"
require_uint "RESERVATION_COUNT" "${RESERVATION_COUNT}"
require_uint "RESERVED_SEAT_PER_RESERVATION" "${RESERVED_SEAT_PER_RESERVATION}"
require_uint "PERFORMANCE_TIME_COUNT" "${PERFORMANCE_TIME_COUNT}"
require_uint "EXPIRED_RATIO" "${EXPIRED_RATIO}"

if (( EXPIRED_RATIO > 100 )); then
  echo "Invalid EXPIRED_RATIO: ${EXPIRED_RATIO}. Expected 0..100." >&2
  exit 1
fi

RESERVED_SEAT_COUNT=$((RESERVATION_COUNT * RESERVED_SEAT_PER_RESERVATION))
MAX_COUNT="${MEMBER_COUNT}"
for value in "${SEAT_COUNT}" "${RESERVATION_COUNT}" "${RESERVED_SEAT_COUNT}"; do
  if (( value > MAX_COUNT )); then
    MAX_COUNT="${value}"
  fi
done

if (( MAX_COUNT > 1000000 )); then
  echo "This seed script currently supports up to 1,000,000 generated rows per table set." >&2
  echo "Requested max generated count: ${MAX_COUNT}" >&2
  exit 1
fi

TMP_SQL="$(mktemp)"
trap 'rm -f "${TMP_SQL}"' EXIT

{
  echo "SET @base_id := ${BENCHMARK_BASE_ID};"
  echo "SET @member_count := ${MEMBER_COUNT};"
  echo "SET @seat_count := ${SEAT_COUNT};"
  echo "SET @reservation_count := ${RESERVATION_COUNT};"
  echo "SET @reserved_seat_per_reservation := ${RESERVED_SEAT_PER_RESERVATION};"
  echo "SET @performance_time_count := ${PERFORMANCE_TIME_COUNT};"
  echo "SET @expired_ratio := ${EXPIRED_RATIO};"
  echo
  cat "${SQL_FILE}"
} > "${TMP_SQL}"

echo "Benchmark seed configuration"
echo "- host: ${MYSQL_HOST}:${MYSQL_PORT}"
echo "- database: ${MYSQL_DATABASE}"
echo "- user: ${MYSQL_USER}"
echo "- base id: ${BENCHMARK_BASE_ID}"
echo "- members: ${MEMBER_COUNT}"
echo "- seats: ${SEAT_COUNT}"
echo "- reservations: ${RESERVATION_COUNT}"
echo "- reserved seats: ${RESERVED_SEAT_COUNT}"
echo "- expired ratio: ${EXPIRED_RATIO}%"

if (( DRY_RUN == 1 )); then
  echo
  echo "Dry run: generated SQL preview"
  sed -n '1,120p' "${TMP_SQL}"
  exit 0
fi

run_with_local_mysql() {
  MYSQL_PWD="${MYSQL_PASSWORD}" mysql \
    --host="${MYSQL_HOST}" \
    --port="${MYSQL_PORT}" \
    --user="${MYSQL_USER}" \
    --database="${MYSQL_DATABASE}" \
    --default-character-set=utf8mb4 \
    < "${TMP_SQL}"
}

run_with_docker_compose_mysql() {
  docker compose exec -T mysql mysql \
    --user="${MYSQL_USER}" \
    --password="${MYSQL_PASSWORD}" \
    --database="${MYSQL_DATABASE}" \
    --default-character-set=utf8mb4 \
    < "${TMP_SQL}"
}

docker_mysql_available() {
  command -v docker >/dev/null 2>&1 && [[ -n "$(docker compose ps -q mysql 2>/dev/null)" ]]
}

case "${MYSQL_USE_DOCKER}" in
  1 | true | yes)
    run_with_docker_compose_mysql
    ;;
  0 | false | no)
    run_with_local_mysql
    ;;
  auto)
    if docker_mysql_available; then
      run_with_docker_compose_mysql
    elif command -v mysql >/dev/null 2>&1; then
      run_with_local_mysql
    else
      echo "mysql client is not installed and docker compose mysql service is not available." >&2
      echo "Install mysql client or start the docker mysql service first." >&2
      exit 1
    fi
    ;;
  *)
    echo "Invalid MYSQL_USE_DOCKER: ${MYSQL_USE_DOCKER}. Use auto, true, or false." >&2
    exit 1
    ;;
esac

echo "Benchmark seed completed."
