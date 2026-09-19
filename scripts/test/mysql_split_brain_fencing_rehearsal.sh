#!/usr/bin/env bash
set -euo pipefail

# Split-brain / fencing rehearsal for MySQL GTID failover.
#
# The old primary remains alive during a data-network partition.
# We prove it is still locally writable, fence it before promotion,
# promote the replica, reject writes on the old primary, reconnect it,
# and rejoin it as a read-only replica using GTID auto-position.

MYSQL_IMAGE="${MYSQL_IMAGE:-mysql:8.0}"
MYSQL_DATABASE="${MYSQL_DATABASE:-capstone}"
MYSQL_USER="${MYSQL_USER:-capstone}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-split-brain-app-password}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-split-brain-root-password}"
RUN_ROOT="${RUN_ROOT:-${PWD}/build/mysql-split-brain-results}"
RUN_ID="${RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
RUN_ID_LOWER="$(printf '%s' "${RUN_ID}" | tr '[:upper:]' '[:lower:]')"
RUN_DIR="${RUN_ROOT}/${RUN_ID}"

NETWORK="imticket-split-brain-${RUN_ID_LOWER}"
PRIMARY="imticket-split-primary-${RUN_ID_LOWER}"
REPLICA="imticket-split-replica-${RUN_ID_LOWER}"
REPLICATION_PASSWORD="repl-${RUN_ID}"

RESULT_FILE="${RUN_DIR}/result.env"
PARTITION_STATUS_FILE="${RUN_DIR}/replica-status-during-partition.txt"
REJOIN_STATUS_FILE="${RUN_DIR}/old-primary-rejoin-status.txt"
OLD_FENCE_ERROR="${RUN_DIR}/old-primary-fenced-write.stderr"
OLD_POST_PROMOTION_ERROR="${RUN_DIR}/old-primary-post-promotion-write.stderr"
PRIMARY_LOG="${RUN_DIR}/old-primary.log"
REPLICA_LOG="${RUN_DIR}/promoted-primary.log"

mkdir -p "${RUN_DIR}"

now_millis() {
  python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
}

cleanup() {
  set +e
  docker logs "${PRIMARY}" >"${PRIMARY_LOG}" 2>&1 || true
  docker logs "${REPLICA}" >"${REPLICA_LOG}" 2>&1 || true
  docker rm -f "${PRIMARY}" "${REPLICA}" >/dev/null 2>&1 || true
  docker network rm "${NETWORK}" >/dev/null 2>&1 || true
  set -e
}
trap cleanup EXIT

for command in docker python3 awk tr sed grep seq; do
  if ! command -v "${command}" >/dev/null 2>&1; then
    echo "Required command is missing: ${command}" >&2
    exit 1
  fi
done

root_mysql() {
  local container="$1"
  shift
  docker exec -e MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" "${container}"     mysql --host=127.0.0.1 --protocol=tcp --user=root --batch --raw "$@"
}

app_mysql() {
  local container="$1"
  shift
  docker exec -e MYSQL_PWD="${MYSQL_PASSWORD}" "${container}"     mysql --host=127.0.0.1 --protocol=tcp --user="${MYSQL_USER}" --batch --raw "$@"
}

wait_for_mysql() {
  local container="$1"
  local ready=false
  for _ in $(seq 1 90); do
    if docker exec -e MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" "${container}"       mysqladmin --host=127.0.0.1 --protocol=tcp --user=root --silent ping >/dev/null 2>&1; then
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

  docker run -d --rm     --name "${container}"     --network "${NETWORK}"     --tmpfs /var/lib/mysql:rw,size=512m     -e "MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD}"     -e "MYSQL_DATABASE=${MYSQL_DATABASE}"     -e "MYSQL_USER=${MYSQL_USER}"     -e "MYSQL_PASSWORD=${MYSQL_PASSWORD}"     "${MYSQL_IMAGE}"     --character-set-server=utf8mb4     --collation-server=utf8mb4_unicode_ci     --server-id="${server_id}"     --log-bin=binlog     --relay-log=relay-bin     --binlog-format=ROW     --gtid-mode=ON     --enforce-gtid-consistency=ON     --log-replica-updates=ON     --binlog-expire-logs-seconds=3600 >/dev/null

  wait_for_mysql "${container}"
}

replica_status_value() {
  local container="$1"
  local key="$2"
  root_mysql "${container}" -e "SHOW REPLICA STATUS\G" 2>/dev/null     | awk -F ': ' -v key="${key}" '$1 ~ key {print $2; exit}'     | tr -d '\r'
}

