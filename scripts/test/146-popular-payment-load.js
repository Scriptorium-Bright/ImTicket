import http from 'k6/http';
import exec from 'k6/execution';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:10080';
const jwtSecret = required('JWT_SECRET');
const mode = (__ENV.MODE || 'verify').toLowerCase();
const duration = __ENV.DURATION || '2m';
const rate = positiveNumberOrDefault('RATE', 10);
const preAllocatedVus = positiveNumberOrDefault('PRE_ALLOCATED_VUS', 50);
const maxVus = positiveNumberOrDefault('MAX_VUS', Math.max(preAllocatedVus, 200));
const requestTimeout = __ENV.REQUEST_TIMEOUT || '15s';
const benchmarkMemberIdBase = positiveNumberOrDefault('BENCHMARK_MEMBER_ID_BASE', 900000000);
const dataFile = required('PAYMENT_DATA_FILE');
const records = loadRecords(dataFile, mode);

if (!['prepare', 'verify'].includes(mode)) {
  throw new Error(`MODE는 prepare 또는 verify여야 합니다. actual=${mode}`);
}
if (records.length === 0) {
  throw new Error(`결제 테스트 입력 데이터가 비어 있습니다. file=${dataFile}`);
}

const prepareAttempts = new Counter('popular_payment_prepare_attempts');
const prepareSuccess = new Counter('popular_payment_prepare_success');
const prepareUnexpected = new Counter('popular_payment_prepare_unexpected');
const prepareExpected = new Rate('popular_payment_prepare_expected');
const prepareDuration = new Trend('popular_payment_prepare_duration', true);
const verifyAttempts = new Counter('popular_payment_verify_attempts');
const verifySuccess = new Counter('popular_payment_verify_success');
const verifyUnexpected = new Counter('popular_payment_verify_unexpected');
const verifyTransportFailure = new Counter('popular_payment_verify_transport_failure');
const verifyExpected = new Rate('popular_payment_verify_expected');
const verifyDuration = new Trend('popular_payment_verify_duration', true);

export const options = {
  scenarios: {
    payment_requests: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: preAllocatedVus,
      maxVUs: maxVus,
      exec: 'paymentRequest',
      tags: {
        workload: mode === 'prepare' ? 'popular-payment-prepare' : 'popular-payment-verify',
        mode,
      },
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function paymentRequest() {
  const record = records[iterationIndex() % records.length];
  if (mode === 'prepare') {
    preparePayment(record);
  } else {
    verifyPayment(record);
  }
}

function preparePayment(record) {
  const response = http.post(
    `${baseUrl}/api/payments/prepare`,
    JSON.stringify({ reservationId: record.reservationId }),
    requestOptions(record.memberId, `p146prep-${record.reservationId}`),
  );
  prepareAttempts.add(1);
  prepareDuration.add(response.timings.duration);

  const body = safeJson(response);
  const expected = response.status === 200
    && body?.success === true
    && Number(body?.data?.paymentOrderId) > 0;
  if (expected) {
    prepareSuccess.add(1);
  } else {
    prepareUnexpected.add(1, { status: String(response.status), error_code: errorCode(body) });
  }
  prepareExpected.add(expected);
  check(response, {
    'payment prepare 응답이 성공한다': () => expected,
    'payment prepare correlation id가 반환된다': (res) => Boolean(res.headers['X-Correlation-Id']),
  });
}

function verifyPayment(record) {
  const response = http.post(
    `${baseUrl}/api/payments/${record.paymentOrderId}/verify`,
    JSON.stringify({ providerPaymentId: `fake:${record.merchantOrderId}` }),
    requestOptions(record.memberId),
  );
  verifyAttempts.add(1);
  verifyDuration.add(response.timings.duration);

  const body = safeJson(response);
  const expected = response.status === 200
    && body?.success === true
    && body?.data?.paymentStatus === 'APPLIED'
    && body?.data?.reservationStatus === 'SUCCESS';
  if (expected) {
    verifySuccess.add(1);
  } else if (response.status === 0) {
    verifyTransportFailure.add(1, { error_code: response.error_code || 'UNKNOWN' });
  } else {
    verifyUnexpected.add(1, { status: String(response.status), error_code: errorCode(body) });
  }
  verifyExpected.add(expected);
  check(response, {
    'payment verify가 예약 완료를 반환한다': () => expected,
    'payment verify correlation id가 반환된다': (res) => Boolean(res.headers['X-Correlation-Id']),
  });
}

function requestOptions(memberId, idempotencyKey) {
  const headers = {
    Authorization: `Bearer ${createJwt(memberId, walletAddress(memberId), jwtSecret)}`,
    'Content-Type': 'application/json',
  };
  if (idempotencyKey) {
    headers['Idempotency-Key'] = idempotencyKey;
  }
  return {
    headers,
    responseCallback: http.expectedStatuses(200, 400, 409, 500, 503),
    timeout: requestTimeout,
  };
}

function walletAddress(memberId) {
  const fixtureNumber = memberId - benchmarkMemberIdBase;
  if (fixtureNumber <= 0) {
    throw new Error(`회원 ID가 benchmark base보다 작습니다. memberId=${memberId}`);
  }
  return `0xbench${String(fixtureNumber).padStart(34, '0')}`;
}

function createJwt(memberId, wallet, secret) {
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = base64Url(JSON.stringify({
    memberId,
    walletAddress: wallet,
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

function loadRecords(path, recordMode) {
  return open(path)
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0 && !line.startsWith('#'))
    .map((line) => line.split('\t'))
    .map((parts) => recordMode === 'prepare'
      ? {
        reservationId: positiveInteger(parts[0], 'reservationId'),
        memberId: positiveInteger(parts[1], 'memberId'),
      }
      : {
        paymentOrderId: positiveInteger(parts[0], 'paymentOrderId'),
        memberId: positiveInteger(parts[1], 'memberId'),
        merchantOrderId: requiredValue(parts[2], 'merchantOrderId'),
      });
}

function iterationIndex() {
  const scenarioIndex = exec.scenario.iterationInTest;
  if (Number.isInteger(scenarioIndex) && scenarioIndex >= 0) {
    return scenarioIndex;
  }
  return ((__VU - 1) * 1000000) + __ITER;
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

function requiredValue(value, name) {
  if (!value) {
    throw new Error(`${name} 값이 필요합니다.`);
  }
  return value;
}

function positiveInteger(value, name) {
  const number = Number(value);
  if (!Number.isInteger(number) || number <= 0) {
    throw new Error(`${name}은 양의 정수여야 합니다. actual=${value}`);
  }
  return number;
}

function positiveNumberOrDefault(name, fallback) {
  const raw = __ENV[name];
  if (raw === undefined || raw === '') {
    return fallback;
  }
  return positiveInteger(raw, name);
}
