#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -lt 3 || "$#" -gt 4 ]]; then
  echo "사용법: $0 <jfr-file> <load-start-utc> <load-end-utc> [output-file]" >&2
  exit 2
fi

JFR_FILE="$1"
LOAD_START="$2"
LOAD_END="$3"
OUTPUT_FILE="${4:-${JFR_FILE%.jfr}-load-window.tsv}"

if [[ ! -f "${JFR_FILE}" ]]; then
  echo "JFR 파일을 찾지 못했습니다: ${JFR_FILE}" >&2
  exit 1
fi

mkdir -p "$(dirname "${OUTPUT_FILE}")"

jfr print --json --stack-depth 128 --events jdk.ThreadPark,jdk.JavaMonitorEnter,jdk.ExecutionSample "${JFR_FILE}" \
  | jq -r \
    --arg load_start "${LOAD_START}" \
    --arg load_end "${LOAD_END}" \
    '
      def events: (.recording.events // []);
      def in_window:
        (.values.startTime? // "") >= $load_start
        and (.values.startTime? // "") <= $load_end;
      def stack_text: ((.values.stackTrace? // {}) | tostring);
      def duration_seconds:
        (.values.duration? // "PT0S")
        | capture("^PT(?<seconds>[0-9.]+)S$")
        | .seconds
        | tonumber;
      def selected: events | map(select(in_window));
      def of_type($type): selected | map(select(.type == $type));
      def reservation_stack: map(select(stack_text | contains("ReservationLockAspect")));
      def summary($items):
        if ($items | length) == 0 then
          {count: 0, total_seconds: 0, max_seconds: 0, average_seconds: 0}
        else
          ($items | map(duration_seconds)) as $durations
          | {count: ($items | length),
             total_seconds: ($durations | add),
             max_seconds: ($durations | max),
             average_seconds: (($durations | add) / ($durations | length))}
        end;
      (of_type("jdk.ThreadPark")) as $thread_parks
      | ($thread_parks | reservation_stack) as $reservation_parks
      | (of_type("jdk.JavaMonitorEnter")) as $monitor_enters
      | ($monitor_enters | reservation_stack) as $reservation_monitors
      | (of_type("jdk.ExecutionSample")) as $samples
      | ($samples | reservation_stack) as $reservation_samples
      | ($samples | map(select(stack_text | contains("org/hibernate")))) as $hibernate_samples
      | ($samples | map(select(stack_text | contains("com/mysql")))) as $mysql_samples
      | ($samples | map(select(stack_text | contains("io/prometheus")))) as $prometheus_samples
      | [
          ["load_window_start", $load_start],
          ["load_window_end", $load_end],
          ["selected_event_count", (selected | length)],
          ["thread_park_count", ($thread_parks | length)],
          ["thread_park_total_seconds", (summary($thread_parks).total_seconds)],
          ["thread_park_max_seconds", (summary($thread_parks).max_seconds)],
          ["thread_park_average_seconds", (summary($thread_parks).average_seconds)],
          ["reservation_lock_thread_park_count", ($reservation_parks | length)],
          ["reservation_lock_thread_park_total_seconds", (summary($reservation_parks).total_seconds)],
          ["reservation_lock_thread_park_max_seconds", (summary($reservation_parks).max_seconds)],
          ["reservation_lock_thread_park_average_seconds", (summary($reservation_parks).average_seconds)],
          ["reservation_lock_thread_park_count_ratio", (if ($thread_parks | length) == 0 then 0 else (($reservation_parks | length) / ($thread_parks | length)) end)],
          ["reservation_lock_thread_park_duration_ratio", (if (summary($thread_parks).total_seconds) == 0 then 0 else (summary($reservation_parks).total_seconds / summary($thread_parks).total_seconds) end)],
          ["reservation_lock_monitor_enter_count", ($reservation_monitors | length)],
          ["reservation_lock_execution_sample_count", ($reservation_samples | length)],
          ["hibernate_execution_sample_count", ($hibernate_samples | length)],
          ["mysql_execution_sample_count", ($mysql_samples | length)],
          ["prometheus_execution_sample_count", ($prometheus_samples | length)]
        ]
      | .[]
      | @tsv
    ' > "${OUTPUT_FILE}"

printf 'jfr_file=%s\noutput_file=%s\n' "${JFR_FILE}" "${OUTPUT_FILE}"
