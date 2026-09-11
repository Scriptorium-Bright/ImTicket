#!/usr/bin/env bash
set -euo pipefail

# 여러 hot seat에 대한 독립 one-shot burst 하나를 수행한다.
# app은 매 run force-recreate하고 fixture는 새로 만들지만, MySQL은 유지한다.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/scripts/test/load_env_defaults.sh"
load_imticket_env "${ROOT_DIR}/.env"

RUN_LABEL="${1:-}"
CONCURRENCY="${CONCURRENCY:-}"
SEAT_POOL_SIZE="${SEAT_POOL_SIZE:-4}"
SEATS_PER_REQUEST="${SEATS_PER_REQUEST:-1}"
SEAT_SELECTION_MODE="${SEAT_SELECTION_MODE:-round_robin}"
LOCK_STRATEGY="${LOCK_STRATEGY:-reentrant}"
LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS="${LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS:-1000}"
HIKARI_POOL_SIZE="${HIKARI_POOL_SIZE:-30}"
ADMISSION_PER_SEAT_PERMITS="${ADMISSION_PER_SEAT_PERMITS:-1}"
BASE_URL="${BASE_URL:-http://127.0.0.1:10080}"
MANAGEMENT_BASE_URL="${MANAGEMENT_BASE_URL:-http://127.0.0.1:10081}"
PROMETHEUS_CONFIG_FILE="${PROMETHEUS_CONFIG_FILE:-${ROOT_DIR}/monitoring/prometheus/prometheus-management-10081.yml}"
PROMETHEUS_BASE_URL="${PROMETHEUS_BASE_URL:-http://127.0.0.1:9090}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-10047}"
MYSQL_USER="${MYSQL_USER:-capstone}"
MYSQL_DATABASE="${MYSQL_DATABASE:-capstone}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-${MYSQL_LOCK_TEST_PASSWORD:-}}"
MYSQL_METRICS_USER="${MYSQL_METRICS_USER:-root}"
MYSQL_METRICS_PASSWORD="${MYSQL_METRICS_PASSWORD:-${MYSQL_ROOT_PASSWORD:-${MYSQL_PASSWORD:-}}}"
JWT_SECRET="${JWT_SECRET:-}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-15s}"
MAX_DURATION="${MAX_DURATION:-2m}"
SAMPLE_INTERVAL_SECONDS="${SAMPLE_INTERVAL_SECONDS:-1}"
IDLE_SECONDS="${IDLE_SECONDS:-10}"
TAIL_SECONDS="${TAIL_SECONDS:-30}"
CAPTURE_THREAD_DUMPS="${CAPTURE_THREAD_DUMPS:-false}"
BUILD_APP_IMAGE="${BUILD_APP_IMAGE:-true}"
RESULT_ROOT="${RESULT_ROOT:-${ROOT_DIR}/build/k6-results/multi-hot-seat}"
FIXTURE_SEED_SCRIPT="${FIXTURE_SEED_SCRIPT:-}"
K6_EXECUTION_MODE="${K6_EXECUTION_MODE:-local}"
REMOTE_K6_HOST="${REMOTE_K6_HOST:-}"
REMOTE_K6_REPO_DIR="${REMOTE_K6_REPO_DIR:-}"
REMOTE_K6_BASE_URL="${REMOTE_K6_BASE_URL:-}"

if [[ -z "${RUN_LABEL}" ]] || [[ ! "${RUN_LABEL}" =~ ^[a-z0-9][a-z0-9-]*$ ]]; then
  echo "첫 인자는 dry-run 또는 VU/반복을 나타내는 소문자 label이어야 합니다." >&2
  exit 1
fi
if [[ ! "${CONCURRENCY}" =~ ^[1-9][0-9]*$ ]]; then
  echo "CONCURRENCY는 양의 정수여야 합니다." >&2
  exit 1
fi
if [[ ! "${SEAT_POOL_SIZE}" =~ ^[1-9][0-9]*$ || ! "${SEATS_PER_REQUEST}" =~ ^[1-9][0-9]*$ ]]; then
  echo "SEAT_POOL_SIZE와 SEATS_PER_REQUEST는 양의 정수여야 합니다." >&2
  exit 1
fi
if (( SEATS_PER_REQUEST > SEAT_POOL_SIZE )); then
  echo "SEATS_PER_REQUEST는 SEAT_POOL_SIZE보다 클 수 없습니다." >&2
  exit 1
fi
if [[ "${SEAT_SELECTION_MODE}" != "round_robin" && "${SEAT_SELECTION_MODE}" != "random" ]]; then
  echo "SEAT_SELECTION_MODE는 round_robin 또는 random이어야 합니다." >&2
  exit 1
fi
if [[ "${SEAT_SELECTION_MODE}" == "random" && "${SEATS_PER_REQUEST}" != "1" ]]; then
  echo "SEAT_SELECTION_MODE=random은 SEATS_PER_REQUEST=1에서만 지원합니다." >&2
  exit 1
fi
if [[ -z "${FIXTURE_SEED_SCRIPT}" ]]; then
  if [[ "${SEAT_POOL_SIZE}" == "4" ]]; then
    FIXTURE_SEED_SCRIPT="${ROOT_DIR}/scripts/test/seed_pessimistic_lock_fixture.sh"
  else
    FIXTURE_SEED_SCRIPT="${ROOT_DIR}/scripts/test/seed_multi_hot_seat_fixture.sh"
  fi
fi
if [[ ! -f "${FIXTURE_SEED_SCRIPT}" ]]; then
  echo "fixture seed script를 찾지 못했습니다: ${FIXTURE_SEED_SCRIPT}" >&2
  exit 1
fi
if [[ "${LOCK_STRATEGY}" != "reentrant" && "${LOCK_STRATEGY}" != "pessimistic" ]]; then
  echo "이 runner는 reentrant 또는 pessimistic 전략만 지원합니다." >&2
  exit 1
