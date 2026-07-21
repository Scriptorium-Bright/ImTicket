import http from 'k6/http';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:10080';
const jwtSecret = required('JWT_SECRET');
const requestTimeout = __ENV.REQUEST_TIMEOUT || '15s';
const walletAddress = __ENV.WALLET_ADDRESS || '0xLoadTestUser';
const jwt = __ENV.JWT || createJwt(walletAddress, jwtSecret);

export const options = {
  vus: 1,
  iterations: 1,
};

export default function () {
  const response = http.get(`${baseUrl}/api/reservation/pre-reserve`, {
    headers: {
      Authorization: `Bearer ${jwt}`,
    },
    responseCallback: http.expectedStatuses(405),
    timeout: requestTimeout,
    tags: {
      endpoint: 'pre-reserve-warmup',
    },
  });

  if (response.status !== 405) {
    throw new Error(`reservation path warm-up failed: expected=405 actual=${response.status} body=${response.body}`);
  }
}

function createJwt(address, secret) {
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = base64Url(JSON.stringify({
    walletAddress: address,
    role: 'ROLE_USER',
    iat: now,
    exp: now + 3600,
  }));
  const unsignedToken = `${header}.${payload}`;
  const signature = crypto.hmac('sha256', secret, unsignedToken, 'base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
  return `${unsignedToken}.${signature}`;
}

function base64Url(value) {
  return encoding.b64encode(value)
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
}

function required(name) {
  if (!__ENV[name]) {
    throw new Error(`${name} 환경변수가 필요합니다.`);
  }
  return __ENV[name];
}
