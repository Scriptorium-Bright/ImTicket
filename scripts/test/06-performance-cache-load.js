import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const testType = __ENV.TEST_TYPE || 'distribution';
const cacheOption = (__ENV.CACHE || 'false').toLowerCase();
const cacheEnabled = cacheOption === 'true';
const baseUrl = (__ENV.BASE_URL || 'http://127.0.0.1:10080').replace(/\/$/, '');
const users = positiveInteger('USERS', 5000);
const concurrency = positiveInteger(
  'CONCURRENCY',
  testType === 'stampede' ? 500 : users,
);
const steadyRate = positiveInteger('STEADY_RATE', 100);
const steadyPreAllocatedVus = positiveInteger('STEADY_PRE_ALLOCATED_VUS', 150);
const steadyMaxVus = positiveInteger('STEADY_MAX_VUS', 200);
const steadyDuration = __ENV.STEADY_DURATION || '60s';
const burstDelaySeconds = positiveNumber('BURST_DELAY_SECONDS', 10);
const maxDuration = __ENV.MAX_DURATION || '10m';
const performanceIds = parsePerformanceIds();

if (!['distribution', 'stampede', 'steady'].includes(testType)) {
  throw new Error(`TEST_TYPE은 distribution, stampede 또는 steady여야 합니다. actual=${testType}`);
}
if (!['true', 'false'].includes(cacheOption)) {
  throw new Error(`CACHE는 true 또는 false여야 합니다. actual=${__ENV.CACHE}`);
}
if (testType === 'distribution' && performanceIds.length !== 100) {
  throw new Error(
    `distribution 테스트에는 중복 없는 PERFORMANCE_IDS 100개가 필요합니다. actual=${performanceIds.length}`,
  );
}
if (testType === 'distribution' && concurrency > users) {
  throw new Error(`CONCURRENCY는 USERS보다 클 수 없습니다. concurrency=${concurrency}, users=${users}`);
}
if (testType === 'steady' && steadyMaxVus < steadyPreAllocatedVus) {
  throw new Error(
    `STEADY_MAX_VUS는 STEADY_PRE_ALLOCATED_VUS 이상이어야 합니다. max=${steadyMaxVus}, preAllocated=${steadyPreAllocatedVus}`,
  );
}
if (testType === 'stampede' && !cacheEnabled) {
  throw new Error('stampede 테스트는 CACHE=true로 실행해야 합니다.');
}

const expectedRequests = testType === 'stampede' ? concurrency : users;
const oneRequestPerVu = testType === 'distribution' && concurrency === users;
const synchronizedBurst = testType === 'stampede' || oneRequestPerVu;
const requestCountThreshold = testType === 'steady'
  ? 'count>0'
  : `count==${expectedRequests}`;
const performanceRequests = new Counter('performance_requests');
const performanceSuccesses = new Counter('performance_successes');
const unexpectedResponses = new Counter('unexpected_responses');
const requestStartLag = new Trend('request_start_lag', true);

http.setResponseCallback(http.expectedStatuses(200));

export const options = {
  scenarios: scenariosFor(testType),
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    performance_requests: [requestCountThreshold],
    performance_successes: [requestCountThreshold],
    unexpected_responses: ['count==0'],
    ...(testType === 'steady' ? { dropped_iterations: ['count==0'] } : {}),
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  tags: {
    test_type: testType,
    cache: String(cacheEnabled),
  },
};

function scenariosFor(type) {
  if (type === 'stampede') {
    return {
      cold_or_warm_key_burst: {
        executor: 'per-vu-iterations',
        vus: concurrency,
        iterations: 1,
        maxDuration,
        gracefulStop: '0s',
      },
    };
  }

  if (type === 'steady') {
    return {
      steady_performance_details: {
        executor: 'constant-arrival-rate',
        rate: steadyRate,
        timeUnit: '1s',
        duration: steadyDuration,
        preAllocatedVUs: steadyPreAllocatedVus,
        maxVUs: steadyMaxVus,
        gracefulStop: '5s',
      },
    };
  }

  if (oneRequestPerVu) {
    return {
      one_hundred_performances_burst: {
        executor: 'per-vu-iterations',
        vus: concurrency,
        iterations: 1,
        maxDuration,
        gracefulStop: '0s',
      },
    };
  }

  return {
    one_hundred_performances: {
      executor: 'shared-iterations',
      vus: concurrency,
      iterations: users,
      maxDuration,
      gracefulStop: '0s',
    },
  };
}

export function setup() {
  return {
    startAt: synchronizedBurst
      ? Date.now() + burstDelaySeconds * 1000
      : null,
  };
}

export default function (data) {
  if (synchronizedBurst) {
    const remainingSeconds = (data.startAt - Date.now()) / 1000;
    if (remainingSeconds > 0) {
      sleep(remainingSeconds);
    }
    requestStartLag.add(Math.max(0, Date.now() - data.startAt));
  }

  const performanceId = testType === 'stampede'
    ? performanceIds[0]
    : performanceIds[exec.scenario.iterationInTest % performanceIds.length];
  const response = http.get(
    `${baseUrl}/api/performance/intro/${performanceId}?cache=${cacheEnabled}`,
    {
      tags: {
        name: 'GET /api/performance/intro/:performanceId',
        access: cacheEnabled ? 'cache' : 'direct',
      },
      timeout: '15s',
    },
  );

  performanceRequests.add(1);
  const body = safeJson(response);
  const successful = response.status === 200
    && body?.success === true
    && typeof body?.data?.title === 'string';

  if (successful) {
    performanceSuccesses.add(1);
  } else {
    unexpectedResponses.add(1);
  }

  check(response, {
    'status is 200': (res) => res.status === 200,
    'response has performance details': () => successful,
  });
}

function parsePerformanceIds() {
  const raw = __ENV.PERFORMANCE_IDS || __ENV.PERFORMANCE_ID || '';
  const tokens = raw
    .split(',')
    .map((value) => value.trim());

  if (tokens.length === 0 || tokens.some((value) => !/^[1-9][0-9]*$/.test(value))) {
    throw new Error('PERFORMANCE_IDS에는 양의 정수만 사용할 수 있습니다.');
  }

  const values = tokens.map((value) => Number(value));
  const unique = [...new Set(values)];

  if (unique.length !== values.length) {
    throw new Error('PERFORMANCE_IDS에 중복 값이 있습니다.');
  }
  return unique;
}

function positiveInteger(name, fallback) {
  const value = Number(__ENV[name] || fallback);
  if (!Number.isInteger(value) || value <= 0) {
    throw new Error(`${name}은 양의 정수여야 합니다. actual=${__ENV[name]}`);
  }
  return value;
}

function positiveNumber(name, fallback) {
  const value = Number(__ENV[name] || fallback);
  if (!Number.isFinite(value) || value <= 0) {
    throw new Error(`${name}은 0보다 큰 숫자여야 합니다. actual=${__ENV[name]}`);
  }
  return value;
}

function safeJson(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}
