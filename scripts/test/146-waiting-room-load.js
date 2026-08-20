import http from 'k6/http';
import { check, sleep } from 'k6';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:10080';
const performanceTimeId = requiredPositiveNumber('PT_ID');
const jwtSecret = required('JWT_SECRET');
const mode = (__ENV.MODE || 'join').toLowerCase();
const flow = (__ENV.FLOW || 'waiting-room').toLowerCase();
const concurrency = positiveNumber(__ENV.CONCURRENCY || 100);
const memberIdBase = positiveNumber(__ENV.MEMBER_ID_BASE || 900000000);
const walletIdBase = nonNegativeNumber(__ENV.WALLET_ID_BASE || 0);
const startDelaySeconds = nonNegativeNumber(__ENV.START_DELAY_SECONDS || 2);
const statusPolls = nonNegativeNumber(__ENV.STATUS_POLLS || (mode === 'join' ? 0 : 5));
const statusPollIntervalMs = positiveNumber(__ENV.STATUS_POLL_INTERVAL_MS || 1000);
const statusPollJitterRatio = boundedNumber(__ENV.STATUS_POLL_JITTER_RATIO || 0.1, 0, 1);
const requestTimeout = __ENV.REQUEST_TIMEOUT || '10s';
const maxDuration = __ENV.MAX_DURATION || '5m';
const seatIds = parsePositiveNumberList(__ENV.SEAT_IDS || '');
const passHeader = 'X-Waiting-Room-Pass';

if (!['join', 'status', 'seat-map', 'pre-reserve', 'full-flow'].includes(mode)) {
  throw new Error(`MODE는 join, status, seat-map, pre-reserve, full-flow 중 하나여야 합니다. actual=${mode}`);
}
if (!['waiting-room', 'direct'].includes(flow)) {
  throw new Error(`FLOW는 waiting-room 또는 direct 중 하나여야 합니다. actual=${flow}`);
}
if (flow === 'direct' && !['seat-map', 'pre-reserve', 'full-flow'].includes(mode)) {
  throw new Error('direct FLOW는 seat-map, pre-reserve 또는 full-flow mode만 지원합니다.');
}
if ((mode === 'seat-map' || mode === 'pre-reserve' || mode === 'full-flow') && statusPolls === 0) {
  if (flow === 'waiting-room') {
    throw new Error(`${mode} mode는 STATUS_POLLS가 1 이상이어야 합니다.`);
  }
}
if ((mode === 'pre-reserve' || mode === 'full-flow') && seatIds.length === 0) {
  throw new Error(`${mode} mode는 SEAT_IDS가 필요합니다.`);
}

const joinRequests = new Counter('waiting_room_join_requests');
const joinSuccess = new Counter('waiting_room_join_success');
const joinUnexpected = new Counter('waiting_room_join_unexpected');
const statusRequests = new Counter('waiting_room_status_requests');
const statusSuccess = new Counter('waiting_room_status_success');
const statusUnexpected = new Counter('waiting_room_status_unexpected');
const statusWaiting = new Counter('waiting_room_status_waiting');
const statusAdmitted = new Counter('waiting_room_status_admitted');
const admissionTimeout = new Counter('waiting_room_admission_timeout');
const seatMapRequests = new Counter('waiting_room_seat_map_requests');
const seatMapSuccess = new Counter('waiting_room_seat_map_success');
const preReserveRequests = new Counter('waiting_room_pre_reserve_requests');
const preReserveExpected = new Counter('waiting_room_pre_reserve_expected');
const unexpectedResponse = new Counter('waiting_room_unexpected_response');
const joinDuration = new Trend('waiting_room_join_duration', true);
const statusDuration = new Trend('waiting_room_status_duration', true);
const protectedDuration = new Trend('waiting_room_protected_duration', true);
const seatMapDuration = new Trend('waiting_room_seat_map_duration', true);
const preReserveDuration = new Trend('waiting_room_pre_reserve_duration', true);
const queueWaitDuration = new Trend('waiting_room_queue_wait_duration', true);
const journeyDuration = new Trend('waiting_room_total_journey_duration', true);
const contractSuccess = new Rate('waiting_room_contract_success');

