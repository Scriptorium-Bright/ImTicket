import http from 'k6/http';
import { check, sleep } from 'k6';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:10080';
const performanceTimeId = requiredPositiveNumber('PT_ID');
const walletAddress = __ENV.WALLET_ADDRESS || '0xLoadTestUser';
const jwtSecret = required('JWT_SECRET');
const concurrency = positiveNumber(__ENV.CONCURRENCY || 100);
const seatPoolSize = positiveNumber(__ENV.SEAT_POOL_SIZE || 4);
const seatsPerRequest = positiveNumber(__ENV.SEATS_PER_REQUEST || 1);
const burstDelaySeconds = positiveNumber(__ENV.BURST_DELAY_SECONDS || 2);
const startAtEpochMs = optionalPositiveNumber('START_AT_EPOCH_MS');
const requestTimeout = __ENV.REQUEST_TIMEOUT || '15s';
const maxDuration = __ENV.MAX_DURATION || '2m';
const explicitSeatIds = parseSeatIds(__ENV.SEAT_IDS || '');
const jwt = __ENV.JWT || createJwt(walletAddress, jwtSecret);

if (seatsPerRequest > seatPoolSize) {
  throw new Error('SEATS_PER_REQUEST는 SEAT_POOL_SIZE보다 클 수 없습니다.');
}

const reservationAttempts = new Counter('multi_hot_reservation_attempts');
const outcomeBucket = new Counter('multi_hot_outcome_bucket');
const reservationSuccess = new Counter('multi_hot_reservation_success');
const reservationConflict = new Counter('multi_hot_reservation_conflict');
const reservationConflictExpected = new Counter('multi_hot_conflict_seat_already_reserved');
const reservationConflictOther = new Counter('multi_hot_conflict_other');
const reservation429 = new Counter('multi_hot_reservation_429');
const reservation429AdmissionRejected = new Counter('multi_hot_429_seat_admission_rejected');
const reservation429LockTimeout = new Counter('multi_hot_429_seat_lock_timeout');
const reservation429Other = new Counter('multi_hot_429_other');
const reservation5xx = new Counter('multi_hot_reservation_5xx');
const transportFailure = new Counter('multi_hot_transport_failure');
const transportTimeout = new Counter('multi_hot_transport_timeout');
const transportConnection = new Counter('multi_hot_transport_connection');
const transportOther = new Counter('multi_hot_transport_other');
const unexpectedHttp = new Counter('multi_hot_unexpected_http');
const reservationDuration = new Trend('multi_hot_reservation_duration', true);
const requestStartLag = new Trend('multi_hot_request_start_lag', true);
const classifiedResponse = new Rate('multi_hot_classified_response');

