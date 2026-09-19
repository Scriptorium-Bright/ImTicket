#!/usr/bin/env bash
set -euo pipefail

# Reproduces an ambiguous commit across MySQL GTID failover:
# 1) reservation commit succeeds on primary and reaches replica,
# 2) the client does not receive the response,
# 3) primary is failed and replica is promoted,
# 4) retrying the same Idempotency-Key must replay the same reservation.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

APP_IMAGE="${APP_IMAGE:-imticket-app:ambiguous-commit}"
MYSQL_IMAGE="${MYSQL_IMAGE:-mysql:8.0}"
REDIS_IMAGE="${REDIS_IMAGE:-redis:7.4-alpine}"
MYSQL_DATABASE="${MYSQL_DATABASE:-capstone}"
MYSQL_USER="${MYSQL_USER:-capstone}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-ambiguous-app-password}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-ambiguous-root-password}"
JWT_SECRET="${JWT_SECRET:-ambiguous-commit-jwt-secret-ambiguous-commit-jwt-secret}"
APP_PORT="${APP_PORT:-12080}"
MANAGEMENT_PORT="${MANAGEMENT_PORT:-12081}"
REDIS_PAUSE_MS="${REDIS_PAUSE_MS:-20000}"
ORIGINAL_REQUEST_TIMEOUT_SECONDS="${ORIGINAL_REQUEST_TIMEOUT_SECONDS:-6}"
COMMIT_WAIT_SECONDS="${COMMIT_WAIT_SECONDS:-15}"
REPLICA_WAIT_SECONDS="${REPLICA_WAIT_SECONDS:-15}"
RUN_ROOT="${RUN_ROOT:-${REPO_ROOT}/build/mysql-failover-results}"
RUN_ID="${RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
RUN_ID_LOWER="$(printf '%s' "${RUN_ID}" | tr '[:upper:]' '[:lower:]')"
RUN_DIR="${RUN_ROOT}/${RUN_ID}"

NETWORK="imticket-ambiguous-${RUN_ID_LOWER}"
PRIMARY="imticket-ambiguous-primary-${RUN_ID_LOWER}"
REPLICA="imticket-ambiguous-replica-${RUN_ID_LOWER}"
REDIS="imticket-ambiguous-redis-${RUN_ID_LOWER}"
APP="imticket-ambiguous-app-${RUN_ID_LOWER}"

FIXTURE_MEMBER_ID=990009901
FIXTURE_WALLET="0xambiguouscommit000000000000000000000001"
FIXTURE_VENUE_ID=990009901
FIXTURE_HALL_ID=990009901
FIXTURE_PERFORMANCE_ID=990009901
FIXTURE_PT_ID=990009901
FIXTURE_SEAT_ID=990009901
IDEMPOTENCY_KEY="a9b8c7d6-1234-4abc-8def-1234567890ab"

BASE_DUMP="${RUN_DIR}/baseline.sql"
ORIGINAL_BODY="${RUN_DIR}/original-response.json"
ORIGINAL_STATUS="${RUN_DIR}/original-http-status.txt"
ORIGINAL_ERR="${RUN_DIR}/original-curl.stderr"
RETRY_BODY="${RUN_DIR}/retry-response.json"
RESULT_FILE="${RUN_DIR}/result.env"
PRIMARY_LOG="${RUN_DIR}/primary.log"
REPLICA_LOG="${RUN_DIR}/replica.log"
APP_LOG="${RUN_DIR}/app.log"
REPLICA_STATUS_FILE="${RUN_DIR}/replica-status.txt"

CURL_PID=""
TEST_STATUS="FAILED"

mkdir -p "${RUN_DIR}"

cleanup() {
  set +e
  if [[ -n "${CURL_PID}" ]] && kill -0 "${CURL_PID}" >/dev/null 2>&1; then
    kill "${CURL_PID}" >/dev/null 2>&1 || true
    wait "${CURL_PID}" >/dev/null 2>&1 || true
  fi
  docker logs "${APP}" >"${APP_LOG}" 2>&1 || true
  docker logs "${PRIMARY}" >"${PRIMARY_LOG}" 2>&1 || true
  docker logs "${REPLICA}" >"${REPLICA_LOG}" 2>&1 || true
  docker rm -f "${APP}" "${PRIMARY}" "${REPLICA}" "${REDIS}" >/dev/null 2>&1 || true
  docker network rm "${NETWORK}" >/dev/null 2>&1 || true
  set -e
}
trap cleanup EXIT

