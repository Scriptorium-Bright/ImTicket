import http from 'k6/http';
import { check, sleep } from 'k6';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import { Counter, Rate, Trend } from 'k6/metrics';

const grade = Number(__ENV.GRADE || 1);
const mode = __ENV.MODE || 'baseline';
const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:10080';
const performanceTimeId = requiredNumber('PT_ID');
const targetSeatId = requiredNumber('SEAT_ID');
const walletAddress = __ENV.WALLET_ADDRESS || '0xLoadTestUser';
const burstDelaySeconds = Number(__ENV.BURST_DELAY_SECONDS || 5);
const trafficProfile = __ENV.TRAFFIC_PROFILE || 'minimum';
const distributed = (__ENV.DISTRIBUTED || 'false').toLowerCase() === 'true';
const scheduledStartAt = optionalEpoch('START_AT_EPOCH_MS');
const maxDuration = __ENV.MAX_DURATION || '10m';
const requestTimeout = __ENV.REQUEST_TIMEOUT || '15s';

const gradeConcurrency = {
  1: { minimum: 500, maximum: 5000 },
  2: { minimum: 5000, maximum: 30000 },
  3: { minimum: 20000, maximum: 100000 },
  4: { minimum: 80000, maximum: 300000 },
};

if (!gradeConcurrency[grade]) {
  throw new Error(`GRADE는 1, 2, 3, 4 중 하나여야 합니다. actual=${grade}`);
}
if (!['minimum', 'maximum'].includes(trafficProfile)) {
  throw new Error(`TRAFFIC_PROFILE은 minimum 또는 maximum이어야 합니다. actual=${trafficProfile}`);
}
if (!['baseline', 'forced-timeout'].includes(mode)) {
  throw new Error(`MODE는 baseline 또는 forced-timeout이어야 합니다. actual=${mode}`);
}
if (!['true', 'false'].includes((__ENV.DISTRIBUTED || 'false').toLowerCase())) {
  throw new Error(`DISTRIBUTED는 true 또는 false여야 합니다. actual=${__ENV.DISTRIBUTED}`);
}
if (distributed && mode !== 'baseline') {
  throw new Error('DISTRIBUTED=true는 baseline 테스트에서만 사용할 수 있습니다.');
}
if (!Number.isFinite(burstDelaySeconds) || burstDelaySeconds <= 0 || burstDelaySeconds > 20) {
  throw new Error(
    `BURST_DELAY_SECONDS는 0보다 크고 20 이하여야 합니다. actual=${__ENV.BURST_DELAY_SECONDS}`,
  );
}

const concurrency = Number(__ENV.CONCURRENCY || gradeConcurrency[grade][trafficProfile]);
if (!Number.isInteger(concurrency) || concurrency <= 0) {
  throw new Error(`CONCURRENCY는 양의 정수여야 합니다. actual=${__ENV.CONCURRENCY}`);
}
const jwt = __ENV.JWT || createJwt(walletAddress, required('JWT_SECRET'));

const reservationSuccess = new Counter('reservation_success');
const reservationConflict = new Counter('reservation_conflict');
const reservationInternalError = new Counter('reservation_internal_error');
const authenticationFailure = new Counter('authentication_failure');
const unexpectedResponse = new Counter('unexpected_response');
const expectedOutcome = new Rate('expected_outcome');
const reservationDuration = new Trend('reservation_duration', true);
const requestStartLag = new Trend('request_start_lag', true);

const thresholds = {
  authentication_failure: ['count==0'],
  unexpected_response: ['count==0'],
  expected_outcome: ['rate==1'],
  checks: ['rate==1'],
};

if (mode === 'baseline') {
  thresholds.reservation_success = [distributed ? 'count<=1' : 'count==1'];
  if (!distributed) {
    thresholds.reservation_conflict = [`count==${concurrency - 1}`];
  }
  thresholds.reservation_internal_error = ['count==0'];
}

