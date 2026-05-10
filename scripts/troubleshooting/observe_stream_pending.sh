#!/usr/bin/env bash
set -euo pipefail

REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-6379}"
STREAM_KEY="${STREAM_KEY:-seat-creation-stream:troubleshooting}"
CONSUMER_GROUP="${CONSUMER_GROUP:-seat-creation-troubleshooting-group}"
CLAIM_CONSUMER="${CLAIM_CONSUMER:-pending-observer}"
MIN_IDLE_MS="${MIN_IDLE_MS:-60000}"
COUNT="${COUNT:-10}"

redis_cli() {
  redis-cli -h "${REDIS_HOST}" -p "${REDIS_PORT}" "$@"
}

echo "== XINFO STREAM =="
redis_cli XINFO STREAM "${STREAM_KEY}" || true

echo
echo "== XINFO GROUPS =="
redis_cli XINFO GROUPS "${STREAM_KEY}" || true

echo
echo "== XPENDING summary =="
redis_cli XPENDING "${STREAM_KEY}" "${CONSUMER_GROUP}" || true

echo
echo "== XPENDING details =="
redis_cli XPENDING "${STREAM_KEY}" "${CONSUMER_GROUP}" - + "${COUNT}" || true

echo
echo "== XAUTOCLAIM dry run command =="
echo "redis-cli -h ${REDIS_HOST} -p ${REDIS_PORT} XAUTOCLAIM ${STREAM_KEY} ${CONSUMER_GROUP} ${CLAIM_CONSUMER} ${MIN_IDLE_MS} 0-0 COUNT ${COUNT}"

echo
echo "== XACK command template =="
echo "redis-cli -h ${REDIS_HOST} -p ${REDIS_PORT} XACK ${STREAM_KEY} ${CONSUMER_GROUP} <message-id>"