for command in docker curl python3 awk grep sed tr date; do
  if ! command -v "${command}" >/dev/null 2>&1; then
    echo "Required command is missing: ${command}" >&2
    exit 1
  fi
done

if ! [[ "${REDIS_PAUSE_MS}" =~ ^[0-9]+$ ]] || (( REDIS_PAUSE_MS < 10000 )); then
  echo "REDIS_PAUSE_MS must be an integer >= 10000." >&2
  exit 1
fi
if ! [[ "${ORIGINAL_REQUEST_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "ORIGINAL_REQUEST_TIMEOUT_SECONDS must be a positive integer." >&2
  exit 1
fi
if ! docker image inspect "${APP_IMAGE}" >/dev/null 2>&1; then
  echo "Application image is missing: ${APP_IMAGE}" >&2
  exit 1
fi

mysql_at() {
  local container="$1"
  shift
  docker exec -e MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" "${container}"     mysql --user=root --protocol=tcp --batch --raw "$@"
}

primary_mysql() {
  mysql_at "${PRIMARY}" "$@"
}

replica_mysql() {
  mysql_at "${REPLICA}" "$@"
}

wait_for_mysql() {
  local container="$1"
  local ready=false
  for _ in $(seq 1 90); do
    if docker exec -e MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" "${container}"         mysqladmin --user=root --protocol=tcp --silent ping >/dev/null 2>&1; then
      ready=true
      break
    fi
    sleep 1
  done
  if [[ "${ready}" != true ]]; then
    echo "MySQL did not become ready: ${container}" >&2
    docker logs "${container}" >&2 || true
    return 1
  fi
}

start_mysql() {
  local container="$1"
  local server_id="$2"
  docker run -d --rm     --name "${container}"     --network "${NETWORK}"     -e "MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD}"     -e "MYSQL_DATABASE=${MYSQL_DATABASE}"     -e "MYSQL_USER=${MYSQL_USER}"     -e "MYSQL_PASSWORD=${MYSQL_PASSWORD}"     "${MYSQL_IMAGE}"     --character-set-server=utf8mb4     --collation-server=utf8mb4_unicode_ci     --server-id="${server_id}"     --log-bin=binlog     --relay-log=relay-bin     --binlog-format=ROW     --gtid-mode=ON     --enforce-gtid-consistency=ON     --log-replica-updates=ON     --binlog-expire-logs-seconds=3600 >/dev/null
  wait_for_mysql "${container}"
}

start_app() {
  local database_container="$1"
  local ddl_mode="$2"
  local force_create="$3"

  docker rm -f "${APP}" >/dev/null 2>&1 || true

  local generate_ddl=false
  local hbm2ddl="${ddl_mode}"
  if [[ "${force_create}" == true ]]; then
    generate_ddl=true
  fi

  docker run -d --rm     --name "${APP}"     --network "${NETWORK}"     -p "127.0.0.1:${APP_PORT}:10080"     -p "127.0.0.1:${MANAGEMENT_PORT}:10081"     -e "SPRING_DATASOURCE_URL=jdbc:mysql://${database_container}:3306/${MYSQL_DATABASE}?serverTimezone=Asia/Seoul&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=1500&socketTimeout=3000"     -e "SPRING_DATASOURCE_USERNAME=${MYSQL_USER}"     -e "SPRING_DATASOURCE_PASSWORD=${MYSQL_PASSWORD}"     -e "SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT=2000"     -e "SPRING_JPA_HIBERNATE_DDL_AUTO=${ddl_mode}"     -e "SPRING_JPA_GENERATE_DDL=${generate_ddl}"     -e "SPRING_JPA_PROPERTIES_HIBERNATE_HBM2DDL_AUTO=${hbm2ddl}"     -e "SPRING_JWT_SECRET=${JWT_SECRET}"     -e "SPRING_DATA_REDIS_HOST=${REDIS}"     -e "SPRING_DATA_REDIS_PORT=6379"     -e "SPRING_DATA_REDIS_TIMEOUT=30s"     -e "SPRING_DATA_REDIS_CONNECT_TIMEOUT=1s"     -e "MANAGEMENT_SERVER_PORT=10081"     -e "TICKET_APPLICATION_ROLE=failover-rehearsal"     -e "TICKET_SHEDLOCK_SCHEMA_INITIALIZATION_ENABLED=false"     -e "RESERVATION_WAITING_ROOM_ENABLED=false"     -e "RESERVATION_SEAT_MAP_CACHE_ENABLED=true"     -e "RESERVATION_SEAT_MAP_CACHE_ENABLED_PERFORMANCE_TIME_IDS=${FIXTURE_PT_ID}"     -e "RESERVATION_SEAT_MAP_CACHE_TTL=5m"     -e "LOCK_STRATEGY=pessimistic"     "${APP_IMAGE}" >/dev/null
}

wait_for_app() {
  local ready=false
  for _ in $(seq 1 180); do
    if curl -fsS --connect-timeout 2 --max-time 4       "http://127.0.0.1:${MANAGEMENT_PORT}/actuator/health" >/dev/null 2>&1; then
      ready=true
      break
    fi
    sleep 1
  done
  if [[ "${ready}" != true ]]; then
    echo "Application did not become ready." >&2
    docker logs "${APP}" >&2 || true
    return 1
  fi
}

replica_status_value() {
  local key="$1"
  replica_mysql -e "SHOW REPLICA STATUS\G" 2>/dev/null     | awk -F ': ' -v key="${key}" '$1 ~ key {print $2; exit}'     | tr -d '\r'
}

wait_for_replication_threads() {
  local ready=false
  for _ in $(seq 1 60); do
    local io sql
    io="$(replica_status_value 'Replica_IO_Running')"
    sql="$(replica_status_value 'Replica_SQL_Running')"
    if [[ "${io}" == "Yes" && "${sql}" == "Yes" ]]; then
      ready=true
      break
    fi
    sleep 1
  done
  replica_mysql -e "SHOW REPLICA STATUS\G" >"${REPLICA_STATUS_FILE}" 2>&1 || true
  if [[ "${ready}" != true ]]; then
    echo "Replication did not become healthy." >&2
    cat "${REPLICA_STATUS_FILE}" >&2 || true
    return 1
  fi
}

wait_for_replica_fixture() {
  local ready=false
  for _ in $(seq 1 "${REPLICA_WAIT_SECONDS}"); do
    local count
    count="$(replica_mysql --skip-column-names "${MYSQL_DATABASE}" -e       "SELECT COUNT(*) FROM Seat WHERE id=${FIXTURE_SEAT_ID} AND performance_time_id=${FIXTURE_PT_ID};" 2>/dev/null || true)"
    if [[ "${count}" == "1" ]]; then
      ready=true
      break
    fi
    sleep 1
  done
  if [[ "${ready}" != true ]]; then
    echo "Fixture did not reach replica." >&2
    return 1
  fi
}

wait_for_primary_commit() {
  local value=""
  for _ in $(seq 1 $((COMMIT_WAIT_SECONDS * 10))); do
    value="$(primary_mysql --skip-column-names "${MYSQL_DATABASE}" -e "
      SELECT CONCAT(status, ':', COALESCE(reservation_id, 0))
      FROM reservation_idempotency
      WHERE member_id=${FIXTURE_MEMBER_ID}
        AND idempotency_key='${IDEMPOTENCY_KEY}'
      LIMIT 1;
    " 2>/dev/null | tail -n 1 | tr -d '\r' || true)"
    if [[ "${value}" == SUCCEEDED:* && "${value}" != "SUCCEEDED:0" ]]; then
      printf '%s\n' "${value#SUCCEEDED:}"
      return 0
    fi
    sleep 0.1
  done
  echo "Primary commit was not observed." >&2
  return 1
}

wait_for_replica_commit() {
  local expected_reservation_id="$1"
  local value=""
  for _ in $(seq 1 $((REPLICA_WAIT_SECONDS * 10))); do
    value="$(replica_mysql --skip-column-names "${MYSQL_DATABASE}" -e "
      SELECT CONCAT(status, ':', COALESCE(reservation_id, 0))
      FROM reservation_idempotency
      WHERE member_id=${FIXTURE_MEMBER_ID}
        AND idempotency_key='${IDEMPOTENCY_KEY}'
      LIMIT 1;
    " 2>/dev/null | tail -n 1 | tr -d '\r' || true)"
    if [[ "${value}" == "SUCCEEDED:${expected_reservation_id}" ]]; then
      return 0
    fi
    sleep 0.1
  done
  echo "Committed reservation did not reach replica." >&2
  return 1
}

create_jwt() {
  JWT_SECRET="${JWT_SECRET}"   MEMBER_ID="${FIXTURE_MEMBER_ID}"   WALLET_ADDRESS="${FIXTURE_WALLET}"   python3 - <<'PY'
import base64, hashlib, hmac, json, os, time

def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")

secret = os.environ["JWT_SECRET"].encode()
now = int(time.time())
header = b64url(json.dumps({"alg":"HS256","typ":"JWT"}, separators=(",",":")).encode())
payload = b64url(json.dumps({
    "memberId": int(os.environ["MEMBER_ID"]),
    "walletAddress": os.environ["WALLET_ADDRESS"],
    "role": "ROLE_USER",
    "iat": now,
    "exp": now + 3600,
}, separators=(",",":")).encode())
unsigned = f"{header}.{payload}"
signature = b64url(hmac.new(secret, unsigned.encode(), hashlib.sha256).digest())
print(f"{unsigned}.{signature}")
PY
}

extract_reservation_id() {
  local file="$1"
  python3 - "${file}" <<'PY'
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as f:
    body = json.load(f)
value = (((body or {}).get("data") or {}).get("id"))
print("" if value is None else value)
PY
}

echo "== Prepare isolated topology =="
docker network create "${NETWORK}" >/dev/null
docker run -d --rm --name "${REDIS}" --network "${NETWORK}" "${REDIS_IMAGE}" >/dev/null
start_mysql "${PRIMARY}" 401

echo "== Create application schema on primary =="
start_app "${PRIMARY}" create true
wait_for_app
docker rm -f "${APP}" >/dev/null

echo "== Clone baseline into replica and reset GTID history =="
docker exec -e MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" "${PRIMARY}"   mysqldump --user=root --protocol=tcp --single-transaction --quick --no-tablespaces   --set-gtid-purged=OFF "${MYSQL_DATABASE}" >"${BASE_DUMP}"

start_mysql "${REPLICA}" 402
docker exec -i -e MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" "${REPLICA}"   mysql --user=root --protocol=tcp --init-command="SET SESSION sql_log_bin=0" "${MYSQL_DATABASE}"   <"${BASE_DUMP}"

primary_mysql -e "RESET MASTER;"
replica_mysql -e "RESET MASTER;"

REPLICATION_PASSWORD="replication-${RUN_ID}"
primary_mysql -e "
  CREATE USER 'imticket_repl'@'%' IDENTIFIED BY '${REPLICATION_PASSWORD}';
  GRANT REPLICATION SLAVE ON *.* TO 'imticket_repl'@'%';
"
replica_mysql -e "SET GLOBAL read_only=ON; SET GLOBAL super_read_only=ON;"
replica_mysql -e "
  CHANGE REPLICATION SOURCE TO
    SOURCE_HOST='${PRIMARY}',
    SOURCE_PORT=3306,
    SOURCE_USER='imticket_repl',
    SOURCE_PASSWORD='${REPLICATION_PASSWORD}',
    SOURCE_AUTO_POSITION=1,
    GET_SOURCE_PUBLIC_KEY=1;
  START REPLICA;
"
wait_for_replication_threads

echo "== Seed one reservation fixture through the primary =="
primary_mysql "${MYSQL_DATABASE}" -e "
  INSERT INTO Member (
    id, wallet_address, phone_number, user_role, sms_verified, wallet_verified, identify_name
  ) VALUES (
    ${FIXTURE_MEMBER_ID}, '${FIXTURE_WALLET}', '01000009901', 'ROLE_USER', TRUE, TRUE,
    'ambiguous-commit-user'
  );

  INSERT INTO Venue (
    id, performance_venue_name, performance_place_address, phoneNumber
  ) VALUES (
    ${FIXTURE_VENUE_ID}, 'Ambiguous Commit Venue', 'Seoul', '02-0000-9901'
  );

  INSERT INTO VenueHall (
    id, venue_id, venuehall_name, venuehall_total_seats
  ) VALUES (
    ${FIXTURE_HALL_ID}, ${FIXTURE_VENUE_ID}, 'Ambiguous Commit Hall', 1
  );

  INSERT INTO Performance (
    id, performance_title, description, venue_type, performance_start_date, performance_end_date
  ) VALUES (
    ${FIXTURE_PERFORMANCE_ID}, 'Ambiguous Commit Performance', 'failover rehearsal',
    'CONCERT', CURDATE(), CURDATE() + INTERVAL 1 DAY
  );

  INSERT INTO PerformanceTime (
    id, performance_id, venuehall_id, performance_start_date, performance_start_time
  ) VALUES (
    ${FIXTURE_PT_ID}, ${FIXTURE_PERFORMANCE_ID}, ${FIXTURE_HALL_ID},
    CURDATE() + INTERVAL 1 DAY, '19:00:00'
  );

  INSERT INTO Seat (
    id, performance_time_id, seat_floor, seat_section, seat_row, seat_number,
    seat_status, seat_type, is_reservation, seat_price, version
  ) VALUES (
    ${FIXTURE_SEAT_ID}, ${FIXTURE_PT_ID}, 1, 'A', 1, 1,
    'AVAILABLE', 'A', FALSE, 100000, 0
  );
"
wait_for_replica_fixture

echo "== Start application on primary and warm the cache =="
start_app "${PRIMARY}" none false
wait_for_app
curl -fsS --connect-timeout 2 --max-time 10   "http://127.0.0.1:${APP_PORT}/api/seats/${FIXTURE_PT_ID}"   >"${RUN_DIR}/warm-seat-map.json"

JWT="$(create_jwt)"
REQUEST_PAYLOAD="{\"performanceTimeId\":${FIXTURE_PT_ID},\"seatIds\":[${FIXTURE_SEAT_ID}]}"

echo "== Delay post-commit cache invalidation and start original request =="
docker exec "${REDIS}" redis-cli CLIENT PAUSE "${REDIS_PAUSE_MS}" ALL >/dev/null

(
  set +e
  code="$(curl --silent --show-error     --connect-timeout 2     --max-time "${ORIGINAL_REQUEST_TIMEOUT_SECONDS}"     -o "${ORIGINAL_BODY}"     -w '%{http_code}'     -X POST "http://127.0.0.1:${APP_PORT}/api/reservation/pre-reserve"     -H "Authorization: Bearer ${JWT}"     -H "Content-Type: application/json"     -H "Idempotency-Key: ${IDEMPOTENCY_KEY}"     --data "${REQUEST_PAYLOAD}"     2>"${ORIGINAL_ERR}")"
  exit_code=$?
  printf '%s\n' "${code}" >"${ORIGINAL_STATUS}"
  printf '%s\n' "${exit_code}" >"${RUN_DIR}/original-curl-exit.txt"
  exit 0
) &
CURL_PID=$!

PRE_FAILOVER_RESERVATION_ID="$(wait_for_primary_commit)"
echo "primary_commit_reservation_id=${PRE_FAILOVER_RESERVATION_ID}"   >"${RUN_DIR}/timeline.env"

wait_for_replica_commit "${PRE_FAILOVER_RESERVATION_ID}"
echo "replica_commit_observed=true" >>"${RUN_DIR}/timeline.env"

echo "== Fail primary after commit reached replica =="
FAILURE_STARTED_MS="$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
)"
docker stop --time 1 "${PRIMARY}" >/dev/null

set +e
wait "${CURL_PID}"
set -e
CURL_PID=""

ORIGINAL_CURL_EXIT="$(cat "${RUN_DIR}/original-curl-exit.txt")"
ORIGINAL_HTTP_CODE="$(cat "${ORIGINAL_STATUS}")"
if [[ "${ORIGINAL_CURL_EXIT}" == "0" && "${ORIGINAL_HTTP_CODE}" =~ ^2 ]]; then
  echo "Original client received success; ambiguous outcome was not reproduced." >&2
  exit 1
fi

echo "== Promote replica and cut application over =="
docker rm -f "${APP}" >/dev/null 2>&1 || true
docker exec "${REDIS}" redis-cli CLIENT UNPAUSE >/dev/null 2>&1 || true

promotion_start_ms="$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
)"
replica_mysql -e "
  STOP REPLICA;
  RESET REPLICA ALL;
  SET GLOBAL super_read_only=OFF;
  SET GLOBAL read_only=OFF;