wait_for_replication_threads() {
  local container="$1"
  local ready=false

  for _ in $(seq 1 60); do
    local io_running sql_running
    io_running="$(replica_status_value "${container}" 'Replica_IO_Running')"
    sql_running="$(replica_status_value "${container}" 'Replica_SQL_Running')"
    if [[ "${io_running}" == "Yes" && "${sql_running}" == "Yes" ]]; then
      ready=true
      break
    fi
    sleep 1
  done

  if [[ "${ready}" != true ]]; then
    root_mysql "${container}" -e "SHOW REPLICA STATUS\G" >&2 || true
    echo "Replication did not become healthy for ${container}." >&2
    return 1
  fi
}

wait_for_label() {
  local container="$1"
  local label="$2"
  local expected="$3"
  local ready=false

  for _ in $(seq 1 100); do
    local count
    count="$(root_mysql "${container}" --skip-column-names "${MYSQL_DATABASE}" -e       "SELECT COUNT(*) FROM fencing_probe WHERE label='${label}';" 2>/dev/null       | tail -n 1 | tr -d '\r' || true)"
    if [[ "${count}" == "${expected}" ]]; then
      ready=true
      break
    fi
    sleep 0.1
  done

  if [[ "${ready}" != true ]]; then
    echo "Timed out waiting for label=${label} on ${container}." >&2
    return 1
  fi
}

echo "== Start isolated primary and replica =="
docker network create "${NETWORK}" >/dev/null
start_mysql "${PRIMARY}" 501
start_mysql "${REPLICA}" 502

root_mysql "${PRIMARY}" -e "RESET MASTER;"
root_mysql "${REPLICA}" -e "RESET MASTER;"

echo "== Configure GTID replication =="
root_mysql "${PRIMARY}" -e "
  CREATE USER 'imticket_repl'@'%' IDENTIFIED BY '${REPLICATION_PASSWORD}';
  GRANT REPLICATION SLAVE ON *.* TO 'imticket_repl'@'%';
"

root_mysql "${REPLICA}" -e "
  SET GLOBAL read_only=ON;
  SET GLOBAL super_read_only=ON;
  CHANGE REPLICATION SOURCE TO
    SOURCE_HOST='${PRIMARY}',
    SOURCE_PORT=3306,
    SOURCE_USER='imticket_repl',
    SOURCE_PASSWORD='${REPLICATION_PASSWORD}',
    SOURCE_AUTO_POSITION=1,
    GET_SOURCE_PUBLIC_KEY=1;
  START REPLICA;
"
wait_for_replication_threads "${REPLICA}"

echo "== Create baseline data and wait for replica =="
app_mysql "${PRIMARY}" "${MYSQL_DATABASE}" -e "
  CREATE TABLE fencing_probe (
    id BIGINT NOT NULL AUTO_INCREMENT,
    label VARCHAR(128) NOT NULL UNIQUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
  ) ENGINE=InnoDB;

  INSERT INTO fencing_probe(label) VALUES ('BASELINE');
"
wait_for_label "${REPLICA}" BASELINE 1

baseline_primary_gtid="$(root_mysql "${PRIMARY}" --skip-column-names -e "SELECT @@GLOBAL.gtid_executed;" | tr -d '\r')"
baseline_replica_gtid="$(root_mysql "${REPLICA}" --skip-column-names -e "SELECT @@GLOBAL.gtid_executed;" | tr -d '\r')"

echo "== Partition old primary from the data network while keeping it alive =="
partition_started_ms="$(now_millis)"
docker network disconnect "${NETWORK}" "${PRIMARY}"

# The source is now unreachable to the replica, but the old primary process is still alive.
sleep 2
root_mysql "${REPLICA}" -e "SHOW REPLICA STATUS\G" >"${PARTITION_STATUS_FILE}" 2>&1 || true
partition_replica_io="$(replica_status_value "${REPLICA}" 'Replica_IO_Running')"
partition_replica_last_io_error="$(replica_status_value "${REPLICA}" 'Last_IO_Error' | sed 's/[[:space:]]\+/ /g')"

old_read_only_before_fence="$(root_mysql "${PRIMARY}" --skip-column-names -e "SELECT @@GLOBAL.read_only;" | tr -d '\r')"
old_super_read_only_before_fence="$(root_mysql "${PRIMARY}" --skip-column-names -e "SELECT @@GLOBAL.super_read_only;" | tr -d '\r')"

echo "== Prove the isolated old primary would still accept a write before fencing =="
set +e
app_mysql "${PRIMARY}" "${MYSQL_DATABASE}" -e "
  START TRANSACTION;
  INSERT INTO fencing_probe(label) VALUES ('ROLLBACK_PROBE');
  ROLLBACK;
" >"${RUN_DIR}/pre-fence-rollback-write.stdout" 2>"${RUN_DIR}/pre-fence-rollback-write.stderr"
pre_fence_rollback_write_exit=$?
set -e

rollback_probe_count="$(root_mysql "${PRIMARY}" --skip-column-names "${MYSQL_DATABASE}" -e   "SELECT COUNT(*) FROM fencing_probe WHERE label='ROLLBACK_PROBE';" | tail -n 1 | tr -d '\r')"

