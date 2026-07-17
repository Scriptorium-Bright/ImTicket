#!/usr/bin/env bash
set -euo pipefail

REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-6379}"
STREAM_KEY="${STREAM_KEY:-seat-creation-stream:troubleshooting}"
CONSUMER_GROUP="${CONSUMER_GROUP:-seat-creation-troubleshooting-group}"
CONSUMER_NAME="${CONSUMER_NAME:-pending-debugger}"
IDLE_MS="${IDLE_MS:-0}"

redis_cli() {
  redis-cli -h "${REDIS_HOST}" -p "${REDIS_PORT}" "$@"
}

redis_cli XGROUP CREATE "${STREAM_KEY}" "${CONSUMER_GROUP}" 0 MKSTREAM >/dev/null 2>&1 || true

MESSAGE_ID="$(
  redis_cli XADD "${STREAM_KEY}" '*' \
    payload '{"type":"troubleshooting","scenario":"ack-before-crash","source":"reproduce_stream_pending.sh"}'
)"

echo "Published message: ${MESSAGE_ID}"

echo
echo "Reading the message with XREADGROUP without XACK."
redis_cli XREADGROUP GROUP "${CONSUMER_GROUP}" "${CONSUMER_NAME}" COUNT 1 STREAMS "${STREAM_KEY}" '>'

echo
echo "Pending summary:"
redis_cli XPENDING "${STREAM_KEY}" "${CONSUMER_GROUP}"

echo
echo "Pending details:"
redis_cli XPENDING "${STREAM_KEY}" "${CONSUMER_GROUP}" - + 10

if [[ "${IDLE_MS}" != "0" ]]; then
  echo
  echo "Messages can be reclaimed after idle threshold with:"
  echo "redis-cli -h ${REDIS_HOST} -p ${REDIS_PORT} XAUTOCLAIM ${STREAM_KEY} ${CONSUMER_GROUP} <new-consumer> ${IDLE_MS} 0-0 COUNT 10"
fi

echo
echo "No XACK was sent. The message remains in the pending entries list."