"
promotion_end_ms="$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
)"

start_app "${REPLICA}" none false
wait_for_app
RECOVERED_MS="$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
)"

echo "== Retry same Idempotency-Key against promoted replica =="
RETRY_HTTP_CODE="$(curl --silent --show-error   --connect-timeout 2   --max-time 15   -o "${RETRY_BODY}"   -w '%{http_code}'   -X POST "http://127.0.0.1:${APP_PORT}/api/reservation/pre-reserve"   -H "Authorization: Bearer ${JWT}"   -H "Content-Type: application/json"   -H "Idempotency-Key: ${IDEMPOTENCY_KEY}"   --data "${REQUEST_PAYLOAD}")"

RETRY_RESERVATION_ID="$(extract_reservation_id "${RETRY_BODY}")"
IDEMPOTENCY_ROWS="$(replica_mysql --skip-column-names "${MYSQL_DATABASE}" -e "
  SELECT COUNT(*) FROM reservation_idempotency
  WHERE member_id=${FIXTURE_MEMBER_ID} AND idempotency_key='${IDEMPOTENCY_KEY}';
" | tail -n 1 | tr -d '\r')"
RESERVATION_ROWS="$(replica_mysql --skip-column-names "${MYSQL_DATABASE}" -e "
  SELECT COUNT(*) FROM Reservation WHERE id=${PRE_FAILOVER_RESERVATION_ID};
