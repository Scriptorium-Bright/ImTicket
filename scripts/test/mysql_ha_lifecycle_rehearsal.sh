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
PRIMARY_PORT="${HA_PRIMARY_PORT:-10052}"
STANDBY_PORT="${HA_STANDBY_PORT:-10053}"
REJOIN_PORT="${HA_REJOIN_PORT:-10054}"
OBJECT_PORT="${HA_OBJECT_PORT:-11084}"
OBJECT_CONSOLE_PORT="${HA_OBJECT_CONSOLE_PORT:-11085}"
APP_PORT="${HA_APP_PORT:-11086}"
MANAGEMENT_PORT="${HA_MANAGEMENT_PORT:-11087}"
APP_IMAGE="${HA_APP_IMAGE:-imticket-app:latest}"
MINIO_IMAGE="${HA_MINIO_IMAGE:-quay.io/minio/minio:latest}"
MC_IMAGE="${HA_MC_IMAGE:-quay.io/minio/mc:latest}"
RUN_ROOT="${HA_RUN_ROOT:-/private/tmp/imticket-mysql-ha-lifecycle}"
MYSQL_TMPFS_SIZE="${HA_MYSQL_TMPFS_SIZE:-640m}"
OBJECT_TMPFS_SIZE="${HA_OBJECT_TMPFS_SIZE:-256m}"
ALERT_THRESHOLD_MILLIS="${HA_ALERT_THRESHOLD_MILLIS:-1000}"
VERIFY_TABLES="${VERIFY_TABLES:-Member Reservation ReservedSeat Seat PerformanceTime Performance VenueHall}"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
RUN_ID_LOWER="$(printf '%s' "${RUN_ID}" | tr '[:upper:]' '[:lower:]')"
RUN_DIR="${RUN_ROOT}/${RUN_ID}"
LIVE_SEED="${RUN_DIR}/${MYSQL_DATABASE}-live-seed.sql"
OBJECT_SEED="${RUN_DIR}/${MYSQL_DATABASE}-object-seed.sql"
POST_FAILURE_SEED="${RUN_DIR}/${MYSQL_DATABASE}-post-failure-seed.sql"
PROMOTION_SNAPSHOT="${RUN_DIR}/${MYSQL_DATABASE}-promotion-snapshot.sql"
OBJECT_PROMOTION_SNAPSHOT="${RUN_DIR}/${MYSQL_DATABASE}-object-promotion-snapshot.sql"
PRIMARY_STATE="${RUN_DIR}/primary-core-state.tsv"
PROMOTED_STATE="${RUN_DIR}/promoted-core-state.tsv"
REJOIN_STATE="${RUN_DIR}/rejoin-core-state.tsv"
INITIAL_RESPONSE="${RUN_DIR}/initial-venue-halls.json"
PROMOTED_RESPONSE="${RUN_DIR}/promoted-venue-halls.json"
FAILURE_RESPONSE="${RUN_DIR}/failure-venue-halls.json"
ALERT_EVENTS="${RUN_DIR}/replication-alert-events.tsv"
RUNNING_STATUS="${RUN_DIR}/standby-running-status.txt"
PAUSED_STATUS="${RUN_DIR}/standby-paused-status.txt"
REJOIN_STATUS="${RUN_DIR}/rejoin-status.txt"
SEED_OBJECT_STAT="${RUN_DIR}/seed-object-stat.json"
PROMOTION_OBJECT_STAT="${RUN_DIR}/promotion-object-stat.json"
OBJECT_LIST="${RUN_DIR}/object-list.txt"
INITIAL_APP_LOG="${RUN_DIR}/initial-app.log"
PROMOTED_APP_LOG="${RUN_DIR}/promoted-app.log"
OBJECT_LOG="${RUN_DIR}/object-store.log"
RESULT_FILE="${RUN_DIR}/result.env"
PRIMARY_CONTAINER="imticket-ha-primary-${RUN_ID_LOWER}"
STANDBY_CONTAINER="imticket-ha-standby-${RUN_ID_LOWER}"
REJOIN_CONTAINER="imticket-ha-rejoin-${RUN_ID_LOWER}"
APP_CONTAINER="imticket-ha-app-${RUN_ID_LOWER}"
REDIS_CONTAINER="imticket-ha-redis-${RUN_ID_LOWER}"
OBJECT_CONTAINER="imticket-ha-object-${RUN_ID_LOWER}"
NETWORK="imticket-ha-${RUN_ID_LOWER}"
BUCKET="imticket-ha-${RUN_ID_LOWER}"
TEMP_ROOT_PASSWORD="ha-${RUN_ID}-root"
REPLICATION_PASSWORD="r-${RUN_ID}"
OBJECT_ACCESS_KEY="imticket${RUN_ID_LOWER}"
OBJECT_SECRET_KEY="imticket-${RUN_ID}-object-secret"

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

