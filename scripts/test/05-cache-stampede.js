import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '10s', target: 1000 }, // 캐시 만료 시점 전후로 1000명 동시 접속
    { duration: '20s', target: 1000 },
    { duration: '5s', target: 0 },
  ],
};

export default function () {
  const performanceId = 1; // 테스트용 공연 ID
  const url = `http://localhost:8080/api/performance/details/${performanceId}`;

  const res = http.get(url);

  // Cache Stampede 발생 시 응답 지연(Timeout)이나 에러(500)가 발생할 수 있음
  check(res, {
    'status is 200': (r) => r.status === 200,
    'response time < 500ms': (r) => r.timings.duration < 500,
  });

  // 오픈런 대기 중인 사용자가 간헐적으로 새로고침하는 상황
  sleep(Math.random() * 2); // 0~2초 랜덤 대기
}
