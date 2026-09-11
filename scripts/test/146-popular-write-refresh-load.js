import http from 'k6/http';
import exec from 'k6/execution';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:10080';
const performanceTimeId = positiveNumber('PT_ID');
const jwtSecret = required('JWT_SECRET');
const mode = (__ENV.MODE || 'write').toLowerCase();
const duration = __ENV.DURATION || '20s';
const writeRate = nonNegativeNumber(__ENV.WRITE_RATE || 0);
const readRate = nonNegativeNumber(__ENV.READ_RATE || 0);
const readBurstVus = nonNegativeNumber(__ENV.READ_BURST_VUS || 0);
const preAllocatedVus = positiveNumberOrDefault('PRE_ALLOCATED_VUS', 50);
const maxVus = positiveNumberOrDefault('MAX_VUS', Math.max(preAllocatedVus, 200));
const readPreAllocatedVus = positiveNumberOrDefault('READ_PRE_ALLOCATED_VUS', 100);
const readMaxVus = positiveNumberOrDefault('READ_MAX_VUS', Math.max(readPreAllocatedVus, 500));
const burstDelaySeconds = positiveNumberOrDefault('BURST_DELAY_SECONDS', 2);
const memberIdBase = positiveNumberOrDefault('MEMBER_ID_BASE', 900009980);
const memberPoolSize = positiveNumberOrDefault('MEMBER_POOL_SIZE', 21);
const benchmarkMemberIdBase = positiveNumberOrDefault('BENCHMARK_MEMBER_ID_BASE', 900000000);
const idempotencyPrefix = required('IDEMPOTENCY_PREFIX');
const requestTimeout = __ENV.REQUEST_TIMEOUT || '15s';
const seatCountLimit = positiveNumberOrDefault('SEAT_COUNT_LIMIT', 1400);

if (!['write', 'mixed', 'invalidation-burst'].includes(mode)) {
  throw new Error(`MODE는 write, mixed 또는 invalidation-burst여야 합니다. actual=${mode}`);
}
if (mode === 'write' && writeRate <= 0) {
  throw new Error('write mode는 WRITE_RATE가 1 이상이어야 합니다.');
}
if (mode === 'mixed' && (writeRate <= 0 || readRate <= 0)) {
  throw new Error('mixed mode는 WRITE_RATE와 READ_RATE가 모두 1 이상이어야 합니다.');
}
if (mode === 'invalidation-burst' && readBurstVus <= 0) {
  throw new Error('invalidation-burst mode는 READ_BURST_VUS가 1 이상이어야 합니다.');
}
if (!/^[0-9a-f]{8}$/.test(idempotencyPrefix)) {
  throw new Error('IDEMPOTENCY_PREFIX는 UUID 첫 블록에 사용할 8자리 소문자 hexadecimal이어야 합니다.');
}
if (memberPoolSize > 10000) {
  throw new Error('MEMBER_POOL_SIZE는 10000 이하로 설정합니다.');
}

const writeAttempts = new Counter('popular_write_attempts');
const writeSuccess = new Counter('popular_write_success');
const writeConflict = new Counter('popular_write_conflict');
const writeRateLimited = new Counter('popular_write_rate_limited');
const writeUnexpected = new Counter('popular_write_unexpected');
const writeTransportFailure = new Counter('popular_write_transport_failure');
const writeExpected = new Rate('popular_write_expected');
const writeDuration = new Trend('popular_write_duration', true);

const readAttempts = new Counter('popular_read_attempts');
const readSuccess = new Counter('popular_read_success');
const readUnexpected = new Counter('popular_read_unexpected');
const readTransportFailure = new Counter('popular_read_transport_failure');
const readExpected = new Rate('popular_read_expected');
const readDuration = new Trend('popular_read_duration', true);
const readResponseBytes = new Trend('popular_read_response_bytes');