export const options = {
  scenarios: {
    waiting_room_api_load: {
      executor: 'per-vu-iterations',
      vus: concurrency,
      iterations: 1,
      maxDuration,
      gracefulStop: '0s',
      tags: {
        scenario: 'waiting-room-api-load',
        mode,
      },
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return { startAt: Date.now() + startDelaySeconds * 1000 };
}

export default function (data) {
  waitForBurstStart(data.startAt);

  const identity = identityForVu(__VU);
  const headers = {
    Authorization: `Bearer ${createJwt(identity, jwtSecret)}`,
  };
  const journeyStartedAt = Date.now();

  if (flow === 'direct') {
    const success = mode === 'seat-map'
      ? loadSeatMap(headers, null)
      : mode === 'pre-reserve'
        ? preReserve(identity, headers, null)
        : loadFullFlow(identity, headers, null);
    journeyDuration.add(Date.now() - journeyStartedAt);
    contractSuccess.add(success);
    return;
  }

  const join = joinWaitingRoom(identity, headers);
  if (!join.ticketId) {
    contractSuccess.add(false);
    journeyDuration.add(Date.now() - journeyStartedAt);
    return;
  }
  if (mode === 'join') {
    contractSuccess.add(true);
    journeyDuration.add(Date.now() - journeyStartedAt);
    return;
  }

  const status = pollStatus(identity, headers, join.ticketId, join.pollAfterMs);
  if (mode === 'status') {
    contractSuccess.add(status.completedWithoutUnexpected);
    if (status.admittedAt) {
      queueWaitDuration.add(status.admittedAt - join.completedAt);
    }
    journeyDuration.add(Date.now() - journeyStartedAt);
    return;
  }
  if (!status.entryPass) {
    admissionTimeout.add(1);
    contractSuccess.add(false);
    journeyDuration.add(Date.now() - journeyStartedAt);
    return;
  }

  if (status.admittedAt) {
    queueWaitDuration.add(status.admittedAt - join.completedAt);
  }
  if (mode === 'seat-map') {
    const success = loadSeatMap(headers, status.entryPass);
    contractSuccess.add(success);
    journeyDuration.add(Date.now() - journeyStartedAt);
    return;
  }

  const success = mode === 'pre-reserve'
    ? preReserve(identity, headers, status.entryPass)
    : loadFullFlow(identity, headers, status.entryPass);
  contractSuccess.add(success);
  journeyDuration.add(Date.now() - journeyStartedAt);
}

function joinWaitingRoom(identity, headers) {
  const response = http.post(
    `${baseUrl}/api/reservation/waiting-room/${performanceTimeId}/join`,
    null,
    requestOptions(headers, 'waiting-room-join'),
  );
  joinRequests.add(1);
  joinDuration.add(response.timings.duration);

  const body = safeJson(response);
  const ticketId = body?.data?.ticketId;
  const pollAfterMs = positiveOrDefault(body?.data?.pollAfterMs, statusPollIntervalMs);
  const success = response.status === 200 && body?.success === true && typeof ticketId === 'string';
  if (success) {
    joinSuccess.add(1);
  } else {
    joinUnexpected.add(1, { status: String(response.status), error_code: errorCode(body) });
    unexpectedResponse.add(1, { endpoint: 'join', status: String(response.status) });
  }
  check(response, {
    'join이 ticket을 반환한다': () => success,
    'join 응답에 correlation id가 있다': (res) => Boolean(res.headers['X-Correlation-Id']),
  });
  return {
    ticketId: success ? ticketId : null,
    pollAfterMs,
    identity,
    completedAt: Date.now(),
  };
}

function pollStatus(identity, headers, ticketId, initialPollAfterMs) {
  let entryPass = null;
  let completedWithoutUnexpected = true;
  let admittedAt = null;
  let nextPollAfterMs = positiveOrDefault(initialPollAfterMs, statusPollIntervalMs);

  for (let attempt = 0; attempt < statusPolls; attempt += 1) {
    sleep(jitteredPollDelay(nextPollAfterMs) / 1000);
    const response = http.get(
      `${baseUrl}/api/reservation/waiting-room/${performanceTimeId}/tickets/${ticketId}`,
      requestOptions(headers, 'waiting-room-status'),
    );
    statusRequests.add(1);
    statusDuration.add(response.timings.duration);

    const body = safeJson(response);
    const status = body?.data?.status;
    const success = response.status === 200 && body?.success === true && typeof status === 'string';
    if (!success) {
      statusUnexpected.add(1, { status: String(response.status), error_code: errorCode(body) });
      unexpectedResponse.add(1, { endpoint: 'status', status: String(response.status) });
      completedWithoutUnexpected = false;
      continue;
    }

    statusSuccess.add(1, { status });
    if (status === 'WAITING') {
      statusWaiting.add(1);
    }
    if (status === 'ADMITTED') {
      statusAdmitted.add(1);
      admittedAt = admittedAt || Date.now();
      if (typeof body?.data?.entryPass === 'string' && body.data.entryPass.length > 0) {
        entryPass = body.data.entryPass;
        break;
      }
    }
    nextPollAfterMs = positiveOrDefault(body?.data?.pollAfterMs, statusPollIntervalMs);
  }
  return { entryPass, completedWithoutUnexpected, identity, admittedAt };
}

function loadSeatMap(headers, entryPass) {
  const response = http.get(
    `${baseUrl}/api/seats/${performanceTimeId}`,
    requestOptions(withEntryPass(headers, entryPass), 'waiting-room-seat-map'),
  );
  seatMapRequests.add(1);
  protectedDuration.add(response.timings.duration, { endpoint: 'seat-map' });
  seatMapDuration.add(response.timings.duration);

  const body = safeJson(response);
  const success = response.status === 200 && body?.success === true && Array.isArray(body?.data);
  if (success) {
    seatMapSuccess.add(1);
  } else {
    unexpectedResponse.add(1, { endpoint: 'seat-map', status: String(response.status) });
  }
  check(response, {
    'admitted seat map이 성공한다': () => success,
  });
  return success;
}

function loadFullFlow(identity, headers, entryPass) {
  const seatMapSuccess = loadSeatMap(headers, entryPass);
  return seatMapSuccess && preReserve(identity, headers, entryPass);
}

function preReserve(identity, headers, entryPass) {
  const seatId = seatIds[(__VU - 1) % seatIds.length];
  const response = http.post(
    `${baseUrl}/api/reservation/pre-reserve`,
    JSON.stringify({ performanceTimeId, seatIds: [seatId] }),
    requestOptions(
      {
        ...headers,
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey(identity.memberId),
        ...withEntryPass({}, entryPass),
      },
      'waiting-room-pre-reserve',
    ),
  );
  preReserveRequests.add(1);
  protectedDuration.add(response.timings.duration, { endpoint: 'pre-reserve' });
  preReserveDuration.add(response.timings.duration);

  const body = safeJson(response);
  const expected = (response.status === 200 && body?.success === true)
    || (response.status === 409 && body?.error?.code === 'SEAT_ALREADY_RESERVED')
    || (response.status === 429 && body?.error?.code === 'SEAT_ADMISSION_REJECTED');
  if (expected) {
    preReserveExpected.add(1, { status: String(response.status), error_code: errorCode(body) });
  } else {
    unexpectedResponse.add(1, { endpoint: 'pre-reserve', status: String(response.status) });
  }
  check(response, {
    'admitted pre-reserve가 계약 응답을 반환한다': () => expected,
  });
  return expected;
}

function identityForVu(vu) {
  const sequence = memberIdBase + vu;
  const walletSequence = walletIdBase + vu;
  return {
    memberId: sequence,
    walletAddress: `0xbench${String(walletSequence).padStart(34, '0')}`,
  };
}

function createJwt(identity, secret) {
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = base64Url(JSON.stringify({
    memberId: identity.memberId,
    walletAddress: identity.walletAddress,
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

function requestOptions(headers, endpoint) {
  return {
    headers,
    timeout: requestTimeout,
    tags: { endpoint },
    responseCallback: http.expectedStatuses(200, 409, 429),
  };
}

function withEntryPass(headers, entryPass) {
  if (typeof entryPass !== 'string' || entryPass.length === 0) {
    return headers;
  }
  return { ...headers, [passHeader]: entryPass };
}

function jitteredPollDelay(delayMs) {
  const jitter = (Math.random() * 2 - 1) * statusPollJitterRatio;
  return Math.max(1, Math.round(delayMs * (1 + jitter)));
}

function positiveOrDefault(value, fallback) {
  return Number.isFinite(Number(value)) && Number(value) > 0 ? Number(value) : fallback;
}

function waitForBurstStart(startAt) {
  const remainingSeconds = (startAt - Date.now()) / 1000;
  if (remainingSeconds > 0) {
    sleep(remainingSeconds);
  }
}

function idempotencyKey(memberId) {
  const run = Math.floor(Date.now()).toString(16).padStart(12, '0').slice(-12);
  const member = Number(memberId).toString(16).padStart(12, '0').slice(-12);
  return `${run.slice(0, 8)}-${run.slice(8, 12)}-4000-8000-${member}`;
}

function safeJson(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

function errorCode(body) {
  return typeof body?.error?.code === 'string' ? body.error.code : 'NO_API_ERROR_CODE';
}

function parsePositiveNumberList(raw) {
  if (!raw) {
    return [];
  }
  return raw.split(',')
    .map((value) => Number(value.trim()))
    .filter((value) => Number.isInteger(value) && value > 0);
}

function required(name) {
  const value = __ENV[name];
  if (!value) {
    throw new Error(`${name} 환경변수가 필요합니다.`);
  }
  return value;
}

function requiredPositiveNumber(name) {
  return positiveNumber(required(name));
}

function positiveNumber(value) {
  const number = Number(value);
  if (!Number.isInteger(number) || number <= 0) {
    throw new Error(`양의 정수가 필요합니다. actual=${value}`);
  }
  return number;
}

function nonNegativeNumber(value) {
  const number = Number(value);
  if (!Number.isInteger(number) || number < 0) {
    throw new Error(`0 이상의 정수가 필요합니다. actual=${value}`);
  }
  return number;
}

function boundedNumber(value, minimum, maximum) {
  const number = Number(value);
  if (!Number.isFinite(number) || number < minimum || number > maximum) {
    throw new Error(`범위 ${minimum}~${maximum}의 숫자가 필요합니다. actual=${value}`);
  }
  return number;
}