echo "== Fence old primary before promotion =="
fence_started_ms="$(now_millis)"
root_mysql "${PRIMARY}" -e "
  SET GLOBAL read_only=ON;
  SET GLOBAL super_read_only=ON;
"
fence_completed_ms="$(now_millis)"

old_read_only_after_fence="$(root_mysql "${PRIMARY}" --skip-column-names -e "SELECT @@GLOBAL.read_only;" | tr -d '\r')"
old_super_read_only_after_fence="$(root_mysql "${PRIMARY}" --skip-column-names -e "SELECT @@GLOBAL.super_read_only;" | tr -d '\r')"

set +e
app_mysql "${PRIMARY}" "${MYSQL_DATABASE}" -e   "INSERT INTO fencing_probe(label) VALUES ('OLD_AFTER_FENCE');"   >"${RUN_DIR}/old-primary-fenced-write.stdout" 2>"${OLD_FENCE_ERROR}"
old_write_after_fence_exit=$?
set -e

old_after_fence_count="$(root_mysql "${PRIMARY}" --skip-column-names "${MYSQL_DATABASE}" -e   "SELECT COUNT(*) FROM fencing_probe WHERE label='OLD_AFTER_FENCE';" | tail -n 1 | tr -d '\r')"

echo "== Promote replica only after fencing completed =="
promotion_started_ms="$(now_millis)"
root_mysql "${REPLICA}" -e "
  STOP REPLICA;
  RESET REPLICA ALL;
  SET GLOBAL super_read_only=OFF;
  SET GLOBAL read_only=OFF;
"
promotion_completed_ms="$(now_millis)"

new_read_only="$(root_mysql "${REPLICA}" --skip-column-names -e "SELECT @@GLOBAL.read_only;" | tr -d '\r')"
new_super_read_only="$(root_mysql "${REPLICA}" --skip-column-names -e "SELECT @@GLOBAL.super_read_only;" | tr -d '\r')"

app_mysql "${REPLICA}" "${MYSQL_DATABASE}" -e   "INSERT INTO fencing_probe(label) VALUES ('PROMOTED_WRITE');"
promoted_write_count="$(root_mysql "${REPLICA}" --skip-column-names "${MYSQL_DATABASE}" -e   "SELECT COUNT(*) FROM fencing_probe WHERE label='PROMOTED_WRITE';" | tail -n 1 | tr -d '\r')"

echo "== Verify old primary is still fenced after promotion =="
set +e
app_mysql "${PRIMARY}" "${MYSQL_DATABASE}" -e   "INSERT INTO fencing_probe(label) VALUES ('OLD_AFTER_PROMOTION');"   >"${RUN_DIR}/old-primary-post-promotion-write.stdout" 2>"${OLD_POST_PROMOTION_ERROR}"
old_write_after_promotion_exit=$?
set -e

old_after_promotion_count="$(root_mysql "${PRIMARY}" --skip-column-names "${MYSQL_DATABASE}" -e   "SELECT COUNT(*) FROM fencing_probe WHERE label='OLD_AFTER_PROMOTION';" | tail -n 1 | tr -d '\r')"

echo "== Reconnect old primary, keep it fenced, and rejoin it as replica =="
rejoin_started_ms="$(now_millis)"
docker network connect "${NETWORK}" "${PRIMARY}"

root_mysql "${PRIMARY}" -e "
  STOP REPLICA;
" >/dev/null 2>&1 || true

root_mysql "${PRIMARY}" -e "
  CHANGE REPLICATION SOURCE TO
    SOURCE_HOST='${REPLICA}',
    SOURCE_PORT=3306,
    SOURCE_USER='imticket_repl',
    SOURCE_PASSWORD='${REPLICATION_PASSWORD}',
    SOURCE_AUTO_POSITION=1,
    GET_SOURCE_PUBLIC_KEY=1;
  START REPLICA;
"

wait_for_replication_threads "${PRIMARY}"
wait_for_label "${PRIMARY}" PROMOTED_WRITE 1
rejoin_completed_ms="$(now_millis)"

root_mysql "${PRIMARY}" -e "SHOW REPLICA STATUS\G" >"${REJOIN_STATUS_FILE}" 2>&1 || true

old_read_only_after_rejoin="$(root_mysql "${PRIMARY}" --skip-column-names -e "SELECT @@GLOBAL.read_only;" | tr -d '\r')"
old_super_read_only_after_rejoin="$(root_mysql "${PRIMARY}" --skip-column-names -e "SELECT @@GLOBAL.super_read_only;" | tr -d '\r')"
old_rejoin_io="$(replica_status_value "${PRIMARY}" 'Replica_IO_Running')"
old_rejoin_sql="$(replica_status_value "${PRIMARY}" 'Replica_SQL_Running')"

