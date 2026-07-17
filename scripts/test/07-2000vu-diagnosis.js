import http from 'k6/http';
import { sleep } from 'k6';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:10080';
const performanceTimeId = requiredPositiveNumber('PT_ID');
const targetSeatId = requiredPositiveNumber('SEAT_ID');
const concurrency = requiredPositiveNumber('CONCURRENCY');
const startAtEpochMs = requiredPositiveNumber('START_AT_EPOCH_MS');
const jwtSecret = required('JWT_SECRET');
const walletAddress = __ENV.WALLET_ADDRESS || '0xLoadTestUser';
const requestTimeout = __ENV.REQUEST_TIMEOUT || '15s';
const maxDuration = __ENV.MAX_DURATION || '2m';
const jwt = __ENV.JWT || createJwt(walletAddress, jwtSecret);

const reservationAttempts = new Counter('diagnostic_reservation_attempts');
const outcomeBucket = new Counter('diagnostic_outcome_bucket');
const reservationSuccess = new Counter('diagnostic_reservation_success');
const reservationConflict = new Counter('diagnostic_reservation_conflict');
const reservationConflictExpected = new Counter('diagnostic_conflict_seat_already_reserved');
const reservationConflictOther = new Counter('diagnostic_conflict_other');
const reservation429 = new Counter('diagnostic_reservation_429');
const reservation429LockTimeout = new Counter('diagnostic_429_seat_lock_timeout');
const reservation429AdmissionRejected = new Counter('diagnostic_429_seat_admission_rejected');
const reservation429AdmissionOther = new Counter('diagnostic_429_admission_or_rate_limit');
const reservation429Other = new Counter('diagnostic_429_other');
const reservation5xx = new Counter('diagnostic_reservation_5xx');
const reservation500 = new Counter('diagnostic_5xx_500');
const reservation501 = new Counter('diagnostic_5xx_501');
const reservation502 = new Counter('diagnostic_5xx_502');
const reservation503 = new Counter('diagnostic_5xx_503');
const reservation504 = new Counter('diagnostic_5xx_504');
const reservation5xxOther = new Counter('diagnostic_5xx_other');
const transportFailure = new Counter('diagnostic_transport_failure');
const transportTimeout = new Counter('diagnostic_transport_timeout');
const transportConnection = new Counter('diagnostic_transport_connection');
const transportOther = new Counter('diagnostic_transport_other');
const unexpectedHttp = new Counter('diagnostic_unexpected_http');
const reservationDuration = new Trend('diagnostic_reservation_duration', true);
const requestStartLag = new Trend('diagnostic_request_start_lag', true);
const classifiedResponse = new Rate('diagnostic_classified_response');

export const options = {
  scenarios: {
    hot_seat_burst: {
      executor: 'per-vu-iterations',
      vus: concurrency,
      iterations: 1,
      maxDuration,
      gracefulStop: '0s',
      tags: {
        scenario: '2000vu-independent-diagnosis',
        target: 'single-hot-seat',
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

  const seats = safeJson(response)?.data || [];
  const targetSeat = seats.find((seat) => Number(seat.seatId ?? seat.id) === targetSeatId);
  if (!targetSeat) {
    throw new Error(`SEAT_ID=${targetSeatId}가 PT_ID=${performanceTimeId} 좌석 지도에 없습니다.`);
  }
  if (targetSeat.seatStatus !== 'AVAILABLE') {
    throw new Error(`SEAT_ID=${targetSeatId}가 예약 가능하지 않습니다. actual=${targetSeat.seatStatus}`);
  }

  return {
    payload: JSON.stringify({ performanceTimeId, seatIds: [targetSeatId] }),
  };
}

export default function (data) {
  const remainingSeconds = (startAtEpochMs - Date.now()) / 1000;
  if (remainingSeconds > 0) {
    sleep(remainingSeconds);
  }
  requestStartLag.add(Math.max(0, Date.now() - startAtEpochMs));

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
      responseCallback: http.expectedStatuses(200, 201, 409, 429, 500, 501, 502, 503, 504),
      timeout: requestTimeout,
    },
  );

  reservationAttempts.add(1);
  reservationDuration.add(response.timings.duration);

  const body = safeJson(response);
  const apiErrorCode = errorCode(body);
  let bucket;

  if (response.status === 0) {
    bucket = 'transport';
    transportFailure.add(1, {
      error_code: response.error_code || 'UNKNOWN',
    });
    addTransportSubtype(response);
  } else if (
    (response.status === 200 || response.status === 201)
    && body?.success === true
    && Number(body?.data?.id) > 0
  ) {
    bucket = 'success';
    reservationSuccess.add(1, { status: String(response.status) });
  } else if (response.status === 409) {
    bucket = '409';
    const conflictKind = apiErrorCode === 'SEAT_ALREADY_RESERVED' ? 'seat_already_reserved' : 'other';
    reservationConflict.add(1, { kind: conflictKind, error_code: apiErrorCode });
    if (conflictKind === 'seat_already_reserved') {
      reservationConflictExpected.add(1);
    } else {
      reservationConflictOther.add(1);
    }
  } else if (response.status === 429) {
    bucket = '429';
    const status429Kind = classify429(apiErrorCode);
    reservation429.add(1, { kind: status429Kind, error_code: apiErrorCode });
    if (status429Kind === 'seat_lock_timeout') {
      reservation429LockTimeout.add(1);
    } else if (status429Kind === 'seat_admission_rejected') {
      reservation429AdmissionRejected.add(1);
    } else if (status429Kind === 'admission_or_rate_limit') {
      reservation429AdmissionOther.add(1);
    } else {
      reservation429Other.add(1);
    }
  } else if (response.status >= 500 && response.status <= 599) {
    bucket = '5xx';
    reservation5xx.add(1, {
      status: String(response.status),
      error_code: apiErrorCode,
    });
    add5xxStatus(response.status);
  } else {
    bucket = 'unexpected_http';
    unexpectedHttp.add(1, {
      status: String(response.status),
      error_code: apiErrorCode,
    });
  }

  outcomeBucket.add(1, { bucket });
  classifiedResponse.add(bucket !== 'unexpected_http');
}

function classify429(code) {
  if (code === 'SEAT_LOCK_TIMEOUT') {
    return 'seat_lock_timeout';
  }
  if (code === 'SEAT_ADMISSION_REJECTED') {
    return 'seat_admission_rejected';
  }
  if (/(ADMISSION|RATE|LIMIT|QUEUE|CAPACITY|THROTTL)/.test(code)) {
    return 'admission_or_rate_limit';
  }
  return 'other';
}

function errorCode(body) {
  const code = body?.error?.code;
  return typeof code === 'string' && code.length > 0 ? code : 'NO_API_ERROR_CODE';
}

function add5xxStatus(status) {
  if (status === 500) {
    reservation500.add(1);
  } else if (status === 501) {
    reservation501.add(1);
  } else if (status === 502) {
    reservation502.add(1);
  } else if (status === 503) {
    reservation503.add(1);
  } else if (status === 504) {
    reservation504.add(1);
  } else {
    reservation5xxOther.add(1);
  }
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

function requiredPositiveNumber(name) {
  const value = Number(required(name));
  if (!Number.isInteger(value) || value <= 0) {
    throw new Error(`${name}는 양의 정수여야 합니다. actual=${__ENV[name]}`);
  }
  return value;
}
