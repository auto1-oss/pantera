/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
 */
import http from 'k6/http';
import encoding from 'k6/encoding';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { mkReadPath, mkWritePath } from './payload-helpers.js';
import { UPLOAD_BODIES, UPLOAD_NAMES } from './uploads.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8088';
const CELL     = __ENV.CELL_LABEL || 'unknown';
const WARMUP   = (__ENV.WARMUP || 'true') === 'true';
const USER     = __ENV.PANTERA_USER || 'bench';
const PASS     = __ENV.PANTERA_PASS || 'benchpass';
const AUTH_HEADER = 'Basic ' + encoding.b64encode(USER + ':' + PASS);

// UPLOAD_BODIES — array of real npm tarballs (from fixtures/uploads/, loaded by
// k6/uploads.js). Writes rotate through them so Pantera's upload path sees a
// realistic mix of valid package.json payloads. Reads never use these — proxy
// reads hit the synthetic WireMock-served bodies.
// Per-VU shared counter (k6 default scope is per-VU) gives a deterministic
// round-robin without shared state contention.
let uploadCursor = 0;
function nextUploadBody() {
  const body = UPLOAD_BODIES[uploadCursor % UPLOAD_BODIES.length];
  const name = UPLOAD_NAMES[uploadCursor % UPLOAD_NAMES.length];
  uploadCursor += 1;
  return { body, name };
}

const requestsByRoute = new Counter('route_hits');
const bodyBytes       = new Counter('resp_bytes');
const latencyByRoute  = new Trend('latency_by_route', true);

// --- weighted route selection ------------------------------------------------
const WEIGHTS = [
  ['group-local',        0.144],
  ['group-proxy-cached', 0.461],
  ['group-proxy-miss',   0.115],
  ['direct-local',       0.180],
  ['write',              0.100],
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

// --- ramp schedule (150 rps / 60 s steps, ceiling 3000) ----------------------
function stages() {
  const s = [];
  if (WARMUP) s.push({ target: 100, duration: '180s' });   // warm-up
  for (let rps = 150; rps <= 3000; rps += 150) {
    s.push({ target: rps, duration: '60s' });
  }
  s.push({ target: 0, duration: '30s' });                  // cool-down
  return s;
}

export const options = {
  scenarios: {
    mix: {
      executor: 'ramping-arrival-rate',
      startRate: WARMUP ? 50 : 150,
      timeUnit: '1s',
      preAllocatedVUs: 400,
      maxVUs: 2000,
      stages: stages(),
    },
  },
  summaryTrendStats: ['min', 'avg', 'med', 'p(95)', 'p(99)', 'max'],
  summaryTimeUnit: 'ms',
  // Drop response bodies after response.timings are captured — prevents holding
  // up to 10 MB per in-flight request on the k6 side. Without this, a 3000 rps
  // step with the 10 MB tail of the size distribution buffers ~30 GB in memory.
  discardResponseBodies: true,
};

export default function () {
  const route = pickRoute();
  let res;

  if (route === 'write') {
    const upload = nextUploadBody();
    // Upload under the package's real name so Pantera's Tgz parser can match
    // its internal package.json to the URL-encoded name ("bench-upload" would
    // fail consistency checks; the fixtures' own names pass).
    const path = `/local-repo-${1 + (uploadCursor % 5)}/${upload.name}/-/${upload.name}.tgz`;
    res = http.put(BASE_URL + path, upload.body, {
      headers: {
        'Content-Type': 'application/octet-stream',
        'Authorization': AUTH_HEADER,
      },
      tags: { route: 'write', cell: CELL },
    });
  } else {
    const path = mkReadPath(route);
    res = http.get(BASE_URL + path, {
      headers: { 'Authorization': AUTH_HEADER },
      tags: { route, cell: CELL },
    });
  }

  requestsByRoute.add(1, { route });
  latencyByRoute.add(res.timings.duration, { route });
  // res.body is null under discardResponseBodies — track via Content-Length header.
  const clen = res.headers && (res.headers['Content-Length'] || res.headers['content-length']);
  if (clen) bodyBytes.add(parseInt(clen, 10) || 0, { route });
  check(res, {
    'status 2xx': (r) => r.status >= 200 && r.status < 300,
  }, { route });
}