export const options = {
  scenarios: {
    distributed_seat_burst: {
      executor: 'per-vu-iterations',
      vus: concurrency,
      iterations: 1,
      maxDuration,
      gracefulStop: '0s',
      tags: {
        scenario: 'multi-hot-seat-diagnosis',
        seat_pool_size: String(seatPoolSize),
        seats_per_request: String(seatsPerRequest),
      },
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const response = http.get(`${baseUrl}/api/seats/${performanceTimeId}`, {
    timeout: requestTimeout,
  });
  if (response.status !== 200) {
    throw new Error(`좌석 조회 실패: status=${response.status}, body=${response.body}`);
  }

  const body = safeJson(response);
  const seats = body && Array.isArray(body.data) ? body.data : [];
  const seatMap = {};
  for (let i = 0; i < seats.length; i += 1) {
    const id = Number(seats[i].seatId || seats[i].id);
    if (Number.isInteger(id)) {
      seatMap[id] = seats[i];
    }
  }

  let seatIds = explicitSeatIds.length > 0
    ? explicitSeatIds.slice()
    : seats
      .filter((seat) => seat.seatStatus === 'AVAILABLE')
      .map((seat) => Number(seat.seatId || seat.id))
      .filter((id) => Number.isInteger(id))
      .sort((left, right) => left - right)
      .slice(0, seatPoolSize);

  if (explicitSeatIds.length > 0) {
    for (let i = 0; i < seatIds.length; i += 1) {
      const seat = seatMap[seatIds[i]];
      if (!seat) {
        throw new Error(`SEAT_IDS의 좌석이 공연 회차에 없습니다. seatId=${seatIds[i]}`);
      }
      if (seat.seatStatus !== 'AVAILABLE') {
        throw new Error(`SEAT_IDS의 좌석이 예약 가능하지 않습니다. seatId=${seatIds[i]}, status=${seat.seatStatus}`);
      }
    }
    seatIds = seatIds.slice(0, seatPoolSize);
  }

  if (seatIds.length !== seatPoolSize) {
    throw new Error(`좌석 pool 크기가 다릅니다. expected=${seatPoolSize}, actual=${seatIds.length}`);
  }
  if (seatIds.length < seatsPerRequest) {
    throw new Error(`사용 가능한 좌석이 부족합니다. pool=${seatIds.length}, required=${seatsPerRequest}`);
  }

  return {
    seatIds,
    startAt: startAtEpochMs || Date.now() + burstDelaySeconds * 1000,
  };
}

export default function (data) {
  const remainingSeconds = (data.startAt - Date.now()) / 1000;
  if (remainingSeconds > 0) {
    sleep(remainingSeconds);
  }
  requestStartLag.add(Math.max(0, Date.now() - data.startAt));

  const selectedSeatIds = [];
  const startIndex = ((__VU - 1) * seatsPerRequest) % data.seatIds.length;
  for (let i = 0; i < seatsPerRequest; i += 1) {
    selectedSeatIds.push(data.seatIds[(startIndex + i) % data.seatIds.length]);
  }
  selectedSeatIds.sort((left, right) => left - right);

  const response = http.post(
    `${baseUrl}/api/reservation/pre-reserve`,
    JSON.stringify({ performanceTimeId, seatIds: selectedSeatIds }),
    {
      headers: {
        Authorization: `Bearer ${jwt}`,
        'Content-Type': 'application/json',
      },
      tags: {
        endpoint: 'pre-reserve',
        target: 'multi-hot-seat',
      },
      responseCallback: http.expectedStatuses(200, 201, 409, 429),
      timeout: requestTimeout,
    },
  );

  reservationAttempts.add(1);
  reservationDuration.add(response.timings.duration);

  const body = safeJson(response);
  const apiErrorCode = errorCode(body);
  let bucket;
  let expected = false;

  if (response.status === 0) {
    bucket = 'transport';
    transportFailure.add(1, { error_code: response.error_code || 'UNKNOWN' });
    addTransportSubtype(response);
  } else if (
    (response.status === 200 || response.status === 201)
    && body?.success === true
    && Number(body?.data?.id) > 0
  ) {
    bucket = 'success';
    expected = true;
    reservationSuccess.add(1, { status: String(response.status) });
  } else if (response.status === 409) {
    bucket = '409';
    const conflictKind = apiErrorCode === 'SEAT_ALREADY_RESERVED' ? 'seat_already_reserved' : 'other';
    expected = conflictKind === 'seat_already_reserved';
    reservationConflict.add(1, { kind: conflictKind, error_code: apiErrorCode });
    if (expected) {
      reservationConflictExpected.add(1);
    } else {
      reservationConflictOther.add(1);
    }
  } else if (response.status === 429) {
    bucket = '429';
    const status429Kind = classify429(apiErrorCode);
    expected = status429Kind === 'seat_admission_rejected' || status429Kind === 'seat_lock_timeout';
    reservation429.add(1, { kind: status429Kind, error_code: apiErrorCode });
    if (status429Kind === 'seat_admission_rejected') {
      reservation429AdmissionRejected.add(1);
    } else if (status429Kind === 'seat_lock_timeout') {
      reservation429LockTimeout.add(1);
    } else {
      reservation429Other.add(1);
    }
  } else if (response.status >= 500 && response.status <= 599) {
    bucket = '5xx';
    reservation5xx.add(1, {
      status: String(response.status),
      error_code: apiErrorCode,
    });
  } else {
    bucket = 'unexpected_http';
    unexpectedHttp.add(1, {
      status: String(response.status),
      error_code: apiErrorCode,
    });
  }

  outcomeBucket.add(1, { bucket });
  classifiedResponse.add(expected);
  check(response, {
    '응답이 계약 버킷으로 분류된다': () => expected,
    'correlation id가 반환된다': (res) => Boolean(res.headers['X-Correlation-Id']),
  });
}

function classify429(code) {
  if (code === 'SEAT_ADMISSION_REJECTED') {
    return 'seat_admission_rejected';
  }
  if (code === 'SEAT_LOCK_TIMEOUT') {
    return 'seat_lock_timeout';
  }
  return 'other';
}

function addTransportSubtype(response) {
  const errorText = `${response.error_code || ''} ${response.error || ''}`.toLowerCase();
  if (/(timeout|deadline)/.test(errorText)) {
    transportTimeout.add(1);
  } else if (/(connect|connection|refused|reset|eof|socket)/.test(errorText)) {
    transportConnection.add(1);
  } else {
    transportOther.add(1);
  }
}

function safeJson(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

function errorCode(body) {
  const code = body?.error?.code;
  return typeof code === 'string' && code.length > 0 ? code : 'NO_API_ERROR_CODE';
}

function parseSeatIds(raw) {
  if (!raw) {
    return [];
  }
  return raw.split(',').map((value) => Number(value.trim())).filter((value) => Number.isInteger(value) && value > 0);
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

function requiredPositiveNumber(name) {
  return positiveNumber(required(name));
}

function optionalPositiveNumber(name) {
  return __ENV[name] ? positiveNumber(__ENV[name]) : null;
}

function positiveNumber(value) {
  const number = Number(value);
  if (!Number.isInteger(number) || number <= 0) {
    throw new Error(`양의 정수가 필요합니다. actual=${value}`);
  }
  return number;
}
