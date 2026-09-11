#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/load_env_defaults.sh"
load_imticket_env "${REPO_ROOT}/.env"

LIVE_MYSQL_HOST="${LIVE_MYSQL_HOST:-127.0.0.1}"
LIVE_MYSQL_PORT="${LIVE_MYSQL_PORT:-10047}"
MYSQL_DATABASE="${MYSQL_DATABASE:-capstone}"
MYSQL_USER="${MYSQL_USER:-capstone}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"
LIVE_MYSQL_ADMIN_USER="${LIVE_MYSQL_ADMIN_USER:-root}"
LIVE_MYSQL_ADMIN_PASSWORD="${LIVE_MYSQL_ADMIN_PASSWORD:-${MYSQL_ROOT_PASSWORD:-${MYSQL_PASSWORD}}}"
PRIMARY_PORT="${FAILOVER_PRIMARY_PORT:-10050}"
REPLICA_PORT="${FAILOVER_REPLICA_PORT:-10051}"
APP_PORT="${FAILOVER_APP_PORT:-11082}"
MANAGEMENT_PORT="${FAILOVER_MANAGEMENT_PORT:-11083}"
APP_IMAGE="${FAILOVER_APP_IMAGE:-imticket-app:latest}"
RUN_ROOT="${FAILOVER_RUN_ROOT:-/tmp/imticket-mysql-replication-failover}"
MYSQL_TMPFS_SIZE="${FAILOVER_MYSQL_TMPFS_SIZE:-640m}"
VERIFY_TABLES="${VERIFY_TABLES:-Member Reservation ReservedSeat Seat PerformanceTime Performance VenueHall}"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
RUN_ID_LOWER="$(printf '%s' "${RUN_ID}" | tr '[:upper:]' '[:lower:]')"
RUN_DIR="${RUN_ROOT}/${RUN_ID}"
BASE_DUMP="${RUN_DIR}/${MYSQL_DATABASE}-seed.sql"
PRIMARY_STATE="${RUN_DIR}/primary-core-state.tsv"
PROMOTED_STATE="${RUN_DIR}/promoted-core-state.tsv"
INITIAL_RESPONSE="${RUN_DIR}/initial-venue-halls.json"
PROMOTED_RESPONSE="${RUN_DIR}/promoted-venue-halls.json"
FAILURE_RESPONSE="${RUN_DIR}/failure-venue-halls.json"
REPLICA_RUNNING_STATUS="${RUN_DIR}/replica-running-status.txt"
REPLICA_PAUSED_STATUS="${RUN_DIR}/replica-paused-status.txt"
INITIAL_APP_LOG="${RUN_DIR}/initial-app.log"
PROMOTED_APP_LOG="${RUN_DIR}/promoted-app.log"
RESULT_FILE="${RUN_DIR}/result.env"
PRIMARY_CONTAINER="imticket-failover-primary-${RUN_ID_LOWER}"
REPLICA_CONTAINER="imticket-failover-replica-${RUN_ID_LOWER}"
APP_CONTAINER="imticket-failover-app-${RUN_ID_LOWER}"
REDIS_CONTAINER="imticket-failover-redis-${RUN_ID_LOWER}"
NETWORK="imticket-failover-${RUN_ID_LOWER}"
TEMP_ROOT_PASSWORD="failover-${RUN_ID}-root"
REPLICATION_PASSWORD="r-${RUN_ID}"

now_millis() {
  perl -MTime::HiRes=time -e 'printf "%.0f\n", time() * 1000'
}

live_mysql() {
  MYSQL_PWD="${LIVE_MYSQL_ADMIN_PASSWORD}" mysql \
    --host="${LIVE_MYSQL_HOST}" \
    --port="${LIVE_MYSQL_PORT}" \
    --user="${LIVE_MYSQL_ADMIN_USER}" \
    --protocol=tcp \
    --batch \
    --raw \
    "$@"
}

