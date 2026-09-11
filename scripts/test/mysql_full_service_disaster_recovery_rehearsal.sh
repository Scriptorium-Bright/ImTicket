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
DR_MYSQL_PORT="${DR_MYSQL_PORT:-10049}"
DR_APP_PORT="${DR_APP_PORT:-11080}"
DR_MANAGEMENT_PORT="${DR_MANAGEMENT_PORT:-11081}"
DR_APP_IMAGE="${DR_APP_IMAGE:-imticket-app:latest}"
DR_RUN_ROOT="${DR_RUN_ROOT:-/tmp/imticket-mysql-full-service-recovery}"
DR_MYSQL_TMPFS_SIZE="${DR_MYSQL_TMPFS_SIZE:-768m}"
VERIFY_TABLES="${VERIFY_TABLES:-Member Reservation ReservedSeat Seat PerformanceTime Performance VenueHall}"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
RUN_ID_LOWER="$(printf '%s' "${RUN_ID}" | tr '[:upper:]' '[:lower:]')"
RUN_DIR="${DR_RUN_ROOT}/${RUN_ID}"
EXTERNAL_STORE="${RUN_DIR}/external-store"
BASE_DUMP="${EXTERNAL_STORE}/${MYSQL_DATABASE}-base.sql"
SOURCE_STATE="${RUN_DIR}/source-core-state.tsv"
RECOVERY_STATE="${RUN_DIR}/recovery-core-state.tsv"
INITIAL_RESPONSE="${RUN_DIR}/initial-venue-halls.json"
RECOVERED_RESPONSE="${RUN_DIR}/recovered-venue-halls.json"
FAILURE_RESPONSE="${RUN_DIR}/failure-venue-halls.json"
INITIAL_APP_LOG="${RUN_DIR}/initial-app.log"
RECOVERED_APP_LOG="${RUN_DIR}/recovered-app.log"
RESULT_FILE="${RUN_DIR}/result.env"
SOURCE_CONTAINER="imticket-dr-source-${RUN_ID_LOWER}"
RECOVERY_CONTAINER="imticket-dr-recovery-${RUN_ID_LOWER}"
APP_CONTAINER="imticket-dr-app-${RUN_ID_LOWER}"
REDIS_CONTAINER="imticket-dr-redis-${RUN_ID_LOWER}"
NETWORK="imticket-dr-${RUN_ID_LOWER}"
TEMP_ROOT_PASSWORD="dr-${RUN_ID}-root"

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

dr_mysql() {
  MYSQL_PWD="${TEMP_ROOT_PASSWORD}" mysql \
    --host=127.0.0.1 \
    --port="${DR_MYSQL_PORT}" \
    --user=root \
    --protocol=tcp \
    --batch \
    --raw \
    "$@"
}

wait_for_mysql() {
  local ready="false"
  local attempt
  for attempt in $(seq 1 90); do
    if MYSQL_PWD="${TEMP_ROOT_PASSWORD}" mysqladmin \
        --host=127.0.0.1 \
        --port="${DR_MYSQL_PORT}" \
        --user=root \
        --protocol=tcp \
        --silent ping >/dev/null 2>&1; then
      ready="true"
      break
    fi
    sleep 1
  done
  if [[ "${ready}" != "true" ]]; then
    echo "Temporary MySQL did not become ready." >&2
    return 1
  fi
}

