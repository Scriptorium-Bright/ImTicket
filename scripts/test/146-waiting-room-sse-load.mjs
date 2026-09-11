import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import https from 'node:https';
import path from 'node:path';

const baseUrl = new URL(required('BASE_URL'));
const performanceTimeId = positiveInteger(required('PT_ID'));
const jwtSecret = required('JWT_SECRET');
const cohort = positiveInteger(process.env.COHORT ?? '2000');
const memberIdBase = positiveInteger(process.env.MEMBER_ID_BASE ?? '900000000');
const walletIdBase = nonNegativeInteger(process.env.WALLET_ID_BASE ?? '0');
const startDelayMs = nonNegativeInteger(process.env.START_DELAY_MS ?? '2000');
const requestTimeoutMs = positiveInteger(process.env.REQUEST_TIMEOUT_MS ?? '10000');
const maxWaitMs = positiveInteger(process.env.MAX_WAIT_MS ?? '300000');
const seatIds = parsePositiveNumberList(required('SEAT_IDS'));
const resultPath = process.env.RESULT_PATH ?? 'build/k6-results/waiting-room-sse/result.json';
const transport = baseUrl.protocol === 'https:' ? https : http;
const agent = new transport.Agent({ keepAlive: true, maxSockets: cohort + 100 });
const startAt = Date.now() + startDelayMs;

const metrics = {
  joinDurations: [],
  queueWaitDurations: [],
  journeyDurations: [],
  seatMapDurations: [],
  preReserveDurations: [],
  streamConnections: 0,
  streamFailures: 0,
  streamRetries: 0,
  snapshots: 0,
  admittedEvents: 0,
  statusHttpRequests: 0,
  joinSuccess: 0,
  seatMapSuccess: 0,
  preReserveExpected: 0,
  contractSuccess: 0,
  failures: {},
};

await Promise.all(Array.from({ length: cohort }, (_, index) => runMember(index + 1)));
agent.destroy();

const summary = {
  cohort,
  statusHttpRequests: metrics.statusHttpRequests,
  streamConnections: metrics.streamConnections,
  streamFailures: metrics.streamFailures,
  streamRetries: metrics.streamRetries,
  snapshots: metrics.snapshots,
  admittedEvents: metrics.admittedEvents,
  joinSuccess: metrics.joinSuccess,
  seatMapSuccess: metrics.seatMapSuccess,
  preReserveExpected: metrics.preReserveExpected,
  contractSuccess: metrics.contractSuccess,
  contractSuccessRate: ratio(metrics.contractSuccess, cohort),
  joinDurationMs: percentileSummary(metrics.joinDurations),
  queueWaitDurationMs: percentileSummary(metrics.queueWaitDurations),
  journeyDurationMs: percentileSummary(metrics.journeyDurations),
  seatMapDurationMs: percentileSummary(metrics.seatMapDurations),
  preReserveDurationMs: percentileSummary(metrics.preReserveDurations),
  failures: metrics.failures,
};

fs.mkdirSync(path.dirname(resultPath), { recursive: true });
fs.writeFileSync(resultPath, JSON.stringify({
  configuration: {
    baseUrl: baseUrl.toString(),
    performanceTimeId,
    cohort,
    memberIdBase,
    startDelayMs,
    requestTimeoutMs,
    maxWaitMs,
    seatCount: seatIds.length,
  },
  summary,
}, null, 2));
console.log(JSON.stringify(summary, null, 2));
if (metrics.contractSuccess !== cohort) {
  process.exitCode = 1;
}