" | tail -n 1 | tr -d '\r')"
RESERVED_SEAT_ROWS="$(replica_mysql --skip-column-names "${MYSQL_DATABASE}" -e "
  SELECT COUNT(*) FROM ReservedSeat
  WHERE reservation_id=${PRE_FAILOVER_RESERVATION_ID} AND seat_id=${FIXTURE_SEAT_ID};
" | tail -n 1 | tr -d '\r')"
FINAL_IDEMPOTENCY_STATUS="$(replica_mysql --skip-column-names "${MYSQL_DATABASE}" -e "
  SELECT status FROM reservation_idempotency
  WHERE member_id=${FIXTURE_MEMBER_ID} AND idempotency_key='${IDEMPOTENCY_KEY}'
  LIMIT 1;
" | tail -n 1 | tr -d '\r')"
FINAL_SEAT_STATUS="$(replica_mysql --skip-column-names "${MYSQL_DATABASE}" -e "
  SELECT seat_status FROM Seat WHERE id=${FIXTURE_SEAT_ID};
" | tail -n 1 | tr -d '\r')"

PROMOTION_MILLIS=$((promotion_end_ms - promotion_start_ms))
FULL_SERVICE_RTO_MILLIS=$((RECOVERED_MS - FAILURE_STARTED_MS))

