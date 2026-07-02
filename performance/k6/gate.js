//
// Copyright (c) 2025-2026 Auto1 Group
// Maintainers: Auto1 DevOps Team
// Lead Maintainer: Ayd Asraf
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License v3.0.
//
// gate.js — bounded traffic ramp for the CI perf gate (M3-M4 invariants).
//
// This is NOT the scaling benchmark. scenario.js ramps to 3000 rps over
// ~20 minutes to find the ceiling; the perf gate only needs enough
// cache-miss traffic through the proxy group for the outbound counters
// (429s, amplification ratio, breaker trips) to be meaningful. A constant
// arrival rate for a fixed window keeps the gate fast (~1 min of load)
// and deterministic on shared CI runners, where wall-clock benchmarks
// would be noise.
//
// Env knobs (see docker-compose-scaling.yml `k6` service):
//   BASE_URL       default http://pantera-sut:8080
//   GATE_RATE      requests/second, default 30
//   GATE_DURATION  default 60s
//
// The `checks` threshold makes k6 exit non-zero when responses stop
// being 2xx — a silent all-401/all-503 run must fail the workflow
// instead of handing the invariant check an idle instance.
//
import http from 'k6/http';
import { check } from 'k6';
import encoding from 'k6/encoding';
import { mkReadPath } from './payload-helpers.js';

const BASE_URL = __ENV.BASE_URL || 'http://pantera-sut:8080';
const RATE = parseInt(__ENV.GATE_RATE || '30', 10);
const DURATION = __ENV.GATE_DURATION || '60s';
const USER = __ENV.PANTERA_USER || 'bench';
const PASS = __ENV.PANTERA_PASS || 'benchpass';
const AUTH_HEADER = 'Basic ' + encoding.b64encode(`${USER}:${PASS}`);

// Read-only mix, biased toward the proxy cold-cache path — the traffic
// shape the M3-M4 invariants exist to police. No writes: uploads bring
// fixture/consistency baggage the gate does not need.
const WEIGHTS = [
  ['group-proxy-miss', 0.50],
  ['group-proxy-cached', 0.30],
  ['direct-local', 0.20],
];

function pickRoute() {
  const r = Math.random();
  let acc = 0;
  for (const [name, w] of WEIGHTS) {
    acc += w;
    if (r < acc) return name;
  }
  return WEIGHTS[WEIGHTS.length - 1][0];
}

export const options = {
  scenarios: {
    gate: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 20,
      maxVUs: 60,
    },
  },
  thresholds: {
    // 404s on never-seeded coordinates are tolerable; auth failures or a
    // dead upstream are not. 2xx+404 must dominate.
    checks: ['rate>0.90'],
  },
  discardResponseBodies: true,
};

// Sequential warm-up before the measured load: primes Pantera's JIT-cold
// request path AND the WireMock mock upstream (mapping compilation, body
// file page cache). Without it the first concurrent burst can see enough
// mock-side hiccups to trip the circuit breaker — which the gate then
// rightly reports, but as a harness artifact rather than a product
// regression. setup() failures are non-fatal by design; the measured
// phase decides the verdict.
export function setup() {
  for (let i = 0; i < 25; i++) {
    http.get(BASE_URL + mkReadPath('group-proxy-miss'), {
      headers: { Authorization: AUTH_HEADER },
      tags: { route: 'warmup' },
    });
  }
}

export default function () {
  const route = pickRoute();
  const res = http.get(BASE_URL + mkReadPath(route), {
    headers: { Authorization: AUTH_HEADER },
    tags: { route },
  });
  check(res, {
    'status 2xx or 404': (r) =>
      (r.status >= 200 && r.status < 300) || r.status === 404,
  }, { route });
}
