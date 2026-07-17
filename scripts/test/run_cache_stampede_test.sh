#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "🧹 1. Redis 캐시를 초기화합니다..."
docker exec -i imticket-redis redis-cli FLUSHALL

echo "🚀 2. 캐시 스탬피드(Cache Stampede) 재현 부하 테스트 시작!"
echo "   (3000명의 유저가 동시에 텅 빈 캐시를 찌릅니다)"
k6 run -e PERF_ID=1 "${SCRIPT_DIR}/05-cache-stampede.js"
