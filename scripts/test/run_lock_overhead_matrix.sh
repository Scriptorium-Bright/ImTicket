#!/usr/bin/env bash
set -euo pipefail

# ReentrantLock와 MySQL pessimistic lock을 같은 hot-seat workload로 비교한다.
# 각 case는 app 재기동·fixture 재생성·Prometheus/MySQL/app metric 수집을 포함한다.
# 기본 admission permit은 최대 VU와 같아야 하므로, 요청이 실제 lock 경로까지 도달한다.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/scripts/test/load_env_defaults.sh"
load_imticket_env "${ROOT_DIR}/.env"

MATRIX_LABEL="${1:-lock-overhead}"
VUS_LIST="${VUS_LIST:-2000,3000,4000,5000}"
LOCK_STRATEGIES="${LOCK_STRATEGIES:-pessimistic,reentrant}"
SEAT_POOL_SIZE="${SEAT_POOL_SIZE:-4}"
SEATS_PER_REQUEST="${SEATS_PER_REQUEST:-1}"
SEAT_SELECTION_MODE="${SEAT_SELECTION_MODE:-round_robin}"
HIKARI_POOL_SIZE="${HIKARI_POOL_SIZE:-30}"
ADMISSION_PER_SEAT_PERMITS="${ADMISSION_PER_SEAT_PERMITS:-5000}"
LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS="${LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS:-1000}"
BASE_URL="${BASE_URL:-http://127.0.0.1:10080}"
MANAGEMENT_BASE_URL="${MANAGEMENT_BASE_URL:-http://127.0.0.1:10081}"
PROMETHEUS_CONFIG_FILE="${PROMETHEUS_CONFIG_FILE:-${ROOT_DIR}/monitoring/prometheus/prometheus-management-10081.yml}"
PROMETHEUS_BASE_URL="${PROMETHEUS_BASE_URL:-http://127.0.0.1:9090}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-10047}"
MYSQL_USER="${MYSQL_USER:-capstone}"
MYSQL_DATABASE="${MYSQL_DATABASE:-capstone}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-${MYSQL_LOCK_TEST_PASSWORD:-}}"
JWT_SECRET="${JWT_SECRET:-}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-15s}"
MAX_DURATION="${MAX_DURATION:-2m}"
SAMPLE_INTERVAL_SECONDS="${SAMPLE_INTERVAL_SECONDS:-1}"
IDLE_SECONDS="${IDLE_SECONDS:-10}"
TAIL_SECONDS="${TAIL_SECONDS:-30}"
CAPTURE_THREAD_DUMPS="${CAPTURE_THREAD_DUMPS:-false}"
BUILD_APP_IMAGE="${BUILD_APP_IMAGE:-true}"
JFR_ENABLED="${JFR_ENABLED:-true}"
JFR_DELAY_SECONDS="${JFR_DELAY_SECONDS:-35}"
JFR_DURATION_SECONDS="${JFR_DURATION_SECONDS:-120}"
JFR_STACK_DEPTH="${JFR_STACK_DEPTH:-128}"
JFR_MAX_SIZE="${JFR_MAX_SIZE:-256m}"
JFR_STOP_TIMEOUT_SECONDS="${JFR_STOP_TIMEOUT_SECONDS:-120}"
RESTORE_APP_AFTER_MATRIX="${RESTORE_APP_AFTER_MATRIX:-true}"
DRY_RUN="${DRY_RUN:-false}"
RESULT_ROOT="${RESULT_ROOT:-${ROOT_DIR}/build/k6-results/lock-overhead-matrix}"
ORIGINAL_JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-}"

usage() {
  cat >&2 <<'EOF'
사용법:
  scripts/test/run_lock_overhead_matrix.sh [matrix-label]

기본값:
  VUS_LIST=2000,3000,4000,5000
  LOCK_STRATEGIES=pessimistic,reentrant
  ADMISSION_PER_SEAT_PERMITS=5000
  HIKARI_POOL_SIZE=30
  JFR_ENABLED=true

검증만:
  DRY_RUN=true scripts/test/run_lock_overhead_matrix.sh lock-overhead
EOF
}

