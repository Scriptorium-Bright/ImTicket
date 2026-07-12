#!/bin/bash

# ==================================================================
# 💡 ShedLock 분산 락 검증용 멀티 서버 시뮬레이션 스크립트 💡
# ==================================================================

# 1. 애플리케이션 빌드
echo "🚀 프로젝트 빌드 시작 (테스트 제외)..."
./gradlew build -x test

if [ $? -ne 0 ]; then
  echo "❌ 빌드 실패! 스크립트를 종료합니다."
  exit 1
fi

echo "✅ 빌드 성공!"

# 2. 데이터베이스 초기화 및 더미 데이터 삽입
echo "🧹 DB 초기화 및 더미 예약 데이터 삽입 중..."

DB_HOST="127.0.0.1"
DB_PORT="10046"
DB_USER="capstone"
DB_PASS="cider123"
DB_NAME="capstone"

# MySQL에 강제로 더미 데이터를 넣고 shedlock 테이블 초기화
# 주의: 이 스크립트는 로컬 MySQL 클라이언트가 설치되어 있어야 동작합니다.
mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS $DB_NAME <<EOF
-- 1. 기존 분산 락 해제 (ShedLock 테이블 비우기)
CREATE TABLE IF NOT EXISTS shedlock(name VARCHAR(64) NOT NULL, lock_until TIMESTAMP(3) NOT NULL,
    locked_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), locked_by VARCHAR(255) NOT NULL, PRIMARY KEY (name));
TRUNCATE TABLE shedlock;

-- 2. 테스트용 더미 회원 생성 (있으면 무시)
INSERT INTO member (wallet_address, user_role, sms_verified, wallet_verified)
VALUES ('0xDUMMY1', 'ROLE_USER', true, true)
ON DUPLICATE KEY UPDATE id=id;

-- 3. 회원 ID 가져오기
SET @member_id = (SELECT id FROM member WHERE wallet_address = '0xDUMMY1' LIMIT 1);

-- 4. 만료된(결제 기한이 지난) 테스트용 예약 데이터 3건 삽입
INSERT INTO reservation (reservation_code, total_price, member_id, reservation_status, reservation_date, reservation_expired_time)
VALUES
('test-expired-1', 10000, @member_id, 'PENDING_PAYMENT', NOW(), DATE_SUB(NOW(), INTERVAL 1 HOUR)),
('test-expired-2', 20000, @member_id, 'PENDING_PAYMENT', NOW(), DATE_SUB(NOW(), INTERVAL 1 HOUR)),
('test-expired-3', 30000, @member_id, 'PENDING_PAYMENT', NOW(), DATE_SUB(NOW(), INTERVAL 1 HOUR))
ON DUPLICATE KEY UPDATE reservation_expired_time = DATE_SUB(NOW(), INTERVAL 1 HOUR), reservation_status = 'PENDING_PAYMENT';
EOF

if [ $? -eq 0 ]; then
  echo "✅ 데이터 삽입 완료!"
else
  echo "⚠️ MySQL 데이터 삽입 중 오류가 발생했습니다. (MySQL 클라이언트가 없거나 접속 정보가 틀릴 수 있습니다.)"
  echo "하지만 서버 실행은 계속 진행합니다."
fi

# 3. 분산 서버(다중 JVM) 환경 시뮬레이션 실행
echo "=================================================================="
echo "🔥 두 대의 서버를 각각 다른 포트로 실행하여 분산 환경을 시뮬레이션합니다."
echo "=================================================================="

# 서버 A 실행 (포트 10080)
SERVER_PORT=10080 java -jar build/libs/ticket-0.0.1-SNAPSHOT.jar > server_a.log 2>&1 &
PID_A=$!
echo "🟢 [Node A] 포트 10080 실행 중... (PID: $PID_A)"

# 서버 B 실행 (포트 10081)
SERVER_PORT=10081 java -jar build/libs/ticket-0.0.1-SNAPSHOT.jar > server_b.log 2>&1 &
PID_B=$!
echo "🟢 [Node B] 포트 10081 실행 중... (PID: $PID_B)"

echo "⏳ 스케줄러가 트리거될 때까지 약 45초 대기합니다 (스케줄 주기 30초)..."
sleep 45

# 4. 로그에서 ShedLock 획득 여부 확인
echo "=================================================================="
echo "🔎 스케줄러 로그 분석 결과:"
echo "=================================================================="

echo "📌 [Node A] 로그 중 'cleanup' 관련 항목:"
grep -i -E "cleanup|shedlock" server_a.log | tail -n 5 || echo "(관련 로그 없음)"

echo ""
echo "📌 [Node B] 로그 중 'cleanup' 관련 항목:"
grep -i -E "cleanup|shedlock" server_b.log | tail -n 5 || echo "(관련 로그 없음)"

echo "=================================================================="
echo "🛑 테스트 종료: 백그라운드 서버를 종료합니다."
kill -9 $PID_A $PID_B
echo "✅ 스크립트가 종료되었습니다. 전체 로그는 server_a.log 및 server_b.log 파일에서 확인 가능합니다."