mysql_at() {
  local port="$1"
  shift
  MYSQL_PWD="${TEMP_ROOT_PASSWORD}" mysql \
    --host=127.0.0.1 \
    --port="${port}" \
    --user=root \
    --protocol=tcp \
    --batch \
    --raw \
    "$@"
}

primary_mysql() {
  mysql_at "${PRIMARY_PORT}" "$@"
}

replica_mysql() {
  mysql_at "${REPLICA_PORT}" "$@"
}

wait_for_mysql() {
  local port="$1"
  local ready="false"
  local attempt
  for attempt in $(seq 1 90); do
    if MYSQL_PWD="${TEMP_ROOT_PASSWORD}" mysqladmin \
        --host=127.0.0.1 \
        --port="${port}" \
        --user=root \
        --protocol=tcp \
        --silent ping >/dev/null 2>&1; then
      ready="true"
      break
    fi
    sleep 1
  done
  if [[ "${ready}" != "true" ]]; then
    echo "Temporary MySQL did not become ready on port ${port}." >&2
    return 1
  fi
}

start_mysql() {
  local container_name="$1"
  local port="$2"
  local server_id="$3"
  docker run -d --rm \
    --name "${container_name}" \
    --network "${NETWORK}" \
    --tmpfs "/var/lib/mysql:rw,size=${MYSQL_TMPFS_SIZE}" \
    -e "MYSQL_ROOT_PASSWORD=${TEMP_ROOT_PASSWORD}" \
    -e "MYSQL_DATABASE=${MYSQL_DATABASE}" \
    -e "MYSQL_USER=${MYSQL_USER}" \
    -e "MYSQL_PASSWORD=${MYSQL_PASSWORD}" \
    -p "127.0.0.1:${port}:3306" \
    mysql:8.0 \
    --character-set-server=utf8mb4 \
    --collation-server=utf8mb4_unicode_ci \
    --server-id="${server_id}" \
    --log-bin=binlog \
    --relay-log=relay-bin \
    --binlog-format=ROW \
    --gtid-mode=ON \
    --enforce-gtid-consistency=ON \
    --log-replica-updates=ON \
    --binlog-expire-logs-seconds=3600 >/dev/null
  wait_for_mysql "${port}"
}

restore_seed_without_binlog() {
  local port="$1"
  MYSQL_PWD="${TEMP_ROOT_PASSWORD}" mysql \
    --host=127.0.0.1 \
    --port="${port}" \
    --user=root \
    --protocol=tcp \
    --default-character-set=utf8mb4 \
    --init-command="SET SESSION sql_log_bin=0" \
    "${MYSQL_DATABASE}" < "${BASE_DUMP}"
}

replica_status_value() {
  local key="$1"
  replica_mysql -e "SHOW REPLICA STATUS;" | awk -F '\t' -v key="${key}" '
    NR == 1 {
      for (column = 1; column <= NF; column++) {
        if ($column == key) {
          target = column
          break
        }
      }
      next
    }
    NR == 2 && target > 0 { print $target }
  '
}

wait_for_replication() {
  local ready="false"
  local io_running
  local sql_running
  local attempt
  for attempt in $(seq 1 60); do
    io_running="$(replica_status_value 'Replica_IO_Running')"
    sql_running="$(replica_status_value 'Replica_SQL_Running')"
    if [[ "${io_running}" == "Yes" && "${sql_running}" == "Yes" ]]; then
      ready="true"
      break
    fi
    sleep 1
  done
  if [[ "${ready}" != "true" ]]; then
    replica_mysql -e "SHOW REPLICA STATUS;" >&2 || true
    echo "GTID replication did not become healthy." >&2
    return 1
  fi
}