old_gtid_after_rejoin="$(root_mysql "${PRIMARY}" --skip-column-names -e "SELECT @@GLOBAL.gtid_executed;" | tr -d '\r')"
new_gtid_after_rejoin="$(root_mysql "${REPLICA}" --skip-column-names -e "SELECT @@GLOBAL.gtid_executed;" | tr -d '\r')"

old_subset_new="$(root_mysql "${REPLICA}" --skip-column-names -e   "SELECT GTID_SUBSET('${old_gtid_after_rejoin}', '${new_gtid_after_rejoin}');" | tr -d '\r')"
new_subset_old="$(root_mysql "${REPLICA}" --skip-column-names -e   "SELECT GTID_SUBSET('${new_gtid_after_rejoin}', '${old_gtid_after_rejoin}');" | tr -d '\r')"

new_probe_count="$(root_mysql "${REPLICA}" --skip-column-names "${MYSQL_DATABASE}" -e   "SELECT COUNT(*) FROM fencing_probe;" | tail -n 1 | tr -d '\r')"
old_probe_count="$(root_mysql "${PRIMARY}" --skip-column-names "${MYSQL_DATABASE}" -e   "SELECT COUNT(*) FROM fencing_probe;" | tail -n 1 | tr -d '\r')"

fence_millis=$((fence_completed_ms - fence_started_ms))
promotion_millis=$((promotion_completed_ms - promotion_started_ms))
rejoin_millis=$((rejoin_completed_ms - rejoin_started_ms))

fence_completed_before_promotion=false
if (( fence_completed_ms <= promotion_started_ms )); then
  fence_completed_before_promotion=true
fi

status="OK"
if [[ "${old_read_only_before_fence}" != "0"       || "${old_super_read_only_before_fence}" != "0"       || "${pre_fence_rollback_write_exit}" != "0"       || "${rollback_probe_count}" != "0"       || "${old_read_only_after_fence}" != "1"       || "${old_super_read_only_after_fence}" != "1"       || "${old_write_after_fence_exit}" == "0"       || "${old_after_fence_count}" != "0"       || "${fence_completed_before_promotion}" != "true"       || "${new_read_only}" != "0"       || "${new_super_read_only}" != "0"       || "${promoted_write_count}" != "1"       || "${old_write_after_promotion_exit}" == "0"       || "${old_after_promotion_count}" != "0"       || "${old_read_only_after_rejoin}" != "1"       || "${old_super_read_only_after_rejoin}" != "1"       || "${old_rejoin_io}" != "Yes"       || "${old_rejoin_sql}" != "Yes"       || "${old_subset_new}" != "1"       || "${new_subset_old}" != "1"       || "${old_probe_count}" != "${new_probe_count}" ]]; then
  status="MISMATCH"
fi

cat >"${RESULT_FILE}" <<EOF
status=${status}
run_id=${RUN_ID}
scenario=network_partition_fence_promote_safe_rejoin
partition_replica_io=${partition_replica_io}
partition_replica_last_io_error=${partition_replica_last_io_error}
old_read_only_before_fence=${old_read_only_before_fence}
old_super_read_only_before_fence=${old_super_read_only_before_fence}
pre_fence_rollback_write_exit=${pre_fence_rollback_write_exit}
rollback_probe_count=${rollback_probe_count}
fence_millis=${fence_millis}
old_read_only_after_fence=${old_read_only_after_fence}
old_super_read_only_after_fence=${old_super_read_only_after_fence}
old_write_after_fence_exit=${old_write_after_fence_exit}
old_after_fence_count=${old_after_fence_count}
fence_completed_before_promotion=${fence_completed_before_promotion}
promotion_millis=${promotion_millis}
new_read_only=${new_read_only}
new_super_read_only=${new_super_read_only}
promoted_write_count=${promoted_write_count}
old_write_after_promotion_exit=${old_write_after_promotion_exit}
old_after_promotion_count=${old_after_promotion_count}
rejoin_millis=${rejoin_millis}
old_read_only_after_rejoin=${old_read_only_after_rejoin}
old_super_read_only_after_rejoin=${old_super_read_only_after_rejoin}
old_rejoin_io=${old_rejoin_io}
old_rejoin_sql=${old_rejoin_sql}
old_subset_new=${old_subset_new}
new_subset_old=${new_subset_old}
old_probe_count=${old_probe_count}
new_probe_count=${new_probe_count}
baseline_primary_gtid=${baseline_primary_gtid}
baseline_replica_gtid=${baseline_replica_gtid}
old_gtid_after_rejoin=${old_gtid_after_rejoin}
new_gtid_after_rejoin=${new_gtid_after_rejoin}
EOF

cat "${RESULT_FILE}"

if [[ "${status}" != "OK" ]]; then
  exit 1
fi
