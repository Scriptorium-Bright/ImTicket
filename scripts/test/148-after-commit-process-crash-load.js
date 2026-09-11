import http from 'k6/http';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:10080';
const performanceTimeId = positiveNumber('PT_ID');
const seatId = positiveNumber('SEAT_ID');
const memberId = positiveNumber('MEMBER_ID');
const benchmarkMemberIdBase = positiveNumberOrDefault('BENCHMARK_MEMBER_ID_BASE', 900000000);
const jwtSecret = required('JWT_SECRET');
const idempotencyKey = required('IDEMPOTENCY_KEY');
const requestTimeout = __ENV.REQUEST_TIMEOUT || '45s';
const wallet = __ENV.WALLET_ADDRESS || walletAddress(memberId);
const maxDuration = __ENV.MAX_DURATION || '50s';

const writeAttempts = new Counter('crash_write_attempts');
const writeSuccess = new Counter('crash_write_success');
const writeConflict = new Counter('crash_write_conflict');
const writeTransportFailure = new Counter('crash_write_transport_failure');
const writeUnexpected = new Counter('crash_write_unexpected');
const writeExpected = new Rate('crash_write_expected');
const writeDuration = new Trend('crash_write_duration', true);

export const options = {
  scenarios: {
    after_commit_process_crash: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      maxDuration,
      gracefulStop: '0s',
      exec: 'preReserve',
      tags: { workload: 'after-commit-process-crash' },
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function preReserve() {
  const response = http.post(
    `${baseUrl}/api/reservation/pre-reserve`,
    JSON.stringify({ performanceTimeId, seatIds: [seatId] }),
    {
      headers: {
        Authorization: `Bearer ${createJwt(memberId, wallet, jwtSecret)}`,
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
      },
      timeout: requestTimeout,
      responseCallback: http.expectedStatuses(200, 201, 409, 429, 500, 503),
      tags: { endpoint: 'pre-reserve', workload: 'after-commit-process-crash' },
    },
  );

  writeAttempts.add(1);
  writeDuration.add(response.timings.duration);

  const body = safeJson(response);
  const expected = classifyResponse(response, body);
  writeExpected.add(expected);
  check(response, {
    '프로세스 종료 시험 요청 결과가 분류된다': () => expected,
  });

  console.log(JSON.stringify({
    event: 'pre_reserve_result',
    status: response.status,
    error_code: response.error_code || null,
    duration_ms: response.timings.duration,
  }));
}

function classifyResponse(response, body) {
  if (response.status === 200 || response.status === 201) {
    writeSuccess.add(1);
    return body?.success === true && Number(body?.data?.id) > 0;
  }
  if (response.status === 409) {
    writeConflict.add(1, { error_code: errorCode(body) });
    return true;
  }
  if (response.status === 0) {
    writeTransportFailure.add(1, { error_code: response.error_code || 'UNKNOWN' });
    return true;
  }
  if (response.status === 429) {
    return true;
  }
  writeUnexpected.add(1, { status: String(response.status), error_code: errorCode(body) });
  return false;
}

function createJwt(subjectMemberId, walletAddressValue, secret) {
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = base64Url(JSON.stringify({
    memberId: subjectMemberId,
    walletAddress: walletAddressValue,
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

function walletAddress(subjectMemberId) {
  const fixtureNumber = subjectMemberId - benchmarkMemberIdBase;
  if (fixtureNumber <= 0) {
    throw new Error(
      `회원 ID가 benchmark base보다 작습니다. memberId=${subjectMemberId}, base=${benchmarkMemberIdBase}`,
    );
  }
  return `0xbench${String(fixtureNumber).padStart(34, '0')}`;
}

function safeJson(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

function errorCode(body) {
  return body?.error?.code || body?.errorCode || 'UNKNOWN';
}

function required(name) {
  const value = __ENV[name];
  if (!value) {
    throw new Error(`${name} 환경변수가 필요합니다.`);
  }
  return value;
}

function positiveNumber(name) {
  const value = Number(required(name));
  if (!Number.isInteger(value) || value <= 0) {
    throw new Error(`${name}은 양의 정수여야 합니다. actual=${__ENV[name]}`);
  }
  return value;
}

function positiveNumberOrDefault(name, fallback) {
  const raw = __ENV[name];
  if (raw === undefined || raw === '') {
    return fallback;
  }
  const value = Number(raw);
  if (!Number.isInteger(value) || value <= 0) {
    throw new Error(`${name}은 양의 정수여야 합니다. actual=${raw}`);
  }
  return value;
}
