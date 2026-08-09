#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd "$script_dir/../.." && pwd)"
lua_dir="$project_root/src/main/resources/redis/reservation-queue"
redis_server_bin="${REDIS_SERVER_BIN:-$(command -v redis-server || true)}"
redis_cli_bin="${REDIS_CLI_BIN:-$(command -v redis-cli || true)}"

if [[ -z "$redis_server_bin" || -z "$redis_cli_bin" ]]; then
    echo "redis-server and redis-cli are required" >&2
    exit 1
fi

test_dir="$(mktemp -d "${TMPDIR:-/tmp}/imticket-queue-admission.XXXXXX")"
redis_socket="$test_dir/redis.sock"
redis_pid=""

cleanup() {
    if [[ -n "$redis_pid" ]] && kill -0 "$redis_pid" 2>/dev/null; then
        kill "$redis_pid" 2>/dev/null || true
        wait "$redis_pid" 2>/dev/null || true
    fi
    rm -rf -- "$test_dir"
}
trap cleanup EXIT

"$redis_server_bin" \
    --port 0 \
    --unixsocket "$redis_socket" \
    --unixsocketperm 700 \
    --save '' \
    --appendonly no \
    --dir "$test_dir" >"$test_dir/redis.log" 2>&1 &
redis_pid=$!

for _ in $(seq 1 50); do
    if "$redis_cli_bin" -s "$redis_socket" ping >/dev/null 2>&1; then
        break
    fi
    sleep 0.1
done
if [[ "$("$redis_cli_bin" -s "$redis_socket" --raw ping)" != "PONG" ]]; then
    echo "isolated Redis did not start" >&2
    exit 1
fi

assert_equal() {
    local expected="$1"
    local actual="$2"
    local context="$3"
    if [[ "$actual" != "$expected" ]]; then
        echo "$context: expected=$expected actual=$actual" >&2
        exit 1
    fi
}

reserve_script="$lua_dir/reservation_queue_reserve_idempotency.lua"
mark_script="$lua_dir/reservation_queue_mark_idempotency_queued.lua"
release_script="$lua_dir/reservation_queue_release_idempotency.lua"
enqueue_script="$lua_dir/reservation_queue_enqueue.lua"

owner_hash="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
key_hash="f54367c55daa813d9a0723535674b25cd20057e644b7297dbe1fdf4de3368aa1"
request_hash="cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
other_request_hash="dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
ticket_id="f76f5ac8-a475-4e04-906a-1f54765f9770"
owner_token="da64524f-ac82-45a8-9d38-4cd641b72343"
payload_schema_version="1"
member_id="42"
idempotency_key="a0ebc4c9-8d82-47af-8127-1fc3d27e47a1"
performance_time_id="1371001"
now_ms="1786356000000"
retention_ms="60000"
idem_key="reservation:queue:idempotency:$owner_hash:$key_hash"

result="$("$redis_cli_bin" -s "$redis_socket" --raw --eval "$reserve_script" "$idem_key" , \
    "$request_hash" "$ticket_id" "$performance_time_id" "$owner_token" "$now_ms" "$retention_ms")"
assert_equal "CREATED" "$result" "first idempotency reserve"

result="$("$redis_cli_bin" -s "$redis_socket" --raw --eval "$reserve_script" "$idem_key" , \
    "$request_hash" "another-ticket" "$performance_time_id" "another-owner" "$now_ms" "$retention_ms")"
assert_equal "EXISTING|ENQUEUING|$ticket_id|$performance_time_id" "$result" "same request reserve"

result="$("$redis_cli_bin" -s "$redis_socket" --raw --eval "$reserve_script" "$idem_key" , \
    "$other_request_hash" "another-ticket" "$performance_time_id" "another-owner" "$now_ms" "$retention_ms")"
assert_equal "CONFLICT" "$result" "different request reserve"

result="$("$redis_cli_bin" -s "$redis_socket" --raw --eval "$mark_script" "$idem_key" , \
    "$owner_token" "$ticket_id" "$now_ms" "$retention_ms")"
assert_equal "MARKED" "$result" "mark queued"
assert_equal "QUEUED" "$("$redis_cli_bin" -s "$redis_socket" --raw HGET "$idem_key" state)" "queued state"

release_key="reservation:queue:idempotency:$owner_hash:$other_request_hash"
"$redis_cli_bin" -s "$redis_socket" --raw --eval "$reserve_script" "$release_key" , \
    "$request_hash" "release-ticket" "$performance_time_id" "$owner_token" "$now_ms" "$retention_ms" >/dev/null
result="$("$redis_cli_bin" -s "$redis_socket" --raw --eval "$release_script" "$release_key" , \
    "wrong-owner" "release-ticket")"
assert_equal "OWNER_MISMATCH" "$result" "foreign release"
assert_equal "1" "$("$redis_cli_bin" -s "$redis_socket" --raw EXISTS "$release_key")" "mapping retained"
result="$("$redis_cli_bin" -s "$redis_socket" --raw --eval "$release_script" "$release_key" , \
    "$owner_token" "release-ticket")"
assert_equal "RELEASED" "$result" "owned release"

race_key="reservation:queue:idempotency:$owner_hash:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
race_output_dir="$test_dir/idempotency-results"
mkdir -p "$race_output_dir"
pids=()
for index in $(seq 1 20); do
    "$redis_cli_bin" -s "$redis_socket" --raw --eval "$reserve_script" "$race_key" , \
        "$request_hash" "race-ticket-$index" "$performance_time_id" "race-owner-$index" \
        "$now_ms" "$retention_ms" >"$race_output_dir/$index" &
    pids+=("$!")
done
for pid in "${pids[@]}"; do
    wait "$pid"
