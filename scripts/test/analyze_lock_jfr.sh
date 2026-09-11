#!/usr/bin/env bash
set -euo pipefail

# 부하 실행이 끝난 JFR에서 lock·GC·CPU 확인에 필요한 최소 산출물을 만든다.
# 원본 JFR은 수정하지 않는다.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JFR_FILE="${1:-}"
OUTPUT_DIR="${2:-}"

if [[ "${JFR_FILE}" == "--help" || "${JFR_FILE}" == "-h" ]]; then
  echo "사용법: $0 <recording.jfr> [analysis-output-dir]" >&2
  exit 0
fi
if [[ -z "${JFR_FILE}" ]]; then
  echo "사용법: $0 <recording.jfr> [analysis-output-dir]" >&2
  exit 2
fi
if [[ ! -f "${JFR_FILE}" || ! -s "${JFR_FILE}" ]]; then
  echo "JFR 파일을 찾지 못했거나 비어 있습니다: ${JFR_FILE}" >&2
  exit 1
fi
if ! command -v jfr > /dev/null 2>&1; then
  echo "jfr 명령을 찾지 못했습니다. JDK 21의 jfr를 PATH에 추가하십시오." >&2
  exit 1
fi

if [[ -z "${OUTPUT_DIR}" ]]; then
  file_name="$(basename "${JFR_FILE}")"
  file_name="${file_name%.jfr}"
  OUTPUT_DIR="${ROOT_DIR}/build/k6-results/jfr-analysis/${file_name}"
fi
mkdir -p "${OUTPUT_DIR}"

jfr summary "${JFR_FILE}" > "${OUTPUT_DIR}/jfr-summary.txt"
jfr view --width 160 gc "${JFR_FILE}" > "${OUTPUT_DIR}/jfr-gc-view.txt"
jfr print --stack-depth 64 \
  --events jdk.ThreadPark,jdk.JavaMonitorEnter,jdk.JavaMonitorWait,jdk.GarbageCollection,jdk.ExecutionSample,jdk.ThreadCPULoad \
  "${JFR_FILE}" > "${OUTPUT_DIR}/jfr-selected-events.txt"

grep -E -n -C 8 \
  'ReservationLockAspect|AbstractQueuedSynchronizer|LockSupport|ReentrantLock|JavaMonitorEnter|ThreadPark' \
  "${OUTPUT_DIR}/jfr-selected-events.txt" \
  > "${OUTPUT_DIR}/jfr-lock-matches.txt" || true

grep -E -n -C 6 \
  'GarbageCollection|G1|duration|heap' \
  "${OUTPUT_DIR}/jfr-selected-events.txt" \
  > "${OUTPUT_DIR}/jfr-gc-matches.txt" || true

cat > "${OUTPUT_DIR}/README.txt" <<EOF
JFR_FILE=${JFR_FILE}

우선 확인할 파일:
  jfr-summary.txt          이벤트 개수와 recording 범위
  jfr-gc-view.txt          GC pause/heap 요약
  jfr-lock-matches.txt     ThreadPark/JavaMonitorEnter와 lock stack 후보
  jfr-gc-matches.txt       GC event 후보
  jfr-selected-events.txt  lock·GC·CPU sample의 전체 출력

같은 시각을 다음 결과와 대조한다:
  ../run-summary.txt
  ../app-metrics.tsv
  ../mysql-metrics.tsv
  ../k6-summary.json
EOF

printf 'jfr_analysis=passed\noutput_dir=%s\n' "${OUTPUT_DIR}"