standby_mysql() {
  mysql_at "${STANDBY_PORT}" "$@"
}

rejoin_mysql() {
  mysql_at "${REJOIN_PORT}" "$@"
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

restore_without_binlog() {
  local port="$1"
  local dump_file="$2"
  MYSQL_PWD="${TEMP_ROOT_PASSWORD}" mysql \
    --host=127.0.0.1 \
    --port="${port}" \
    --user=root \
    --protocol=tcp \
    --default-character-set=utf8mb4 \
    --init-command="SET SESSION sql_log_bin=0" \
    "${MYSQL_DATABASE}" < "${dump_file}"
}

replica_status_value() {
  local port="$1"
  local key="$2"
  mysql_at "${port}" -e "SHOW REPLICA STATUS;" | awk -F '\t' -v key="${key}" '
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
  local port="$1"
  local ready="false"
  local io_running
  local sql_running
  local attempt
  for attempt in $(seq 1 60); do
    io_running="$(replica_status_value "${port}" 'Replica_IO_Running')"
    sql_running="$(replica_status_value "${port}" 'Replica_SQL_Running')"
    if [[ "${io_running}" == "Yes" && "${sql_running}" == "Yes" ]]; then
      ready="true"
      break
    fi
    sleep 1
  done
  if [[ "${ready}" != "true" ]]; then
    mysql_at "${port}" -e "SHOW REPLICA STATUS;" >&2 || true
    echo "GTID replication did not become healthy on port ${port}." >&2
    return 1
  fi
}

write_core_state() {
  local port="$1"
  local output_file="$2"
  local table
  local count
  local checksum
  printf 'table\tcount\tchecksum\n' > "${output_file}"
  for table in ${VERIFY_TABLES}; do
    count="$(mysql_at "${port}" --skip-column-names "${MYSQL_DATABASE}" -e "SELECT COUNT(*) FROM \`${table}\`;")"
    checksum="$(mysql_at "${port}" --skip-column-names "${MYSQL_DATABASE}" -e "CHECKSUM TABLE \`${table}\`;" | awk '{print $2}')"
    printf '%s\t%s\t%s\n' "${table}" "${count}" "${checksum}" >> "${output_file}"
  done
}

run_mc() {
  docker run --rm \
    --network "${NETWORK}" \
    -e "MC_HOST_store=http://${OBJECT_ACCESS_KEY}:${OBJECT_SECRET_KEY}@${OBJECT_CONTAINER}:9000" \
    -v "${RUN_DIR}:/work" \
    "${MC_IMAGE}" \
    "$@"
}

wait_for_object_store() {
  local ready="false"
  local attempt
  for attempt in $(seq 1 60); do
    if curl --max-time 3 -fsS "http://127.0.0.1:${OBJECT_PORT}/minio/health/live" >/dev/null 2>&1; then
      ready="true"
      break
    fi
    sleep 1
  done
  if [[ "${ready}" != "true" ]]; then
    echo "Temporary object store did not become ready." >&2
    return 1
  fi
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
    -e "SPRING_JWT_SECRET=ha-lifecycle-jwt-secret-ha-lifecycle-jwt-secret" \
    -e "SPRING_DATA_REDIS_HOST=${REDIS_CONTAINER}" \
    -e "SPRING_DATA_REDIS_PORT=6379" \
    -e "SPRING_DATA_REDIS_TIMEOUT=500ms" \
    -e "SPRING_DATA_REDIS_CONNECT_TIMEOUT=500ms" \
    -e "MANAGEMENT_SERVER_PORT=10081" \
    -e "TICKET_APPLICATION_ROLE=ha-lifecycle-rehearsal" \
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
  docker logs "${OBJECT_CONTAINER}" > "${OBJECT_LOG}" 2>&1 || true
  docker rm -f \
    "${APP_CONTAINER}" \
    "${PRIMARY_CONTAINER}" \
    "${STANDBY_CONTAINER}" \
    "${REJOIN_CONTAINER}" \
    "${REDIS_CONTAINER}" \
    "${OBJECT_CONTAINER}" >/dev/null 2>&1 || true
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
if [[ ! "${ALERT_THRESHOLD_MILLIS}" =~ ^[0-9]+$ ]]; then
  echo "HA_ALERT_THRESHOLD_MILLIS must be numeric." >&2
  exit 1
fi
for name in "${PRIMARY_CONTAINER}" "${STANDBY_CONTAINER}" "${REJOIN_CONTAINER}" "${APP_CONTAINER}" "${REDIS_CONTAINER}" "${OBJECT_CONTAINER}" "${NETWORK}"; do
  if [[ ! "${name}" =~ ^imticket-ha- ]]; then
    echo "Unsafe temporary Docker name: ${name}" >&2
    exit 1
  fi
done
for port in "${PRIMARY_PORT}" "${STANDBY_PORT}" "${REJOIN_PORT}" "${OBJECT_PORT}" "${OBJECT_CONSOLE_PORT}" "${APP_PORT}" "${MANAGEMENT_PORT}"; do
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
if ! docker image inspect "${APP_IMAGE}" mysql:8.0 redis:7.4-alpine "${MINIO_IMAGE}" "${MC_IMAGE}" >/dev/null 2>&1; then
  echo "Required Docker images are missing." >&2
  exit 1
fi

live_mysql_before="$(live_mysql --skip-column-names -e "SELECT 1;")"
live_app_before="$(curl --max-time 5 -sS -o /dev/null -w '%{http_code}' http://127.0.0.1:10081/actuator/health 2>/dev/null || true)"
app_image_id="$(docker image inspect "${APP_IMAGE}" --format '{{.Id}}')"
minio_image_id="$(docker image inspect "${MINIO_IMAGE}" --format '{{.Id}}')"
mc_image_id="$(docker image inspect "${MC_IMAGE}" --format '{{.Id}}')"

echo "MySQL high-availability lifecycle rehearsal"
echo "- live source: ${LIVE_MYSQL_HOST}:${LIVE_MYSQL_PORT}/${MYSQL_DATABASE} (read only)"
echo "- run directory: ${RUN_DIR}"
echo "- object store: 127.0.0.1:${OBJECT_PORT}"
echo "- primary, standby, rejoin ports: ${PRIMARY_PORT}, ${STANDBY_PORT}, ${REJOIN_PORT}"
echo "- application ports: ${APP_PORT}, ${MANAGEMENT_PORT}"
echo "- alert threshold: ${ALERT_THRESHOLD_MILLIS} ms"
echo

echo "== Start S3-compatible object store =="
docker network create "${NETWORK}" >/dev/null
docker run -d --rm \
  --name "${OBJECT_CONTAINER}" \
  --network "${NETWORK}" \
  --tmpfs "/data:rw,size=${OBJECT_TMPFS_SIZE}" \
  -e "MINIO_ROOT_USER=${OBJECT_ACCESS_KEY}" \
  -e "MINIO_ROOT_PASSWORD=${OBJECT_SECRET_KEY}" \
  -p "127.0.0.1:${OBJECT_PORT}:9000" \
  -p "127.0.0.1:${OBJECT_CONSOLE_PORT}:9001" \
  "${MINIO_IMAGE}" server /data --console-address ':9001' >/dev/null
wait_for_object_store
run_mc mb --ignore-existing "store/${BUCKET}" >/dev/null

echo "== Upload and retrieve live seed backup =="
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
  "${MYSQL_DATABASE}" > "${LIVE_SEED}"
backup_end_ms="$(now_millis)"
backup_millis="$((backup_end_ms - backup_start_ms))"
backup_bytes="$(wc -c < "${LIVE_SEED}" | tr -d ' ')"
live_seed_sha256="$(shasum -a 256 "${LIVE_SEED}" | awk '{print $1}')"
object_upload_start_ms="$(now_millis)"
run_mc cp "/work/$(basename "${LIVE_SEED}")" "store/${BUCKET}/live-seed.sql" >/dev/null
object_upload_end_ms="$(now_millis)"
object_upload_millis="$((object_upload_end_ms - object_upload_start_ms))"
run_mc stat --json "store/${BUCKET}/live-seed.sql" > "${SEED_OBJECT_STAT}"
run_mc cp "store/${BUCKET}/live-seed.sql" "/work/$(basename "${OBJECT_SEED}")" >/dev/null
object_seed_sha256="$(shasum -a 256 "${OBJECT_SEED}" | awk '{print $1}')"
if [[ "${live_seed_sha256}" != "${object_seed_sha256}" ]]; then
  echo "Object-store seed backup digest mismatch." >&2
  exit 1
fi

echo "== Start GTID primary and standby from object backup =="
docker run -d --rm --name "${REDIS_CONTAINER}" --network "${NETWORK}" redis:7.4-alpine >/dev/null
start_mysql "${PRIMARY_CONTAINER}" "${PRIMARY_PORT}" 301
start_mysql "${STANDBY_CONTAINER}" "${STANDBY_PORT}" 302
restore_without_binlog "${PRIMARY_PORT}" "${OBJECT_SEED}"
restore_without_binlog "${STANDBY_PORT}" "${OBJECT_SEED}"
primary_mysql -e "RESET MASTER;"
standby_mysql -e "RESET MASTER;"
primary_mysql -e "CREATE USER 'imticket_repl'@'%' IDENTIFIED BY '${REPLICATION_PASSWORD}'; GRANT REPLICATION SLAVE ON *.* TO 'imticket_repl'@'%';"
standby_mysql -e "SET GLOBAL read_only=ON; SET GLOBAL super_read_only=ON;"
standby_mysql -e "
CHANGE REPLICATION SOURCE TO
  SOURCE_HOST='${PRIMARY_CONTAINER}',
  SOURCE_PORT=3306,
  SOURCE_USER='imticket_repl',
  SOURCE_PASSWORD='${REPLICATION_PASSWORD}',
  SOURCE_AUTO_POSITION=1,
  GET_SOURCE_PUBLIC_KEY=1;
START REPLICA;
"
wait_for_replication "${STANDBY_PORT}"
standby_mysql -e "SHOW REPLICA STATUS;" > "${RUNNING_STATUS}"

echo "== Start application and verify normal replication =="
start_app "${PRIMARY_CONTAINER}"
wait_for_app "${INITIAL_RESPONSE}"
initial_response_sha256="$(shasum -a 256 "${INITIAL_RESPONSE}" | awk '{print $1}')"
replication_write_ms="$(now_millis)"
primary_mysql "${MYSQL_DATABASE}" -e "
CREATE TABLE ha_lifecycle_marker (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    label VARCHAR(64) NOT NULL UNIQUE,
    created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;
INSERT INTO ha_lifecycle_marker(label, created_at)
VALUES ('RECOVERED_BEFORE_FAILOVER', UTC_TIMESTAMP(6));
"
target_time="$(primary_mysql "${MYSQL_DATABASE}" --skip-column-names -e "SELECT DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') FROM ha_lifecycle_marker WHERE label='RECOVERED_BEFORE_FAILOVER';")"
target_epoch_millis="$(primary_mysql "${MYSQL_DATABASE}" --skip-column-names -e "SELECT ROUND(UNIX_TIMESTAMP(created_at) * 1000) FROM ha_lifecycle_marker WHERE label='RECOVERED_BEFORE_FAILOVER';")"
replicated_marker_count="0"
for _ in $(seq 1 30); do
  table_exists="$(standby_mysql --skip-column-names -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${MYSQL_DATABASE}' AND table_name='ha_lifecycle_marker';")"
  if [[ "${table_exists}" == "1" ]]; then
    replicated_marker_count="$(standby_mysql "${MYSQL_DATABASE}" --skip-column-names -e "SELECT COUNT(*) FROM ha_lifecycle_marker WHERE label='RECOVERED_BEFORE_FAILOVER';")"
  fi
  if [[ "${replicated_marker_count}" == "1" ]]; then
    break
  fi
  sleep 1
done
replication_applied_ms="$(now_millis)"
replication_apply_millis="$((replication_applied_ms - replication_write_ms))"
if [[ "${replicated_marker_count}" != "1" ]]; then
  echo "Recoverable marker did not reach the standby." >&2
  exit 1
fi

echo "== Create replication lag and record alert =="
printf 'detected_at_utc\tstate\tio_running\tsql_running\tretrieved_gtid_set\texecuted_gtid_set\tlag_duration_millis\n' > "${ALERT_EVENTS}"
standby_mysql -e "STOP REPLICA SQL_THREAD;"
lag_started_ms="$(now_millis)"
primary_mysql "${MYSQL_DATABASE}" -e "INSERT INTO ha_lifecycle_marker(label, created_at) VALUES ('UNREPLICATED_BEFORE_FAILOVER', UTC_TIMESTAMP(6));"
unreplicated_time="$(primary_mysql "${MYSQL_DATABASE}" --skip-column-names -e "SELECT DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') FROM ha_lifecycle_marker WHERE label='UNREPLICATED_BEFORE_FAILOVER';")"
alert_state="PENDING"
retrieved_gtid_set=""
executed_gtid_set=""
replica_io_after_pause=""
replica_sql_after_pause=""
alert_detection_millis=0
for _ in $(seq 1 30); do
  replica_io_after_pause="$(replica_status_value "${STANDBY_PORT}" 'Replica_IO_Running')"
  replica_sql_after_pause="$(replica_status_value "${STANDBY_PORT}" 'Replica_SQL_Running')"
  retrieved_gtid_set="$(replica_status_value "${STANDBY_PORT}" 'Retrieved_Gtid_Set')"
  executed_gtid_set="$(replica_status_value "${STANDBY_PORT}" 'Executed_Gtid_Set')"
  current_ms="$(now_millis)"
  lag_duration_millis="$((current_ms - lag_started_ms))"
  if [[ "${replica_sql_after_pause}" != "Yes" \
        && -n "${retrieved_gtid_set}" \
        && "${retrieved_gtid_set}" != "${executed_gtid_set}" \
        && "${lag_duration_millis}" -ge "${ALERT_THRESHOLD_MILLIS}" ]]; then
    alert_state="FIRING"
    alert_detection_millis="${lag_duration_millis}"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
      "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
      "${alert_state}" \
      "${replica_io_after_pause}" \
      "${replica_sql_after_pause}" \
      "${retrieved_gtid_set}" \
      "${executed_gtid_set}" \
      "${alert_detection_millis}" >> "${ALERT_EVENTS}"
    break
  fi
  sleep 1
done
sleep 2
standby_mysql -e "SHOW REPLICA STATUS;" > "${PAUSED_STATUS}"
unreplicated_before_failover="$(standby_mysql "${MYSQL_DATABASE}" --skip-column-names -e "SELECT COUNT(*) FROM ha_lifecycle_marker WHERE label='UNREPLICATED_BEFORE_FAILOVER';")"
write_core_state "${PRIMARY_PORT}" "${PRIMARY_STATE}"

echo "== Fail primary, retain object backup, and promote standby =="
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
run_mc cp "store/${BUCKET}/live-seed.sql" "/work/$(basename "${POST_FAILURE_SEED}")" >/dev/null
post_failure_seed_sha256="$(shasum -a 256 "${POST_FAILURE_SEED}" | awk '{print $1}')"

promotion_start_ms="$(now_millis)"
standby_mysql -e "STOP REPLICA; RESET REPLICA ALL; SET GLOBAL super_read_only=OFF; SET GLOBAL read_only=OFF;"
promotion_end_ms="$(now_millis)"
promotion_millis="$((promotion_end_ms - promotion_start_ms))"
promoted_read_only="$(standby_mysql --skip-column-names -e "SELECT @@GLOBAL.read_only;")"
promoted_super_read_only="$(standby_mysql --skip-column-names -e "SELECT @@GLOBAL.super_read_only;")"
recovered_marker_count="$(standby_mysql "${MYSQL_DATABASE}" --skip-column-names -e "SELECT COUNT(*) FROM ha_lifecycle_marker WHERE label='RECOVERED_BEFORE_FAILOVER';")"
unreplicated_after_promotion="$(standby_mysql "${MYSQL_DATABASE}" --skip-column-names -e "SELECT COUNT(*) FROM ha_lifecycle_marker WHERE label='UNREPLICATED_BEFORE_FAILOVER';")"
standby_mysql "${MYSQL_DATABASE}" -e "INSERT INTO ha_lifecycle_marker(label, created_at) VALUES ('WRITABLE_AFTER_PROMOTION', UTC_TIMESTAMP(6));"
post_promotion_write_count="$(standby_mysql "${MYSQL_DATABASE}" --skip-column-names -e "SELECT COUNT(*) FROM ha_lifecycle_marker WHERE label='WRITABLE_AFTER_PROMOTION';")"
write_core_state "${STANDBY_PORT}" "${PROMOTED_STATE}"
core_state_match="false"
if cmp -s "${PRIMARY_STATE}" "${PROMOTED_STATE}"; then
  core_state_match="true"
fi

application_cutover_start_ms="$(now_millis)"
docker rm -f "${APP_CONTAINER}" >/dev/null
start_app "${STANDBY_CONTAINER}"
wait_for_app "${PROMOTED_RESPONSE}"
service_recovered_ms="$(now_millis)"
application_cutover_millis="$((service_recovered_ms - application_cutover_start_ms))"
full_service_rto_millis="$((service_recovered_ms - failure_started_ms))"
rpo_millis="$((failure_started_ms - target_epoch_millis))"
promoted_response_sha256="$(shasum -a 256 "${PROMOTED_RESPONSE}" | awk '{print $1}')"

echo "== Store promoted snapshot and rejoin a replacement replica =="
snapshot_start_ms="$(now_millis)"
MYSQL_PWD="${TEMP_ROOT_PASSWORD}" mysqldump \
  --host=127.0.0.1 \
  --port="${STANDBY_PORT}" \
  --user=root \
  --protocol=tcp \
  --default-character-set=utf8mb4 \
  --single-transaction \
  --quick \
  --no-tablespaces \
  --set-gtid-purged=ON \
  "${MYSQL_DATABASE}" > "${PROMOTION_SNAPSHOT}"
snapshot_end_ms="$(now_millis)"
promotion_snapshot_millis="$((snapshot_end_ms - snapshot_start_ms))"
promotion_snapshot_sha256="$(shasum -a 256 "${PROMOTION_SNAPSHOT}" | awk '{print $1}')"
run_mc cp "/work/$(basename "${PROMOTION_SNAPSHOT}")" "store/${BUCKET}/promotion-snapshot.sql" >/dev/null
run_mc stat --json "store/${BUCKET}/promotion-snapshot.sql" > "${PROMOTION_OBJECT_STAT}"
run_mc cp "store/${BUCKET}/promotion-snapshot.sql" "/work/$(basename "${OBJECT_PROMOTION_SNAPSHOT}")" >/dev/null
object_promotion_sha256="$(shasum -a 256 "${OBJECT_PROMOTION_SNAPSHOT}" | awk '{print $1}')"

rejoin_started_ms="$(now_millis)"
start_mysql "${REJOIN_CONTAINER}" "${REJOIN_PORT}" 303
rejoin_mysql -e "RESET MASTER;"
restore_without_binlog "${REJOIN_PORT}" "${OBJECT_PROMOTION_SNAPSHOT}"
rejoin_mysql -e "SET GLOBAL read_only=ON; SET GLOBAL super_read_only=ON;"
rejoin_mysql -e "
CHANGE REPLICATION SOURCE TO
  SOURCE_HOST='${STANDBY_CONTAINER}',
  SOURCE_PORT=3306,
  SOURCE_USER='imticket_repl',
  SOURCE_PASSWORD='${REPLICATION_PASSWORD}',
  SOURCE_AUTO_POSITION=1,
  GET_SOURCE_PUBLIC_KEY=1;
START REPLICA;
"
wait_for_replication "${REJOIN_PORT}"
rejoin_ready_ms="$(now_millis)"
rejoin_millis="$((rejoin_ready_ms - rejoin_started_ms))"
rejoin_mysql -e "SHOW REPLICA STATUS;" > "${REJOIN_STATUS}"
rejoin_io_running="$(replica_status_value "${REJOIN_PORT}" 'Replica_IO_Running')"
rejoin_sql_running="$(replica_status_value "${REJOIN_PORT}" 'Replica_SQL_Running')"
rejoin_last_io_error="$(replica_status_value "${REJOIN_PORT}" 'Last_IO_Error')"
rejoin_last_sql_error="$(replica_status_value "${REJOIN_PORT}" 'Last_SQL_Error')"
rejoin_read_only="$(rejoin_mysql --skip-column-names -e "SELECT @@GLOBAL.read_only;")"
rejoin_super_read_only="$(rejoin_mysql --skip-column-names -e "SELECT @@GLOBAL.super_read_only;")"

rejoin_write_ms="$(now_millis)"
standby_mysql "${MYSQL_DATABASE}" -e "INSERT INTO ha_lifecycle_marker(label, created_at) VALUES ('REJOINED_AFTER_PROMOTION', UTC_TIMESTAMP(6));"
rejoined_marker_count="0"
for _ in $(seq 1 30); do
  rejoined_marker_count="$(rejoin_mysql "${MYSQL_DATABASE}" --skip-column-names -e "SELECT COUNT(*) FROM ha_lifecycle_marker WHERE label='REJOINED_AFTER_PROMOTION';")"
  if [[ "${rejoined_marker_count}" == "1" ]]; then
    break
  fi
  sleep 1
done
rejoin_applied_ms="$(now_millis)"
rejoin_apply_millis="$((rejoin_applied_ms - rejoin_write_ms))"
write_core_state "${REJOIN_PORT}" "${REJOIN_STATE}"
rejoin_core_state_match="false"
if cmp -s "${PROMOTED_STATE}" "${REJOIN_STATE}"; then
  rejoin_core_state_match="true"
fi

run_mc ls --recursive "store/${BUCKET}" > "${OBJECT_LIST}"
live_mysql_after="$(live_mysql --skip-column-names -e "SELECT 1;")"
live_app_after="$(curl --max-time 5 -sS -o /dev/null -w '%{http_code}' http://127.0.0.1:10081/actuator/health 2>/dev/null || true)"

status="OK"
if [[ "${live_seed_sha256}" != "${object_seed_sha256}" \
      || "${live_seed_sha256}" != "${post_failure_seed_sha256}" \
      || "${promotion_snapshot_sha256}" != "${object_promotion_sha256}" \
      || "${alert_state}" != "FIRING" \
      || "${replica_io_after_pause}" != "Yes" \
      || "${replica_sql_after_pause}" != "No" \
      || "${unreplicated_before_failover}" != "0" \
      || "${failure_api_code}" == "200" \
      || "${promoted_read_only}" != "0" \
      || "${promoted_super_read_only}" != "0" \
      || "${recovered_marker_count}" != "1" \
      || "${unreplicated_after_promotion}" != "0" \
      || "${post_promotion_write_count}" != "1" \
      || "${core_state_match}" != "true" \
      || "${initial_response_sha256}" != "${promoted_response_sha256}" \
      || "${rejoin_io_running}" != "Yes" \
      || "${rejoin_sql_running}" != "Yes" \
      || -n "${rejoin_last_io_error}" \
      || -n "${rejoin_last_sql_error}" \
      || "${rejoin_read_only}" != "1" \
      || "${rejoin_super_read_only}" != "1" \
      || "${rejoined_marker_count}" != "1" \
      || "${rejoin_core_state_match}" != "true" \
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
live_seed_sha256=${live_seed_sha256}
object_seed_sha256=${object_seed_sha256}
post_failure_seed_sha256=${post_failure_seed_sha256}
object_upload_millis=${object_upload_millis}
replication_apply_millis=${replication_apply_millis}
alert_threshold_millis=${ALERT_THRESHOLD_MILLIS}
alert_state=${alert_state}
alert_detection_millis=${alert_detection_millis}
replica_io_after_pause=${replica_io_after_pause}
replica_sql_after_pause=${replica_sql_after_pause}
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
promotion_snapshot_millis=${promotion_snapshot_millis}
promotion_snapshot_sha256=${promotion_snapshot_sha256}
object_promotion_sha256=${object_promotion_sha256}
rejoin_millis=${rejoin_millis}
rejoin_apply_millis=${rejoin_apply_millis}
rejoin_io_running=${rejoin_io_running}
rejoin_sql_running=${rejoin_sql_running}
rejoin_last_io_error=${rejoin_last_io_error}
rejoin_last_sql_error=${rejoin_last_sql_error}
rejoin_read_only=${rejoin_read_only}
rejoin_super_read_only=${rejoin_super_read_only}
rejoined_marker_count=${rejoined_marker_count}
rejoin_core_state_match=${rejoin_core_state_match}
live_mysql_before=${live_mysql_before}
live_mysql_after=${live_mysql_after}
live_app_before=${live_app_before}
live_app_after=${live_app_after}
app_image_id=${app_image_id}
minio_image_id=${minio_image_id}
mc_image_id=${mc_image_id}
bucket=${BUCKET}
run_directory=${RUN_DIR}
alert_events=${ALERT_EVENTS}
object_list=${OBJECT_LIST}
rejoin_status=${REJOIN_STATUS}
EOF

echo "== Summary =="
cat "${RESULT_FILE}"

if [[ "${status}" != "OK" ]]; then
  exit 1
fi
