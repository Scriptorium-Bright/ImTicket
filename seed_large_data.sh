#!/bin/bash
echo "🚀 10,000석 부하 테스트 데이터를 MySQL에 직접 주입합니다..."

# SQL 실행 결과를 파일로 잠시 저장합니다.
mysql -h 127.0.0.1 -P 10046 -u capstone -pcider123 capstone < seed_large_data.sql > seed_output.txt

# 생성된 PerformanceTime ID 추출
PT_ID=$(tail -n 1 seed_output.txt | tr -d '[:space:]')

echo "=========================================================="
echo "✅ 데이터 세팅 완료! "
echo "🎯 타겟 PerformanceTime ID: $PT_ID"
echo "=========================================================="
echo "지금 바로 아래 명령어를 복사해서 실행하세요!"
echo ""
echo "k6 run -e PT_ID=$PT_ID load-test-reservation.js"
echo ""
