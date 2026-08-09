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

test_dir="$(mktemp -d "${TMPDIR:-/tmp}/imticket-queue-expiry.XXXXXX")"
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

enqueue_script="$lua_dir/reservation_queue_enqueue.lua"
expire_script="$lua_dir/reservation_queue_expire_ticket.lua"
performance_time_id="1372001"
admitted_key="reservation:queue:{$performance_time_id}:admitted"
waiting_key="reservation:queue:{$performance_time_id}:waiting"
deadline_key="reservation:queue:{$performance_time_id}:deadline"
sequence_key="reservation:queue:{$performance_time_id}:sequence"
stream_key="reservation:queue:{$performance_time_id}:stream"
owner_hash="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
owner_token="da64524f-ac82-45a8-9d38-4cd641b72343"
payload_schema_version="1"
member_id="42"
idempotency_key="a0ebc4c9-8d82-47af-8127-1fc3d27e47a1"
idempotency_key_hash="f54367c55daa813d9a0723535674b25cd20057e644b7297dbe1fdf4de3368aa1"
request_hash="cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
retention_ms="60000"

enqueue_ticket() {
    local ticket_id="$1"
    local deadline_ms="$2"
    local ticket_key="reservation:queue:{$performance_time_id}:ticket:$ticket_id"
    "$redis_cli_bin" -s "$redis_socket" --raw --eval "$enqueue_script" \
        "$admitted_key" "$waiting_key" "$deadline_key" "$sequence_key" "$ticket_key" "$stream_key" , \
        "$ticket_id" "$performance_time_id" "$owner_hash" "$owner_token" \
        "$payload_schema_version" "$member_id" "$idempotency_key" "$idempotency_key_hash" \
        "$request_hash" "1,3" \
        "1000" "$deadline_ms" "10" "$retention_ms"
}

expire_ticket() {
    local ticket_id="$1"
    local now_ms="$2"
    local ticket_key="reservation:queue:{$performance_time_id}:ticket:$ticket_id"
    "$redis_cli_bin" -s "$redis_socket" --raw --eval "$expire_script" \
        "$admitted_key" "$waiting_key" "$deadline_key" "$ticket_key" , \
        "$ticket_id" "$now_ms" "$retention_ms"
}

due_ticket="f76f5ac8-a475-4e04-906a-1f54765f9770"
future_ticket="e4f31fe2-ce93-4082-b9d1-7904c952bb8d"
enqueue_ticket "$due_ticket" "2000" >/dev/null
enqueue_ticket "$future_ticket" "4000" >/dev/null

assert_equal "NOT_DUE" "$(expire_ticket "$future_ticket" "3000")" "future ticket"
assert_equal "2" "$("$redis_cli_bin" -s "$redis_socket" --raw ZCARD "$admitted_key")" "capacity before expiry"

assert_equal "EXPIRED" "$(expire_ticket "$due_ticket" "3000")" "due ticket"
assert_equal "1" "$("$redis_cli_bin" -s "$redis_socket" --raw ZCARD "$admitted_key")" "admitted after expiry"
assert_equal "1" "$("$redis_cli_bin" -s "$redis_socket" --raw ZCARD "$waiting_key")" "waiting after expiry"
assert_equal "1" "$("$redis_cli_bin" -s "$redis_socket" --raw ZCARD "$deadline_key")" "deadline after expiry"
assert_equal "EXPIRED" "$("$redis_cli_bin" -s "$redis_socket" --raw HGET \
    "reservation:queue:{$performance_time_id}:ticket:$due_ticket" status)" "stored expiry status"
assert_equal "NOT_EXPIRABLE" "$(expire_ticket "$due_ticket" "3001")" "repeat expiry"

missing_ticket="00000000-0000-0000-0000-000000000001"
assert_equal "MISSING" "$(expire_ticket "$missing_ticket" "3000")" "missing ticket"

echo "PASS due=expired future=retained admitted=1 waiting=1 deadline=1"
