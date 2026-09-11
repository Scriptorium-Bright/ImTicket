#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/load_env_defaults.sh"
load_imticket_env "${REPO_ROOT}/.env"

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-10047}"
MYSQL_ADMIN_USER="${MYSQL_ADMIN_USER:-root}"
MYSQL_ADMIN_PASSWORD="${MYSQL_ADMIN_PASSWORD:-${MYSQL_ROOT_PASSWORD:-${MYSQL_PASSWORD:-}}}"
PITR_SOURCE_DATABASE="${PITR_SOURCE_DATABASE:-imticket_pitr_source_rehearsal}"
PITR_RESTORE_PORT="${PITR_RESTORE_PORT:-10048}"
PITR_ALLOW_RECREATE="${PITR_ALLOW_RECREATE:-false}"
PITR_RUN_ROOT="${PITR_RUN_ROOT:-/tmp/imticket-mysql-pitr}"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
RUN_ID_LOWER="$(printf '%s' "${RUN_ID}" | tr '[:upper:]' '[:lower:]')"
RUN_DIR="${PITR_RUN_ROOT}/${RUN_ID}"
TEMP_CONTAINER="imticket-mysql-pitr-${RUN_ID_LOWER}"
TEMP_ROOT_PASSWORD="pitr-${RUN_ID}-root"
BASE_DUMP="${RUN_DIR}/base.sql"
REPLAY_SQL="${RUN_DIR}/replay-to-target.sql"
BINLOG_AUDIT="${RUN_DIR}/binlog-audit.sql"
SOURCE_ROWS="${RUN_DIR}/source-rows.tsv"
RESTORE_ROWS="${RUN_DIR}/restore-rows.tsv"
RESULT_FILE="${RUN_DIR}/result.env"

validate_database_name() {
  local database="$1"
  if [[ ! "${database}" =~ ^imticket_pitr_[a-z0-9_]+$ ]]; then
    echo "PITR database must start with imticket_pitr_: ${database}" >&2
    exit 1
  fi
}

mysql_source() {
  MYSQL_PWD="${MYSQL_ADMIN_PASSWORD}" mysql \
    --host="${MYSQL_HOST}" \
    --port="${MYSQL_PORT}" \
    --user="${MYSQL_ADMIN_USER}" \
    --protocol=tcp \
    --batch \
    --raw \
    "$@"
}

mysql_restore() {
  MYSQL_PWD="${TEMP_ROOT_PASSWORD}" mysql \
    --host=127.0.0.1 \
    --port="${PITR_RESTORE_PORT}" \
    --user=root \
    --protocol=tcp \
    --batch \
    --raw \
    "$@"
}

master_status() {
  mysql_source --skip-column-names -e "SHOW MASTER STATUS;" | awk 'NR == 1 { print $1, $2 }'
}

now_millis() {
  perl -MTime::HiRes=time -e 'printf "%.0f\n", time() * 1000'
}

cleanup() {
  docker rm -f "${TEMP_CONTAINER}" >/dev/null 2>&1 || true
}

trap cleanup EXIT

for command in mysql mysqladmin mysqldump mysqlbinlog docker perl awk tr lsof seq; do
  if ! command -v "${command}" >/dev/null 2>&1; then
    echo "Required command is missing: ${command}" >&2
    exit 1
  fi
done

validate_database_name "${PITR_SOURCE_DATABASE}"
if [[ "${PITR_SOURCE_DATABASE}" == "${MYSQL_DATABASE:-capstone}" ]]; then
  echo "PITR source database must differ from the application database." >&2
  exit 1
fi
if [[ -z "${MYSQL_ADMIN_PASSWORD}" ]]; then
  echo "MySQL admin password is required." >&2
  exit 1
fi
if [[ ! "${PITR_RESTORE_PORT}" =~ ^[0-9]+$ ]]; then
  echo "PITR_RESTORE_PORT must be numeric." >&2
  exit 1
fi

mkdir -p "${RUN_DIR}"

log_bin="$(mysql_source --skip-column-names -e "SELECT @@GLOBAL.log_bin;")"
binlog_format="$(mysql_source --skip-column-names -e "SELECT @@GLOBAL.binlog_format;")"
binlog_expire_logs_seconds="$(mysql_source --skip-column-names -e "SELECT @@GLOBAL.binlog_expire_logs_seconds;")"
gtid_mode="$(mysql_source --skip-column-names -e "SELECT @@GLOBAL.gtid_mode;")"

if [[ "${log_bin}" != "1" || "${binlog_format}" != "ROW" ]]; then
  echo "PITR rehearsal requires log_bin=ON and binlog_format=ROW." >&2
  exit 1
fi

database_exists="$(mysql_source --skip-column-names -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '${PITR_SOURCE_DATABASE}';")"
if [[ "${database_exists}" != "0" && "${PITR_ALLOW_RECREATE}" != "true" ]]; then
  cat >&2 <<EOF
The isolated rehearsal database already exists: ${PITR_SOURCE_DATABASE}
Review it first or rerun with PITR_ALLOW_RECREATE=true.
EOF
  exit 1
