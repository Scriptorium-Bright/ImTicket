import http from 'k6/http';
import { check } from 'k6';

// 조회할 Performance ID (기본값 1)
const PERF_ID = __ENV.PERF_ID || 1;

export const options = {
    // 캐시 스탬피드는 '동시에' 엄청난 요청이 쏟아질 때 발생하므로, 짧은 시간에 VUs를 확 끌어올립니다.
    vus: 3000,
    duration: '10s', 
};

const BASE_URL = 'http://localhost:10080/api';

export default function () {
    // ?cache=true 쿼리 파라미터를 주어 PerformanceService.viewPerformanceDetailsCached() 를 타게 만듭니다.
    const res = http.get(`${BASE_URL}/performance/intro/${PERF_ID}?cache=true`);

    check(res, {
        '성공 (200)': (r) => r.status === 200,
        '서버 에러 (500) - DB 커넥션 고갈': (r) => r.status === 500,
    });
}