async function runMember(sequence) {
  await waitFor(startAt);
  const identity = identityFor(sequence);
  const headers = { Authorization: `Bearer ${createJwt(identity)}` };
  const journeyStartedAt = Date.now();
  try {
    const joinStartedAt = Date.now();
    const join = await requestJson('POST', `/api/reservation/waiting-room/${performanceTimeId}/join`, headers);
    metrics.joinDurations.push(Date.now() - joinStartedAt);
    const ticket = join.body?.data;
    if (join.status !== 200 || join.body?.success !== true || typeof ticket?.ticketId !== 'string') {
      fail(`join_${join.status}`);
      return;
    }
    metrics.joinSuccess += 1;

    const admitted = await waitForAdmission(headers, ticket.ticketId);
    metrics.queueWaitDurations.push(Date.now() - join.completedAt);
    const passHeaders = { ...headers, 'X-Waiting-Room-Pass': admitted.entryPass };

    const seatMapStartedAt = Date.now();
    const seatMap = await requestJson('GET', `/api/seats/${performanceTimeId}`, passHeaders);
    metrics.seatMapDurations.push(Date.now() - seatMapStartedAt);
    const seatMapSucceeded = seatMap.status === 200 && seatMap.body?.success === true && Array.isArray(seatMap.body?.data);
    if (seatMapSucceeded) {
      metrics.seatMapSuccess += 1;
    } else {
      fail(`seat_map_${seatMap.status}`);
    }

    const preReserveStartedAt = Date.now();
    const seatId = seatIds[(sequence - 1) % seatIds.length];
    const preReserve = await requestJson(
      'POST',
      '/api/reservation/pre-reserve',
      {
        ...passHeaders,
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey(identity.memberId),
      },
      JSON.stringify({ performanceTimeId, seatIds: [seatId] }),
    );
    metrics.preReserveDurations.push(Date.now() - preReserveStartedAt);
    const preReserveExpected = (preReserve.status === 200 && preReserve.body?.success === true)
      || (preReserve.status === 409 && preReserve.body?.error?.code === 'SEAT_ALREADY_RESERVED')
      || (preReserve.status === 429 && preReserve.body?.error?.code === 'SEAT_ADMISSION_REJECTED');
    if (preReserveExpected) {
      metrics.preReserveExpected += 1;
    } else {
      fail(`pre_reserve_${preReserve.status}`);
    }

    if (seatMapSucceeded && preReserveExpected) {
      metrics.contractSuccess += 1;
    }
  } catch (error) {
    fail(error instanceof Error ? error.message : 'unknown');
  } finally {
    metrics.journeyDurations.push(Date.now() - journeyStartedAt);
  }
}

async function waitForAdmission(headers, ticketId) {
  const deadline = Date.now() + maxWaitMs;
  let consecutiveFailures = 0;
  while (Date.now() < deadline) {
    try {
      return await openSseStream(headers, ticketId, deadline);
    } catch (error) {
      if (String(error.message).startsWith('terminal_')) {
        throw error;
      }
      consecutiveFailures += 1;
      metrics.streamFailures += 1;
      if (consecutiveFailures === 3) {
        const status = await requestJson(
          'GET',
          `/api/reservation/waiting-room/${performanceTimeId}/tickets/${ticketId}`,
          headers,
        );
        metrics.statusHttpRequests += 1;
        const snapshot = status.body?.data;
        if (status.status === 200 && snapshot?.status === 'ADMITTED' && typeof snapshot.entryPass === 'string') {
          return snapshot;
        }
        if (status.status === 200 && snapshot?.status && snapshot.status !== 'WAITING') {
          throw new Error(`terminal_${snapshot.status}`);
        }
      }
      metrics.streamRetries += 1;
      await wait(fullJitterReconnectDelay(consecutiveFailures));
    }
  }
  throw new Error('sse_timeout');
}

function openSseStream(headers, ticketId, deadline) {
  return new Promise((resolve, reject) => {
    const request = transport.request(requestOptions(
      'GET',
      `/api/reservation/waiting-room/${performanceTimeId}/tickets/${ticketId}/events`,
      { ...headers, Accept: 'text/event-stream' },
    ));
    let settled = false;
    const finish = (callback, value) => {
      if (settled) {
        return;
      }
      settled = true;
      request.destroy();
      callback(value);
    };
    request.setTimeout(Math.max(1, deadline - Date.now()), () => finish(reject, new Error('sse_timeout')));
    request.on('error', (error) => finish(reject, error));
    request.on('response', (response) => {
      if (response.statusCode !== 200) {
        response.resume();
        finish(reject, new Error(`sse_${response.statusCode}`));
        return;
      }
      metrics.streamConnections += 1;
      let buffer = '';
      response.setEncoding('utf8');
      response.on('data', (chunk) => {
        buffer += chunk.replace(/\r/g, '');
        let boundary = buffer.indexOf('\n\n');
        while (boundary >= 0) {
          const frame = buffer.slice(0, boundary);
          buffer = buffer.slice(boundary + 2);
          const event = parseSseFrame(frame);
          if (event?.type === 'snapshot') {
            metrics.snapshots += 1;
          }
          if (event?.type === 'admitted') {
            metrics.admittedEvents += 1;
          }
          const ticket = event?.data;
          if ((event?.type === 'snapshot' || event?.type === 'admitted')
              && ticket?.status === 'ADMITTED'
              && typeof ticket.entryPass === 'string') {
            finish(resolve, ticket);
            return;
          }
          if (event?.type === 'terminal') {
            finish(reject, new Error(`terminal_${ticket?.status ?? 'unknown'}`));
            return;
          }
          boundary = buffer.indexOf('\n\n');
        }
      });
      response.on('error', (error) => finish(reject, error));
      response.on('end', () => finish(reject, new Error('sse_closed')));
    });
    request.end();
  });
}