const scenarioDefinitions = {};
if (mode === 'write' || mode === 'mixed') {
  scenarioDefinitions.popular_writes = {
    executor: 'constant-arrival-rate',
    rate: writeRate,
    timeUnit: '1s',
    duration,
    preAllocatedVUs: preAllocatedVus,
    maxVUs: maxVus,
    startTime: `${burstDelaySeconds}s`,
    exec: 'writeSeat',
    tags: { workload: 'popular-seat-write', mode },
  };
}
if (mode === 'mixed') {
  scenarioDefinitions.popular_reads = {
    executor: 'constant-arrival-rate',
    rate: readRate,
    timeUnit: '1s',
    duration,
    preAllocatedVUs: readPreAllocatedVus,
    maxVUs: readMaxVus,
    startTime: `${burstDelaySeconds}s`,
    exec: 'readSeatMap',
    tags: { workload: 'popular-seat-read-refresh', mode },
  };
}
if (mode === 'invalidation-burst') {
  scenarioDefinitions.invalidation_reads = {
    executor: 'per-vu-iterations',
    vus: readBurstVus,
    iterations: 1,
    maxDuration: __ENV.MAX_DURATION || '2m',
    gracefulStop: '0s',
    startTime: `${burstDelaySeconds}s`,
    exec: 'readSeatMap',
    tags: { workload: 'popular-seat-invalidation-burst', mode },
  };
}

export const options = {
  scenarios: scenarioDefinitions,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const response = http.get(`${baseUrl}/api/seats/${performanceTimeId}`, {
    timeout: requestTimeout,
    responseCallback: http.expectedStatuses(200),
  });
  if (response.status !== 200) {
    throw new Error(`좌석 조회 setup 실패: status=${response.status}, body=${response.body}`);
  }

  const seats = safeJson(response)?.data || [];
  const availableSeatIds = seats
    .filter((seat) => seat?.seatStatus === 'AVAILABLE')
    .map((seat) => Number(seat.seatId ?? seat.id))
    .filter((id) => Number.isInteger(id) && id > 0)
    .sort((left, right) => left - right)
    .slice(0, seatCountLimit);

  if (availableSeatIds.length === 0) {
    throw new Error(`PT_ID=${performanceTimeId}에 AVAILABLE 좌석이 없습니다.`);
  }

  const durationSeconds = parseDurationSeconds(duration);
  const requiredWriteSeats = mode === 'write' || mode === 'mixed'
    ? Math.ceil(writeRate * durationSeconds)
    : 1;
  if (availableSeatIds.length < requiredWriteSeats) {
    throw new Error(
      `쓰기 좌석이 부족합니다. required=${requiredWriteSeats}, available=${availableSeatIds.length}, `
      + `WRITE_RATE=${writeRate}, DURATION=${duration}`,
    );
  }

  if (mode === 'invalidation-burst') {
    const invalidationSeatId = availableSeatIds[0];
    const invalidationResponse = http.post(
      `${baseUrl}/api/reservation/pre-reserve`,
      JSON.stringify({ performanceTimeId, seatIds: [invalidationSeatId] }),
      requestOptions(memberIdBase, walletAddress(memberIdBase), idempotencyKey(0)),
    );
    if (invalidationResponse.status !== 200 && invalidationResponse.status !== 201) {
      throw new Error(
        `invalidation setup pre-reserve 실패: status=${invalidationResponse.status}, body=${invalidationResponse.body}`,
      );
    }
  }

  return {
    availableSeatIds,
    startAt: Date.now() + burstDelaySeconds * 1000,
  };
}

export function writeSeat(data) {
  waitForStart(data.startAt);
  const sequence = iterationIndex();
  const seatId = data.availableSeatIds[sequence % data.availableSeatIds.length];
  const memberOffset = sequence % memberPoolSize;
  const memberId = memberIdBase + memberOffset;
  const response = http.post(
    `${baseUrl}/api/reservation/pre-reserve`,
    JSON.stringify({ performanceTimeId, seatIds: [seatId] }),
    requestOptions(memberId, walletAddress(memberId), idempotencyKey(sequence)),
  );

  writeAttempts.add(1);
  writeDuration.add(response.timings.duration);
  const body = safeJson(response);
  const expected = classifyWrite(response, body);
  writeExpected.add(expected);
  check(response, {
    'popular write 응답이 분류된다': () => expected,
    'popular write correlation id가 반환된다': (res) => Boolean(res.headers['X-Correlation-Id']),
  });
}

