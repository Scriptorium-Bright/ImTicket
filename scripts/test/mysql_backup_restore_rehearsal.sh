#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

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
MYSQL_PASSWORD="${MYSQL_PASSWORD:-${MYSQL_ROOT_PASSWORD:-cider123}}"
MYSQL_ADMIN_USER="${MYSQL_ADMIN_USER:-root}"
MYSQL_ADMIN_PASSWORD="${MYSQL_ADMIN_PASSWORD:-${MYSQL_ROOT_PASSWORD:-${MYSQL_PASSWORD}}}"
RESTORE_DATABASE="${RESTORE_DATABASE:-${MYSQL_DATABASE}_restore_verify}"
BACKUP_DIR="${BACKUP_DIR:-/tmp/imticket-mysql-backup-restore}"
VERIFY_TABLES="${VERIFY_TABLES:-Member Reservation ReservedSeat Seat PerformanceTime Performance VenueHall}"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_FILE="${BACKUP_DIR}/${MYSQL_DATABASE}_${RUN_ID}.sql"
VERIFY_FILE="${BACKUP_DIR}/${MYSQL_DATABASE}_${RUN_ID}_verify.tsv"

mysql_exec() {
  MYSQL_PWD="${MYSQL_PASSWORD}" mysql \
    --host="${MYSQL_HOST}" \
    --port="${MYSQL_PORT}" \
    --user="${MYSQL_USER}" \
    --protocol=tcp \
    --batch \
    --raw \
    "$@"
}

mysql_admin_exec() {
  MYSQL_PWD="${MYSQL_ADMIN_PASSWORD}" mysql \
    --host="${MYSQL_HOST}" \
    --port="${MYSQL_PORT}" \
    --user="${MYSQL_ADMIN_USER}" \
    --protocol=tcp \
    --batch \
    --raw \
    "$@"
}

mysql_dump() {
  MYSQL_PWD="${MYSQL_PASSWORD}" mysqldump \
    --host="${MYSQL_HOST}" \
    --port="${MYSQL_PORT}" \
    --user="${MYSQL_USER}" \
    --protocol=tcp \
    --default-character-set=utf8mb4 \
    --single-transaction \
    --quick \
    --no-tablespaces \
    "${MYSQL_DATABASE}"
}

quote_identifier() {
  local identifier="$1"
  printf '`%s`' "${identifier//\`/\`\`}"
}

table_count() {
  local database="$1"
  local table="$2"
  local quoted_table
  quoted_table="$(quote_identifier "${table}")"
  mysql_admin_exec --skip-column-names "${database}" -e "SELECT COUNT(*) FROM ${quoted_table};"
}

table_checksum() {
  local database="$1"
  local table="$2"
  local quoted_table
  quoted_table="$(quote_identifier "${table}")"
  mysql_admin_exec --skip-column-names "${database}" -e "CHECKSUM TABLE ${quoted_table};" | awk '{print $2}'
}

mkdir -p "${BACKUP_DIR}"

echo "MySQL backup/restore verification"
echo "- host: ${MYSQL_HOST}:${MYSQL_PORT}"
echo "- source database: ${MYSQL_DATABASE}"
echo "- restore database: ${RESTORE_DATABASE}"
echo "- dump user: ${MYSQL_USER}"
echo "- admin user: ${MYSQL_ADMIN_USER}"
echo "- verify tables: ${VERIFY_TABLES}"
echo "- backup file: ${BACKUP_FILE}"
echo "- verify file: ${VERIFY_FILE}"
echo

if ! mysql_exec --connect-timeout=3 -e "SELECT 1;" >/dev/null 2>&1; then
  cat >&2 <<EOF
Cannot connect to MySQL at ${MYSQL_HOST}:${MYSQL_PORT}.

Check one of these first:
- Start Docker Desktop and run: docker compose up -d mysql
- Verify the port: docker compose ps mysql
- Override connection info: MYSQL_HOST=... MYSQL_PORT=... MYSQL_USER=... MYSQL_PASSWORD=...
EOF
  exit 1
fi

echo "== Backup =="
backup_start_epoch="$(date +%s)"
mysql_dump > "${BACKUP_FILE}"
backup_end_epoch="$(date +%s)"
backup_size="$(wc -c < "${BACKUP_FILE}" | tr -d ' ')"
echo "backup_seconds=$((backup_end_epoch - backup_start_epoch))"
echo "backup_bytes=${backup_size}"

echo
echo "== Restore =="
restore_start_epoch="$(date +%s)"
mysql_admin_exec -e "DROP DATABASE IF EXISTS \`${RESTORE_DATABASE//\`/\`\`}\`; CREATE DATABASE \`${RESTORE_DATABASE//\`/\`\`}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
echo "restore_started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
restore_tmp_log="${BACKUP_DIR}/${MYSQL_DATABASE}_${RUN_ID}_restore.log"

if command -v pv >/dev/null 2>&1; then
  set +e
  pv "${BACKUP_FILE}" | MYSQL_PWD="${MYSQL_ADMIN_PASSWORD}" mysql \
    --host="${MYSQL_HOST}" \
    --port="${MYSQL_PORT}" \
    --user="${MYSQL_ADMIN_USER}" \
    --protocol=tcp \
    --default-character-set=utf8mb4 \
    "${RESTORE_DATABASE}" >"${restore_tmp_log}" 2>&1
  restore_status=$?
  set -e
  if [[ "${restore_status}" -ne 0 ]]; then
    echo "Restore failed. See ${restore_tmp_log}." >&2
    cat "${restore_tmp_log}" >&2
    exit "${restore_status}"
  fi
else
  echo "pv is not installed. Restore can look idle until mysql finishes importing ${backup_size} bytes."
  MYSQL_PWD="${MYSQL_ADMIN_PASSWORD}" mysql \
    --host="${MYSQL_HOST}" \
    --port="${MYSQL_PORT}" \
    --user="${MYSQL_ADMIN_USER}" \
    --protocol=tcp \
    --default-character-set=utf8mb4 \
    "${RESTORE_DATABASE}" < "${BACKUP_FILE}"
fi
restore_end_epoch="$(date +%s)"
echo "restore_seconds=$((restore_end_epoch - restore_start_epoch))"

echo
echo "== Verify =="
IFS=' ' read -r -a source_tables <<< "${VERIFY_TABLES}"

echo -e "table\tsource_count\trestore_count\tsource_checksum\trestore_checksum\tstatus" > "${VERIFY_FILE}"

overall_status="OK"
for table in "${source_tables[@]}"; do
  source_count="$(table_count "${MYSQL_DATABASE}" "${table}")"
  restore_count="$(table_count "${RESTORE_DATABASE}" "${table}")"
  source_checksum="$(table_checksum "${MYSQL_DATABASE}" "${table}")"
  restore_checksum="$(table_checksum "${RESTORE_DATABASE}" "${table}")"

  status="OK"
  if [[ "${source_count}" != "${restore_count}" || "${source_checksum}" != "${restore_checksum}" ]]; then
    status="MISMATCH"
    overall_status="MISMATCH"
  fi

  echo -e "${table}\t${source_count}\t${restore_count}\t${source_checksum}\t${restore_checksum}\t${status}" >> "${VERIFY_FILE}"
done

cat "${VERIFY_FILE}"

echo
echo "== Summary =="
echo "backup_file=${BACKUP_FILE}"
echo "verify_file=${VERIFY_FILE}"
echo "table_count=${#source_tables[@]}"
echo "status=${overall_status}"

if [[ "${overall_status}" != "OK" ]]; then
  exit 1
fi
