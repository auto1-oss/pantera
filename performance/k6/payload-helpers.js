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
// Shared helpers for the k6 scaling scenario.
// Exports:
//   - pickPkgId()        → integer in [0, 19999]
//   - pickSize(pkgId)    → 102400 | 1048576 | 10485760 (deterministic per pkgId)
//   - mkReadPath(route)  → URL path for the chosen route
//   - mkWritePath()      → URL path for a PUT
//
// URL convention: Pantera routes requests of shape `/<repo-or-group-name>/<pkg>/-/<file>`
// (no `/artifactory/api/npm/` prefix). Mirrors benchmark/setup/repos-new/npm.yaml `url:` field.
//
// ALL reads go through the proxy group (`hot-proxy-group`) because the 100-k-row
// DB fixture does NOT materialise actual tarball bytes on disk — local-repo reads
// would 404. Proxy reads hit WireMock which serves real bodies, exercising the
// cache+stream path. When materialised fixtures are added later, local-repo and
// group-local routes can be re-enabled.

export const REPO_POOL = ['local-repo-1','local-repo-2','local-repo-3','local-repo-4','local-repo-5'];
export const PKG_COUNT = 20000;

export function pickPkgId() {
  return Math.floor(Math.random() * PKG_COUNT);
}

// Deterministic size bucket: mod 10 → 7,8 → 1M; 9 → 10M; else 100K.
export function pickSize(pkgId) {
  const m = pkgId % 10;
  if (m === 9) return 10485760;
  if (m === 7 || m === 8) return 1048576;
  return 102400;
}

function pkgName(id) {
  return 'pkg-' + String(id).padStart(5, '0');
}

function pickRepo() {
  return REPO_POOL[Math.floor(Math.random() * REPO_POOL.length)];
}

// Route categories defined by weighted sampling in scenario.js:
//   'group-local'        — hit one of the 3 all-local groups
//   'group-proxy-cached' — hot proxy group, artifact already cached at Pantera
//   'group-proxy-miss'   — hot proxy group, first fetch from upstream
//   'direct-local'       — direct local-repo read
export function mkReadPath(route) {
  const id = pickPkgId();
  const name = pkgName(id);
  const ver = '1.0.0';
  const tgz = `${name}-${ver}.tgz`;
  switch (route) {
    case 'group-local':
      return `/local-group-${1 + Math.floor(Math.random() * 3)}/${name}/-/${tgz}`;
    case 'group-proxy-cached':
    case 'group-proxy-miss':
      // Same URL shape for both; cached-ness is a function of whether the
      // artifact was fetched before. scenario.js pre-warms the 'cached'
      // set during warm-up.
      return `/hot-proxy-group/${name}/-/${tgz}`;
    case 'direct-local':
      return `/${pickRepo()}/${name}/-/${tgz}`;
    default:
      throw new Error('unknown route: ' + route);
  }
}

export function mkWritePath() {
  // Write-only identifier space avoids colliding with read pool.
  const id = 20000 + Math.floor(Math.random() * 1000);
  const name = 'upload-pkg-' + String(id).padStart(5, '0');
  const ver = '1.0.0';
  return `/${pickRepo()}/${name}/-/${name}-${ver}.tgz`;
}