status="OK"
if [[ "${RETRY_HTTP_CODE}" != "200"       || "${RETRY_RESERVATION_ID}" != "${PRE_FAILOVER_RESERVATION_ID}"       || "${IDEMPOTENCY_ROWS}" != "1"       || "${RESERVATION_ROWS}" != "1"       || "${RESERVED_SEAT_ROWS}" != "1"       || "${FINAL_IDEMPOTENCY_STATUS}" != "SUCCEEDED"       || "${FINAL_SEAT_STATUS}" != "LOCKED" ]]; then
  status="MISMATCH"
fi

cat >"${RESULT_FILE}" <<EOF
status=${status}
run_id=${RUN_ID}
scenario=replicated_commit_response_lost_then_retry_after_gtid_failover
original_curl_exit=${ORIGINAL_CURL_EXIT}
original_http_code=${ORIGINAL_HTTP_CODE}
pre_failover_reservation_id=${PRE_FAILOVER_RESERVATION_ID}
replica_commit_observed=true
retry_http_code=${RETRY_HTTP_CODE}
retry_reservation_id=${RETRY_RESERVATION_ID}
idempotency_rows=${IDEMPOTENCY_ROWS}
reservation_rows=${RESERVATION_ROWS}
reserved_seat_rows=${RESERVED_SEAT_ROWS}
final_idempotency_status=${FINAL_IDEMPOTENCY_STATUS}
final_seat_status=${FINAL_SEAT_STATUS}
promotion_millis=${PROMOTION_MILLIS}
full_service_rto_millis=${FULL_SERVICE_RTO_MILLIS}
EOF

cat "${RESULT_FILE}"

if [[ "${status}" != "OK" ]]; then
  exit 1
fi

TEST_STATUS="OK"
