#!/usr/bin/env bash
set -euo pipefail

# 인기 공연 phase 중 앱·MySQL 상태를 1초 간격으로 같은 시간축에 기록한다.
# 실행 종료는 STOP_FILE 생성으로 알린다.

OUT_DIR="${OUT_DIR:?OUT_DIR이 필요합니다.}"
STOP_FILE="${STOP_FILE:?STOP_FILE이 필요합니다.}"
MANAGEMENT_BASE_URL="${MANAGEMENT_BASE_URL:-http://127.0.0.1:10081}"
PT_ID="${PT_ID:-900000001}"
INTERVAL_SECONDS="${INTERVAL_SECONDS:-1}"

if ! [[ "${PT_ID}" =~ ^[1-9][0-9]*$ ]]; then
  echo "PT_ID는 양의 정수여야 합니다." >&2
  exit 1
fi
if ! [[ "${INTERVAL_SECONDS}" =~ ^[1-9][0-9]*(\.[0-9]+)?$ ]]; then
  echo "INTERVAL_SECONDS는 양수여야 합니다." >&2
  exit 1
fi

mkdir -p "${OUT_DIR}"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/imticket-popular-metrics.XXXXXX")"
trap 'rm -rf "${tmp_dir}"' EXIT

metric_value() {
  local metric_file="$1"
  local metric_name="$2"
  awk -v metric_name="${metric_name}" '$1 ~ ("^" metric_name "\\{") { print $NF; exit }' "${metric_file}"
}

mysql_status() {
  printf '%s\n' "
      SELECT VARIABLE_NAME, VARIABLE_VALUE
      FROM performance_schema.global_status
      WHERE VARIABLE_NAME IN (
        'Innodb_buffer_pool_pages_dirty',
        'Innodb_data_reads',
        'Innodb_data_writes',
        'Innodb_data_written',
        'Innodb_log_write_requests',
        'Innodb_log_writes',
        'Innodb_os_log_fsyncs',
        'Innodb_log_waits',
        'Innodb_row_lock_current_waits',
        'Innodb_row_lock_waits',
        'Innodb_row_lock_time',
        'Threads_running'
      )
      ORDER BY VARIABLE_NAME;
    " | docker compose exec -T mysql sh -c \
    'mysql --user=root --password="$MYSQL_ROOT_PASSWORD" --database="$MYSQL_DATABASE" --batch --skip-column-names'
}

innodb_status() {
  docker compose exec -T mysql sh -c \
    'mysql --user=root --password="$MYSQL_ROOT_PASSWORD" --database="$MYSQL_DATABASE" --raw --batch -e "SHOW ENGINE INNODB STATUS\\G"'
}

seat_status() {
  printf '%s\n' "
      SELECT
        COALESCE(SUM(seat_status = 'AVAILABLE'), 0),
        COALESCE(SUM(seat_status = 'LOCKED'), 0),
        COALESCE(SUM(seat_status = 'RESERVED'), 0)
      FROM Seat
      WHERE performance_time_id = ${PT_ID};
    " | docker compose exec -T mysql sh -c \
    'mysql --user=root --password="$MYSQL_ROOT_PASSWORD" --database="$MYSQL_DATABASE" --batch --skip-column-names'
}

status_value() {
  local status_payload="$1"
  local status_name="$2"
  printf '%s\n' "${status_payload}" | awk -F '\t' -v key="${status_name}" '$1 == key { print $2; exit }'
}

printf '%s\n' \
  'sample\tepoch_seconds\thikari_active\thikari_pending\thikari_max\ttomcat_busy\ttomcat_current\ttomcat_max\tprocess_cpu\tsystem_cpu\tinnodb_buffer_pool_pages_dirty\tinnodb_data_reads\tinnodb_data_writes\tinnodb_data_written\tinnodb_log_write_requests\tinnodb_log_writes\tinnodb_os_log_fsyncs\tinnodb_log_waits\tinnodb_row_lock_current_waits\tinnodb_row_lock_waits\tinnodb_row_lock_time\tthreads_running\tlog_lsn_current\tlog_lsn_last_checkpoint\tlog_lsn_flushed\tmodified_db_pages\tavailable_seats\tlocked_seats\treserved_seats' \
  > "${OUT_DIR}/popular-lifecycle-timeline.tsv"

sample=0
while [[ ! -e "${STOP_FILE}" ]]; do
  sample=$((sample + 1))
  timestamp="$(date +%s)"

  app_file="${tmp_dir}/app.prom"
  curl -fsS --connect-timeout 1 --max-time 3 \
    "${MANAGEMENT_BASE_URL}/actuator/prometheus" > "${app_file}" 2>/dev/null || :

  status_payload="$(mysql_status 2>/dev/null || true)"
  innodb_payload="$(innodb_status 2>/dev/null || true)"
  seat_payload="$(seat_status 2>/dev/null || true)"

  log_lsn_current="$(printf '%s\n' "${innodb_payload}" | awk '/Log sequence number[[:space:]]/{print $NF; exit}')"
  log_lsn_last_checkpoint="$(printf '%s\n' "${innodb_payload}" | awk '/Last checkpoint at[[:space:]]/{print $NF; exit}')"
  log_lsn_flushed="$(printf '%s\n' "${innodb_payload}" | awk '/Log flushed up to[[:space:]]/{print $NF; exit}')"
  modified_db_pages="$(printf '%s\n' "${innodb_payload}" | awk '/Modified db pages[[:space:]]/{print $NF; exit}')"
  read -r available_seats locked_seats reserved_seats <<< "${seat_payload}"

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "${sample}" \
    "${timestamp}" \
    "$(metric_value "${app_file}" hikaricp_connections_active)" \
    "$(metric_value "${app_file}" hikaricp_connections_pending)" \
    "$(metric_value "${app_file}" hikaricp_connections_max)" \
    "$(metric_value "${app_file}" tomcat_threads_busy_threads)" \
    "$(metric_value "${app_file}" tomcat_threads_current_threads)" \
    "$(metric_value "${app_file}" tomcat_threads_config_max_threads)" \
    "$(metric_value "${app_file}" process_cpu_usage)" \
    "$(metric_value "${app_file}" system_cpu_usage)" \
    "$(status_value "${status_payload}" Innodb_buffer_pool_pages_dirty)" \
    "$(status_value "${status_payload}" Innodb_data_reads)" \
    "$(status_value "${status_payload}" Innodb_data_writes)" \
    "$(status_value "${status_payload}" Innodb_data_written)" \
    "$(status_value "${status_payload}" Innodb_log_write_requests)" \
    "$(status_value "${status_payload}" Innodb_log_writes)" \
    "$(status_value "${status_payload}" Innodb_os_log_fsyncs)" \
    "$(status_value "${status_payload}" Innodb_log_waits)" \
    "$(status_value "${status_payload}" Innodb_row_lock_current_waits)" \
    "$(status_value "${status_payload}" Innodb_row_lock_waits)" \
    "$(status_value "${status_payload}" Innodb_row_lock_time)" \
    "$(status_value "${status_payload}" Threads_running)" \
    "${log_lsn_current:-}" \
    "${log_lsn_last_checkpoint:-}" \
    "${log_lsn_flushed:-}" \
    "${modified_db_pages:-}" \
    "${available_seats:-}" \
    "${locked_seats:-}" \
    "${reserved_seats:-}" \
    >> "${OUT_DIR}/popular-lifecycle-timeline.tsv"

  sleep "${INTERVAL_SECONDS}"
done
