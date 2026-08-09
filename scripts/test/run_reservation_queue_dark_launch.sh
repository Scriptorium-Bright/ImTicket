#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
mode="${1:-}"
base_url="${BASE_URL:-http://127.0.0.1:10080}"
management_base_url="${MANAGEMENT_BASE_URL:-$base_url}"
tomcat_connector_name="${TOMCAT_CONNECTOR_NAME:-http-nio-10080}"
performance_time_id="${PT_ID:-}"
seat_id="${SEAT_ID:-1}"
concurrency="${CONCURRENCY:-2000}"
expected_accepted="${EXPECTED_ACCEPTED:-}"
jwt_secret="${JWT_SECRET:-}"
sample_interval_seconds="${SAMPLE_INTERVAL_SECONDS:-0.2}"
tail_seconds="${TAIL_SECONDS:-3}"
result_root="${RESULT_ROOT:-$root_dir/build/k6-results/reservation-queue-dark-launch}"
request_timeout="${REQUEST_TIMEOUT:-15s}"

if [[ ! "$mode" =~ ^(dark-open|dark-full)$ ]]; then
    echo "first argument must be dark-open or dark-full" >&2
    exit 1
fi
for value_name in PT_ID CONCURRENCY EXPECTED_ACCEPTED; do
    value="${!value_name:-}"
    if [[ ! "$value" =~ ^[1-9][0-9]*$ ]]; then
        echo "$value_name must be a positive integer" >&2
        exit 1
    fi
done
if [[ ! "$seat_id" =~ ^[1-9][0-9]*$ ]]; then
    echo "SEAT_ID must be a positive integer" >&2
    exit 1
fi
if [[ -z "$jwt_secret" ]]; then
    echo "JWT_SECRET is required" >&2
    exit 1
fi
if (( expected_accepted > concurrency )); then
    echo "EXPECTED_ACCEPTED cannot exceed CONCURRENCY" >&2
    exit 1
fi
for command_name in curl jq perl k6 awk; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "$command_name is required" >&2
        exit 1
    fi
done

run_id="$mode-$(date -u +%Y%m%dT%H%M%SZ)"
run_dir="$result_root/$run_id"
metrics_file="$run_dir/app-metrics.tsv"
k6_summary_file="$run_dir/k6-summary.json"
k6_console_file="$run_dir/k6-console.log"
summary_file="$run_dir/run-summary.txt"
manifest_file="$run_dir/manifest.txt"
sampler_pid=""
mkdir -p "$run_dir"

