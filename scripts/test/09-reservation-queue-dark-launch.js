import http from 'k6/http';
import { sleep } from 'k6';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:10080';
const managementBaseUrl = __ENV.MANAGEMENT_BASE_URL || baseUrl;
const performanceTimeId = requiredPositiveNumber('PT_ID');
const seatId = requiredPositiveNumber('SEAT_ID');
const concurrency = requiredPositiveNumber('CONCURRENCY');
const startAtEpochMs = requiredPositiveNumber('START_AT_EPOCH_MS');
const jwtSecret = required('JWT_SECRET');
const walletAddress = __ENV.WALLET_ADDRESS || '0xQueueDarkLaunchUser';
const requestTimeout = __ENV.REQUEST_TIMEOUT || '15s';
const maxDuration = __ENV.MAX_DURATION || '2m';
const jwt = __ENV.JWT || createJwt(walletAddress, jwtSecret);

const attempts = new Counter('queue_attempts');
const accepted = new Counter('queue_accepted_202');
const full = new Counter('queue_full_429');
const conflict = new Counter('queue_conflict_409');
const unavailable = new Counter('queue_unavailable_503');
const serverError = new Counter('queue_5xx');
const transportFailure = new Counter('queue_transport_failure');
const unexpected = new Counter('queue_unexpected_http');
const validResponse = new Rate('queue_valid_response');
const enqueueDuration = new Trend('queue_enqueue_duration', true);
const requestStartLag = new Trend('queue_request_start_lag', true);

export const options = {
  scenarios: {
    queue_one_shot: {
      executor: 'per-vu-iterations',
      vus: concurrency,
      iterations: 1,
      maxDuration,
      gracefulStop: '0s',
      tags: {
        scenario: 'reservation-queue-dark-launch',
      },
    },
  },
  thresholds: {
    queue_transport_failure: ['count==0'],
    queue_5xx: ['count==0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const response = http.get(`${managementBaseUrl}/actuator/health`, { timeout: requestTimeout });
  if (response.status !== 200) {
    throw new Error(`health check failed: status=${response.status}`);
  }
  return {
    payload: JSON.stringify({ performanceTimeId, seatIds: [seatId] }),
  };
}

export default function (data) {
  const remainingSeconds = (startAtEpochMs - Date.now()) / 1000;
  if (remainingSeconds > 0) {
    sleep(remainingSeconds);
  }
  requestStartLag.add(Math.max(0, Date.now() - startAtEpochMs));

  const response = http.post(
    `${baseUrl}/api/reservation/pre-reserve/queue`,
    data.payload,
    {
      headers: {
        Authorization: `Bearer ${jwt}`,
        'Content-Type': 'application/json',
        'Idempotency-Key': newIdempotencyKey(),
      },
      responseCallback: http.expectedStatuses(202, 409, 429, 503),
      timeout: requestTimeout,
      tags: {
        endpoint: 'reservation-queue-enqueue',
      },
    },
  );

  attempts.add(1);
  enqueueDuration.add(response.timings.duration);
  const body = safeJson(response);
  let valid = false;

  if (response.status === 0) {
    transportFailure.add(1, { error_code: response.error_code || 'UNKNOWN' });
  } else if (
    response.status === 202
    && body?.success === true
    && body?.data?.status === 'WAITING'
    && typeof body?.data?.ticketId === 'string'
  ) {
    accepted.add(1);
    valid = true;
  } else if (response.status === 429 && body?.error?.code === 'RESERVATION_QUEUE_FULL') {
    full.add(1);
    valid = true;
  } else if (response.status === 409) {
    conflict.add(1, { error_code: body?.error?.code || 'NO_CODE' });
  } else if (response.status === 503) {
    unavailable.add(1, { error_code: body?.error?.code || 'NO_CODE' });
  } else if (response.status >= 500 && response.status <= 599) {
    serverError.add(1, { status: String(response.status) });
  } else {
    unexpected.add(1, {
      status: String(response.status),
      error_code: body?.error?.code || 'NO_CODE',
    });
  }
  validResponse.add(valid);
}

function newIdempotencyKey() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (character) => {
    const random = Math.floor(Math.random() * 16);
    const value = character === 'x' ? random : (random & 0x3) | 0x8;
    return value.toString(16);
  });
}

function safeJson(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
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
  const value = __ENV[name];
  if (!value) {
    throw new Error(`${name} is required`);
  }
  return value;
}

function requiredPositiveNumber(name) {
  const value = Number(required(name));
  if (!Number.isInteger(value) || value <= 0) {
    throw new Error(`${name} must be a positive integer: actual=${__ENV[name]}`);
  }
  return value;
}
