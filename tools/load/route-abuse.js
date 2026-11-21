import http from 'k6/http';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8082';

const limited = new Counter('responses_429');
const unavailable = new Counter('responses_503');
const served = new Counter('responses_200');

export const options = { vus: 20, duration: '20s' };

export function setup() {
  const cinemas = http.get(`${BASE}/api/cinemas?country=GB`).json();
  return { cinemaId: cinemas[0].id };
}

export default function (data) {
  // A different origin every time, so the cache never answers it.
  const lat = 53 + Math.random();
  const lon = -3 + Math.random();
    const res = http.get(
    `${BASE}/api/cinemas/${data.cinemaId}/route?lat=${lat}&lon=${lon}`,
    { responseCallback: http.expectedStatuses(200, 429, 503), tags: { name: 'GET /api/cinemas/{id}/route' } }
  );
  if (res.status === 429) limited.add(1);
  else if (res.status === 503) unavailable.add(1);
  else if (res.status === 200) served.add(1);
}