function fullJitterReconnectDelay(consecutiveFailures) {
  const cap = Math.min(30_000, 1_000 * (2 ** Math.min(consecutiveFailures - 1, 5)));
  return Math.floor(Math.random() * (cap + 1));
}

function wait(delayMs) {
  return new Promise((resolve) => setTimeout(resolve, delayMs));
}

function parseSseFrame(frame) {
  let type = null;
  const data = [];
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) {
      type = line.slice('event:'.length).trim();
    }
    if (line.startsWith('data:')) {
      data.push(line.slice('data:'.length).trim());
    }
  }
  if (!type || data.length === 0) {
    return null;
  }
  try {
    return { type, data: JSON.parse(data.join('\n')) };
  } catch {
    return null;
  }
}

function requestJson(method, path, headers, body) {
  return new Promise((resolve, reject) => {
    const request = transport.request(requestOptions(method, path, headers), (response) => {
      let payload = '';
      response.setEncoding('utf8');
      response.on('data', (chunk) => {
        payload += chunk;
      });
      response.on('end', () => {
        let parsed = null;
        try {
          parsed = JSON.parse(payload);
        } catch {
          // HTTP status와 body 원문은 실패 분류에 충분하다.
        }
        resolve({ status: response.statusCode, body: parsed, completedAt: Date.now() });
      });
    });
    request.setTimeout(requestTimeoutMs, () => request.destroy(new Error('request_timeout')));
    request.on('error', reject);
    if (body) {
      request.write(body);
    }
    request.end();
  });
}

function requestOptions(method, path, headers) {
  return {
    protocol: baseUrl.protocol,
    hostname: baseUrl.hostname,
    port: baseUrl.port || undefined,
    method,
    path: `${baseUrl.pathname.replace(/\/$/, '')}${path}`,
    headers,
    agent,
  };
}

function identityFor(sequence) {
  const memberId = memberIdBase + sequence;
  const walletSequence = walletIdBase + sequence;
  return {
    memberId,
    walletAddress: `0xbench${String(walletSequence).padStart(34, '0')}`,
  };
}

function createJwt(identity) {
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = base64Url(JSON.stringify({
    memberId: identity.memberId,
    walletAddress: identity.walletAddress,
    role: 'ROLE_USER',
    iat: now,
    exp: now + 3600,
  }));
  const unsigned = `${header}.${payload}`;
  const signature = crypto.createHmac('sha256', jwtSecret).update(unsigned).digest('base64url');
  return `${unsigned}.${signature}`;
}

function base64Url(value) {
  return Buffer.from(value).toString('base64url');
}

function idempotencyKey(memberId) {
  const run = Date.now().toString(16).padStart(12, '0').slice(-12);
  const member = Number(memberId).toString(16).padStart(12, '0').slice(-12);
  return `${run.slice(0, 8)}-${run.slice(8, 12)}-4000-8000-${member}`;
}

function waitFor(timestamp) {
  return new Promise((resolve) => setTimeout(resolve, Math.max(0, timestamp - Date.now())));
}

function percentileSummary(values) {
  if (values.length === 0) {
    return null;
  }
  const sorted = [...values].sort((left, right) => left - right);
  return {
    count: sorted.length,
    p50: percentile(sorted, 0.5),
    p95: percentile(sorted, 0.95),
    p99: percentile(sorted, 0.99),
    max: sorted.at(-1),
  };
}

function percentile(sorted, ratioValue) {
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * ratioValue) - 1)];
}

function ratio(numerator, denominator) {
  return denominator === 0 ? 0 : numerator / denominator;
}

function fail(key) {
  metrics.failures[key] = (metrics.failures[key] ?? 0) + 1;
}

function parsePositiveNumberList(value) {
  const parsed = value.split(',').map((entry) => Number(entry.trim())).filter((entry) => Number.isInteger(entry) && entry > 0);
  if (parsed.length === 0) {
    throw new Error('SEAT_IDS must contain positive integers');
  }
  return parsed;
}

function required(name) {
  const value = process.env[name];
  if (!value) {
    throw new Error(`${name} is required`);
  }
  return value;
}

function positiveInteger(value) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`positive integer required: ${value}`);
  }
  return parsed;
}

function nonNegativeInteger(value) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 0) {
    throw new Error(`non-negative integer required: ${value}`);
  }
  return parsed;
}
