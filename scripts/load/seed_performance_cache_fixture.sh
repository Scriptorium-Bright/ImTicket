#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-10047}"
MYSQL_USER="${MYSQL_USER:-capstone}"
MYSQL_DATABASE="${MYSQL_DATABASE:-capstone}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-${MYSQL_CACHE_TEST_PASSWORD:-}}"

if [[ -z "${MYSQL_PASSWORD}" ]]; then
  echo "MYSQL_PASSWORD 또는 MYSQL_CACHE_TEST_PASSWORD를 설정해야 합니다." >&2
  exit 1
fi

result="$(MYSQL_PWD="${MYSQL_PASSWORD}" mysql \
  --host="${MYSQL_HOST}" \
  --port="${MYSQL_PORT}" \
  --user="${MYSQL_USER}" \
  --protocol=tcp \
  --batch \
  --skip-column-names \
  "${MYSQL_DATABASE}" < "${SCRIPT_DIR}/seed_performance_cache_fixture.sql")"

IFS=$'\t' read -r fixture_count performance_ids <<< "${result}"
if [[ "${fixture_count}" != "100" ]] || [[ -z "${performance_ids}" ]]; then
  echo "100개 공연 fixture 생성 결과를 확인하지 못했습니다: ${result}" >&2
  exit 1
fi

echo "fixture_count=${fixture_count}"
echo "PERFORMANCE_IDS=${performance_ids}"