write_core_state() {
  local target="$1"
  local output_file="$2"
  local table
  local count
  local checksum
  printf 'table\tcount\tchecksum\n' > "${output_file}"
  for table in ${VERIFY_TABLES}; do
    if [[ "${target}" == "primary" ]]; then
      count="$(primary_mysql --skip-column-names "${MYSQL_DATABASE}" -e "SELECT COUNT(*) FROM \`${table}\`;")"
      checksum="$(primary_mysql --skip-column-names "${MYSQL_DATABASE}" -e "CHECKSUM TABLE \`${table}\`;" | awk '{print $2}')"
    else
      count="$(replica_mysql --skip-column-names "${MYSQL_DATABASE}" -e "SELECT COUNT(*) FROM \`${table}\`;")"
      checksum="$(replica_mysql --skip-column-names "${MYSQL_DATABASE}" -e "CHECKSUM TABLE \`${table}\`;" | awk '{print $2}')"
    fi
    printf '%s\t%s\t%s\n' "${table}" "${count}" "${checksum}" >> "${output_file}"
  done
}

start_app() {
  local database_container="$1"
  docker run -d --rm \
    --name "${APP_CONTAINER}" \
    --network "${NETWORK}" \
    -p "127.0.0.1:${APP_PORT}:10080" \
    -p "127.0.0.1:${MANAGEMENT_PORT}:10081" \
    -e "SPRING_DATASOURCE_URL=jdbc:mysql://${database_container}:3306/${MYSQL_DATABASE}?serverTimezone=Asia/Seoul&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=5000" \
    -e "SPRING_DATASOURCE_USERNAME=${MYSQL_USER}" \
    -e "SPRING_DATASOURCE_PASSWORD=${MYSQL_PASSWORD}" \
    -e "SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT=3000" \
    -e "SPRING_JPA_HIBERNATE_DDL_AUTO=none" \
    -e "SPRING_JPA_GENERATE_DDL=false" \
    -e "SPRING_JPA_PROPERTIES_HIBERNATE_HBM2DDL_AUTO=none" \
    -e "SPRING_JWT_SECRET=failover-rehearsal-jwt-secret-failover-rehearsal" \
    -e "SPRING_DATA_REDIS_HOST=${REDIS_CONTAINER}" \
    -e "SPRING_DATA_REDIS_PORT=6379" \
    -e "SPRING_DATA_REDIS_TIMEOUT=500ms" \
    -e "SPRING_DATA_REDIS_CONNECT_TIMEOUT=500ms" \
    -e "MANAGEMENT_SERVER_PORT=10081" \
    -e "TICKET_APPLICATION_ROLE=failover-rehearsal" \
    -e "TICKET_SHEDLOCK_SCHEMA_INITIALIZATION_ENABLED=false" \
    -e "RESERVATION_WAITING_ROOM_ENABLED=false" \
    "${APP_IMAGE}" >/dev/null
}

wait_for_app() {
  local response_file="$1"
  local ready="false"
  local health_code
  local api_code
  local attempt
  for attempt in $(seq 1 180); do
    health_code="$(curl --max-time 5 -sS -o "${RUN_DIR}/health.json" -w '%{http_code}' \
      "http://127.0.0.1:${MANAGEMENT_PORT}/actuator/health" 2>/dev/null || true)"
    api_code="$(curl --max-time 5 -sS -o "${response_file}" -w '%{http_code}' \
      "http://127.0.0.1:${APP_PORT}/api/venue/halls" 2>/dev/null || true)"
    if [[ "${health_code}" == "200" && "${api_code}" == "200" ]]; then
      ready="true"
      break
    fi
    sleep 1
  done
  if [[ "${ready}" != "true" ]]; then
    echo "Temporary application did not become ready." >&2
    return 1
  fi
}

cleanup() {
  docker logs "${APP_CONTAINER}" > "${PROMOTED_APP_LOG}" 2>&1 || true
  docker rm -f "${APP_CONTAINER}" "${PRIMARY_CONTAINER}" "${REPLICA_CONTAINER}" "${REDIS_CONTAINER}" >/dev/null 2>&1 || true
  docker network rm "${NETWORK}" >/dev/null 2>&1 || true
}

trap cleanup EXIT

for command in mysql mysqladmin mysqldump docker curl perl awk tr lsof seq cmp shasum wc; do
  if ! command -v "${command}" >/dev/null 2>&1; then
    echo "Required command is missing: ${command}" >&2
    exit 1
  fi
done

if [[ -z "${MYSQL_PASSWORD}" || -z "${LIVE_MYSQL_ADMIN_PASSWORD}" ]]; then
  echo "MySQL application and administrator passwords are required." >&2
  exit 1
fi
if [[ ! "${MYSQL_DATABASE}" =~ ^[A-Za-z0-9_]+$ || ! "${MYSQL_USER}" =~ ^[A-Za-z0-9_]+$ ]]; then
  echo "MySQL database and user names must be alphanumeric identifiers." >&2
  exit 1
fi
for name in "${PRIMARY_CONTAINER}" "${REPLICA_CONTAINER}" "${APP_CONTAINER}" "${REDIS_CONTAINER}" "${NETWORK}"; do
  if [[ ! "${name}" =~ ^imticket-failover- ]]; then
    echo "Unsafe temporary Docker name: ${name}" >&2
    exit 1
  fi
done
for port in "${PRIMARY_PORT}" "${REPLICA_PORT}" "${APP_PORT}" "${MANAGEMENT_PORT}"; do
  if [[ ! "${port}" =~ ^[0-9]+$ ]]; then
    echo "Temporary ports must be numeric: ${port}" >&2
    exit 1
  fi
  if lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Temporary port is already in use: ${port}" >&2
    exit 1
  fi
done

mkdir -p "${RUN_DIR}"
if ! live_mysql --connect-timeout=3 -e "SELECT 1;" >/dev/null 2>&1; then
  echo "Cannot connect to the live MySQL source." >&2
  exit 1
fi
if ! docker image inspect "${APP_IMAGE}" mysql:8.0 redis:7.4-alpine >/dev/null 2>&1; then
  echo "Required Docker images are missing." >&2
  exit 1
fi

live_mysql_before="$(live_mysql --skip-column-names -e "SELECT 1;")"
live_app_before="$(curl --max-time 5 -sS -o /dev/null -w '%{http_code}' http://127.0.0.1:10081/actuator/health 2>/dev/null || true)"
app_image_id="$(docker image inspect "${APP_IMAGE}" --format '{{.Id}}')"

echo "MySQL GTID replication failover rehearsal"
echo "- live source: ${LIVE_MYSQL_HOST}:${LIVE_MYSQL_PORT}/${MYSQL_DATABASE} (read only)"
echo "- run directory: ${RUN_DIR}"
echo "- temporary primary port: ${PRIMARY_PORT}"
echo "- temporary replica port: ${REPLICA_PORT}"
echo "- temporary application ports: ${APP_PORT}, ${MANAGEMENT_PORT}"
echo

echo "== Create seed backup =="
backup_start_ms="$(now_millis)"
MYSQL_PWD="${MYSQL_PASSWORD}" mysqldump \
  --host="${LIVE_MYSQL_HOST}" \
  --port="${LIVE_MYSQL_PORT}" \
  --user="${MYSQL_USER}" \
  --protocol=tcp \
  --default-character-set=utf8mb4 \
  --single-transaction \
  --quick \
  --no-tablespaces \
  --set-gtid-purged=OFF \
  "${MYSQL_DATABASE}" > "${BASE_DUMP}"
backup_end_ms="$(now_millis)"
backup_millis="$((backup_end_ms - backup_start_ms))"
backup_bytes="$(wc -c < "${BASE_DUMP}" | tr -d ' ')"
backup_sha256="$(shasum -a 256 "${BASE_DUMP}" | awk '{print $1}')"

echo "== Start and seed primary and replica =="
docker network create "${NETWORK}" >/dev/null
docker run -d --rm --name "${REDIS_CONTAINER}" --network "${NETWORK}" redis:7.4-alpine >/dev/null
start_mysql "${PRIMARY_CONTAINER}" "${PRIMARY_PORT}" 201
start_mysql "${REPLICA_CONTAINER}" "${REPLICA_PORT}" 202
restore_seed_without_binlog "${PRIMARY_PORT}"
restore_seed_without_binlog "${REPLICA_PORT}"
primary_mysql -e "RESET MASTER;"
replica_mysql -e "RESET MASTER;"

primary_gtid_mode="$(primary_mysql --skip-column-names -e "SELECT @@GLOBAL.gtid_mode;")"
replica_gtid_mode="$(replica_mysql --skip-column-names -e "SELECT @@GLOBAL.gtid_mode;")"
primary_mysql -e "CREATE USER 'imticket_repl'@'%' IDENTIFIED BY '${REPLICATION_PASSWORD}'; GRANT REPLICATION SLAVE ON *.* TO 'imticket_repl'@'%';"
replica_mysql -e "SET GLOBAL read_only=ON; SET GLOBAL super_read_only=ON;"
replica_mysql -e "
CHANGE REPLICATION SOURCE TO
  SOURCE_HOST='${PRIMARY_CONTAINER}',
  SOURCE_PORT=3306,
  SOURCE_USER='imticket_repl',
  SOURCE_PASSWORD='${REPLICATION_PASSWORD}',
  SOURCE_AUTO_POSITION=1,
  GET_SOURCE_PUBLIC_KEY=1;
START REPLICA;
"
wait_for_replication
replica_mysql -e "SHOW REPLICA STATUS;" > "${REPLICA_RUNNING_STATUS}"
replica_io_running="$(replica_status_value 'Replica_IO_Running')"
replica_sql_running="$(replica_status_value 'Replica_SQL_Running')"

echo "== Start application on primary =="
start_app "${PRIMARY_CONTAINER}"
wait_for_app "${INITIAL_RESPONSE}"
initial_response_sha256="$(shasum -a 256 "${INITIAL_RESPONSE}" | awk '{print $1}')"

echo "== Measure replication and create controlled lag =="
replication_write_ms="$(now_millis)"
primary_mysql "${MYSQL_DATABASE}" -e "
CREATE TABLE failover_recovery_marker (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    label VARCHAR(64) NOT NULL UNIQUE,
    created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;
INSERT INTO failover_recovery_marker(label, created_at)
VALUES ('RECOVERED_BEFORE_FAILOVER', UTC_TIMESTAMP(6));
"
target_time="$(primary_mysql "${MYSQL_DATABASE}" --skip-column-names -e "SELECT DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') FROM failover_recovery_marker WHERE label='RECOVERED_BEFORE_FAILOVER';")"
target_epoch_millis="$(primary_mysql "${MYSQL_DATABASE}" --skip-column-names -e "SELECT ROUND(UNIX_TIMESTAMP(created_at) * 1000) FROM failover_recovery_marker WHERE label='RECOVERED_BEFORE_FAILOVER';")"
replicated_marker_count="0"
for _ in $(seq 1 30); do
  replicated_marker_count="$(replica_mysql "${MYSQL_DATABASE}" --skip-column-names -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${MYSQL_DATABASE}' AND table_name='failover_recovery_marker';")"
  if [[ "${replicated_marker_count}" == "1" ]]; then
    replicated_marker_count="$(replica_mysql "${MYSQL_DATABASE}" --skip-column-names -e "SELECT COUNT(*) FROM failover_recovery_marker WHERE label='RECOVERED_BEFORE_FAILOVER';")"
  fi
  if [[ "${replicated_marker_count}" == "1" ]]; then
    break
  fi
  sleep 1
done
replication_applied_ms="$(now_millis)"
replication_apply_millis="$((replication_applied_ms - replication_write_ms))"
if [[ "${replicated_marker_count}" != "1" ]]; then
  echo "Recoverable marker did not reach the replica." >&2
  exit 1
fi

replica_mysql -e "STOP REPLICA SQL_THREAD;"
primary_mysql "${MYSQL_DATABASE}" -e "INSERT INTO failover_recovery_marker(label, created_at) VALUES ('UNREPLICATED_BEFORE_FAILOVER', UTC_TIMESTAMP(6));"
unreplicated_time="$(primary_mysql "${MYSQL_DATABASE}" --skip-column-names -e "SELECT DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') FROM failover_recovery_marker WHERE label='UNREPLICATED_BEFORE_FAILOVER';")"
lag_observed="false"
retrieved_gtid_set=""
executed_gtid_set=""
for _ in $(seq 1 20); do
  retrieved_gtid_set="$(replica_status_value 'Retrieved_Gtid_Set')"
  executed_gtid_set="$(replica_status_value 'Executed_Gtid_Set')"
  if [[ -n "${retrieved_gtid_set}" && "${retrieved_gtid_set}" != "${executed_gtid_set}" ]]; then
    lag_observed="true"
    break
  fi
  sleep 1
done
sleep 2
replica_mysql -e "SHOW REPLICA STATUS;" > "${REPLICA_PAUSED_STATUS}"
replica_io_after_pause="$(replica_status_value 'Replica_IO_Running')"
replica_sql_after_pause="$(replica_status_value 'Replica_SQL_Running')"
unreplicated_before_failover="$(replica_mysql "${MYSQL_DATABASE}" --skip-column-names -e "SELECT COUNT(*) FROM failover_recovery_marker WHERE label='UNREPLICATED_BEFORE_FAILOVER';")"
write_core_state primary "${PRIMARY_STATE}"

echo "== Fail primary and detect database outage =="
failure_started_ms="$(now_millis)"
failure_started_time="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
docker stop --time 1 "${PRIMARY_CONTAINER}" >/dev/null
failure_api_code="200"
for _ in $(seq 1 30); do
  failure_api_code="$(curl --max-time 5 -sS -o "${FAILURE_RESPONSE}" -w '%{http_code}' \
    "http://127.0.0.1:${APP_PORT}/api/venue/halls" 2>/dev/null || true)"
  if [[ "${failure_api_code}" != "200" ]]; then
    break
  fi
  sleep 1
done
failure_detected_ms="$(now_millis)"
failure_detection_millis="$((failure_detected_ms - failure_started_ms))"
docker logs "${APP_CONTAINER}" > "${INITIAL_APP_LOG}" 2>&1 || true

echo "== Promote replica and switch application =="
promotion_start_ms="$(now_millis)"
replica_mysql -e "STOP REPLICA; RESET REPLICA ALL; SET GLOBAL super_read_only=OFF; SET GLOBAL read_only=OFF;"
promotion_end_ms="$(now_millis)"
promotion_millis="$((promotion_end_ms - promotion_start_ms))"
promoted_read_only="$(replica_mysql --skip-column-names -e "SELECT @@GLOBAL.read_only;")"
promoted_super_read_only="$(replica_mysql --skip-column-names -e "SELECT @@GLOBAL.super_read_only;")"
recovered_marker_count="$(replica_mysql "${MYSQL_DATABASE}" --skip-column-names -e "SELECT COUNT(*) FROM failover_recovery_marker WHERE label='RECOVERED_BEFORE_FAILOVER';")"
unreplicated_after_promotion="$(replica_mysql "${MYSQL_DATABASE}" --skip-column-names -e "SELECT COUNT(*) FROM failover_recovery_marker WHERE label='UNREPLICATED_BEFORE_FAILOVER';")"
write_core_state replica "${PROMOTED_STATE}"
core_state_match="false"
if cmp -s "${PRIMARY_STATE}" "${PROMOTED_STATE}"; then
  core_state_match="true"
fi

application_cutover_start_ms="$(now_millis)"
docker rm -f "${APP_CONTAINER}" >/dev/null
start_app "${REPLICA_CONTAINER}"
wait_for_app "${PROMOTED_RESPONSE}"
service_recovered_ms="$(now_millis)"
application_cutover_millis="$((service_recovered_ms - application_cutover_start_ms))"
full_service_rto_millis="$((service_recovered_ms - failure_started_ms))"
rpo_millis="$((failure_started_ms - target_epoch_millis))"
promoted_response_sha256="$(shasum -a 256 "${PROMOTED_RESPONSE}" | awk '{print $1}')"

replica_mysql "${MYSQL_DATABASE}" -e "INSERT INTO failover_recovery_marker(label, created_at) VALUES ('WRITABLE_AFTER_PROMOTION', UTC_TIMESTAMP(6));"
post_promotion_write_count="$(replica_mysql "${MYSQL_DATABASE}" --skip-column-names -e "SELECT COUNT(*) FROM failover_recovery_marker WHERE label='WRITABLE_AFTER_PROMOTION';")"
live_mysql_after="$(live_mysql --skip-column-names -e "SELECT 1;")"
live_app_after="$(curl --max-time 5 -sS -o /dev/null -w '%{http_code}' http://127.0.0.1:10081/actuator/health 2>/dev/null || true)"

status="OK"
if [[ "${primary_gtid_mode}" != "ON" \
      || "${replica_gtid_mode}" != "ON" \
      || "${replica_io_running}" != "Yes" \
      || "${replica_sql_running}" != "Yes" \
      || "${replica_io_after_pause}" != "Yes" \
      || "${replica_sql_after_pause}" != "No" \
      || "${lag_observed}" != "true" \
      || "${unreplicated_before_failover}" != "0" \
      || "${failure_api_code}" == "200" \
      || "${promoted_read_only}" != "0" \
      || "${promoted_super_read_only}" != "0" \
      || "${recovered_marker_count}" != "1" \
      || "${unreplicated_after_promotion}" != "0" \
      || "${post_promotion_write_count}" != "1" \
      || "${core_state_match}" != "true" \
      || "${initial_response_sha256}" != "${promoted_response_sha256}" \
      || "${live_mysql_before}" != "1" \
      || "${live_mysql_after}" != "1" \
      || "${live_app_before}" != "200" \
      || "${live_app_after}" != "200" ]]; then
  status="MISMATCH"
fi

cat > "${RESULT_FILE}" <<EOF
status=${status}
run_id=${RUN_ID}
failure_started_time=${failure_started_time}
backup_bytes=${backup_bytes}
backup_millis=${backup_millis}
backup_sha256=${backup_sha256}
primary_gtid_mode=${primary_gtid_mode}
replica_gtid_mode=${replica_gtid_mode}
replica_io_running=${replica_io_running}
replica_sql_running=${replica_sql_running}
replication_apply_millis=${replication_apply_millis}
replica_io_after_pause=${replica_io_after_pause}
replica_sql_after_pause=${replica_sql_after_pause}
lag_observed=${lag_observed}
retrieved_gtid_set=${retrieved_gtid_set}
executed_gtid_set=${executed_gtid_set}
target_time=${target_time}
unreplicated_time=${unreplicated_time}
failure_api_code=${failure_api_code}
failure_detection_millis=${failure_detection_millis}
promotion_millis=${promotion_millis}
application_cutover_millis=${application_cutover_millis}
full_service_rto_millis=${full_service_rto_millis}
rpo_millis=${rpo_millis}
promoted_read_only=${promoted_read_only}
promoted_super_read_only=${promoted_super_read_only}
recovered_marker_count=${recovered_marker_count}
unreplicated_after_promotion=${unreplicated_after_promotion}
post_promotion_write_count=${post_promotion_write_count}
core_state_match=${core_state_match}
initial_response_sha256=${initial_response_sha256}
promoted_response_sha256=${promoted_response_sha256}
live_mysql_before=${live_mysql_before}
live_mysql_after=${live_mysql_after}
live_app_before=${live_app_before}
live_app_after=${live_app_after}
app_image_id=${app_image_id}
base_dump=${BASE_DUMP}
primary_state=${PRIMARY_STATE}
promoted_state=${PROMOTED_STATE}
replica_running_status=${REPLICA_RUNNING_STATUS}
replica_paused_status=${REPLICA_PAUSED_STATUS}
initial_app_log=${INITIAL_APP_LOG}
promoted_app_log=${PROMOTED_APP_LOG}
EOF

echo "== Summary =="
cat "${RESULT_FILE}"

if [[ "${status}" != "OK" ]]; then
  exit 1
fi
