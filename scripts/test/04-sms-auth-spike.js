import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '5s', target: 200 }, // 짧은 시간 내에 다수의 SMS 요청
    { duration: '10s', target: 200 },
    { duration: '5s', target: 0 },
  ],
};

export default function () {
  const url = 'http://localhost:8080/api/sms/certificate';
  // 동일한 IP에서 다양한(혹은 동일한) 번호로 대량 요청을 보내는 공격자 시나리오
  const payload = JSON.stringify({
    to: `010${Math.floor(Math.random() * 90000000) + 10000000}` // 랜덤한 전화번호
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  const res = http.post(url, payload, params);

  // Rate Limiter에 의해 어느 시점부터는 429 Too Many Requests가 반환되어야 함
  check(res, {
    'status is 200 (success)': (r) => r.status === 200,
    'status is 429 (rate limited)': (r) => r.status === 429,
  });

  // 짧은 주기로 무차별 요청
  sleep(0.1);
}