cleanup() {
    if [[ -n "$sampler_pid" ]] && kill -0 "$sampler_pid" 2>/dev/null; then
        kill "$sampler_pid" 2>/dev/null || true
        wait "$sampler_pid" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

cat >"$manifest_file" <<MANIFEST
run_id=$run_id
mode=$mode
base_url=$base_url
management_base_url=$management_base_url
tomcat_connector_name=$tomcat_connector_name
performance_time_id=$performance_time_id
seat_id=$seat_id
concurrency=$concurrency
expected_accepted=$expected_accepted
request_timeout=$request_timeout
sample_interval_seconds=$sample_interval_seconds
MANIFEST

printf 'epoch_seconds\ttomcat_busy\ttomcat_current\ttomcat_max\thikari_active\thikari_pending\thikari_max\n' \
    >"$metrics_file"

metric_value() {
    local payload="$1"
    local metric_name="$2"
    local label_fragment="${3:-}"
    awk -v name="$metric_name" -v label="$label_fragment" '
      ($1 == name || index($1, name "{") == 1) && (label == "" || index($0, label) > 0) {
        print $NF
        found = 1
        exit
      }
      END { if (!found) print "NA" }
    ' <<<"$payload"
}

sample_once() {
    local payload timestamp connector_label busy current configured_max active pending hikari_max
    payload="$(curl -fsS --connect-timeout 1 --max-time 1 "$management_base_url/actuator/prometheus" 2>/dev/null || true)"
    timestamp="$(perl -MTime::HiRes=time -e 'printf "%.3f", time')"
    if [[ -z "$payload" ]]; then
        printf '%s\tNA\tNA\tNA\tNA\tNA\tNA\n' "$timestamp" >>"$metrics_file"
        return
    fi
    connector_label="name=\"$tomcat_connector_name\""
    busy="$(metric_value "$payload" tomcat_threads_busy_threads "$connector_label")"
    current="$(metric_value "$payload" tomcat_threads_current_threads "$connector_label")"
    configured_max="$(metric_value "$payload" tomcat_threads_config_max_threads "$connector_label")"
    active="$(metric_value "$payload" hikaricp_connections_active)"
    pending="$(metric_value "$payload" hikaricp_connections_pending)"
    hikari_max="$(metric_value "$payload" hikaricp_connections_max)"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$timestamp" "$busy" "$current" "$configured_max" "$active" "$pending" "$hikari_max" \
        >>"$metrics_file"
}

collect_metrics() {
    while true; do
        sample_once
        sleep "$sample_interval_seconds"
    done
}

if ! curl -fsS --connect-timeout 2 --max-time 3 "$management_base_url/actuator/health" >/dev/null; then
    echo "application health check failed" >&2
    exit 1
fi
if ! curl -fsS --connect-timeout 2 --max-time 3 "$management_base_url/actuator/prometheus" >/dev/null; then
    echo "management metrics check failed" >&2
    exit 1
fi

collect_metrics &
sampler_pid=$!
start_at_epoch_ms="$(($(date +%s) * 1000 + 3000))"

set +e
BASE_URL="$base_url" \
MANAGEMENT_BASE_URL="$management_base_url" \
PT_ID="$performance_time_id" \
SEAT_ID="$seat_id" \
CONCURRENCY="$concurrency" \
START_AT_EPOCH_MS="$start_at_epoch_ms" \
JWT_SECRET="$jwt_secret" \
REQUEST_TIMEOUT="$request_timeout" \
k6 run --quiet \
    --summary-export "$k6_summary_file" \
    "$root_dir/scripts/test/09-reservation-queue-dark-launch.js" \
    >"$k6_console_file" 2>&1
k6_exit=$?
set -e

sleep "$tail_seconds"
cleanup
sampler_pid=""

metric_count() {
    local metric_name="$1"
    jq -r --arg name "$metric_name" \
        '.metrics[$name].values.count // .metrics[$name].count // 0' \
        "$k6_summary_file"
}

accepted="$(metric_count queue_accepted_202)"
full="$(metric_count queue_full_429)"
conflict="$(metric_count queue_conflict_409)"
unavailable="$(metric_count queue_unavailable_503)"
server_error="$(metric_count queue_5xx)"
transport="$(metric_count queue_transport_failure)"
unexpected="$(metric_count queue_unexpected_http)"
p95_ms="$(jq -r \
    '.metrics.queue_enqueue_duration.values["p(95)"] // .metrics.queue_enqueue_duration["p(95)"] // "NA"' \
    "$k6_summary_file")"
p99_ms="$(jq -r \
    '.metrics.queue_enqueue_duration.values["p(99)"] // .metrics.queue_enqueue_duration["p(99)"] // "NA"' \
    "$k6_summary_file")"

read -r tomcat_busy_peak tomcat_current_peak tomcat_max hikari_active_peak hikari_pending_peak tomcat_90_seconds \
    < <(awk -F '\t' -v interval="$sample_interval_seconds" '
      NR > 1 {
        if ($2 != "NA" && $2 + 0 > busy) busy = $2 + 0
        if ($3 != "NA" && $3 + 0 > current) current = $3 + 0
        if ($4 != "NA" && $4 + 0 > configured) configured = $4 + 0
        if ($5 != "NA" && $5 + 0 > active) active = $5 + 0
        if ($6 != "NA" && $6 + 0 > pending) pending = $6 + 0
        if ($2 != "NA" && $4 != "NA" && $4 + 0 > 0 && ($2 + 0) / ($4 + 0) >= 0.9) high++
      }
      END { printf "%g %g %g %g %g %.3f\n", busy, current, configured, active, pending, high * interval }
    ' "$metrics_file")

contract="passed"
if [[ "$k6_exit" -ne 0 \
      || "$accepted" -ne "$expected_accepted" \
      || "$full" -ne "$((concurrency - expected_accepted))" \
      || "$conflict" -ne 0 \
      || "$unavailable" -ne 0 \
      || "$server_error" -ne 0 \
      || "$transport" -ne 0 \
      || "$unexpected" -ne 0 ]]; then
    contract="failed"
fi

cat >"$summary_file" <<SUMMARY
run_id=$run_id
contract=$contract
k6_exit=$k6_exit
attempts=$concurrency
accepted_202=$accepted
full_429=$full
conflict_409=$conflict
unavailable_503=$unavailable
server_error_5xx=$server_error
transport_failure=$transport
unexpected_http=$unexpected
enqueue_p95_ms=$p95_ms
enqueue_p99_ms=$p99_ms
tomcat_busy_peak=$tomcat_busy_peak
tomcat_current_peak=$tomcat_current_peak
tomcat_configured_max=$tomcat_max
tomcat_90_percent_seconds=$tomcat_90_seconds
hikari_active_peak=$hikari_active_peak
hikari_pending_peak=$hikari_pending_peak
SUMMARY

cat "$summary_file"
if [[ "$contract" != "passed" ]]; then
    exit 1
fi