if [[ "${MATRIX_LABEL}" == "--help" || "${MATRIX_LABEL}" == "-h" ]]; then
  usage
  exit 0
fi

if [[ ! "${MATRIX_LABEL}" =~ ^[a-z0-9][a-z0-9-]*$ ]]; then
  echo "matrix-label은 소문자·숫자·하이픈만 사용해야 합니다." >&2
  exit 1
fi
if [[ ! "${SEAT_POOL_SIZE}" =~ ^[1-9][0-9]*$ ]]; then
  echo "SEAT_POOL_SIZE는 양의 정수여야 합니다." >&2
  exit 1
fi
if [[ ! "${SEATS_PER_REQUEST}" =~ ^[1-9][0-9]*$ || "${SEATS_PER_REQUEST}" != "1" ]]; then
  echo "이번 비교는 SEATS_PER_REQUEST=1만 지원합니다." >&2
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
if [[ ! "${HIKARI_POOL_SIZE}" =~ ^[1-9][0-9]*$ ]]; then
  echo "HIKARI_POOL_SIZE는 양의 정수여야 합니다." >&2
  exit 1
fi
if [[ ! "${ADMISSION_PER_SEAT_PERMITS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "ADMISSION_PER_SEAT_PERMITS는 양의 정수여야 합니다." >&2
  exit 1
fi
if [[ ! "${LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS는 양의 정수여야 합니다." >&2
  exit 1
fi
if [[ ! "${SAMPLE_INTERVAL_SECONDS}" =~ ^[1-9][0-9]*$ || ! "${IDLE_SECONDS}" =~ ^[0-9]+$ || ! "${TAIL_SECONDS}" =~ ^[0-9]+$ ]]; then
  echo "sample/idle/tail 값 형식이 올바르지 않습니다." >&2
  exit 1
fi
if [[ ! "${JFR_DURATION_SECONDS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "JFR_DURATION_SECONDS는 양의 정수여야 합니다." >&2
  exit 1
fi
if [[ ! "${JFR_DELAY_SECONDS}" =~ ^[0-9]+$ ]]; then
  echo "JFR_DELAY_SECONDS는 0 이상의 정수여야 합니다." >&2
  exit 1
fi
if [[ ! "${JFR_STACK_DEPTH}" =~ ^[1-9][0-9]*$ ]]; then
  echo "JFR_STACK_DEPTH는 양의 정수여야 합니다." >&2
  exit 1
fi
if [[ ! "${JFR_STOP_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "JFR_STOP_TIMEOUT_SECONDS는 양의 정수여야 합니다." >&2
  exit 1
fi
for boolean_value in BUILD_APP_IMAGE JFR_ENABLED RESTORE_APP_AFTER_MATRIX CAPTURE_THREAD_DUMPS DRY_RUN; do
  case "${!boolean_value}" in
    true|false) ;;
    *) echo "${boolean_value}는 true 또는 false여야 합니다." >&2; exit 1 ;;
  esac
done

IFS=',' read -r -a vu_values <<< "${VUS_LIST}"
if (( ${#vu_values[@]} == 0 )); then
  echo "VUS_LIST가 비어 있습니다." >&2
  exit 1
fi
max_vus=0
for vus in "${vu_values[@]}"; do
  if [[ ! "${vus}" =~ ^[1-9][0-9]*$ ]]; then
    echo "VUS_LIST에는 양의 정수만 쉼표로 구분해 입력해야 합니다: ${VUS_LIST}" >&2
    exit 1
  fi
  if (( vus > max_vus )); then
    max_vus="${vus}"
  fi
done
if (( ADMISSION_PER_SEAT_PERMITS < max_vus )); then
  echo "ADMISSION_PER_SEAT_PERMITS=${ADMISSION_PER_SEAT_PERMITS}가 최대 VU=${max_vus}보다 작습니다." >&2
  echo "순수 lock 경로를 보려면 permit을 최대 VU 이상으로 설정하거나 ALLOW_ADMISSION_REJECTIONS를 명시하십시오." >&2
  if [[ "${ALLOW_ADMISSION_REJECTIONS:-false}" != "true" ]]; then
    exit 1
  fi
fi

IFS=',' read -r -a strategy_values <<< "${LOCK_STRATEGIES}"
if (( ${#strategy_values[@]} == 0 )); then
  echo "LOCK_STRATEGIES가 비어 있습니다." >&2
  exit 1
fi
for strategy in "${strategy_values[@]}"; do
  if [[ "${strategy}" != "pessimistic" && "${strategy}" != "reentrant" ]]; then
    echo "이번 matrix는 pessimistic 또는 reentrant만 지원합니다: ${strategy}" >&2
    exit 1
  fi
done

if [[ "${PROMETHEUS_CONFIG_FILE}" != /* ]]; then
  PROMETHEUS_CONFIG_FILE="${ROOT_DIR}/${PROMETHEUS_CONFIG_FILE}"
fi
if [[ "${JFR_ENABLED}" == "true" && ! -d "${ROOT_DIR}/uploads/jfr" ]]; then
  mkdir -p "${ROOT_DIR}/uploads/jfr"
fi

printf 'matrix_label=%s\nvu_values=%s\nlock_strategies=%s\nadmission_per_seat_permits=%s\nhikari_pool_size=%s\njfr_enabled=%s\njfr_delay_seconds=%s\njfr_stack_depth=%s\n' \
  "${MATRIX_LABEL}" "${VUS_LIST}" "${LOCK_STRATEGIES}" "${ADMISSION_PER_SEAT_PERMITS}" "${HIKARI_POOL_SIZE}" "${JFR_ENABLED}" "${JFR_DELAY_SECONDS}" "${JFR_STACK_DEPTH}"

if [[ "${DRY_RUN}" == "true" ]]; then
  for strategy in "${strategy_values[@]}"; do
    for vus in "${vu_values[@]}"; do
      printf 'planned_case=%s-%svu\n' "${strategy}" "${vus}"
    done
  done
  exit 0
fi

if [[ -z "${MYSQL_PASSWORD}" || -z "${JWT_SECRET}" ]]; then
  echo "MYSQL_PASSWORD와 JWT_SECRET을 설정해야 합니다." >&2
  exit 1
fi
if [[ ! -f "${PROMETHEUS_CONFIG_FILE}" ]]; then
  echo "Prometheus 설정 파일을 찾지 못했습니다: ${PROMETHEUS_CONFIG_FILE}" >&2
  exit 1
fi

required_commands=(curl docker jq mysql k6 git awk sed)
for required_command in "${required_commands[@]}"; do
  if ! command -v "${required_command}" > /dev/null 2>&1; then
    echo "필수 명령을 찾지 못했습니다: ${required_command}" >&2
    exit 1
  fi
done
if [[ "${JFR_ENABLED}" == "true" ]] && ! command -v jfr > /dev/null 2>&1; then
  echo "경고: jfr가 없어 JFR 분석 파일을 만들 수 없습니다." >&2
fi

MATRIX_ID="${MATRIX_LABEL}-$(date -u +%Y%m%dT%H%M%SZ)"
MATRIX_DIR="${RESULT_ROOT}/${MATRIX_ID}"
MATRIX_SUMMARY_FILE="${MATRIX_DIR}/matrix-summary.tsv"
MATRIX_MANIFEST_FILE="${MATRIX_DIR}/matrix-manifest.txt"
mkdir -p "${MATRIX_DIR}"

{
  printf 'matrix_id=%s\n' "${MATRIX_ID}"
  printf 'started_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'vus_list=%s\nlock_strategies=%s\n' "${VUS_LIST}" "${LOCK_STRATEGIES}"
  printf 'seat_pool_size=%s\nseats_per_request=%s\nseat_selection_mode=%s\n' "${SEAT_POOL_SIZE}" "${SEATS_PER_REQUEST}" "${SEAT_SELECTION_MODE}"
  printf 'hikari_pool_size=%s\nadmission_per_seat_permits=%s\n' "${HIKARI_POOL_SIZE}" "${ADMISSION_PER_SEAT_PERMITS}"
  printf 'request_timeout=%s\nmax_duration=%s\n' "${REQUEST_TIMEOUT}" "${MAX_DURATION}"
  printf 'jfr_enabled=%s\njfr_delay_seconds=%s\njfr_duration_seconds=%s\njfr_max_size=%s\njfr_stop_timeout_seconds=%s\n' \
    "${JFR_ENABLED}" "${JFR_DELAY_SECONDS}" "${JFR_DURATION_SECONDS}" "${JFR_MAX_SIZE}" "${JFR_STOP_TIMEOUT_SECONDS}"
  printf 'git_revision=%s\n' "$(git -C "${ROOT_DIR}" rev-parse HEAD)"
  printf '\n[git_status]\n'
  git -C "${ROOT_DIR}" status --short
} > "${MATRIX_MANIFEST_FILE}"

printf 'case\tstrategy\tvus\trunner_exit\tcontract_status\tk6_p95_ms\tk6_p99_ms\ttomcat_busy_peak\thikari_pending_peak\tmysql_data_lock_waits_peak\tjvm_threads_live_peak\tjvm_threads_blocked_peak\tjvm_gc_overhead_peak\tjvm_heap_used_peak_bytes\tprocess_resident_memory_peak_bytes\tprocess_cpu_peak\tjfr_status\trun_dir\n' > "${MATRIX_SUMMARY_FILE}"

app_was_stopped=false
restore_application() {
  if [[ "${RESTORE_APP_AFTER_MATRIX}" == "true" && "${app_was_stopped}" == "true" ]]; then
    MANAGEMENT_SERVER_PORT=10081 \
    MANAGEMENT_HOST_PORT=10081 \
    MANAGEMENT_BIND_ADDRESS=127.0.0.1 \
    TICKET_STARTUP_JFR_ENABLED=false \
    JAVA_TOOL_OPTIONS="${ORIGINAL_JAVA_TOOL_OPTIONS}" \
      docker compose -f "${ROOT_DIR}/docker-compose.yml" up -d --no-deps --force-recreate app \
      > "${MATRIX_DIR}/app-restore.log" 2>&1 || true
    app_was_stopped=false
  fi
}
trap restore_application EXIT INT TERM

summary_value() {
  local summary_file="${1}" key="${2}" value
  if [[ ! -s "${summary_file}" ]]; then
    printf 'NA'
    return
  fi
  value="$(awk -F= -v key="${key}" '$1 == key { value = substr($0, index($0, "=") + 1) } END { print value }' "${summary_file}")"
  if [[ -n "${value}" ]]; then
    printf '%s' "${value}"
  else
    printf 'NA'
  fi
}

JFR_COLLECTION_STATUS=disabled
stop_app_and_collect_jfr() {
  local jfr_host_file="${1}" stop_log="${2}" found=false
  if [[ "${JFR_ENABLED}" != "true" ]]; then
    JFR_COLLECTION_STATUS=disabled
    return
  fi

  docker compose -f "${ROOT_DIR}/docker-compose.yml" stop \
    --timeout "${JFR_STOP_TIMEOUT_SECONDS}" app > "${stop_log}" 2>&1 || true
  app_was_stopped=true
  for _ in $(seq 1 30); do
    if [[ -s "${jfr_host_file}" ]]; then
      found=true
      break
    fi
    sleep 1
  done
  if [[ "${found}" != "true" ]]; then
    JFR_COLLECTION_STATUS=missing
    return
  fi
  JFR_COLLECTION_STATUS=available
}

fatal_count=0
build_image_for_case="${BUILD_APP_IMAGE}"
for strategy in "${strategy_values[@]}"; do
  for vus in "${vu_values[@]}"; do
    case_name="${strategy}-${vus}vu"
    case_dir="${MATRIX_DIR}/${case_name}"
    inner_result_root="${case_dir}/runner"
    jfr_id="lock-${case_name}-${MATRIX_ID}"
    jfr_host_file="${ROOT_DIR}/uploads/jfr/${jfr_id}.jfr"
    jfr_copy_file="${case_dir}/${jfr_id}.jfr"
    case_console="${case_dir}/matrix-console.log"
    run_dir=''
    jfr_status='disabled'
    mkdir -p "${case_dir}"
    rm -f "${jfr_host_file}"

    recording_options="${ORIGINAL_JAVA_TOOL_OPTIONS}"
    if [[ "${JFR_ENABLED}" == "true" ]]; then
      recording_options="${recording_options} -XX:StartFlightRecording=name=${jfr_id},settings=profile,stackdepth=${JFR_STACK_DEPTH},delay=${JFR_DELAY_SECONDS}s,duration=${JFR_DURATION_SECONDS}s,filename=/app/uploads/jfr/${jfr_id}.jfr,maxsize=${JFR_MAX_SIZE},dumponexit=true"
    fi

    {
      printf 'case=%s\nstrategy=%s\nvus=%s\n' "${case_name}" "${strategy}" "${vus}"
      printf 'admission_per_seat_permits=%s\nhikari_pool_size=%s\n' "${ADMISSION_PER_SEAT_PERMITS}" "${HIKARI_POOL_SIZE}"
      printf 'jfr_host_file=%s\n' "${jfr_host_file}"
      printf 'started_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    } > "${case_dir}/case-manifest.txt"

    echo "[matrix] start ${case_name}"
    set +e
    env \
      MYSQL_PASSWORD="${MYSQL_PASSWORD}" \
      JWT_SECRET="${JWT_SECRET}" \
      LOCK_STRATEGY="${strategy}" \
      CONCURRENCY="${vus}" \
      SEAT_POOL_SIZE="${SEAT_POOL_SIZE}" \
      SEATS_PER_REQUEST="${SEATS_PER_REQUEST}" \
      SEAT_SELECTION_MODE="${SEAT_SELECTION_MODE}" \
      LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS="${LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS}" \
      HIKARI_POOL_SIZE="${HIKARI_POOL_SIZE}" \
      ADMISSION_PER_SEAT_PERMITS="${ADMISSION_PER_SEAT_PERMITS}" \
      BASE_URL="${BASE_URL}" \
      MANAGEMENT_BASE_URL="${MANAGEMENT_BASE_URL}" \
      PROMETHEUS_CONFIG_FILE="${PROMETHEUS_CONFIG_FILE}" \
      PROMETHEUS_BASE_URL="${PROMETHEUS_BASE_URL}" \
      MYSQL_HOST="${MYSQL_HOST}" \
      MYSQL_PORT="${MYSQL_PORT}" \
      MYSQL_USER="${MYSQL_USER}" \
      MYSQL_DATABASE="${MYSQL_DATABASE}" \
      REQUEST_TIMEOUT="${REQUEST_TIMEOUT}" \
      MAX_DURATION="${MAX_DURATION}" \
      SAMPLE_INTERVAL_SECONDS="${SAMPLE_INTERVAL_SECONDS}" \
      IDLE_SECONDS="${IDLE_SECONDS}" \
      TAIL_SECONDS="${TAIL_SECONDS}" \
      CAPTURE_THREAD_DUMPS="${CAPTURE_THREAD_DUMPS}" \
      BUILD_APP_IMAGE="${build_image_for_case}" \
      RESULT_ROOT="${inner_result_root}" \
      JAVA_TOOL_OPTIONS="${recording_options}" \
      "${ROOT_DIR}/scripts/test/run_multi_hot_seat_diagnosis.sh" "${case_name}" \
      > "${case_console}" 2>&1
    runner_exit=$?
    set -e
    build_image_for_case=false

    run_dir="$(sed -n 's/^multi_hot_seat_run_dir=//p' "${case_console}" | tail -n 1 || true)"
    if [[ -z "${run_dir}" ]]; then
      run_dir="$(find "${inner_result_root}" -mindepth 1 -maxdepth 1 -type d -name "${case_name}-*" -print 2>/dev/null | sort | tail -n 1 || true)"
    fi

    stop_app_and_collect_jfr "${jfr_host_file}" "${case_dir}/jfr-stop.log"
    jfr_status="${JFR_COLLECTION_STATUS}"
    if [[ "${jfr_status}" == "available" ]]; then
      cp "${jfr_host_file}" "${jfr_copy_file}"
      if command -v jfr > /dev/null 2>&1; then
        if "${ROOT_DIR}/scripts/test/analyze_lock_jfr.sh" "${jfr_copy_file}" "${case_dir}/jfr-analysis" > "${case_dir}/jfr-analysis.log" 2>&1; then
          jfr_status='analyzed'
        else
          jfr_status='analysis_failed'
        fi
      else
        jfr_status='available_no_cli'
      fi
    fi

    summary_file="${run_dir}/run-summary.txt"
    contract_status="$(summary_value "${summary_file}" contract_status)"
    k6_p95="$(summary_value "${summary_file}" k6_http_req_duration_p95_ms)"
    k6_p99="$(summary_value "${summary_file}" k6_http_req_duration_p99_ms)"
    tomcat_busy="$(summary_value "${summary_file}" tomcat_busy_peak)"
    hikari_pending="$(summary_value "${summary_file}" hikari_pending_peak)"
    mysql_waits="$(summary_value "${summary_file}" mysql_data_lock_waits_peak)"
    jvm_live="$(summary_value "${summary_file}" jvm_threads_live_peak)"
    jvm_blocked="$(summary_value "${summary_file}" jvm_threads_blocked_peak)"
    jvm_gc_overhead="$(summary_value "${summary_file}" jvm_gc_overhead_peak)"
    jvm_heap_used="$(summary_value "${summary_file}" jvm_heap_used_peak_bytes)"
    process_resident="$(summary_value "${summary_file}" process_resident_memory_peak_bytes)"
    process_cpu="$(summary_value "${summary_file}" process_cpu_peak)"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
      "${case_name}" "${strategy}" "${vus}" "${runner_exit}" "${contract_status}" \
      "${k6_p95}" "${k6_p99}" "${tomcat_busy}" "${hikari_pending}" "${mysql_waits}" \
      "${jvm_live}" "${jvm_blocked}" "${jvm_gc_overhead}" "${jvm_heap_used}" \
      "${process_resident}" "${process_cpu}" "${jfr_status}" "${run_dir}" >> "${MATRIX_SUMMARY_FILE}"

    {
      printf 'runner_exit=%s\nrun_dir=%s\njfr_status=%s\n' "${runner_exit}" "${run_dir}" "${jfr_status}"
      printf 'finished_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    } >> "${case_dir}/case-manifest.txt"

    if (( runner_exit != 0 )); then
      fatal_count=$((fatal_count + 1))
    fi
    if (( runner_exit == 0 )) \
      && [[ "${JFR_ENABLED}" == "true" && "${jfr_status}" != "analyzed" && "${jfr_status}" != "available_no_cli" ]]; then
      fatal_count=$((fatal_count + 1))
    fi
    echo "[matrix] complete ${case_name} runner_exit=${runner_exit} contract=${contract_status} jfr=${jfr_status}"
  done
done

{
  printf 'finished_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'fatal_count=%s\n' "${fatal_count}"
  printf 'matrix_summary=%s\n' "${MATRIX_SUMMARY_FILE}"
} >> "${MATRIX_MANIFEST_FILE}"

printf 'lock_overhead_matrix_dir=%s\n' "${MATRIX_DIR}"
printf 'matrix_summary=%s\n' "${MATRIX_SUMMARY_FILE}"
sed -n '1,20p' "${MATRIX_SUMMARY_FILE}"

if (( fatal_count > 0 )); then
  echo "matrix는 완료됐지만 runner 또는 JFR 수집 실패가 ${fatal_count}건 있습니다." >&2
  exit 1
fi