export function readSeatMap(data) {
  waitForStart(data.startAt);
  const response = http.get(`${baseUrl}/api/seats/${performanceTimeId}`, {
    timeout: requestTimeout,
    responseCallback: http.expectedStatuses(200, 503),
    tags: { endpoint: 'seat-map', target: 'popular-seat-read-refresh' },
  });

  readAttempts.add(1);
  readDuration.add(response.timings.duration);
  readResponseBytes.add(response.body ? response.body.length : 0);
  const body = safeJson(response);
  const expected = response.status === 200 && body?.success === true && Array.isArray(body.data);
  if (response.status === 200 && expected) {
    readSuccess.add(1);
  } else if (response.status === 0) {
    readTransportFailure.add(1);
  } else {
    readUnexpected.add(1, { status: String(response.status), error_code: errorCode(body) });
  }
  readExpected.add(expected);
  check(response, {
    'popular seat map 응답이 성공한다': () => expected,
    'popular seat map correlation id가 반환된다': (res) => Boolean(res.headers['X-Correlation-Id']),
  });
}

function classifyWrite(response, body) {
  if (response.status === 200 || response.status === 201) {
    writeSuccess.add(1);
    return body?.success === true && Number(body?.data?.id) > 0;
  }
  if (response.status === 409) {
    writeConflict.add(1, { error_code: errorCode(body) });
    return errorCode(body) === 'SEAT_ALREADY_RESERVED';
  }
  if (response.status === 429) {
    writeRateLimited.add(1, { error_code: errorCode(body) });
    return true;
  }
  if (response.status === 0) {
    writeTransportFailure.add(1, { error_code: response.error_code || 'UNKNOWN' });
    return false;
  }
  writeUnexpected.add(1, { status: String(response.status), error_code: errorCode(body) });
  return false;
}

function requestOptions(memberId, wallet, key) {
  return {
    headers: {
      Authorization: `Bearer ${createJwt(memberId, wallet, jwtSecret)}`,
      'Content-Type': 'application/json',
      'Idempotency-Key': key,
    },
    responseCallback: http.expectedStatuses(200, 201, 409, 429, 500, 503),
    timeout: requestTimeout,
  };
}

function walletAddress(memberId) {
  const fixtureNumber = memberId - benchmarkMemberIdBase;
  if (fixtureNumber <= 0) {
    throw new Error(`회원 ID가 benchmark base보다 작습니다. memberId=${memberId}, base=${benchmarkMemberIdBase}`);
  }
  return `0xbench${String(fixtureNumber).padStart(34, '0')}`;
}

function idempotencyKey(sequence) {
  return `${idempotencyPrefix}-${String(sequence % 10000).padStart(4, '0')}-4000-8000-${String(sequence).padStart(12, '0')}`;
}

function iterationIndex() {
  const scenarioIndex = exec.scenario.iterationInTest;
  if (Number.isInteger(scenarioIndex) && scenarioIndex >= 0) {
    return scenarioIndex;
  }
  return ((__VU - 1) * 1000000) + __ITER;
}

function waitForStart(startAt) {
  const remainingSeconds = (startAt - Date.now()) / 1000;
  if (remainingSeconds > 0) {
    sleep(remainingSeconds);
  }
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

function nonNegativeNumber(raw) {
  const value = Number(raw);
  if (!Number.isInteger(value) || value < 0) {
    throw new Error(`부하 rate는 0 이상의 정수여야 합니다. actual=${raw}`);
  }
  return value;
}

function parseDurationSeconds(raw) {
  const match = String(raw).match(/^([0-9]+(?:\.[0-9]+)?)(ms|s|m|h)$/);
  if (!match) {
    throw new Error(`DURATION은 예: 20s, 1m 형식이어야 합니다. actual=${raw}`);
  }
  const value = Number(match[1]);
  const unit = match[2];
  if (unit === 'ms') return value / 1000;
  if (unit === 's') return value;
  if (unit === 'm') return value * 60;
  return value * 3600;
}