write_core_state() {
  local output_file="$1"
  local table
  local count
  local checksum
  printf 'table\tcount\tchecksum\n' > "${output_file}"
  for table in ${VERIFY_TABLES}; do
    count="$(dr_mysql --skip-column-names "${MYSQL_DATABASE}" -e "SELECT COUNT(*) FROM \`${table}\`;")"
    checksum="$(dr_mysql --skip-column-names "${MYSQL_DATABASE}" -e "CHECKSUM TABLE \`${table}\`;" | awk '{print $2}')"
    printf '%s\t%s\t%s\n' "${table}" "${count}" "${checksum}" >> "${output_file}"
  done
}

start_mysql_container() {
  local container_name="$1"
  local server_id="$2"
  docker run -d --rm \
    --name "${container_name}" \
    --network "${NETWORK}" \
    --tmpfs "/var/lib/mysql:rw,size=${DR_MYSQL_TMPFS_SIZE}" \
    -e "MYSQL_ROOT_PASSWORD=${TEMP_ROOT_PASSWORD}" \
    -e "MYSQL_DATABASE=${MYSQL_DATABASE}" \
    -e "MYSQL_USER=${MYSQL_USER}" \
    -e "MYSQL_PASSWORD=${MYSQL_PASSWORD}" \
    -p "127.0.0.1:${DR_MYSQL_PORT}:3306" \
    mysql:8.0 \
    --character-set-server=utf8mb4 \
    --collation-server=utf8mb4_unicode_ci \
    --log-bin=binlog \
    --binlog-format=ROW \
    --server-id="${server_id}" >/dev/null
  wait_for_mysql
}

start_app_container() {
  local database_container="$1"
  docker run -d --rm \
    --name "${APP_CONTAINER}" \
    --network "${NETWORK}" \
    -p "127.0.0.1:${DR_APP_PORT}:10080" \
    -p "127.0.0.1:${DR_MANAGEMENT_PORT}:10081" \
    -e "SPRING_DATASOURCE_URL=jdbc:mysql://${database_container}:3306/${MYSQL_DATABASE}?serverTimezone=Asia/Seoul&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=5000" \
    -e "SPRING_DATASOURCE_USERNAME=${MYSQL_USER}" \
    -e "SPRING_DATASOURCE_PASSWORD=${MYSQL_PASSWORD}" \
    -e "SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT=3000" \
    -e "SPRING_JPA_HIBERNATE_DDL_AUTO=none" \
    -e "SPRING_JPA_GENERATE_DDL=false" \
    -e "SPRING_JPA_PROPERTIES_HIBERNATE_HBM2DDL_AUTO=none" \
    -e "SPRING_JWT_SECRET=dr-rehearsal-jwt-secret-dr-rehearsal-jwt-secret" \
    -e "SPRING_DATA_REDIS_HOST=${REDIS_CONTAINER}" \
    -e "SPRING_DATA_REDIS_PORT=6379" \
    -e "SPRING_DATA_REDIS_TIMEOUT=500ms" \
    -e "SPRING_DATA_REDIS_CONNECT_TIMEOUT=500ms" \
    -e "MANAGEMENT_SERVER_PORT=10081" \
    -e "TICKET_APPLICATION_ROLE=disaster-recovery-rehearsal" \
    -e "TICKET_SHEDLOCK_SCHEMA_INITIALIZATION_ENABLED=false" \
    -e "RESERVATION_WAITING_ROOM_ENABLED=false" \
    "${DR_APP_IMAGE}" >/dev/null
}

wait_for_application() {
  local response_file="$1"
  local ready="false"
  local health_code
  local api_code
  local attempt
  for attempt in $(seq 1 180); do
    health_code="$(curl --max-time 5 -sS -o "${RUN_DIR}/health.json" -w '%{http_code}' \
      "http://127.0.0.1:${DR_MANAGEMENT_PORT}/actuator/health" 2>/dev/null || true)"
    api_code="$(curl --max-time 5 -sS -o "${response_file}" -w '%{http_code}' \
      "http://127.0.0.1:${DR_APP_PORT}/api/venue/halls" 2>/dev/null || true)"
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
  docker logs "${APP_CONTAINER}" > "${RECOVERED_APP_LOG}" 2>&1 || true
  docker rm -f "${APP_CONTAINER}" "${SOURCE_CONTAINER}" "${RECOVERY_CONTAINER}" "${REDIS_CONTAINER}" >/dev/null 2>&1 || true
  docker network rm "${NETWORK}" >/dev/null 2>&1 || true
}

trap cleanup EXIT

for command in mysql mysqladmin mysqldump mysqlbinlog docker curl perl awk tr lsof seq cmp shasum wc; do
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
for name in "${SOURCE_CONTAINER}" "${RECOVERY_CONTAINER}" "${APP_CONTAINER}" "${REDIS_CONTAINER}" "${NETWORK}"; do
  if [[ ! "${name}" =~ ^imticket-dr- ]]; then
    echo "Unsafe temporary Docker name: ${name}" >&2
    exit 1
  fi
done
for port in "${DR_MYSQL_PORT}" "${DR_APP_PORT}" "${DR_MANAGEMENT_PORT}"; do
  if [[ ! "${port}" =~ ^[0-9]+$ ]]; then
    echo "Temporary ports must be numeric: ${port}" >&2
    exit 1
  fi
  if lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Temporary port is already in use: ${port}" >&2
    exit 1
  fi
done

mkdir -p "${EXTERNAL_STORE}"

if ! live_mysql --connect-timeout=3 -e "SELECT 1;" >/dev/null 2>&1; then
  echo "Cannot connect to the live MySQL source." >&2
  exit 1
fi
if ! docker image inspect "${DR_APP_IMAGE}" mysql:8.0 redis:7.4-alpine >/dev/null 2>&1; then
  echo "Required Docker images are missing." >&2
  exit 1
fi

live_mysql_before="$(live_mysql --skip-column-names -e "SELECT 1;")"
live_app_before="$(curl --max-time 5 -sS -o /dev/null -w '%{http_code}' http://127.0.0.1:10081/actuator/health 2>/dev/null || true)"
app_image_id="$(docker image inspect "${DR_APP_IMAGE}" --format '{{.Id}}')"

echo "MySQL full-service disaster recovery rehearsal"
echo "- live source: ${LIVE_MYSQL_HOST}:${LIVE_MYSQL_PORT}/${MYSQL_DATABASE} (read only)"
echo "- run directory: ${RUN_DIR}"
echo "- external store: ${EXTERNAL_STORE}"
echo "- temporary network: ${NETWORK}"
echo "- temporary MySQL port: ${DR_MYSQL_PORT}"
echo "- temporary application ports: ${DR_APP_PORT}, ${DR_MANAGEMENT_PORT}"
echo

echo "== Create full backup outside the rehearsal MySQL container =="
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
base_sha256="$(shasum -a 256 "${BASE_DUMP}" | awk '{print $1}')"

echo "== Start isolated source MySQL and application =="
docker network create "${NETWORK}" >/dev/null
docker run -d --rm --name "${REDIS_CONTAINER}" --network "${NETWORK}" redis:7.4-alpine >/dev/null
start_mysql_container "${SOURCE_CONTAINER}" 101
MYSQL_PWD="${TEMP_ROOT_PASSWORD}" mysql \
  --host=127.0.0.1 \
  --port="${DR_MYSQL_PORT}" \
  --user=root \
  --protocol=tcp \
  --default-character-set=utf8mb4 \
  "${MYSQL_DATABASE}" < "${BASE_DUMP}"

start_app_container "${SOURCE_CONTAINER}"
initial_app_start_ms="$(now_millis)"
wait_for_application "${INITIAL_RESPONSE}"
initial_app_ready_ms="$(now_millis)"
initial_app_startup_millis="$((initial_app_ready_ms - initial_app_start_ms))"
initial_response_sha256="$(shasum -a 256 "${INITIAL_RESPONSE}" | awk '{print $1}')"

echo "== Create and archive recoverable binary log =="
dr_mysql -e "FLUSH BINARY LOGS;"
read -r binlog_file start_position <<< "$(dr_mysql --skip-column-names -e "SHOW MASTER STATUS;" | awk 'NR == 1 {print $1, $2}')"
dr_mysql "${MYSQL_DATABASE}" -e "
CREATE TABLE dr_recovery_marker (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    label VARCHAR(64) NOT NULL UNIQUE,
    created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;
INSERT INTO dr_recovery_marker(label, created_at)
VALUES ('RECOVERABLE_BEFORE_FAILURE', UTC_TIMESTAMP(6));
"
target_time="$(dr_mysql "${MYSQL_DATABASE}" --skip-column-names -e "SELECT DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') FROM dr_recovery_marker WHERE label='RECOVERABLE_BEFORE_FAILURE';")"
target_epoch_millis="$(dr_mysql "${MYSQL_DATABASE}" --skip-column-names -e "SELECT ROUND(UNIX_TIMESTAMP(created_at) * 1000) FROM dr_recovery_marker WHERE label='RECOVERABLE_BEFORE_FAILURE';")"
read -r target_binlog target_position <<< "$(dr_mysql --skip-column-names -e "SHOW MASTER STATUS;" | awk 'NR == 1 {print $1, $2}')"
if [[ "${binlog_file}" != "${target_binlog}" ]]; then
  echo "Binary log rotated before external archival." >&2
  exit 1
fi

MYSQL_PWD="${TEMP_ROOT_PASSWORD}" mysqlbinlog \
  --read-from-remote-server \
  --raw \
  --host=127.0.0.1 \
  --port="${DR_MYSQL_PORT}" \
  --user=root \
  --result-file="${EXTERNAL_STORE}/" \
  "${binlog_file}"
RAW_BINLOG="${EXTERNAL_STORE}/${binlog_file}"
if [[ ! -s "${RAW_BINLOG}" ]]; then
  echo "Externally archived binary log is missing." >&2
  exit 1
fi
binlog_sha256="$(shasum -a 256 "${RAW_BINLOG}" | awk '{print $1}')"

mysqlbinlog \
  --database="${MYSQL_DATABASE}" \
  --start-position="${start_position}" \
  --stop-position="${target_position}" \
  --disable-log-bin \
  "${RAW_BINLOG}" > "${EXTERNAL_STORE}/replay-to-target.sql"
mysqlbinlog \
  --database="${MYSQL_DATABASE}" \
  --start-position="${start_position}" \
  --stop-position="${target_position}" \
  --base64-output=DECODE-ROWS \
  --verbose \
  "${RAW_BINLOG}" > "${EXTERNAL_STORE}/binlog-audit.sql"

sleep 2
dr_mysql "${MYSQL_DATABASE}" -e "INSERT INTO dr_recovery_marker(label, created_at) VALUES ('UNSHIPPED_BEFORE_FAILURE', UTC_TIMESTAMP(6));"
unshipped_time="$(dr_mysql "${MYSQL_DATABASE}" --skip-column-names -e "SELECT DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') FROM dr_recovery_marker WHERE label='UNSHIPPED_BEFORE_FAILURE';")"
write_core_state "${SOURCE_STATE}"

echo "== Stop and remove the isolated source MySQL =="
failure_started_ms="$(now_millis)"
failure_started_time="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
docker stop --time 1 "${SOURCE_CONTAINER}" >/dev/null
failure_api_code="200"
for _ in $(seq 1 30); do
  failure_api_code="$(curl --max-time 5 -sS -o "${FAILURE_RESPONSE}" -w '%{http_code}' \
    "http://127.0.0.1:${DR_APP_PORT}/api/venue/halls" 2>/dev/null || true)"
  if [[ "${failure_api_code}" != "200" ]]; then
    break
  fi
  sleep 1
done
failure_detected_ms="$(now_millis)"
failure_detection_millis="$((failure_detected_ms - failure_started_ms))"
docker logs "${APP_CONTAINER}" > "${INITIAL_APP_LOG}" 2>&1 || true

if docker container inspect "${SOURCE_CONTAINER}" >/dev/null 2>&1; then
  echo "Source MySQL container still exists after the failure simulation." >&2
  exit 1
fi
if [[ ! -s "${BASE_DUMP}" || ! -s "${RAW_BINLOG}" ]]; then
  echo "External recovery artifacts did not survive source removal." >&2
  exit 1
fi

echo "== Restore to a new MySQL instance =="
recovery_mysql_start_ms="$(now_millis)"
start_mysql_container "${RECOVERY_CONTAINER}" 102
recovery_mysql_ready_ms="$(now_millis)"
recovery_mysql_startup_millis="$((recovery_mysql_ready_ms - recovery_mysql_start_ms))"
data_restore_start_ms="$(now_millis)"
MYSQL_PWD="${TEMP_ROOT_PASSWORD}" mysql \
  --host=127.0.0.1 \
  --port="${DR_MYSQL_PORT}" \
  --user=root \
  --protocol=tcp \
  --default-character-set=utf8mb4 \
  "${MYSQL_DATABASE}" < "${BASE_DUMP}"
dr_mysql < "${EXTERNAL_STORE}/replay-to-target.sql"
data_restore_end_ms="$(now_millis)"
data_restore_millis="$((data_restore_end_ms - data_restore_start_ms))"
write_core_state "${RECOVERY_STATE}"

recovered_marker_count="$(dr_mysql "${MYSQL_DATABASE}" --skip-column-names -e "SELECT COUNT(*) FROM dr_recovery_marker WHERE label='RECOVERABLE_BEFORE_FAILURE';")"
unshipped_marker_count="$(dr_mysql "${MYSQL_DATABASE}" --skip-column-names -e "SELECT COUNT(*) FROM dr_recovery_marker WHERE label='UNSHIPPED_BEFORE_FAILURE';")"
core_state_match="false"
if cmp -s "${SOURCE_STATE}" "${RECOVERY_STATE}"; then
  core_state_match="true"
fi

echo "== Switch the application connection and verify service recovery =="
application_cutover_start_ms="$(now_millis)"
docker rm -f "${APP_CONTAINER}" >/dev/null
start_app_container "${RECOVERY_CONTAINER}"
wait_for_application "${RECOVERED_RESPONSE}"
service_recovered_ms="$(now_millis)"
application_cutover_millis="$((service_recovered_ms - application_cutover_start_ms))"
full_service_rto_millis="$((service_recovered_ms - failure_started_ms))"
recovered_response_sha256="$(shasum -a 256 "${RECOVERED_RESPONSE}" | awk '{print $1}')"
rpo_millis="$((failure_started_ms - target_epoch_millis))"
live_mysql_after="$(live_mysql --skip-column-names -e "SELECT 1;")"
live_app_after="$(curl --max-time 5 -sS -o /dev/null -w '%{http_code}' http://127.0.0.1:10081/actuator/health 2>/dev/null || true)"

status="OK"
if [[ "${failure_api_code}" == "200" \
      || "${recovered_marker_count}" != "1" \
      || "${unshipped_marker_count}" != "0" \
      || "${core_state_match}" != "true" \
      || "${initial_response_sha256}" != "${recovered_response_sha256}" \
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
base_sha256=${base_sha256}
binlog_file=${binlog_file}
start_position=${start_position}
target_position=${target_position}
target_time=${target_time}
unshipped_time=${unshipped_time}
binlog_sha256=${binlog_sha256}
failure_api_code=${failure_api_code}
failure_detection_millis=${failure_detection_millis}
recovery_mysql_startup_millis=${recovery_mysql_startup_millis}
data_restore_millis=${data_restore_millis}
application_cutover_millis=${application_cutover_millis}
full_service_rto_millis=${full_service_rto_millis}
rpo_millis=${rpo_millis}
initial_app_startup_millis=${initial_app_startup_millis}
recovered_marker_count=${recovered_marker_count}
unshipped_marker_count=${unshipped_marker_count}
core_state_match=${core_state_match}
initial_response_sha256=${initial_response_sha256}
recovered_response_sha256=${recovered_response_sha256}
live_mysql_before=${live_mysql_before}
live_mysql_after=${live_mysql_after}
live_app_before=${live_app_before}
live_app_after=${live_app_after}
app_image_id=${app_image_id}
external_store=${EXTERNAL_STORE}
source_state=${SOURCE_STATE}
recovery_state=${RECOVERY_STATE}
initial_app_log=${INITIAL_APP_LOG}
recovered_app_log=${RECOVERED_APP_LOG}
EOF

echo "== Summary =="
cat "${RESULT_FILE}"

if [[ "${status}" != "OK" ]]; then
  exit 1
fi
