#!/usr/bin/env bash
set -euo pipefail

# 실제 MySQL에서 row lock wait, lock wait timeout, 반대 순서 lock의 deadlock(1213)을 재현한다.
# 애플리케이션 endpoint 부하 시험과 달리 DB lock의 실패 모드 자체를 분리해서 검증한다.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MYSQL_LOCK_TEST_URL="${MYSQL_LOCK_TEST_URL:-jdbc:mysql://127.0.0.1:10047/capstone?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul}"
MYSQL_LOCK_TEST_USERNAME="${MYSQL_LOCK_TEST_USERNAME:-capstone}"
MYSQL_LOCK_TEST_PASSWORD="${MYSQL_LOCK_TEST_PASSWORD:-${MYSQL_PASSWORD:-}}"

if [[ -z "${MYSQL_LOCK_TEST_PASSWORD}" ]]; then
  echo "MYSQL_LOCK_TEST_PASSWORD 또는 MYSQL_PASSWORD를 설정해야 합니다." >&2
  exit 1
fi

set +e
MYSQL_LOCK_TEST_ENABLED=true \
MYSQL_LOCK_TEST_URL="${MYSQL_LOCK_TEST_URL}" \
MYSQL_LOCK_TEST_USERNAME="${MYSQL_LOCK_TEST_USERNAME}" \
MYSQL_LOCK_TEST_PASSWORD="${MYSQL_LOCK_TEST_PASSWORD}" \
  "${ROOT_DIR}/gradlew" test \
    --tests org.example.ticket.reservation.repository.MySqlPessimisticLockFailureTest
test_status=$?
set -e

report_file="${ROOT_DIR}/build/test-results/test/TEST-org.example.ticket.reservation.repository.MySqlPessimisticLockFailureTest.xml"
if [[ -f "${report_file}" ]]; then
  printf '\n== MySQL lock semantics evidence ==\n'
  if ! grep -F '[LOCK-EVIDENCE]' "${report_file}" \
    | sed -E 's/^.*(\[LOCK-EVIDENCE\]) /\1 /'; then
    printf '[LOCK-EVIDENCE] test report에 표준 출력이 없습니다. report=%s\n' "${report_file}" >&2
  fi
fi

exit "${test_status}"
