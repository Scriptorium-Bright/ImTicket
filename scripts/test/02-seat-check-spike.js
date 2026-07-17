import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '5s', target: 1000 }, // 5초 동안 1000명까지 VUs 증가 (새로고침 폭주)
    { duration: '15s', target: 1000 }, // 15초 동안 1000명 유지
    { duration: '5s', target: 0 },    // 5초 동안 0명으로 감소
  ],
};

export default function () {
  const performanceTimeId = 1; // 테스트용 공연 시간 ID
  const url = `http://localhost:8080/api/reservation/empty-seat/${performanceTimeId}`;

  const res = http.get(url);

  check(res, {
    'status is 200': (r) => r.status === 200,
    'response time < 200ms': (r) => r.timings.duration < 200,
  });

  // 유저당 1초에 한 번씩 새로고침한다고 가정
  sleep(1);
}