fi
if [[ ! "${ADMISSION_PER_SEAT_PERMITS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "ADMISSION_PER_SEAT_PERMITS는 양의 정수여야 합니다." >&2
  exit 1
fi
if [[ ! "${LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS}" =~ ^[1-9][0-9]*$ ]] \
  || [[ ! "${HIKARI_POOL_SIZE}" =~ ^[1-9][0-9]*$ ]] \
  || [[ ! "${SAMPLE_INTERVAL_SECONDS}" =~ ^[1-9][0-9]*$ ]] \
  || [[ ! "${IDLE_SECONDS}" =~ ^[0-9]+$ ]] \
  || [[ ! "${TAIL_SECONDS}" =~ ^[0-9]+$ ]]; then
  echo "lock/Hikari/sample/idle/tail 값 형식이 올바르지 않습니다." >&2
  exit 1
fi
if [[ -z "${MYSQL_PASSWORD}" || -z "${JWT_SECRET}" ]]; then
  echo "MYSQL_PASSWORD와 JWT_SECRET을 설정해야 합니다." >&2
  exit 1
fi
if [[ ! "${BUILD_APP_IMAGE}" =~ ^(true|false)$ ]]; then
  echo "BUILD_APP_IMAGE는 true 또는 false여야 합니다." >&2
  exit 1
fi
if [[ ! "${CAPTURE_THREAD_DUMPS}" =~ ^(true|false)$ ]]; then
  echo "CAPTURE_THREAD_DUMPS는 true 또는 false여야 합니다." >&2
  exit 1
fi
if [[ ! "${K6_EXECUTION_MODE}" =~ ^(local|remote)$ ]]; then
  echo "K6_EXECUTION_MODE는 local 또는 remote여야 합니다." >&2
  exit 1
fi
if [[ "${K6_EXECUTION_MODE}" == "remote" ]]; then
  if [[ -z "${REMOTE_K6_HOST}" || -z "${REMOTE_K6_REPO_DIR}" || "${REMOTE_K6_REPO_DIR}" != /* ]]; then
    echo "remote mode는 절대 경로 REMOTE_K6_REPO_DIR와 REMOTE_K6_HOST를 설정해야 합니다." >&2
    exit 1
  fi
  if [[ -z "${REMOTE_K6_BASE_URL}" ]]; then
    if command -v tailscale > /dev/null 2>&1; then
      REMOTE_K6_BASE_URL="http://$(tailscale ip -4 | head -n 1):10080"
    else
      echo "remote mode는 REMOTE_K6_BASE_URL 또는 Mac의 tailscale 명령이 필요합니다." >&2
      exit 1
    fi
  fi
  if [[ ! "${REMOTE_K6_BASE_URL}" =~ ^http://100\.[0-9.]+:10080$ ]]; then
    echo "REMOTE_K6_BASE_URL은 Mac Tailscale business 주소(http://100.x.x.x:10080)여야 합니다." >&2
    exit 1
  fi
  if [[ "${CAPTURE_THREAD_DUMPS}" == "true" ]]; then
    echo "remote mode에서는 CAPTURE_THREAD_DUMPS=false만 허용합니다." >&2
    exit 1
  fi
fi
if [[ ! -f "${PROMETHEUS_CONFIG_FILE}" ]]; then
  echo "Prometheus 설정 파일을 찾지 못했습니다: ${PROMETHEUS_CONFIG_FILE}" >&2
  exit 1
fi

required_commands=(curl docker jq mysql)
if [[ "${K6_EXECUTION_MODE}" == "local" ]]; then
  required_commands+=(k6)
else
  required_commands+=(ssh scp shasum)
fi
for required_command in "${required_commands[@]}"; do
  if ! command -v "${required_command}" > /dev/null 2>&1; then
    echo "필수 명령을 찾지 못했습니다: ${required_command}" >&2
    exit 1
  fi
done

RUN_ID="${RUN_LABEL}-$(date -u +%Y%m%dT%H%M%SZ)"
RUN_DIR="${RESULT_ROOT}/${RUN_ID}"
MANIFEST_FILE="${RUN_DIR}/manifest.txt"
APP_METRICS_FILE="${RUN_DIR}/app-metrics.tsv"
MYSQL_METRICS_FILE="${RUN_DIR}/mysql-metrics.tsv"
DOCKER_STATS_FILE="${RUN_DIR}/docker-stats.tsv"
OBSERVATION_LOSS_FILE="${RUN_DIR}/observation-loss.tsv"
THREAD_DUMP_EVENTS_FILE="${RUN_DIR}/thread-dump-events.tsv"
FIXTURE_FILE="${RUN_DIR}/fixture.tsv"
APP_LOG_FILE="${RUN_DIR}/app-container.log"
MYSQL_LOG_FILE="${RUN_DIR}/mysql-container.log"
K6_CONSOLE_FILE="${RUN_DIR}/k6-console.log"
K6_SUMMARY_FILE="${RUN_DIR}/k6-summary.json"
REMOTE_K6_MANIFEST_FILE="${RUN_DIR}/remote-k6-manifest.txt"
POST_RUN_DB_FILE="${RUN_DIR}/post-run-fixture-state.tsv"
POST_RUN_INSPECT_FILE="${RUN_DIR}/post-run-container-state.txt"
SUMMARY_FILE="${RUN_DIR}/run-summary.txt"

app_metrics_collector_pid=""
docker_stats_collector_pid=""
app_log_pid=""
app_container=""
mysql_container=""
redis_container=""
remote_k6_run_dir=""
local_k6_script_sha256=""

if [[ "${K6_EXECUTION_MODE}" == "remote" ]]; then
  remote_k6_run_dir="${REMOTE_K6_REPO_DIR%/}/build/k6-results/remote-multi-hot-seat/${RUN_ID}"
  local_k6_script_sha256="$(shasum -a 256 "${ROOT_DIR}/scripts/test/03-multi-seat-distributed-run.js" | awk '{print $1}')"
fi

mkdir -p "${RUN_DIR}"
printf 'timestamp\tsource\treason\n' > "${OBSERVATION_LOSS_FILE}"
printf 'timestamp\tlabel\tcontainer_id\tsignal_result\n' > "${THREAD_DUMP_EVENTS_FILE}"
printf 'timestamp\ttomcat_busy\ttomcat_current\ttomcat_config_max\thikari_active\thikari_pending\thikari_max\thikari_acquire_max_seconds\thikari_connection_timeouts\tjvm_threads_live\tjvm_threads_peak\tjvm_threads_blocked\tjvm_threads_runnable\tjvm_threads_waiting\tjvm_threads_timed_waiting\tjvm_gc_pause_seconds_sum\tjvm_gc_overhead\tjvm_heap_used_bytes\tjvm_heap_max_bytes\tprocess_resident_memory_bytes\tprocess_cpu_usage\tsystem_cpu_usage\n' > "${APP_METRICS_FILE}"
printf 'timestamp\tdata_lock_waits\tthreads_connected\tthreads_running\tmax_used_connections\tinnodb_row_lock_current_waits\n' > "${MYSQL_METRICS_FILE}"
printf 'timestamp\tcontainer_id\tcontainer_name\tcpu_percent\tmemory_usage_limit\tnet_io\tblock_io\tpids\n' > "${DOCKER_STATS_FILE}"

timestamp() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

record_loss() {
  printf '%s\t%s\t%s\n' "$(timestamp)" "$1" "$2" >> "${OBSERVATION_LOSS_FILE}"
}

shell_quote() {
  printf '%q' "$1"
}

run_remote_k6() {
  local remote_repo_q remote_run_dir_q remote_base_url_q remote_tailnet_ip_q expected_sha_q
  local remote_script remote_status=0 summary_copy_status=0 manifest_copy_status=0
  local remote_tailnet_ip

  remote_tailnet_ip="${REMOTE_K6_BASE_URL#http://}"
  remote_tailnet_ip="${remote_tailnet_ip%%:*}"
  printf -v remote_repo_q '%q' "${REMOTE_K6_REPO_DIR}"
  printf -v remote_run_dir_q '%q' "${remote_k6_run_dir}"
  printf -v remote_base_url_q '%q' "${REMOTE_K6_BASE_URL}"
  printf -v remote_tailnet_ip_q '%q' "${remote_tailnet_ip}"
  printf -v expected_sha_q '%q' "${local_k6_script_sha256}"

  remote_script="$(printf '%s\n' \
    'set -euo pipefail' \
    "REMOTE_REPO_DIR=${remote_repo_q}" \
    "REMOTE_RUN_DIR=${remote_run_dir_q}" \
    "BASE_URL=${remote_base_url_q}" \
    "REMOTE_TAILNET_IP=${remote_tailnet_ip_q}" \
    "EXPECTED_SCRIPT_SHA256=${expected_sha_q}" \
    'IFS= read -r JWT_SECRET' \
    'export JWT_SECRET' \
    'cd "$REMOTE_REPO_DIR"' \
    'git pull --ff-only' \
    'actual_script_sha256="$(sha256sum scripts/test/03-multi-seat-distributed-run.js | awk '\''{print $1}'\'')"' \
    'if [[ "$actual_script_sha256" != "$EXPECTED_SCRIPT_SHA256" ]]; then' \
    '  echo "OCI k6 script revision does not match Mac: expected=$EXPECTED_SCRIPT_SHA256 actual=$actual_script_sha256" >&2' \
    '  exit 1' \
    'fi' \
    'tailscale_ping_output="$(tailscale ping "$REMOTE_TAILNET_IP")"' \
    'printf "%s\n" "$tailscale_ping_output"' \
    'grep -q "direct" <<< "$tailscale_ping_output"' \
    'nc -vz "$REMOTE_TAILNET_IP" 10080' \
    'mkdir -p "$REMOTE_RUN_DIR"' \
    '{' \
    '  printf "remote_git_revision=%s\n" "$(git rev-parse HEAD)"' \
    '  printf "remote_script_sha256=%s\n" "$actual_script_sha256"' \
    '  printf "remote_base_url=%s\n" "$BASE_URL"' \
    '  printf "remote_tailscale_ping=%s\n" "$tailscale_ping_output"' \
    '} > "$REMOTE_RUN_DIR/remote-manifest.txt"')"
  remote_script+=$'\n'
  remote_script+=$'docker run --rm \\\n'
  remote_script+=$'  --network host \\\n'
  remote_script+=$'  --user "$(id -u):$(id -g)" \\\n'
  remote_script+=$'  -v "$REMOTE_REPO_DIR/scripts/test:/scripts:ro" \\\n'
  remote_script+=$'  -v "$REMOTE_RUN_DIR:/results" \\\n'
  remote_script+=$'  -e BASE_URL \\\n'
  remote_script+=$'  -e JWT_SECRET \\\n'
  remote_script+="  -e PT_ID=$(shell_quote "${performance_time_id}") \\\n"
  remote_script+="  -e SEAT_IDS=$(shell_quote "${seat_ids_csv}") \\\n"
  remote_script+="  -e SEAT_POOL_SIZE=$(shell_quote "${SEAT_POOL_SIZE}") \\\n"
  remote_script+="  -e SEATS_PER_REQUEST=$(shell_quote "${SEATS_PER_REQUEST}") \\\n"
  remote_script+="  -e CONCURRENCY=$(shell_quote "${CONCURRENCY}") \\\n"
  remote_script+=$'  -e BURST_DELAY_SECONDS=10 \\\n'
  remote_script+="  -e REQUEST_TIMEOUT=$(shell_quote "${REQUEST_TIMEOUT}") \\\n"
  remote_script+="  -e MAX_DURATION=$(shell_quote "${MAX_DURATION}") \\\n"
  remote_script+=$'  grafana/k6:1.6.1 \\\n'
  remote_script+=$'  run --summary-export /results/k6-summary.json \\\n'
  remote_script+=$'  /scripts/03-multi-seat-distributed-run.js\n'

  printf '%s\n' "${JWT_SECRET}" \
    | ssh "${REMOTE_K6_HOST}" "bash -lc $(shell_quote "${remote_script}")" \
    || remote_status=$?

  scp "${REMOTE_K6_HOST}:${remote_k6_run_dir}/k6-summary.json" "${K6_SUMMARY_FILE}" \
    || summary_copy_status=$?
  scp "${REMOTE_K6_HOST}:${remote_k6_run_dir}/remote-manifest.txt" "${REMOTE_K6_MANIFEST_FILE}" \
    || manifest_copy_status=$?

  if (( summary_copy_status != 0 || manifest_copy_status != 0 )); then
    echo "OCI k6 결과 파일을 회수하지 못했습니다: ${remote_k6_run_dir}" >&2
    return 1
  fi
  return "${remote_status}"
}

cleanup() {
  local pid
  for pid in "${app_metrics_collector_pid}" "${docker_stats_collector_pid}" "${app_log_pid}"; do
    if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
      kill "${pid}" 2>/dev/null || true
      wait "${pid}" 2>/dev/null || true
    fi
  done
}
trap cleanup EXIT INT TERM

metric_max() {
  local payload="$1"
  local metric_name="$2"
  awk -v name="${metric_name}" '
    ($1 == name || index($1, name "{") == 1) {
      value = $NF + 0
      if (!found || value > max) { max = value; found = 1 }
    }
    END { if (found) print max; else print "NA" }
  ' <<< "${payload}"
}

parse_prometheus_snapshot() {
  awk '
    BEGIN {
      busy=current=tomcat_max=active=pending=maximum=acquire=timeouts="NA"
      jvm_live=jvm_peak=jvm_blocked=jvm_runnable=jvm_waiting=jvm_timed_waiting="NA"
      gc_sum=gc_overhead=heap_used=heap_max=resident=process_cpu=system_cpu="NA"
    }
    /^tomcat_threads_busy_threads/ { busy = $NF }
    /^tomcat_threads_current_threads/ { current = $NF }
    /^tomcat_threads_config_max_threads/ { tomcat_max = $NF }
    /^hikaricp_connections_active/ { active = $NF }
    /^hikaricp_connections_pending/ { pending = $NF }
    /^hikaricp_connections_max/ { maximum = $NF }
    /^hikaricp_connections_acquire_seconds_max/ { acquire = $NF }
    /^hikaricp_connections_timeout_total/ { timeouts = $NF }
    /^jvm_threads_live_threads/ { jvm_live = $NF }
    /^jvm_threads_peak_threads/ { jvm_peak = $NF }
    /^jvm_threads_states_threads/ {
      if (index($1, "state=\"blocked\"") > 0) jvm_blocked = $NF
      if (index($1, "state=\"runnable\"") > 0) jvm_runnable = $NF
      if (index($1, "state=\"waiting\"") > 0) jvm_waiting = $NF
      if (index($1, "state=\"timed-waiting\"") > 0) jvm_timed_waiting = $NF
    }
    /^jvm_gc_pause_seconds_sum/ { gc_sum += $NF; gc_sum_seen = 1 }
    /^jvm_gc_overhead/ { gc_overhead = $NF }
    /^jvm_memory_used_bytes/ {
      if (index($1, "area=\"heap\"") > 0) { heap_used += $NF; heap_used_seen = 1 }
    }
    /^jvm_memory_max_bytes/ {
      if (index($1, "area=\"heap\"") > 0 && ($NF + 0) > 0) {
        if (heap_max == "NA" || ($NF + 0) > (heap_max + 0)) heap_max = $NF
      }
    }
    /^process_resident_memory_bytes/ { resident = $NF }
    /^process_cpu_usage/ { process_cpu = $NF }
    /^system_cpu_usage/ { system_cpu = $NF }
    END {
      if (!gc_sum_seen) gc_sum = "NA"
      if (!heap_used_seen) heap_used = "NA"
      print busy "\t" current "\t" tomcat_max "\t" active "\t" pending "\t" maximum "\t" acquire "\t" timeouts "\t" jvm_live "\t" jvm_peak "\t" jvm_blocked "\t" jvm_runnable "\t" jvm_waiting "\t" jvm_timed_waiting "\t" gc_sum "\t" gc_overhead "\t" heap_used "\t" heap_max "\t" resident "\t" process_cpu "\t" system_cpu
    }
  '
}

mysql_query() {
  MYSQL_PWD="${MYSQL_METRICS_PASSWORD}" mysql \
    --host="${MYSQL_HOST}" \
    --port="${MYSQL_PORT}" \
    --user="${MYSQL_METRICS_USER}" \
    --protocol=tcp \
    --batch \
    --skip-column-names \
    --connect-timeout=1 \
    "${MYSQL_DATABASE}" \
    --execute="$1"
}

sample_once() {
  local now payload prometheus_values mysql_values
  local busy current tomcat_max active pending maximum acquire connection_timeouts
  local jvm_live jvm_peak jvm_blocked jvm_runnable jvm_waiting jvm_timed_waiting
  local gc_sum gc_overhead heap_used heap_max resident process_cpu system_cpu
  local lock_waits threads_connected threads_running max_used row_lock_waits

  now="$(timestamp)"
  payload="$(curl -fsS --connect-timeout 1 --max-time 1 "${MANAGEMENT_BASE_URL}/actuator/prometheus" 2>/dev/null || true)"
  if [[ -z "${payload}" ]]; then
    record_loss actuator_prometheus no_response
    busy=NA; current=NA; tomcat_max=NA; active=NA; pending=NA; maximum=NA; acquire=NA; connection_timeouts=NA
    jvm_live=NA; jvm_peak=NA; jvm_blocked=NA; jvm_runnable=NA; jvm_waiting=NA; jvm_timed_waiting=NA
    gc_sum=NA; gc_overhead=NA; heap_used=NA; heap_max=NA; resident=NA; process_cpu=NA; system_cpu=NA
  else
    prometheus_values="$(parse_prometheus_snapshot <<< "${payload}")"
    IFS=$'\t' read -r busy current tomcat_max active pending maximum acquire connection_timeouts \
      jvm_live jvm_peak jvm_blocked jvm_runnable jvm_waiting jvm_timed_waiting \
      gc_sum gc_overhead heap_used heap_max resident process_cpu system_cpu <<< "${prometheus_values}"
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "${now}" "${busy}" "${current}" "${tomcat_max}" "${active}" "${pending}" "${maximum}" "${acquire}" "${connection_timeouts}" \
    "${jvm_live}" "${jvm_peak}" "${jvm_blocked}" "${jvm_runnable}" "${jvm_waiting}" "${jvm_timed_waiting}" \
    "${gc_sum}" "${gc_overhead}" "${heap_used}" "${heap_max}" "${resident}" "${process_cpu}" "${system_cpu}" >> "${APP_METRICS_FILE}"

  mysql_values="$(mysql_query "
    SELECT
      (SELECT COUNT(*) FROM performance_schema.data_lock_waits),
      MAX(CASE WHEN VARIABLE_NAME = 'Threads_connected' THEN VARIABLE_VALUE END),
      MAX(CASE WHEN VARIABLE_NAME = 'Threads_running' THEN VARIABLE_VALUE END),
      MAX(CASE WHEN VARIABLE_NAME = 'Max_used_connections' THEN VARIABLE_VALUE END),
      MAX(CASE WHEN VARIABLE_NAME = 'Innodb_row_lock_current_waits' THEN VARIABLE_VALUE END)
    FROM performance_schema.global_status;
  " 2>/dev/null || true)"
  if [[ "${mysql_values}" =~ ^[0-9]+$'\t'([0-9]+|NULL)$'\t'([0-9]+|NULL)$'\t'([0-9]+|NULL)$'\t'([0-9]+|NULL)$ ]]; then
    IFS=$'\t' read -r lock_waits threads_connected threads_running max_used row_lock_waits <<< "${mysql_values}"
  else
    record_loss mysql_metrics query_failed
    lock_waits=NA; threads_connected=NA; threads_running=NA; max_used=NA; row_lock_waits=NA
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
    "${now}" "${lock_waits}" "${threads_connected}" "${threads_running}" "${max_used}" "${row_lock_waits}" >> "${MYSQL_METRICS_FILE}"
}

app_metrics_collector_loop() {
  while :; do
    sample_once
    sleep "${SAMPLE_INTERVAL_SECONDS}"
  done
}

docker_stats_collector_loop() {
  local now docker_values
  while :; do
    now="$(timestamp)"
    docker_values="$(docker stats --no-stream \
      --format '{{.ID}}\t{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}\t{{.BlockIO}}\t{{.PIDs}}' \
      "${app_container}" "${mysql_container}" "${redis_container}" 2>> "${RUN_DIR}/docker-stats.stderr" || true)"
    if [[ -z "${docker_values}" ]]; then
      record_loss docker_stats no_response
    else
      awk -v now="${now}" 'BEGIN { FS=OFS="\t" } { print now, $0 }' <<< "${docker_values}" >> "${DOCKER_STATS_FILE}"
    fi
    sleep "${SAMPLE_INTERVAL_SECONDS}"
  done
}

wait_until_healthy() {
  local attempt business_status metrics observed_hikari
  for attempt in $(seq 1 90); do
    business_status="$(curl -sS --connect-timeout 1 --max-time 2 -o "${RUN_DIR}/business-readiness-${attempt}.body" -w '%{http_code}' "${BASE_URL}/api/seats/0" 2>/dev/null || true)"
    if [[ "${business_status}" != "000" ]] \
      && curl -fsS --connect-timeout 1 --max-time 2 "${MANAGEMENT_BASE_URL}/actuator/health" > "${RUN_DIR}/management-health-${attempt}.json" 2>/dev/null; then
      metrics="$(curl -fsS --connect-timeout 1 --max-time 2 "${MANAGEMENT_BASE_URL}/actuator/prometheus" 2>/dev/null || true)"
      observed_hikari="$(metric_max "${metrics}" hikaricp_connections_max)"
      if [[ "${observed_hikari}" == "${HIKARI_POOL_SIZE}" ]]; then
        printf '%s\n' "${metrics}" > "${RUN_DIR}/preflight-prometheus.txt"
        return 0
      fi
    fi
    sleep 1
  done
  echo "business/management health 또는 Hikari max=${HIKARI_POOL_SIZE} 확인에 실패했습니다." >&2
  return 1
}

wait_until_prometheus_target() {
  local attempt
  for attempt in $(seq 1 30); do
    if curl -fsS --connect-timeout 1 --max-time 2 "${PROMETHEUS_BASE_URL}/api/v1/targets" > "${RUN_DIR}/prometheus-targets-${attempt}.json" 2>/dev/null \
      && jq -e '
        any(.data.activeTargets[]?; .scrapeUrl == "http://app:10081/actuator/prometheus" and .health == "up")
      ' "${RUN_DIR}/prometheus-targets-${attempt}.json" > /dev/null; then
      return 0
    fi
    sleep 1
  done
  echo "Prometheus가 app:10081 management target을 UP으로 수집하는지 확인하지 못했습니다." >&2
  return 1
}

peak_column() {
  local file="$1"
  local column="$2"
  awk -F '\t' -v column="${column}" '
    NR > 1 && $column != "NA" && $column != "" {
      value = $column + 0
      if (!seen || value > max) { max = value; seen = 1 }
    }
    END { if (seen) print max; else print "NA" }
  ' "${file}"
}

line_count_without_header() {
  local file="$1"
  awk 'END { print (NR > 0 ? NR - 1 : 0) }' "${file}"
}

metric_value() {
  local metric_name="$1"
  jq -r --arg metric_name "${metric_name}" '
    (.metrics[$metric_name] // {}) | (.count // .value // 0)
  ' "${K6_SUMMARY_FILE}"
}

summarize_k6() {
  if [[ ! -s "${K6_SUMMARY_FILE}" ]]; then
    printf 'k6_summary=missing\n'
    return
  fi
  jq -r '
    .metrics as $metrics |
    [
      "k6_http_reqs=" + (($metrics.http_reqs.count // "NA") | tostring),
      "k6_http_req_failed_rate=" + (($metrics.http_req_failed.value // "NA") | tostring),
      "k6_http_req_duration_p95_ms=" + (($metrics.http_req_duration["p(95)"] // "NA") | tostring),
      "k6_http_req_duration_p99_ms=" + (($metrics.http_req_duration["p(99)"] // "NA") | tostring),
      "k6_reservation_duration_p95_ms=" + (($metrics.multi_hot_reservation_duration["p(95)"] // "NA") | tostring),
      "k6_request_start_lag_p95_ms=" + (($metrics.multi_hot_request_start_lag["p(95)"] // "NA") | tostring)
    ] | .[]
  ' "${K6_SUMMARY_FILE}"
  jq -r '
    .metrics
    | to_entries[]
    | select(.key | startswith("multi_hot_outcome_bucket") or startswith("multi_hot_reservation_") or startswith("multi_hot_429_") or startswith("multi_hot_transport_") or startswith("multi_hot_unexpected_http") or startswith("multi_hot_conflict_"))
    | "k6_metric_" + .key + "=" + ((.value.count // .value.value // "NA") | tostring)
  ' "${K6_SUMMARY_FILE}"
}

printf 'run_id=%s\nrun_label=%s\nstarted_at=%s\n' "${RUN_ID}" "${RUN_LABEL}" "$(timestamp)" > "${MANIFEST_FILE}"
{
  printf 'configured_concurrency=%s\n' "${CONCURRENCY}"
  printf 'configured_seat_pool_size=%s\n' "${SEAT_POOL_SIZE}"
  printf 'configured_seats_per_request=%s\n' "${SEATS_PER_REQUEST}"
  printf 'configured_seat_selection_mode=%s\n' "${SEAT_SELECTION_MODE}"
  printf 'configured_lock_strategy=%s\n' "${LOCK_STRATEGY}"
  printf 'configured_lock_reentrant_wait_timeout_millis=%s\n' "${LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS}"
  printf 'configured_hikari_pool_size=%s\n' "${HIKARI_POOL_SIZE}"
  printf 'configured_admission_per_seat_permits=%s\n' "${ADMISSION_PER_SEAT_PERMITS}"
  printf 'configured_mysql_metrics_user=%s\n' "${MYSQL_METRICS_USER}"
  printf 'configured_fixture_seed_script=%s\n' "${FIXTURE_SEED_SCRIPT}"
  printf 'configured_business_base_url=%s\n' "${BASE_URL}"
  printf 'configured_management_base_url=%s\n' "${MANAGEMENT_BASE_URL}"
  printf 'configured_prometheus_base_url=%s\n' "${PROMETHEUS_BASE_URL}"
  printf 'configured_prometheus_config_file=%s\n' "${PROMETHEUS_CONFIG_FILE}"
  printf 'configured_request_timeout=%s\n' "${REQUEST_TIMEOUT}"
  printf 'configured_capture_thread_dumps=%s\n' "${CAPTURE_THREAD_DUMPS}"
  printf 'configured_build_app_image=%s\n' "${BUILD_APP_IMAGE}"
  printf 'configured_k6_execution_mode=%s\n' "${K6_EXECUTION_MODE}"
  if [[ "${K6_EXECUTION_MODE}" == "remote" ]]; then
    printf 'configured_remote_k6_host=%s\n' "${REMOTE_K6_HOST}"
    printf 'configured_remote_k6_repo_dir=%s\n' "${REMOTE_K6_REPO_DIR}"
    printf 'configured_remote_k6_base_url=%s\n' "${REMOTE_K6_BASE_URL}"
    printf 'configured_remote_k6_run_dir=%s\n' "${remote_k6_run_dir}"
    printf 'local_k6_script_sha256=%s\n' "${local_k6_script_sha256}"
  fi
  printf 'git_revision=%s\n' "$(git -C "${ROOT_DIR}" rev-parse HEAD)"
  printf '\n[git_status]\n'
  git -C "${ROOT_DIR}" status --short
} >> "${MANIFEST_FILE}"

if [[ "${BUILD_APP_IMAGE}" == "true" ]]; then
  docker compose -f "${ROOT_DIR}/docker-compose.yml" build app > "${RUN_DIR}/compose-build.log" 2>&1
else
  docker image inspect imticket-app --format 'skipped_build_existing_image={{.Id}}' > "${RUN_DIR}/compose-build.log" 2>&1
fi

env \
  "LOCK_STRATEGY=${LOCK_STRATEGY}" \
  "LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS=${LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS}" \
  "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=${HIKARI_POOL_SIZE}" \
  "RESERVATION_ADMISSION_PER_SEAT_PERMITS=${ADMISSION_PER_SEAT_PERMITS}" \
  "MANAGEMENT_SERVER_PORT=10081" \
  "MANAGEMENT_HOST_PORT=10081" \
  "MANAGEMENT_BIND_ADDRESS=127.0.0.1" \
  docker compose -f "${ROOT_DIR}/docker-compose.yml" up -d --force-recreate --no-deps app > "${RUN_DIR}/compose-up.log" 2>&1

app_container="$(docker compose -f "${ROOT_DIR}/docker-compose.yml" ps -q app)"
mysql_container="$(docker compose -f "${ROOT_DIR}/docker-compose.yml" ps -q mysql)"
redis_container="$(docker compose -f "${ROOT_DIR}/docker-compose.yml" ps -q redis)"
if [[ -z "${app_container}" || -z "${mysql_container}" || -z "${redis_container}" ]]; then
  echo "app/mysql/redis container ID를 확인하지 못했습니다." >&2
  exit 1
fi
wait_until_healthy

PROMETHEUS_CONFIG_FILE="${PROMETHEUS_CONFIG_FILE}" \
  docker compose -f "${ROOT_DIR}/docker-compose.yml" up -d --force-recreate --no-deps prometheus > "${RUN_DIR}/prometheus-compose-up.log" 2>&1
wait_until_prometheus_target

{
  printf '\n[container_after_recreate]\n'
  docker inspect --format 'app_id={{.Id}} image={{.Image}} started_at={{.State.StartedAt}} restart_count={{.RestartCount}} oom_killed={{.State.OOMKilled}}' "${app_container}"
  docker inspect --format 'mysql_id={{.Id}} image={{.Image}} started_at={{.State.StartedAt}} restart_count={{.RestartCount}} oom_killed={{.State.OOMKilled}}' "${mysql_container}"
  docker inspect --format 'redis_id={{.Id}} image={{.Image}} started_at={{.State.StartedAt}} restart_count={{.RestartCount}} oom_killed={{.State.OOMKilled}}' "${redis_container}"
} >> "${MANIFEST_FILE}"

fixture_output="$(MYSQL_HOST="${MYSQL_HOST}" MYSQL_PORT="${MYSQL_PORT}" MYSQL_USER="${MYSQL_USER}" \
  MYSQL_DATABASE="${MYSQL_DATABASE}" MYSQL_PASSWORD="${MYSQL_PASSWORD}" \
  SEAT_COUNT="${SEAT_POOL_SIZE}" \
  "${FIXTURE_SEED_SCRIPT}")"
printf '%s\n' "${fixture_output}" > "${FIXTURE_FILE}"
fixture_line="$(printf '%s\n' "${fixture_output}" | awk -v expected="${SEAT_POOL_SIZE}" 'NF == expected + 2 && $2 ~ /^[1-9][0-9]*$/ { line = $0 } END { print line }')"
wallet_address="$(awk '{print $1}' <<< "${fixture_line}")"
performance_time_id="$(awk '{print $2}' <<< "${fixture_line}")"
seat_ids_csv="$(awk '{for (i = 3; i <= NF; i++) { if (i > 3) printf ","; printf "%s", $i } printf "\n"}' <<< "${fixture_line}")"
if [[ "${wallet_address}" != "0xLoadTestUser" ]] \
  || [[ ! "${performance_time_id}" =~ ^[1-9][0-9]*$ ]] \
  || [[ "$(awk -v expected="${SEAT_POOL_SIZE}" '{ok = (NF == expected + 2); for (i = 3; i <= NF; i++) if ($i !~ /^[1-9][0-9]*$/) ok = 0; print ok}' <<< "${fixture_line}")" != "1" ]] \
  || [[ ! "${seat_ids_csv}" =~ ^[1-9][0-9]*(,[1-9][0-9]*)*$ ]]; then
  echo "새 fixture 출력 형식 또는 값이 올바르지 않습니다: ${fixture_line}" >&2
  exit 1
fi
printf '\nfixture_performance_time_id=%s\nfixture_seat_ids=%s\n' "${performance_time_id}" "${seat_ids_csv}" >> "${MANIFEST_FILE}"

docker logs --timestamps -f "${app_container}" > "${APP_LOG_FILE}" 2>&1 &
app_log_pid=$!
app_metrics_collector_loop &
app_metrics_collector_pid=$!
docker_stats_collector_loop &
docker_stats_collector_pid=$!

sleep "${IDLE_SECONDS}"
idle_loss_count="$(line_count_without_header "${OBSERVATION_LOSS_FILE}")"
if (( idle_loss_count > 0 )); then
  echo "idle 구간에서 관측 손실 ${idle_loss_count}건이 발생했습니다. 부하를 시작하지 않습니다." >&2
  exit 1
fi

if [[ "${K6_EXECUTION_MODE}" == "local" ]]; then
  start_at_epoch_ms="$(( $(date +%s) * 1000 + 12000 ))"
  printf 'scheduled_burst_start_at_epoch_ms=%s\n' "${start_at_epoch_ms}" >> "${MANIFEST_FILE}"
else
  printf 'remote_burst_delay_seconds=10\n' >> "${MANIFEST_FILE}"
fi

set +e
if [[ "${K6_EXECUTION_MODE}" == "local" ]]; then
  k6 run \
    -e "BASE_URL=${BASE_URL}" \
    -e "PT_ID=${performance_time_id}" \
    -e "SEAT_IDS=${seat_ids_csv}" \
    -e "SEAT_POOL_SIZE=${SEAT_POOL_SIZE}" \
    -e "SEATS_PER_REQUEST=${SEATS_PER_REQUEST}" \
    -e "SEAT_SELECTION_MODE=${SEAT_SELECTION_MODE}" \
    -e "CONCURRENCY=${CONCURRENCY}" \
    -e "START_AT_EPOCH_MS=${start_at_epoch_ms}" \
    -e "JWT_SECRET=${JWT_SECRET}" \
    -e "REQUEST_TIMEOUT=${REQUEST_TIMEOUT}" \
    -e "MAX_DURATION=${MAX_DURATION}" \
    --summary-export "${K6_SUMMARY_FILE}" \
    "${ROOT_DIR}/scripts/test/03-multi-seat-distributed-run.js" > "${K6_CONSOLE_FILE}" 2>&1 &
else
  run_remote_k6 > "${K6_CONSOLE_FILE}" 2>&1 &
fi
k6_pid=$!
set -e

if [[ "${CAPTURE_THREAD_DUMPS}" == "true" ]]; then
  sleep 14
  for dump_label in t_plus_2_seconds t_plus_3_seconds; do
    if docker kill --signal=QUIT "${app_container}" > /dev/null 2>&1; then
      printf '%s\t%s\t%s\tsent\n' "$(timestamp)" "${dump_label}" "${app_container}" >> "${THREAD_DUMP_EVENTS_FILE}"
    else
      printf '%s\t%s\t%s\tfailed\n' "$(timestamp)" "${dump_label}" "${app_container}" >> "${THREAD_DUMP_EVENTS_FILE}"
      record_loss jvm_thread_dump signal_failed
    fi
    [[ "${dump_label}" == "t_plus_2_seconds" ]] && sleep 1
  done
fi

set +e
wait "${k6_pid}"
k6_status=$?
set -e
sleep "${TAIL_SECONDS}"

cleanup
app_metrics_collector_pid=""
docker_stats_collector_pid=""
app_log_pid=""

if ! docker logs --timestamps "${mysql_container}" > "${MYSQL_LOG_FILE}" 2>&1; then
  record_loss mysql_container_log command_failed
fi
if ! docker inspect --format 'app_restart_count={{.RestartCount}} app_oom_killed={{.State.OOMKilled}} app_status={{.State.Status}}' "${app_container}" > "${POST_RUN_INSPECT_FILE}"; then
  record_loss docker_inspect_post_run command_failed
fi
if ! mysql_query "
  SELECT s.id, s.seat_status, s.is_reservation, s.version, COUNT(rs.id) AS reserved_seat_rows
  FROM Seat s
  LEFT JOIN ReservedSeat rs ON rs.seat_id = s.id
  WHERE s.id IN (${seat_ids_csv})
  GROUP BY s.id, s.seat_status, s.is_reservation, s.version
  ORDER BY s.id;
" > "${POST_RUN_DB_FILE}" 2>&1; then
  record_loss post_run_fixture_verification query_failed
fi

attempts=NA
successes=NA
conflicts_expected=NA
conflicts_other=NA
admission_rejected=NA
lock_timeout=NA
other_429=NA
five_xx=NA
transport=NA
unexpected=NA
request_contract_status=incomplete
observation_contract_status=incomplete
contract_status=incomplete
if [[ -s "${K6_SUMMARY_FILE}" ]]; then
  attempts="$(metric_value multi_hot_reservation_attempts)"
  successes="$(metric_value multi_hot_reservation_success)"
  conflicts_expected="$(metric_value multi_hot_conflict_seat_already_reserved)"
  conflicts_other="$(metric_value multi_hot_conflict_other)"
  admission_rejected="$(metric_value multi_hot_429_seat_admission_rejected)"
  lock_timeout="$(metric_value multi_hot_429_seat_lock_timeout)"
  other_429="$(metric_value multi_hot_429_other)"
  five_xx="$(metric_value multi_hot_reservation_5xx)"
  transport="$(metric_value multi_hot_transport_failure)"
  unexpected="$(metric_value multi_hot_unexpected_http)"
  if [[ "${attempts},${successes},${conflicts_expected},${conflicts_other},${admission_rejected},${lock_timeout},${other_429},${five_xx},${transport},${unexpected}" =~ ^[0-9]+,[0-9]+,[0-9]+,[0-9]+,[0-9]+,[0-9]+,[0-9]+,[0-9]+,[0-9]+,[0-9]+$ ]]; then
    expected_non_success="$(( CONCURRENCY - SEAT_POOL_SIZE ))"
    expected_non_success_actual="$(( conflicts_expected + admission_rejected + lock_timeout ))"
    if [[ "${attempts}" == "${CONCURRENCY}" ]] \
      && [[ "${successes}" == "${SEAT_POOL_SIZE}" ]] \
      && [[ "${expected_non_success_actual}" == "${expected_non_success}" ]] \
      && [[ "${conflicts_other}" == "0" ]] \
      && [[ "${other_429}" == "0" ]] \
      && [[ "${five_xx}" == "0" ]] \
      && [[ "${transport}" == "0" ]] \
      && [[ "${unexpected}" == "0" ]]; then
      request_contract_status=pass
    else
      request_contract_status=failed
    fi
  fi
fi

observation_loss_count="$(line_count_without_header "${OBSERVATION_LOSS_FILE}")"
if [[ "${observation_loss_count}" == "0" ]]; then
  observation_contract_status=pass
else
  observation_contract_status=degraded
fi

if [[ "${request_contract_status}" == "pass" && "${observation_contract_status}" == "pass" ]]; then
  contract_status=pass
elif [[ "${request_contract_status}" == "pass" ]]; then
  contract_status=request_pass_observation_degraded
elif [[ "${request_contract_status}" == "failed" ]]; then
  contract_status=request_failed
fi

dump_event_count="$(line_count_without_header "${THREAD_DUMP_EVENTS_FILE}")"
dump_header_count="$(grep -E -c 'Full thread dump|Java Thread Dump' "${APP_LOG_FILE}" 2>/dev/null || true)"
{
  printf 'run_id=%s\n' "${RUN_ID}"
  printf 'k6_exit_code=%s\n' "${k6_status}"
  printf 'contract_status=%s\n' "${contract_status}"
  printf 'request_contract_status=%s\n' "${request_contract_status}"
  printf 'observation_contract_status=%s\n' "${observation_contract_status}"
  printf 'configured_concurrency=%s\n' "${CONCURRENCY}"
  printf 'fixture_performance_time_id=%s\n' "${performance_time_id}"
  printf 'fixture_seat_ids=%s\n' "${seat_ids_csv}"
  printf 'k6_reservation_attempts=%s\n' "${attempts}"
  printf 'k6_reservation_success=%s\n' "${successes}"
  printf 'k6_conflict_seat_already_reserved=%s\n' "${conflicts_expected}"
  printf 'k6_conflict_other=%s\n' "${conflicts_other}"
  printf 'k6_429_admission_rejected=%s\n' "${admission_rejected}"
  printf 'k6_429_lock_timeout=%s\n' "${lock_timeout}"
  printf 'k6_429_other=%s\n' "${other_429}"
  printf 'k6_5xx=%s\n' "${five_xx}"
  printf 'k6_transport=%s\n' "${transport}"
  printf 'k6_unexpected_http=%s\n' "${unexpected}"
  printf 'observation_loss_count=%s\n' "${observation_loss_count}"
  printf 'thread_dump_signal_events=%s\n' "${dump_event_count}"
  printf 'thread_dump_log_headers=%s\n' "${dump_header_count}"
  printf 'tomcat_busy_peak=%s\n' "$(peak_column "${APP_METRICS_FILE}" 2)"
  printf 'tomcat_current_peak=%s\n' "$(peak_column "${APP_METRICS_FILE}" 3)"
  printf 'hikari_active_peak=%s\n' "$(peak_column "${APP_METRICS_FILE}" 5)"
  printf 'hikari_pending_peak=%s\n' "$(peak_column "${APP_METRICS_FILE}" 6)"
  printf 'hikari_acquire_max_seconds_peak=%s\n' "$(peak_column "${APP_METRICS_FILE}" 8)"
  printf 'jvm_threads_live_peak=%s\n' "$(peak_column "${APP_METRICS_FILE}" 10)"
  printf 'jvm_threads_blocked_peak=%s\n' "$(peak_column "${APP_METRICS_FILE}" 12)"
  printf 'jvm_gc_pause_seconds_sum_peak=%s\n' "$(peak_column "${APP_METRICS_FILE}" 16)"
  printf 'jvm_gc_overhead_peak=%s\n' "$(peak_column "${APP_METRICS_FILE}" 17)"
  printf 'jvm_heap_used_peak_bytes=%s\n' "$(peak_column "${APP_METRICS_FILE}" 18)"
  printf 'process_resident_memory_peak_bytes=%s\n' "$(peak_column "${APP_METRICS_FILE}" 20)"
  printf 'process_cpu_peak=%s\n' "$(peak_column "${APP_METRICS_FILE}" 21)"
  printf 'mysql_data_lock_waits_peak=%s\n' "$(peak_column "${MYSQL_METRICS_FILE}" 2)"
  summarize_k6
  if [[ "${K6_EXECUTION_MODE}" == "remote" ]]; then
    printf 'remote_k6_manifest=%s\nremote_k6_run_dir=%s\n' "${REMOTE_K6_MANIFEST_FILE}" "${remote_k6_run_dir}"
  fi
  printf 'manifest=%s\napp_metrics=%s\nmysql_metrics=%s\ndocker_stats=%s\nobservation_loss=%s\nthread_dump_events=%s\npost_run_db=%s\nk6_summary=%s\n' \
    "${MANIFEST_FILE}" "${APP_METRICS_FILE}" "${MYSQL_METRICS_FILE}" "${DOCKER_STATS_FILE}" \
    "${OBSERVATION_LOSS_FILE}" "${THREAD_DUMP_EVENTS_FILE}" "${POST_RUN_DB_FILE}" "${K6_SUMMARY_FILE}"
} > "${SUMMARY_FILE}"

printf 'multi_hot_seat_run_dir=%s\n' "${RUN_DIR}"
sed -n '1,260p' "${SUMMARY_FILE}"

# k6 실행 자체의 실패만 non-zero로 반환한다. 계약 실패도 원본을 문서화하기 위해 runner는 성공 종료한다.
exit "${k6_status}"