export const options = {
  scenarios: {
    hot_seat_burst: {
      executor: 'per-vu-iterations',
      vus: concurrency,
      iterations: 1,
      maxDuration,
      gracefulStop: '0s',
      tags: {
        grade: String(grade),
        mode,
        traffic_profile: trafficProfile,
        distributed: String(distributed),
      },
    },
  },
  thresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const response = http.get(`${baseUrl}/api/seats/${performanceTimeId}`);
  if (response.status !== 200) {
    throw new Error(`좌석 조회 실패: status=${response.status}, body=${response.body}`);
  }

  const body = safeJson(response);
  const seats = body?.data || [];
  const targetSeat = seats.find((seat) => Number(seat.seatId ?? seat.id) === targetSeatId);
  if (!targetSeat) {
    throw new Error(
      `SEAT_ID=${targetSeatId}가 PT_ID=${performanceTimeId} 좌석 지도에 없습니다.`,
    );
  }
  if (targetSeat.seatStatus !== 'AVAILABLE') {
    throw new Error(
      `SEAT_ID=${targetSeatId}가 예약 가능한 상태가 아닙니다. actual=${targetSeat.seatStatus}`,
    );
  }

  return {
    startAt: resolveStartAt(),
    payload: JSON.stringify({
      performanceTimeId,
      seatIds: [targetSeatId],
    }),
  };
}

export default function (data) {
  if (data.startAt !== null) {
    const remainingSeconds = (data.startAt - Date.now()) / 1000;
    if (remainingSeconds > 0) {
      sleep(remainingSeconds);
    }
    requestStartLag.add(Math.max(0, Date.now() - data.startAt));
  }

  const response = http.post(
    `${baseUrl}/api/reservation/pre-reserve`,
    data.payload,
    {
      headers: {
        Authorization: `Bearer ${jwt}`,
        'Content-Type': 'application/json',
      },
      tags: {
        endpoint: 'pre-reserve',
        target: 'single-hot-seat',
      },
      responseCallback: mode === 'forced-timeout'
        ? http.expectedStatuses(200, 201, 409, 500, 503)
        : http.expectedStatuses(200, 201, 409),
      timeout: requestTimeout,
    },
  );

  reservationDuration.add(response.timings.duration);

  const body = safeJson(response);
  let expected = false;
  if (
    (response.status === 200 || response.status === 201)
    && body?.success === true
    && Number.isInteger(Number(body?.data?.id))
    && Number(body.data.id) > 0
  ) {
    reservationSuccess.add(1);
    expected = true;
  } else if (
    response.status === 409
    && body?.error?.code === 'SEAT_ALREADY_RESERVED'
  ) {
    reservationConflict.add(1);
    expected = true;
  } else if (response.status === 500 || response.status === 503) {
    reservationInternalError.add(1);
    expected = mode === 'forced-timeout';
  } else if (response.status === 401 || response.status === 403) {
    authenticationFailure.add(1);
  } else {
    unexpectedResponse.add(1);
  }
  expectedOutcome.add(expected);

  check(response, {
    '응답이 분류 가능한 상태다': () => expected,
    'correlation id가 반환된다': (res) => Boolean(res.headers['X-Correlation-Id']),
  });
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
    throw new Error(`${name} 환경변수가 필요합니다.`);
  }
  return value;
}

function requiredNumber(name) {
  const value = Number(required(name));
  if (!Number.isInteger(value) || value <= 0) {
    throw new Error(`${name}은 양의 정수여야 합니다. actual=${__ENV[name]}`);
  }
  return value;
}

function optionalEpoch(name) {
  const raw = __ENV[name];
  if (!raw) {
    return null;
  }

  const value = Number(raw);
  if (!Number.isInteger(value) || value <= 0) {
    throw new Error(`${name}은 Unix epoch milliseconds여야 합니다. actual=${raw}`);
  }
  return value;
}

function resolveStartAt() {
  if (mode !== 'baseline') {
    return null;
  }

  const startAt = scheduledStartAt || Date.now() + burstDelaySeconds * 1000;
  if (startAt <= Date.now()) {
    throw new Error('START_AT_EPOCH_MS는 모든 부하 발생기가 준비될 수 있도록 미래 시각이어야 합니다.');
  }
  return startAt;
}

function safeJson(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}
