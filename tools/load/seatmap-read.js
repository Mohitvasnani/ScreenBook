import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8082';

// Ramp up, hold, ramp down. 50 users against a pool of 10 connections.
export const options = {
  stages: [
    { duration: '10s', target: 10 },
    { duration: '30s', target: 50 },
    { duration: '10s', target: 0 },
  ],
};

// Runs once before the test. Finds one real screening to hit.
export function setup() {
  const cinemas = http.get(`${BASE}/api/cinemas?country=GB`).json();
  const showtimes = http.get(`${BASE}/api/showtimes?cinemaId=${cinemas[0].id}`).json();
  return { showtimeId: showtimes[0].id };
}

// Runs in a loop for every virtual user.
export default function (data) {
 const res = http.get(`${BASE}/api/showtimes/${data.showtimeId}/seats`, { timeout: '5s' });
  check(res, { 'status is 200': (r) => r.status === 200 });
}