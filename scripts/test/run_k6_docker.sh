#!/usr/bin/env bash
set -euo pipefail

# k6를 앱 프로세스와 별도 컨테이너에서 실행한다.
# macOS Docker Desktop에서는 호스트 앱을 host.docker.internal로 접근한다.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
K6_IMAGE="${K6_IMAGE:-grafana/k6:latest}"

exec docker run --rm \
  --add-host=host.docker.internal:host-gateway \
  -v "${ROOT_DIR}:${ROOT_DIR}" \
  -w "${ROOT_DIR}" \
  "${K6_IMAGE}" "$@"