fi

if lsof -nP -iTCP:"${PITR_RESTORE_PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "PITR restore port is already in use: ${PITR_RESTORE_PORT}" >&2
  exit 1
fi

echo "MySQL point-in-time recovery rehearsal"
echo "- source server: ${MYSQL_HOST}:${MYSQL_PORT}"
echo "- isolated source database: ${PITR_SOURCE_DATABASE}"
echo "- temporary restore container: ${TEMP_CONTAINER}"
echo "- temporary restore port: ${PITR_RESTORE_PORT}"
echo "- run directory: ${RUN_DIR}"
echo "- log_bin: ${log_bin}"
echo "- binlog_format: ${binlog_format}"
echo "- binlog_expire_logs_seconds: ${binlog_expire_logs_seconds}"
echo "- gtid_mode: ${gtid_mode}"
echo

echo "== Prepare isolated source =="
mysql_source -e "DROP DATABASE IF EXISTS \`${PITR_SOURCE_DATABASE}\`; CREATE DATABASE \`${PITR_SOURCE_DATABASE}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql_source "${PITR_SOURCE_DATABASE}" -e "
CREATE TABLE recovery_marker (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    label VARCHAR(64) NOT NULL UNIQUE,
    created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;
INSERT INTO recovery_marker(label, created_at) VALUES ('BASELINE_IN_FULL_BACKUP', UTC_TIMESTAMP(6));
"

mysql_source -e "FLUSH BINARY LOGS;"

echo "== Full backup =="
backup_start_ms="$(now_millis)"
MYSQL_PWD="${MYSQL_ADMIN_PASSWORD}" mysqldump \
  --host="${MYSQL_HOST}" \
  --port="${MYSQL_PORT}" \
  --user="${MYSQL_ADMIN_USER}" \
  --protocol=tcp \
  --default-character-set=utf8mb4 \
  --single-transaction \
  --quick \
  --no-tablespaces \
  --set-gtid-purged=OFF \
  "${PITR_SOURCE_DATABASE}" > "${BASE_DUMP}"
backup_end_ms="$(now_millis)"
backup_millis="$((backup_end_ms - backup_start_ms))"
backup_bytes="$(wc -c < "${BASE_DUMP}" | tr -d ' ')"

read -r start_binlog start_position <<< "$(master_status)"
if [[ -z "${start_binlog}" || -z "${start_position}" ]]; then
  echo "Cannot read binary log start position." >&2
  exit 1
fi

echo "== Write target and excluded transactions =="
mysql_source "${PITR_SOURCE_DATABASE}" -e "INSERT INTO recovery_marker(label, created_at) VALUES ('VALID_AFTER_BACKUP', UTC_TIMESTAMP(6));"
target_time="$(mysql_source "${PITR_SOURCE_DATABASE}" --skip-column-names -e "SELECT DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') FROM recovery_marker WHERE label = 'VALID_AFTER_BACKUP';")"
read -r target_binlog target_position <<< "$(master_status)"

sleep 2

mysql_source "${PITR_SOURCE_DATABASE}" -e "INSERT INTO recovery_marker(label, created_at) VALUES ('EXCLUDED_AFTER_TARGET', UTC_TIMESTAMP(6));"
excluded_time="$(mysql_source "${PITR_SOURCE_DATABASE}" --skip-column-names -e "SELECT DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') FROM recovery_marker WHERE label = 'EXCLUDED_AFTER_TARGET';")"
read -r end_binlog end_position <<< "$(master_status)"

if [[ "${start_binlog}" != "${target_binlog}" || "${start_binlog}" != "${end_binlog}" ]]; then
  echo "Binary log rotated during the controlled transaction window." >&2
  exit 1
fi

rpo_millis="$(mysql_source "${PITR_SOURCE_DATABASE}" --skip-column-names -e "
SELECT TIMESTAMPDIFF(
    MICROSECOND,
    (SELECT created_at FROM recovery_marker WHERE label = 'VALID_AFTER_BACKUP'),
    (SELECT created_at FROM recovery_marker WHERE label = 'EXCLUDED_AFTER_TARGET')
) DIV 1000;
")"

echo "== Download and extract binary log =="
MYSQL_PWD="${MYSQL_ADMIN_PASSWORD}" mysqlbinlog \
  --read-from-remote-server \
  --raw \
  --host="${MYSQL_HOST}" \
  --port="${MYSQL_PORT}" \
  --user="${MYSQL_ADMIN_USER}" \
  --result-file="${RUN_DIR}/" \
  "${start_binlog}"

RAW_BINLOG="${RUN_DIR}/${start_binlog}"
if [[ ! -s "${RAW_BINLOG}" ]]; then
  echo "Downloaded binary log is missing: ${RAW_BINLOG}" >&2
  exit 1
fi

mysqlbinlog \
  --database="${PITR_SOURCE_DATABASE}" \
  --start-position="${start_position}" \
  --stop-position="${end_position}" \
  --base64-output=DECODE-ROWS \
  --verbose \
  "${RAW_BINLOG}" > "${BINLOG_AUDIT}"

mysqlbinlog \
  --database="${PITR_SOURCE_DATABASE}" \
  --start-position="${start_position}" \
  --stop-position="${target_position}" \
  --disable-log-bin \
  "${RAW_BINLOG}" > "${REPLAY_SQL}"

echo "== Start temporary restore MySQL =="
restore_start_ms="$(now_millis)"
docker run -d --rm \
  --name "${TEMP_CONTAINER}" \
  --tmpfs /var/lib/mysql:rw,size=768m \
  -e "MYSQL_ROOT_PASSWORD=${TEMP_ROOT_PASSWORD}" \
  -p "127.0.0.1:${PITR_RESTORE_PORT}:3306" \
  mysql:8.0 \
  --character-set-server=utf8mb4 \
  --collation-server=utf8mb4_unicode_ci >/dev/null

restore_ready="false"
for _ in $(seq 1 90); do
  if MYSQL_PWD="${TEMP_ROOT_PASSWORD}" mysqladmin \
      --host=127.0.0.1 \
      --port="${PITR_RESTORE_PORT}" \
      --user=root \
      --protocol=tcp \
      --silent ping >/dev/null 2>&1; then
    restore_ready="true"
    break
  fi
  sleep 1
done
if [[ "${restore_ready}" != "true" ]]; then
  echo "Temporary restore MySQL did not become ready." >&2
  docker logs "${TEMP_CONTAINER}" >&2 || true
  exit 1
fi

echo "== Restore full backup and replay to target =="
mysql_restore -e "CREATE DATABASE \`${PITR_SOURCE_DATABASE}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql_restore "${PITR_SOURCE_DATABASE}" < "${BASE_DUMP}"
mysql_restore < "${REPLAY_SQL}"
restore_end_ms="$(now_millis)"
restore_millis="$((restore_end_ms - restore_start_ms))"

echo "== Verify recovery point =="
mysql_source "${PITR_SOURCE_DATABASE}" --skip-column-names -e "SELECT id, label, DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') FROM recovery_marker ORDER BY id;" > "${SOURCE_ROWS}"
mysql_restore "${PITR_SOURCE_DATABASE}" --skip-column-names -e "SELECT id, label, DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') FROM recovery_marker ORDER BY id;" > "${RESTORE_ROWS}"

source_count="$(wc -l < "${SOURCE_ROWS}" | tr -d ' ')"
restore_count="$(wc -l < "${RESTORE_ROWS}" | tr -d ' ')"
restored_valid_count="$(mysql_restore "${PITR_SOURCE_DATABASE}" --skip-column-names -e "SELECT COUNT(*) FROM recovery_marker WHERE label = 'VALID_AFTER_BACKUP';")"
restored_excluded_count="$(mysql_restore "${PITR_SOURCE_DATABASE}" --skip-column-names -e "SELECT COUNT(*) FROM recovery_marker WHERE label = 'EXCLUDED_AFTER_TARGET';")"
source_target_digest="$(mysql_source "${PITR_SOURCE_DATABASE}" --skip-column-names -e "SELECT SHA2(GROUP_CONCAT(CONCAT_WS('|', id, label, DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ')) ORDER BY id SEPARATOR '#'), 256) FROM recovery_marker WHERE id <= 2;")"
restore_digest="$(mysql_restore "${PITR_SOURCE_DATABASE}" --skip-column-names -e "SELECT SHA2(GROUP_CONCAT(CONCAT_WS('|', id, label, DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ')) ORDER BY id SEPARATOR '#'), 256) FROM recovery_marker;")"

status="OK"
if [[ "${source_count}" != "3" \
      || "${restore_count}" != "2" \
      || "${restored_valid_count}" != "1" \
      || "${restored_excluded_count}" != "0" \
      || "${source_target_digest}" != "${restore_digest}" ]]; then
  status="MISMATCH"
fi

cat > "${RESULT_FILE}" <<EOF
status=${status}
source_database=${PITR_SOURCE_DATABASE}
backup_bytes=${backup_bytes}
backup_millis=${backup_millis}
binlog_file=${start_binlog}
start_position=${start_position}
target_position=${target_position}
end_position=${end_position}
target_time=${target_time}
excluded_time=${excluded_time}
rpo_millis=${rpo_millis}
restore_millis=${restore_millis}
source_count=${source_count}
restore_count=${restore_count}
restored_valid_count=${restored_valid_count}
restored_excluded_count=${restored_excluded_count}
source_target_digest=${source_target_digest}
restore_digest=${restore_digest}
base_dump=${BASE_DUMP}
raw_binlog=${RAW_BINLOG}
replay_sql=${REPLAY_SQL}
binlog_audit=${BINLOG_AUDIT}
source_rows=${SOURCE_ROWS}
restore_rows=${RESTORE_ROWS}
EOF

echo "source rows:"
cat "${SOURCE_ROWS}"
echo "restored rows:"
cat "${RESTORE_ROWS}"
echo
echo "== Summary =="
cat "${RESULT_FILE}"

if [[ "${status}" != "OK" ]]; then
  exit 1
fi
