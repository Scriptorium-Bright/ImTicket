import http from 'k6/http';
import { check, sleep } from 'k6';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

// 테스트 등급 (1, 2, 3)
const GRADE = __ENV.GRADE || 1;
// seed_large_data.sh 에서 출력된 target_performance_time_id (기본값 1)
const PT_ID = __ENV.PT_ID || 1;
const JWT_SECRET = 'change-me-jwt-secret-change-me-jwt-secret-change-me-jwt-secret';

let vusCount = 1000;
let durationStr = '1m';

if (GRADE == 1) {
    vusCount = 3000;
} else if (GRADE == 2) {
    vusCount = 10000;
} else if (GRADE == 3) {
    vusCount = 30000;
}

export const options = {
    scenarios: {
        reservation_stress_test: {
            executor: 'constant-vus',
            vus: vusCount,
            duration: durationStr,
        },
    },
    thresholds: {
        http_req_failed: ['rate<1.0'],
    },
};

const BASE_URL = 'http://localhost:10080/api';

// K6 환경에서 JWT 토큰을 직접 생성하는 함수 (스프링 시큐리티 우회 목적)
function generateJWT(walletAddress, secret) {
    const header = { alg: 'HS256', typ: 'JWT' };
    const payload = {
        walletAddress: walletAddress,
        role: 'ROLE_USER',
        iat: Math.floor(Date.now() / 1000),
        exp: Math.floor(Date.now() / 1000) + 3600
    };

    function toBase64Url(obj) {
        let b64 = encoding.b64encode(JSON.stringify(obj));
        return b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    }

    const encodedHeader = toBase64Url(header);
    const encodedPayload = toBase64Url(payload);
    const message = `${encodedHeader}.${encodedPayload}`;

    let signature = crypto.hmac('sha256', secret, message, 'base64');
    signature = signature.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

    return `${message}.${signature}`;
}

export function setup() {
    console.log(`🚀 [Setup] Fetching seat information for PerformanceTimeId = ${PT_ID}`);
    const res = http.get(`${BASE_URL}/seats/${PT_ID}`);
    if (res.status !== 200) {
        throw new Error(`좌석 정보를 불러오지 못했습니다. 상태 코드: ${res.status}`);
    }
    
    const body = JSON.parse(res.body);
    const seatIds = body.data.map(s => s.seatId || s.id);
    
    if (!seatIds || seatIds.length === 0) {
        throw new Error("❌ 좌석이 하나도 없습니다! 먼저 bash seed_large_data.sh 를 실행하세요.");
    }
    
    console.log(`✅ [Setup] ${seatIds.length}개의 좌석을 찾았습니다. 부하 테스트를 시작합니다.`);
    return { seatIds: seatIds };
}

export default function (data) {
    const seatIds = data.seatIds;
    const targetSeatId = seatIds[Math.floor(Math.random() * seatIds.length)];
    const walletAddress = '0xTestUser_Dummy';

    // 백엔드의 코드를 전혀 수정하지 않고, 테스트 스크립트 단에서 완벽한 JWT 토큰을 만들어 냄
    const token = generateJWT(walletAddress, JWT_SECRET);

    const payload = JSON.stringify({
        performanceTimeId: parseInt(PT_ID),
        seatIds: [targetSeatId]
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${token}`
        },
    };

    // 실제 프로덕션과 100% 동일한 엔드포인트 호출
    const res = http.post(`${BASE_URL}/reservation/pre-reserve`, payload, params);

    check(res, {
        '성공 (200/201)': (r) => r.status === 200 || r.status === 201,
        '이미 예약됨 (409 Conflict)': (r) => r.status === 409,
        '회원 못찾음 (404)': (r) => r.status === 404,
        '서버 에러 (500) - Lock/Connection Timeout': (r) => r.status === 500,
        '인증 에러 (401/403)': (r) => r.status === 401 || r.status === 403,
    });
    
    sleep(1);
}
