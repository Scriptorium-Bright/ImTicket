#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:10080}"
ENDPOINT="${ENDPOINT:-/api/reservation/pre-reserve}"
CONCURRENCY="${CONCURRENCY:-20}"
SEAT_IDS="${SEAT_IDS:-${SEAT_ID:-}}"
PERFORMANCE_TIME_ID="${PERFORMANCE_TIME_ID:-}"
JWT="${JWT:-}"
OUTPUT_DIR="${OUTPUT_DIR:-/tmp/imticket-lock-wait}"

if [[ -z "${SEAT_IDS}" ]]; then
  echo "Set SEAT_ID or SEAT_IDS. Example: SEAT_ID=1 $0" >&2
  exit 1
fi

if [[ -z "${PERFORMANCE_TIME_ID}" ]]; then
  echo "Set PERFORMANCE_TIME_ID for the requested seats." >&2
  exit 1
fi

if [[ -z "${JWT}" ]]; then
  echo "Set JWT with an authenticated bearer token." >&2
  exit 1
fi

mkdir -p "${OUTPUT_DIR}"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
RESULT_FILE="${OUTPUT_DIR}/lock-wait-${RUN_ID}.tsv"
BODY_FILE="${OUTPUT_DIR}/lock-wait-${RUN_ID}.json"

IFS=',' read -r -a SEAT_ID_ARRAY <<< "${SEAT_IDS}"
SEAT_JSON="$(printf '"%s",' "${SEAT_ID_ARRAY[@]}")"
SEAT_JSON="[${SEAT_JSON%,}]"

cat > "${BODY_FILE}" <<JSON
{"performanceTimeId":${PERFORMANCE_TIME_ID},"seatIds":${SEAT_JSON}}
JSON

echo -e "request\tstatus\ttime_total\tresponse_file" > "${RESULT_FILE}"

for index in $(seq 1 "${CONCURRENCY}"); do
  (
    RESPONSE_FILE="${OUTPUT_DIR}/lock-wait-${RUN_ID}-${index}.body"
    CURL_RESULT="$(
      curl -sS \
        -o "${RESPONSE_FILE}" \
        -w "%{http_code}\t%{time_total}" \
        -X POST "${BASE_URL}${ENDPOINT}" \
        -H "Authorization: Bearer ${JWT}" \
        -H "Content-Type: application/json" \
        --data-binary @"${BODY_FILE}" || echo -e "curl_error\t0"
    )"
    echo -e "${index}\t${CURL_RESULT}\t${RESPONSE_FILE}" >> "${RESULT_FILE}"
  ) &
done

wait

sort -n "${RESULT_FILE}" -o "${RESULT_FILE}"
cat "${RESULT_FILE}"

echo
echo "Result file: ${RESULT_FILE}"
echo "Request body: ${BODY_FILE}"
