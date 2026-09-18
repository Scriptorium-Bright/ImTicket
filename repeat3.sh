strategy="${1:-pessimistic}"
root="build/k6-results/lock-matrix/${strategy}-repeat3"
mkdir -p "$root"

for repeat in 1 2 3; do
  for n in 500 1000 2000 5000 10000; do
    echo "=== strategy=$strategy repeat=$repeat concurrency=$n ==="

    LOCK_STRATEGY="$strategy" \
    SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=30 \
    docker compose up -d --force-recreate app
    until curl -fsS --connect-timeout 1 --max-time 3 "$BASE_URL/actuator/health" >/dev/null 2>&1; do sleep 1; done
    sleep 2

    fixture="$({
      MYSQL_HOST="$MYSQL_HOST" MYSQL_PORT="$MYSQL_PORT" MYSQL_PASSWORD="$MYSQL_PASSWORD" \
        scripts/test/seed_pessimistic_lock_fixture.sh
    } | tail -n 1)"
    pt_id="$(echo "$fixture" | awk '{print $2}')"
    seat_id="$(echo "$fixture" | awk '{print $3}')"
    result_dir="$root/repeat-${repeat}/vu-${n}"
    mkdir -p "$result_dir"

    set +e
    LOCK_STRATEGY="$strategy" \
    RESULT_DIR="$result_dir" \
    BASE_URL="$BASE_URL" MYSQL_HOST="$MYSQL_HOST" \
    MYSQL_PORT="$MYSQL_PORT" MYSQL_PASSWORD="$MYSQL_PASSWORD" \
    scripts/test/observe_pessimistic_lock_run.sh \
      env BASE_URL="$BASE_URL" \
      GRADE=1 TRAFFIC_PROFILE=minimum CONCURRENCY="$n" \
      LOCK_STRATEGY="$strategy" \
      ALLOW_LARGE_LOAD=true PT_ID="$pt_id" SEAT_ID="$seat_id" \
      JWT_SECRET="$JWT_SECRET" \
      scripts/test/run_pessimistic_lock_natural_k6.sh \
      >"$result_dir/console.log" 2>&1
    rc=$?
    set -e

    echo "repeat=$repeat concurrency=$n exit_code=$rc"
    rg -n '^(run_id|test_exit_code|tomcat_busy_peak|tomcat_current_peak|hikari_active_peak|hikari_pending_peak|jvm_threads_live_peak|jvm_gc_pause_seconds_sum_peak|process_cpu_peak|system_cpu_peak|mysql_data_lock_waits_peak)=' \
      "$result_dir"/*/observation-summary.txt 2>/dev/null || true
    rg -n 'reservation_(success|conflict|internal_error)|unexpected_response|http_req_duration|http_req_failed' \
      "$result_dir"/*/test-command.log 2>/dev/null | tail -n 12 || true
  done
done
