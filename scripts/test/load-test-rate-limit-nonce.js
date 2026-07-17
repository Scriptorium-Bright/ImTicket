import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:10080';
const WALLET_ADDRESS = __ENV.WALLET_ADDRESS || '0x1234567890abcdef1234567890abcdef12345678';
const SLEEP_SECONDS = Number(__ENV.SLEEP_SECONDS || 0);

export const options = {
  stages: [
    { duration: '20s', target: 200 },
    { duration: '40s', target: 200 },
    { duration: '40s', target: 400 },
    { duration: '20s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<200'],
  },
};

const successCount = new Counter('nonce_success_count');
const rateLimitedCount = new Counter('nonce_rate_limited_count');
const unexpectedStatusCount = new Counter('nonce_unexpected_status_count');

export default function () {
  const response = http.get(
    `${BASE_URL}/api/user/nonce?walletAddress=${encodeURIComponent(WALLET_ADDRESS)}`,
    {
      headers: {
        Accept: 'application/json',
      },
      tags: {
        endpoint: 'nonce',
        scenario: 'rate-limit-heavy',
      },
    }
  );

  if (response.status === 200) {
    successCount.add(1);
  } else if (response.status === 429) {
    rateLimitedCount.add(1);
  } else {
    unexpectedStatusCount.add(1);
  }

  if (SLEEP_SECONDS > 0) {
    sleep(SLEEP_SECONDS);
  }
}