done

reserved_ticket="$("$redis_cli_bin" -s "$redis_socket" --raw HGET "$race_key" ticketId)"
created=0
existing=0
for index in $(seq 1 20); do
    result="$(<"$race_output_dir/$index")"
    if [[ "$result" == "CREATED" ]]; then
        created=$((created + 1))
    elif [[ "$result" == "EXISTING|ENQUEUING|$reserved_ticket|$performance_time_id" ]]; then
        existing=$((existing + 1))
    else
        echo "unexpected concurrent reserve result: $result" >&2
        exit 1
    fi
done
assert_equal "1" "$created" "idempotency owner count"
assert_equal "19" "$existing" "idempotency follower count"

admitted_key="reservation:queue:{$performance_time_id}:admitted"
waiting_key="reservation:queue:{$performance_time_id}:waiting"
deadline_key="reservation:queue:{$performance_time_id}:deadline"
sequence_key="reservation:queue:{$performance_time_id}:sequence"
stream_key="reservation:queue:{$performance_time_id}:stream"
max_depth=8
attempts=40
output_dir="$test_dir/enqueue-results"
mkdir -p "$output_dir"
pids=()

for index in $(seq 1 "$attempts"); do
    candidate="ticket-$index"
    ticket_key="reservation:queue:{$performance_time_id}:ticket:$candidate"
    "$redis_cli_bin" -s "$redis_socket" --raw --eval "$enqueue_script" \
        "$admitted_key" "$waiting_key" "$deadline_key" "$sequence_key" "$ticket_key" "$stream_key" , \
        "$candidate" "$performance_time_id" "$owner_hash" "$owner_token" \
        "$payload_schema_version" "$member_id" "$idempotency_key" "$key_hash" \
        "$request_hash" "1,3" \
        "$now_ms" "$((now_ms + 10000))" "$max_depth" "$retention_ms" \
        >"$output_dir/$index" &
    pids+=("$!")
done
for pid in "${pids[@]}"; do
    wait "$pid"
done

accepted=0
full=0
accepted_ticket=""
for index in $(seq 1 "$attempts"); do
    result="$(<"$output_dir/$index")"
    if [[ "$result" == ACCEPTED\|* ]]; then
        accepted=$((accepted + 1))
        if [[ -z "$accepted_ticket" ]]; then
            accepted_ticket="ticket-$index"
        fi
        status="$("$redis_cli_bin" -s "$redis_socket" --raw HGET \
            "reservation:queue:{$performance_time_id}:ticket:ticket-$index" status)"
        assert_equal "WAITING" "$status" "accepted ticket status"
    elif [[ "$result" == "FULL" ]]; then
        full=$((full + 1))
    else
        echo "unexpected enqueue result: $result" >&2
        exit 1
    fi
done

assert_equal "$max_depth" "$accepted" "accepted count"
assert_equal "$((attempts - max_depth))" "$full" "full count"
assert_equal "$max_depth" "$("$redis_cli_bin" -s "$redis_socket" --raw ZCARD "$admitted_key")" "admitted size"
assert_equal "$max_depth" "$("$redis_cli_bin" -s "$redis_socket" --raw ZCARD "$waiting_key")" "waiting size"
assert_equal "$max_depth" "$("$redis_cli_bin" -s "$redis_socket" --raw ZCARD "$deadline_key")" "deadline size"
assert_equal "$max_depth" "$("$redis_cli_bin" -s "$redis_socket" --raw XLEN "$stream_key")" "stream size"
assert_equal "$max_depth" "$("$redis_cli_bin" -s "$redis_socket" --raw GET "$sequence_key")" "sequence value"

accepted_ticket_key="reservation:queue:{$performance_time_id}:ticket:$accepted_ticket"
assert_equal "$payload_schema_version" "$("$redis_cli_bin" -s "$redis_socket" --raw HGET "$accepted_ticket_key" payloadSchemaVersion)" "ticket payload schema"
assert_equal "$member_id" "$("$redis_cli_bin" -s "$redis_socket" --raw HGET "$accepted_ticket_key" memberId)" "ticket member"
assert_equal "$idempotency_key" "$("$redis_cli_bin" -s "$redis_socket" --raw HGET "$accepted_ticket_key" idempotencyKey)" "ticket idempotency key"
assert_equal "$key_hash" "$("$redis_cli_bin" -s "$redis_socket" --raw HGET "$accepted_ticket_key" idempotencyKeyHash)" "ticket idempotency hash"
assert_equal "$owner_token" "$("$redis_cli_bin" -s "$redis_socket" --raw HGET "$accepted_ticket_key" ownerToken)" "ticket owner token"

stream_entry="$("$redis_cli_bin" -s "$redis_socket" --raw XRANGE "$stream_key" - + COUNT 1)"
stream_field() {
    local field="$1"
    local return_next="false"
    while IFS= read -r line; do
        if [[ "$return_next" == "true" ]]; then
            printf '%s' "$line"
            return
        fi
        if [[ "$line" == "$field" ]]; then
            return_next="true"
        fi
    done <<<"$stream_entry"
}
assert_equal "$payload_schema_version" "$(stream_field payloadSchemaVersion)" "stream payload schema"
assert_equal "$member_id" "$(stream_field memberId)" "stream member"
assert_equal "$idempotency_key" "$(stream_field idempotencyKey)" "stream idempotency key"
assert_equal "$key_hash" "$(stream_field idempotencyKeyHash)" "stream idempotency hash"
assert_equal "$owner_token" "$(stream_field ownerToken)" "stream owner token"

echo "PASS idempotency=single-ticket followers=$existing conflict=detected owner-release=guarded payload=recoverable admitted=$accepted full=$full"
