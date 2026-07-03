// Throwaway diagnostic: hammer the mock upstream directly (bypassing
// Pantera) with the same shape/rate the gate produces, and count statuses.
import http from 'k6/http';
import { Counter } from 'k6/metrics';
const statuses = new Counter('status_codes');
export const options = {
  scenarios: { probe: { executor: 'constant-arrival-rate', rate: 30, timeUnit: '1s', duration: '20s', preAllocatedVUs: 20, maxVUs: 40 } },
  discardResponseBodies: true,
};
export default function () {
  const id = String(Math.floor(Math.random() * 20000)).padStart(5, '0');
  const m = 1 + Math.floor(Math.random() * 5);
  const res = http.get(`http://mock-upstream:8080/m${m}/pkg-${id}/-/pkg-${id}-1.0.0.tgz`);
  statuses.add(1, { code: String(res.status) });
}
