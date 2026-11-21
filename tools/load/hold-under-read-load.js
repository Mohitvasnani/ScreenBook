import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://localhost:8082';
const READERS = Number(__ENV.READERS || 50);
const RACES = 20;       // one contested seat per race
const CONTENDERS = 8;   // simultaneous hold requests per seat

const scenarios = {
  racers: {
    executor: 'per-vu-iterations',
    exec: 'race',
    vus: 1,
    iterations: RACES,
    startTime: '5s',    // let the readers reach full load first
    maxDuration: '60s',
  },
};
// These thresholds never fail. They exist so k6 prints each scenario's
// latency separately in the summary.
const thresholds = { 'http_req_duration{scenario:racers}': ['max>=0'] };

if (READERS > 0) {
  scenarios.readers = {
    executor: 'constant-vus',
    exec: 'read',
    vus: READERS,
    duration: '40s',
  };
  thresholds['http_req_duration{scenario:readers}'] = ['max>=0'];
}

export const options = { scenarios, thresholds };

// Finds the first screening with enough free seats, so holds left by an
// earlier run never matter. Each screening has its own seats.
export function setup() {
  const cinemas = http.get(`${BASE}/api/cinemas?country=GB`).json();
  const showtimes = http.get(`${BASE}/api/showtimes?cinemaId=${cinemas[0].id}`).json();
  for (const st of showtimes) {
    const map = http.get(`${BASE}/api/showtimes/${st.id}/seats`).json();
    const seatIds = map.seats
      .filter((s) => s.status === 'AVAILABLE')
      .slice(0, RACES)
      .map((s) => s.id);
    if (seatIds.length === RACES) {
      return { showtimeId: st.id, seatIds };
    }
  }
  throw new Error('No screening has enough free seats. Wait for holds to expire.');
}

export function read(data) {
  const res = http.get(`${BASE}/api/showtimes/${data.showtimeId}/seats`, { timeout: '5s' });
  check(res, { 'read is 200': (r) => r.status === 200 });
}

export function race(data) {
  const seatId = data.seatIds[exec.scenario.iterationInTest];
  const body = JSON.stringify({ showtimeId: data.showtimeId, seatIds: [seatId] });
  const params = { headers: { 'Content-Type': 'application/json' }, timeout: '10s', responseCallback: http.expectedStatuses(201, 409) };

  // http.batch sends all of these at the same moment from one VU.
  const responses = http.batch(
    Array.from({ length: CONTENDERS }, () => ['POST', `${BASE}/api/holds`, body, params])
  );

  const result = {
    wins: responses.filter((r) => r.status === 201).length,
    losses: responses.filter((r) => r.status === 409).length,
  };
  check(result, {
    'exactly one winner': (r) => r.wins === 1,
    'everyone else got a 409': (r) => r.losses === CONTENDERS - 1,
  });

  sleep(1); // spread the races over about 20 seconds so they overlap the readers
}