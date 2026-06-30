# Changelog

## Version 2.2.0

- **HTTP client safety-net timer raised 5 ms → 30 s to unblock the
  cooldown-at-headers admission gate.** `JettyClientSlice` schedules
  `publisher::discardIfUnsubscribed` as a safety net for callers that
  never subscribe to the response body publisher. The original 5 ms
  delay was tuned for synchronous subscribers (test patterns that do
  `.get().body().asBytes()` in microseconds). The 2026-06-29 maven
  cooldown-at-headers change introduced an async path — extract
  `Last-Modified` from the GET response headers, then call
  `JdbcCooldownService.evaluateWithKnownDate(...)` which does an async
  DB / Valkey lookup, then subscribe the body to
  `streamThroughAndCommit`. Typical wall-time for that lookup is
  10–50 ms, with tail-latency several hundred ms under load — well
  past the 5 ms timer. When the timer fired first, the publisher's
  `AtomicReference<Subscriber>` got the `DISCARDED_SENTINEL`; the real
  subscribe then lost the CAS with `IllegalStateException:
  JettyContentSourcePublisher is single-subscriber`, the
  stream-through cache write failed, the maven slice returned a 502
  from its outer `.exceptionally`, and the group surfaced as a 500 to
  Maven / Gradle clients. Reported on the first install attempt after
  enabling cooldown.

  Fix: raise the discard timer to 30 s. The timer's job is to clean
  up bodies that callers genuinely abandon; 30 s is longer than any
  reasonable subscriber-attach window and shorter than the upstream
  connection's idle-close, so the safety-net behaviour is preserved
  for the abandoned-body case (just on a longer cadence) while
  legitimate async subscribers no longer lose the race.

  Verified post-fix: 0 single-subscriber failures across the cold
  bench (vs 6 / 7 cooldown-evaluated requests pre-fix), 1577 cold
  requests resolved with cooldown ON in 65.7 s (within 1 s of the
  cooldown-OFF run — cooldown overhead negligible end-to-end).

  Files: `http-client/.../JettyClientSlice.java` (5 ms → 30 s).

- **Maven cooldown moves to a header-time admission gate; cold
  `mvn dependency:resolve` through `maven_group` recovers 56 s of 77 s
  measured overhead vs. direct Maven Central (123 s → 67 s on a 1,577-
  coord workload, while direct stays at 46 s).** The pre-fetch
  cooldown evaluator (`evaluateCooldownOrProceed` in
  `verifyAndServePrimary`) was issuing a HEAD against Maven Central
  via `MavenHeadSource` to read `Last-Modified`, then the cache-miss
  path issued a GET against the same URL milliseconds later — two
  upstream RTTs per cold artifact. A diagnostic phase-timer pass over
  the cold bench attributed **53.4 s of the 77 s gap to 526 of these
  HEADs** (avg 101.6 ms each, with two 1.7 s timeouts).

  Fix: remove the pre-fetch HEAD probe. In maven `CachedProxySlice`,
  `verifyAndServePrimary` now goes directly to the cache-load + single-
  flight upstream fetch on a cache miss. Inside `fetchVerifyAndCache`,
  once `fetchPrimaryBody` returns the upstream response headers, a new
  `cooldownAtHeaders(...)` helper parses `Last-Modified` via
  `BaseCachedProxySlice.extractLastModified`, calls
  `cooldownService.evaluateWithKnownDate(request, parsedDate)` (the
  same path the metadata-filter already uses for inline-date evaluation),
  and either:

  - **block**: drains the upstream body via `drainUpstreamBody(Publisher)`
    (one-shot subscriber, discards every chunk), completes the single-
    flight leader gate so followers re-enter through the cooldown cache
    + DB hit, and returns the existing 403 `buildForbiddenResponse`. The
    body never reaches `streamThroughAndCommit` — blocked artifacts still
    never land in storage.

  - **allow**: proceeds with `streamThroughAndCommit` unchanged
    (Track 3 stream-through + Track 4 atomic commit semantics
    preserved). The verdict is settled before any body byte is
    subscribed, so streaming is single-subscription, header-time, and
    zero-buffering — no memory pressure under concurrent cold-burst
    load.

  Scale invariants under header-time eval (vs. pre-fetch HEAD):

  - Upstream RPS per cold artifact halves: 2 calls (HEAD + GET) → 1
    call (GET only). Cold-burst connection / bulkhead pressure halves
    in the same direction; eases pressure on Maven Central's per-IP
    throttle (the 429 history that motivated the original HEAD).
  - Cache invalidation, stale-while-revalidate, conditional GET for
    `maven-metadata.xml`, `RequestDeduplicator` / single-flight, the
    `artifact_publish_dates` DB row population — all unaffected. The
    cache-hit path remains zero-upstream.
  - Verdict for *allowed* artifacts: no body buffering — `cooldownAtHeaders`
    resolves before the body publisher is ever subscribed.
  - Verdict for *blocked* artifacts: one upstream GET body downloaded
    and discarded. Bounded — cooldown blocks newly-released artifacts
    only; volume is tiny in any cold resolve.
  - Fail-open on cooldown-evaluator errors matches the pre-fix
    `evaluateCooldownOrProceed` posture: availability > strictness.

  When `Last-Modified` is missing or unparseable,
  `evaluateWithKnownDate` is called with `Optional.empty()` and falls
  through to `JdbcCooldownService.shouldBlockNewArtifact`'s
  `PublishDateRegistries.publishDate(..., CACHE_ONLY)` lookup — keyed
  by `CooldownRequest.repoType()`, which still carries the slice's
  suffixed `rtype` (`maven-proxy` / `gradle-proxy`). The
  rtype-mismatch hazard the old `MavenProxySliceInspectorRtypeTest`
  guarded against remains closed; the test is rewritten to verify the
  modern invariant directly on `CooldownRequest`.

  Files: `pantera-core/.../http/cache/BaseCachedProxySlice.java`
  (relax `cooldownService` field + `buildForbiddenResponse` to
  `protected`);
  `maven-adapter/.../http/CachedProxySlice.java` (drop pre-fetch
  gate; add `cooldownAtHeaders` + `drainUpstreamBody`);
  `maven-adapter/.../http/CachedProxySliceTest.java` (cooldown-block
  test asserts upstream IS called once, body drained, still 403,
  storage still empty);
  `maven-adapter/.../http/MavenProxySliceInspectorRtypeTest.java`
  (rewritten to assert `CooldownRequest.repoType()` carries the
  slice's suffixed rtype, no DNS / `JettyClientSlices` needed).

- **Per-phase ECS log emissions for group-resolution diagnostics.**
  Added four additive `INFO`-level ECS log lines auto-tagged with
  `trace.id` via MDC so a single cold request can be reconstructed
  end-to-end with `jq 'select(.["trace.id"] == "...")'`:

  - `group_member_rtt` — per-sequential-member RTT in
    `GroupResolver.tryNextSequentialMember`, carrying `member.name`,
    `http.response.status_code`, `phase.duration_ms`. Pins
    dead-member-walk cost (which the synthesis hypothesis suggested
    was the dominant 18 s contributor — refuted by measurement: only
    1 dead-walk in a 1,579-coord bench, 1 ms total; the artifact_index
    routes 1,578 / 1,579 requests around the hosted member).
  - `cooldown_releasedate_rtt` — wall-time of
    `inspector.releaseDate()` HEAD in `JdbcCooldownService`, with
    `phase.timeout_hit` (capped at the 1.7 s budget) and
    `phase.has_date` flags. Now zero on the cache-miss happy path
    after the header-time-gate fix above.
  - `maven_metadata_rtt` — wall-time of the dedicated
    `maven-metadata.xml` fetch (separate cache path from binary
    artifacts) in maven `CachedProxySlice.preProcess`.
  - `group_request_summary` — one line per group request in
    `GroupResolver.recordMetrics` with total `phase.duration_ms` +
    outcome. Complements the existing Micrometer histogram
    (`pantera_group_resolution_duration_seconds`) with per-trace
    attribution rather than aggregated percentiles.

  Each phase remains zero-overhead when its ECS logger is configured
  off; structured fields avoid string interpolation. Used as the
  attribution basis for the cooldown-at-headers fix above.

  Files: `pantera-main/.../group/GroupResolver.java` (member RTT +
  summary); `pantera-main/.../cooldown/JdbcCooldownService.java`
  (releaseDate RTT); `maven-adapter/.../http/CachedProxySlice.java`
  (metadata RTT).

- **Audit log: `package.size` is now an integer (was scientific
  notation), and `client.ip` + `trace.id` are populated.** The
  `artifact.audit` logger's `artifact_publish` record had two
  long-standing data-quality issues:

  - `AuditLogger.publish(...)` took `double size`. Bytes are an
    integer count, but the ECS JSON layout emitted them with `D` →
    scientific-notation values like `3.64270308E8` for a ~364 MB
    Docker layer. Both unfriendly for humans and lossy / hostile to
    any downstream tool that runs numeric range queries on the
    field. Changed to `long` — values now log as plain integers
    (`248621366`).

  - `trace.id` and `client.ip` were missing from every
    `artifact_publish` record. MDC is per-thread; the EcsLoggingSlice
    values that populate the request-thread MDC do not survive the
    Vert.x worker-thread hops in the slice chain, and each downstream
    slice only re-published `user.name` (via `MDC.put("user.name",
    login)` on the worker), leaving `trace.id` / `client.ip` empty
    when the audit record fired from `DbConsumer` on
    `RxComputationThreadPool-1`.

  Fix: thread the two correlation fields via Pantera-internal request
  headers — `X-Pantera-Ctx-Trace-Id` and `X-Pantera-Ctx-Client-Ip`,
  added to inbound headers by `EcsLoggingSlice` before forwarding —
  and provide a small `RequestContextHeaders.bindToMdc(headers)`
  helper that downstream slices call inside their `.thenCompose` /
  `.thenApply` callbacks to restore MDC on the worker thread right
  before the code that consumes it. Headers are explicit `Slice`
  method arguments, so they propagate through every async hop
  regardless of which executor is wired. Wired into the Docker
  manifest get/head slices and the Maven cached-proxy event-enqueue
  path; future adapters get coverage by calling `bindToMdc` from
  their async boundary.

  `ProxyArtifactEvent` and `ArtifactEvent` now also auto-capture
  `traceId` / `clientIp` from MDC at construction time, and
  `ArtifactEvent.withContext(...)` lets a package processor thread
  the fields explicitly when needed. `DbConsumer.logArtifactPublish`
  restores both onto the consumer thread's MDC before calling
  `AuditLogger.publish` so the audit record carries them.

  Files: `pantera-core/.../audit/AuditLogger.java` (long size +
  client.ip), `pantera-core/.../scheduling/ArtifactEvent.java`
  (clientIp field + withContext copy method),
  `pantera-core/.../scheduling/ProxyArtifactEvent.java` (traceId +
  clientIp auto-capture),
  `pantera-core/.../http/slice/EcsLoggingSlice.java` (internal
  X-Pantera-Ctx-* headers),
  `pantera-core/.../http/log/RequestContextHeaders.java` (new
  helper), `pantera-main/.../db/DbConsumer.java` (restore client.ip
  alongside trace.id),
  `docker-adapter/.../http/manifest/GetManifestSlice.java` +
  `HeadManifestSlice.java` +
  `docker-adapter/.../cache/CacheManifests.java` (bindToMdc on
  worker thread), `maven-adapter/.../http/CachedProxySlice.java`
  (bindToMdc on enqueue),
  `maven-adapter/.../MavenProxyPackageProcessor.java` (`.withContext`
  forwarding).

- **Admin UI: unified save bar replaces nine per-section Save
  buttons.** The System Settings page exposed a separate save button
  per card (`Save`, `Save JWT`, `Save Auth Settings`, `Save Circuit
  Breaker Settings`, `Save Cooldown`, `Save HTTP Client`, per-key
  `Save` × 8 in Bulkhead, `Save HTTP Server`, `Save Links`). Each one
  worked but the flow forced admins to mentally batch their edits
  per-card and click save many times for one logical configuration
  change. Replaced with a sticky **Save changes (N)** bar at the
  bottom of the page that:

  - Hides itself when nothing is dirty (zero visual noise during
    read-only review).
  - Lists the changed sections as colored chips so the user sees
    *what* will be saved before they click. Hot-reload sections are
    blue with a `bolt` icon and tooltip "Applies immediately on save
    (hot reload)"; restart-required sections are amber with a
    `refresh` icon and a tooltip carrying the specific reason
    (`http_server.request_timeout is read once at startup via
    VertxMain.listenOn`).
  - One click submits every dirty section in parallel via the
    existing per-section save endpoints, then refreshes the
    baseline so the bar disappears.
  - A `Discard` button restores every field to the last-loaded
    value (including the bulkhead composable's per-key edits) so
    admins can experiment without committing.

  Per-card titles carry the same hot-reload / restart-required pill
  while editing, so the dynamic-vs-static signal is visible during
  the edit, not just at submit time. The `Reset to default` per-row
  Bulkhead button is kept because a reset is conceptually different
  from saving an edit (it deletes the per-row override).

  The hot-reload map is driven by the actual server-side wiring in
  `VertxMain` — `settingsCache.addListener('cooldown', …)` and
  `settingsCache.addListener('http_client.bulkhead.', …)`, plus the
  supplier patterns in `RepositorySlices` / `CooldownSupport` /
  `JwtTokens`. When a new hot-reload listener lands, flip the
  section's `hotReload` flag in `SECTION_META`.

  Files: `pantera-ui/src/views/admin/SettingsView.vue` (new
  `SECTION_META` constant, baseline-snapshot dirty tracking, sticky
  Save Changes bar, inline `SectionHeader` functional component
  with the hot-reload / restart-required pill, all 9 per-section
  Save buttons removed),
  `pantera-ui/src/views/admin/__tests__/SettingsView.snapshot.test.ts`
  (4 new tests pinning the save-bar hidden/visible behaviour and
  the restart-required signal).

- **Cooldown admin view: refresh button relocated + styled.** The
  previous refresh control sat in the page header next to the title
  as a small secondary-outlined button — low-contrast, low-visibility,
  and visually disconnected from the inputs it controls. Moved into
  the filter bar at the right edge, vertically aligned with the
  search / repo / type inputs via an invisible label spacer, styled
  as a primary outlined button. Added a tooltip showing "Updated N s
  ago" that ticks every 5 s so admins get passive staleness feedback
  without a per-second timer. The aria-label is unchanged so
  existing tests keep working.

  Files: `pantera-ui/src/views/admin/CooldownView.vue`,
  `pantera-ui/src/views/admin/__tests__/CooldownView.test.ts`
  (new test pinning the filter-bar placement).

- **Group-layer request coalescing: concurrent same-path bursts no
  longer race on stream-through commits.** A user's `gradle build`
  reproducing as 8/8 → HTTP 500 on the same kotlin-stdlib JAR through
  `gradle_group` exposed an old hazard: under N-way parallel resolves
  (typical of `mvn dependency:resolve`, `gradle build`, `uv lock`), each
  request entered the per-member `coalesceUpstream` independently. Each
  member's stream-through atomic-rename clobbered the same final path,
  and concurrent readers raced with `NoSuchFileException` at
  `FileStorage.metadata` / `FileChannel.open` mid-rename. Cache writes
  succeeded but readers got 500 because their lazily-opened file
  channels found the path missing.

  Fix: `GroupResolver.response` now coalesces concurrent same-path
  read requests at the group entrypoint via
  `SingleFlight<String, BufferedResponse>` keyed by `method + ' ' + path`.
  Exactly one resolve runs upstream per (method, path) burst; the
  leader buffers its response body into a byte[] snapshot; followers
  rebuild fresh `Response`s from that snapshot without touching the
  filesystem. Memory pressure is bounded by the 2-minute in-flight TTL
  on `SingleFlight` — a function of concurrent-burst × body size, not
  steady state. POST (npm-audit) bypasses dedup since request bodies
  matter per-caller.

  Verification: 10-way parallel curl burst on the failing URL
  `http://localhost:8081/test_prefix/api/gradle_group/org/jetbrains/kotlin/kotlin-stdlib-jdk7/2.0.21/kotlin-stdlib-jdk7-2.0.21.jar`
  with `gradle_proxy` disk + Valkey + `artifacts` DB purged: pre-fix
  10/10 → 500; post-fix 10/10 → 200, 946 bytes each, with 2 upstream
  cache_write events (one per member) instead of 10+.

  File: `pantera-main/.../group/GroupResolver.java`.

- **Cooldown admin view: in-page refresh.** Adds a "Refresh" button
  next to the page title that re-pulls the overview tiles and
  blocked-artifacts table in parallel without a page reload. Preserves
  current search / repo / type filters and pagination state. The
  button spins while in flight and is wired to the existing `loading`
  ref so accidental double-clicks coalesce. Especially useful after a
  dynamic cooldown-duration change — existing blocks are re-evaluated
  against the new `minimumAllowedAge` on the very next request (see
  `JdbcCooldownService.checkExistingBlockWithTimestamp`), but until
  now the only way to see the new state was a full browser reload.

  Files: `pantera-ui/src/views/admin/CooldownView.vue`,
  `pantera-ui/src/views/admin/__tests__/CooldownView.test.ts`.

- **PyPI uv compatibility — PEP 691 content negotiation, neg-cache key
  disambiguation, hosted-PEP 658 HEAD support.** The first wave of pypi
  cooldown fixes (RCA-pypi-A / B above) made cooldown blocks work for
  `pip install`, but the user's `uv` test suite then surfaced three
  follow-ups, all rooted in code paths the earlier fix had touched or
  put under load.

  **uv regression — JSON Accept returned `Content-Type: text/html`.**
  `PypiSimpleHandler.processUpstream` always serialised via the HTML
  rewriter after RCA-pypi-A, so a uv client sending
  `Accept: application/vnd.pypi.simple.v1+json` got HTML bytes under
  the wrong content type and fell back to "no upload-time" mode (which
  in turn silently disables `exclude-newer`). Fix: the handler now
  honours the client's Accept — it still fetches PEP 691 JSON upstream
  (the only shape that carries `upload-time`), then serialises back in
  the format the caller asked for. JSON output filters blocked entries
  in-place via Jackson, preserving upstream `meta` / `name` / `versions`
  fields verbatim. Verbatim pass-through kicks in when the upstream
  shape already matches the client's — no extra parse on the happy
  path. `ProxySlice` was updated to thread `SimpleApiFormat.fromHeaders`
  into the handler so JSON cache keys stay separate from HTML.

  Files: `pypi-adapter/.../cooldown/PypiSimpleHandler.java`
  (new `handle(line, clientWantsJson, user)` signature; new
  `allowedResponse` / `serialize` / `filterJson` / `emptyResponse`
  helpers), `pypi-adapter/.../http/ProxySlice.java` (`fromHeaders`
  + threading), `pypi-adapter/.../cooldown/PypiSimpleHandlerTest.java`
  (signature update).

  **Negative-cache key collision — index 404 poisoned every `.whl`
  for the same package.** `NegativeCacheKey.PYPI_FILE` matched the
  upstream `/packages/<hash>/<file>` layout only; the Pantera-hosted
  `/simple/<pkg>/<version>/<file>` layout that the hosted `SliceIndex`
  emits as relative hrefs fell through to the empty-version fallback,
  so `/simple/hello/` (index path, name="hello", version="") and
  `/simple/hello/0.2.0/hello-0.2.0-py3-none-any.whl` (file path,
  name="hello", version="") collapsed to the same key
  `hello:`. A 404 on the index — or on a *different* version's `.whl` —
  poisoned the cache for every hello variant for the dedup TTL. Fix:
  widen the regex prefix from `(?:packages/(?:[a-f0-9/]+)?)?` to
  `(?:.*/)?` so both layouts now resolve to their own
  `(name, version)` tuple. Hosted-only test packages, JFrog-style
  paths, and any future PEP 740-attestation files share the broadened
  prefix matcher.

  File: `pantera-core/.../http/cache/NegativeCacheKey.java`.

  **Hosted `PySlice` returned 404 on HEAD.** `uv lock` probes every
  `.whl` with HEAD before deciding to stream (Range-request capability
  check, size pre-flight). `PySlice` only registered GET / POST /
  DELETE routes — HEAD requests fell through to the
  `RtRule.FALLBACK` `notFound()` SliceSimple, so uv aborted with
  `Failed to fetch: <whl> 404 Not Found` even though the file
  existed on disk. Fix: add HEAD routes mirroring the file + index
  GET routes, backed by `HeadAsGetSlice` — a small adapter that
  rewrites HEAD → GET against the wrapped slice and drains the body
  before returning (RFC 9110 §9.3.2). The single GET handler stays
  the source of truth for "does this file exist and what are its
  headers"; HEAD callers get Content-Length and Content-Type without
  the bytes.

  File: `pypi-adapter/.../http/PySlice.java`.

  Verification: `pantera-main/docker-compose/pantera/artifacts/python-uv/test.sh`
  now passes 7/7 (`test_hosted_json_index_structure`,
  `test_proxied_json_index_structure`,
  `test_proxied_json_has_upload_time`,
  `test_exclude_newer_pins_proxied_package_version`,
  `test_exclude_newer_rejects_future_proxied_version`,
  `test_exclude_newer_rejects_recent_hosted_package`,
  `test_exclude_newer_accepts_hosted_with_future_cutoff`).

- **PyPI cooldown silently let fresh versions through; two RCAs fixed.**
  User reproduced `pip install requests openai mcp claude cocode` and
  got `openai-2.38.0` (1 day old), `mcp-1.27.1` (14 d), `requests-2.34.2`
  (8 d) — every one inside the configured 30-day minimum. Trace through
  real container logs showed **zero `simple_filter` events** for these
  packages even though `artifact_publish_dates` already had the rows
  populated by the cache-write pipeline.

  **RCA-pypi-A — PyPI's `/simple/<pkg>/` HTML omits
  `data-upload-time`.** `PypiMetadataParser` extracted release dates
  exclusively from the PEP 700 `data-upload-time` attribute, but
  pypi.org doesn't emit it on the HTML variant (verified directly: 0/722
  links carry it for `openai`). `PypiMetadataParser.extractReleaseDates`
  returned an empty map → `evaluateWithKnownDate(req, Optional.empty())`
  for every version → fail-open. The PEP 691 JSON variant of the same
  endpoint *does* carry `upload-time` on every file (722/722 for
  `openai`).

  Fix: `PypiSimpleHandler.handle` now sends
  `Accept: application/vnd.pypi.simple.v1+json` to its upstream — the
  proxy's existing `SimpleApiFormat` negotiation routes that into a
  separate JSON cache key, `ProxySlice` rewrites each file's `url` to
  the local `/pypi_proxy/packages/...` path before caching (existing
  `JSON_PACKAGES` regex), and `PypiMetadataParser` now detects
  JSON-vs-HTML by first non-whitespace byte and parses either shape
  into the same `PypiSimpleIndex`. `processUpstream` was also updated
  to always serialise via the rewriter (HTML) — the
  "serve-upstream-bytes-verbatim" fast paths would have handed JSON
  bytes to pip under `Content-Type: text/html` and broken it.

  Files: `pypi-adapter/.../cooldown/PypiMetadataParser.java`
  (`parse` dispatches by content shape; new `parseJson` builds links
  from PEP 691 files, including `core-metadata` and `requires-python`),
  `pypi-adapter/.../cooldown/PypiSimpleHandler.java` (Accept header on
  upstream call; always-rewrite contract; `rewriteOrFallback` /
  `emptyHtmlResponse` helpers).

  **RCA-pypi-B — `evaluateWithKnownDate(Optional.empty())` silently
  allowed.** `JdbcCooldownService.shouldBlockNewArtifact` line 984
  treated empty inline release date as "no date → allow". But the
  cache-write pipeline had already written `artifact_publish_dates`
  rows for every cached version — the DB had the answer; the cooldown
  service just didn't look. Any handler that hands
  `evaluateWithKnownDate` an empty `Optional` (including any future
  ecosystem the JSON shortcut doesn't cover) hits this fail-open.

  Fix: before the silent-allow branch, look up
  `PublishDateRegistries.instance().publishDate(repoType, name,
  version, Mode.CACHE_ONLY)`. `CACHE_ONLY` skips the upstream HEAD
  fallback to keep the metadata-filter zero-extra-RTT contract from
  Track 5. If the registry has a date (DB hit), recurse with that date
  and run the normal freshness check. If truly unknown, fall through
  to the existing allow. Exceptions on the lookup degrade to
  `Optional.empty()` (existing fail-open behaviour preserved on
  registry hiccup).

  File: `pantera-main/.../cooldown/JdbcCooldownService.java`
  (`shouldBlockNewArtifact` empty-release branch).

### ⚠️ Breaking changes

- **Sequential-only group resolution.** Members are tried in declared order; the first 2xx response wins, and remaining members are consulted only on 404. Parallel fanout has been removed — sequential is now the **only** mode (Nexus / JFrog style). The legacy `members_strategy` YAML key is silently ignored regardless of value (a one-time WARN per group is emitted at boot, naming the deprecated key, then the key is dropped from the effective config). Order `members:` lists with the most-likely-to-have-the-artifact entry first (typically hosted before proxy).
  ([@aydasraf](https://github.com/aydasraf))
- **No metadata union-merge across group members.** `maven-metadata.xml` and `packages.json` group lookups now return the first member's 200 response verbatim. Configs that need union semantics should split into multiple group repos.
  ([@aydasraf](https://github.com/aydasraf))
- **Speculative prefetch removed.** The dependency-prefetch subsystem and its admin surface are gone. The prefetch admin UI panel, the `GET /api/v1/repositories/{name}/prefetch/stats` REST endpoint, and every `prefetch.*` runtime setting are removed; a database migration drops any persisted prefetch settings rows.
  ([@aydasraf](https://github.com/aydasraf))

### 🌟 New features

- **DB-backed publish-date registry** replaces per-adapter cooldown inspectors. Built-in upstream sources cover Maven Central and Go modules (npm / PyPI / Packagist / RubyGems sources were dropped before final 2.2.0 — see Performance below — because their release dates are already inline in the upstream metadata).
  ([@aydasraf](https://github.com/aydasraf))
- **Outbound-traffic observability.** Per-upstream-host request counts, error counts, and the outbound/inbound amplification ratio are exported as Prometheus metrics. Recording rules and Grafana-compatible alert definitions ship under `pantera-main/docker-compose/prometheus/rules/`.
  ([@aydasraf](https://github.com/aydasraf))
- **CI perf-gate workflow** validates every PR touching proxy / cache code against three invariants: zero upstream 429s, no sustained rate-limit gate closures, amplification ratio at or below 1.5.
  ([@aydasraf](https://github.com/aydasraf))
- **Observability pack for the perf surface.** Two new Grafana dashboards ship under `pantera-main/src/main/resources/grafana/` — one for the per-host upstream circuit breaker (state, trip counts, fast-fail rate, time-since-last-trip) and one for proxy-phase latency (stacked p99 by phase and repo, the canonical view for cold-bench debugging). Recording-rule alerts cover the 2.2.0 perf-pack (circuit-breaker-open, bulkhead overflow, sustained upstream 429s, low conditional-GET hit rate) with matching runbooks under `docs/runbooks/`.
  ([@aydasraf](https://github.com/aydasraf))
- **Per-repo anonymous-access controls.** A new `anonymous_read` / `anonymous_write` flag per repo decides whether unauthenticated requests get challenged. **Deny-by-default for every repo type** — admins explicitly opt in (e.g. `anonymous_read: true` on a public OSS-mirror proxy). Absent credentials return `401` with a `WWW-Authenticate: Basic realm="pantera"` header so every package manager prompts. The admin UI exposes both flags as checkboxes on the per-repo "Access" card, plus a bulk-update action on the admin repository-management view for rolling the policy across many repos at once (audit-logged with a shared `bulk_request_id`).
  ([@aydasraf](https://github.com/aydasraf))
- **Async CVE scanner skeleton.** Asynchronous OSV.dev vulnerability-scan plumbing landed (worker pool, exponential backoff, `artifact_vulnerabilities` + `artifact_scan_status` tables). Wiring to the cache-write hot path and the admin REST surface is deferred to a follow-up; the scanner is dormant in 2.2.0 unless explicitly invoked.
  ([@aydasraf](https://github.com/aydasraf))
- **Trace propagation completed across every async hop on the request path.** A single `trace.id` now connects an inbound request to its Vert.x WebClient outbound (webhooks), `java.net.http.HttpClient` outbound (OSV.dev), Valkey pub/sub envelope (v2; v1 still parsed for rolling-deploy compatibility), Quartz job execution, and every internal `CompletableFuture` continuation. `transaction.id` is now a first-class MDC key. Audit-log entries inherit the originating request's `trace.id` so an artifact upload and its HTTP session join in Kibana with a single field.
  ([@aydasraf](https://github.com/aydasraf))

### ⚡ Performance

- **Maven and Gradle cooldown enforcement.** Cooldown rules now block fresh `.jar`/`.pom`/`.module` admissions and strip blocked versions from `maven-metadata.xml` responses, including rewrite of `<latest>` and `<release>`. SNAPSHOT artifacts are evaluated per-timestamp.
  ([@aydasraf](https://github.com/aydasraf))
- **Per-repo-name and per-repo-type cooldown overrides** (enable/disable + minimum_allowed_age) are honored everywhere with `per-repo-name → per-repo-type → global` precedence. Operator changes via the admin UI take effect for both admission decisions and metadata filtering.
  ([@aydasraf](https://github.com/aydasraf))
- **SNAPSHOT cooldown knob** — separate `snapshots:` block under `cooldown:` (global) and `cooldown.repo_names.<repo>` for setting a stricter (or laxer) cooldown for SNAPSHOT artifacts than for releases. Configurable from the admin Settings page (global) and RepoEditView (per repository), permission-gated on `api_cooldown_permissions:write`.
  ([@aydasraf](https://github.com/aydasraf))
- **Cooldown filter no longer fires per-version upstream HEAD calls.** Inline release dates are read from the upstream metadata response directly; ecosystems without inline dates (Maven/Gradle) fall back to the cached publish-date registry. The 1.7s per-version upstream timeout that `npm install` fan-outs used to multiply is gone.
  ([@aydasraf](https://github.com/aydasraf))
- **Removed unused publish-date HTTP sources** for npm/PyPI/Composer/Gem. Their release dates are already inline in the upstream metadata response. Go and Maven head-fallback sources are kept.
  ([@aydasraf](https://github.com/aydasraf))
- **Filtered metadata response carries Pantera-computed `ETag` and `Last-Modified`**; upstream validators no longer leak through the transform. Inbound `If-None-Match` matching the computed ETag returns 304.
  ([@aydasraf](https://github.com/aydasraf))
- **Cooldown configuration is fully DB-driven.** The legacy per-repo YAML `cache.cooldown.enabled` key is no longer consulted; `cooldown.enabled`, `cooldown.repo_types.<type>`, and `cooldown.repo_names.<repo>` from the admin UI / DB are the single source of truth.
  ([@aydasraf](https://github.com/aydasraf))
- **`CachedNpmProxySlice`** no longer emits body-less 200 responses on the tarball metadata-cache hit path.
  ([@aydasraf](https://github.com/aydasraf))
- **Outbound HTTP/1.1 with a keep-alive connection pool (the Nexus / JFrog Artifactory pattern).** The v2.2.0 perf-pack briefly defaulted the upstream client to HTTP/2; during the release stabilisation pass we hit Jetty issue [#12776](https://github.com/jetty/jetty.project/issues/12776) (ByteBufferPool corruption on stream cancel), which manifested as `EOFException: Stream has been reset` bursts against `proxy.golang.org` under multi-module `go get`. 2.2.0 final ships outbound HTTP/1.1 only — every artifact registry Pantera proxies accepts HTTP/1.1 with keep-alive, so HTTP/2 is unnecessary and actively unsafe for this workload. The per-destination keep-alive pool size is sourced from `meta.http_client.max_connections_per_destination` in `pantera.yml`; there is no runtime protocol selector.
  ([@aydasraf](https://github.com/aydasraf))
- **Response-body streaming refactor: eager pre-drain + bounded staging buffer.** `JettyClientSlice` now bridges Jetty's `Content.Source` to Reactive Streams through `JettyContentSourcePublisher`, replacing the old `UnicastProcessor + StreamingDemander` pattern. The bridge pre-drains chunks on the Jetty I/O thread, copies each into a heap `ByteBuffer`, and releases the pooled buffer back to the `ArrayByteBufferPool` immediately — that is what lets HTTP/1.1 keep-alive reclaim connections even when downstream never reads the body. The staging queue caps at 64 chunks; subscribers consume from staging first, then pull further chunks from the source. The previous `UnicastProcessor` had an unbounded internal queue (no end-to-end backpressure) and the per-chunk heap copy through `StreamingDemander` was the single hottest allocation site under load.
  ([@aydasraf](https://github.com/aydasraf))
- **Adaptive per-repo bulkhead.** The fixed-permits bulkhead has been replaced by a controller that grows or shrinks the per-repo permit count from observed p99 latency. Defaults: `initial_permits=10`, `min_permits=5`, `max_permits=100`, `target_p99_ms=500`, `window_seconds=5`, `ramp_up_step=+1`, `ramp_down_factor=×0.5`, master switch `adaptive=true`. Every parameter is a DB-backed runtime setting editable from the admin Settings UI — no restart needed, and every edit is written to `audit_log` with old + new values. Migration `V132__bulkhead_settings.sql` seeds the defaults. `pantera_bulkhead_permits_current` joins the existing `_overflow_total` counter for steady-state observability.
  ([@aydasraf](https://github.com/aydasraf))
- **Production-tuned cache profile for 1000 req/s + 5 M artifacts.** A new `pantera-main/docker-compose/pantera/pantera-performance-tuned.yaml` ships alongside the default `pantera.yml`, sized for the `cache.r6g.large` Valkey + 15 vCPU / 20 GiB JVM reference deployment: 500 K `cooldown-metadata` L2, 3 M `repo-negative` L2, 5 M `artifact-index-positive` L2 (full-catalog coverage), HikariCP pool 80/20, `http_client.max_requests_queued_per_destination=4096`, and 60 s idle timeout. `pantera.yml` itself was re-shaped to the new key vocabulary (`repo-negative`, `cooldown-metadata`, `artifact-index-*`, `policy-*`, `filters`) so a fresh deploy picks up the consolidated names; the legacy `negative` / `cooldown` keys still parse for in-place upgrades.
  ([@aydasraf](https://github.com/aydasraf))
- **Per-host outbound rate limit and 429 back-off.** A token-bucket governor caps the rate at which Pantera issues upstream requests. Defaults are conservative (Maven Central 20 req/s, npm public registry 30 req/s) and configurable per host. On an upstream 429 or 503-with-`Retry-After`, Pantera holds back outbound traffic for that host until the deadline passes and propagates the same `Retry-After` to the calling client.
  ([@aydasraf](https://github.com/aydasraf))
- **Per-host circuit breaker + per-repo bulkhead.** Every upstream now has a state-machine circuit breaker in front of the Jetty client: closed → open on 5xx / non-rejection exceptions only (401 / 407 are credential failures and `429` stays the rate-limiter's responsibility — none of those trip the breaker), Fibonacci backoff with a 30 s seed and 60 min cap, daemon HEAD probe at expiry. While the breaker is open the client sees a fast-fail `502` (`X-Pantera-Fault: circuit-breaker-open`) and the broken upstream is left alone. In parallel, every `*-proxy` repo has its own bounded semaphore (defaults: 10 concurrent / 200 ceiling under adaptive control, with a 1000 queue) so a saturated repo can no longer steal concurrency budget from its neighbours; refusals return `503` with a `Retry-After` header and increment a `pantera_bulkhead_overflow_total` counter. Both surfaces are observable via Prometheus (`pantera_circuit_breaker_state`, `_trips_total`, `_fastfail_total`, `pantera_bulkhead_permits_current`).
  ([@aydasraf](https://github.com/aydasraf))
- **Single-flight coalescing on cache-miss.** Concurrent client requests for the same uncached artifact share one upstream fetch instead of firing independent calls.
  ([@aydasraf](https://github.com/aydasraf))
- **Single-flight cooldown evaluation.** Concurrent `evaluate()` calls for the same `(repoType, repoName, artifact, version)` collapse to a single downstream inspector lookup with a short TTL (30 s) on top of the existing 3-tier cooldown cache. Burst cache-miss patterns no longer fan a hundred lookups onto the publish-date registry for the same tuple.
  ([@aydasraf](https://github.com/aydasraf))
- **Stream-through cache writes.** The client receives the first byte as upstream emits it; integrity verification runs on stream completion before the cache commits. In v2.2.0 the same stream-through path is now applied to PyPI, Composer, Go and any other npm-shape adapter that has no sidecar primary/sig contract. Maven keeps its sidecar-verifying path (the digest pairing is load-bearing). Docker, files-proxy and local-only adapters retain their existing serve paths — documented as deliberate exceptions in the migration matrix.
  ([@aydasraf](https://github.com/aydasraf))
- **Conditional GET + stale-while-revalidate on Maven metadata.** `MetadataCache` now persists `ETag` / `Last-Modified` validators and emits `If-None-Match` / `If-Modified-Since` on refresh — a `304` bumps `lastVerified` without rewriting the blob. Within the soft TTL (default 30 s) hits are served straight from cache; between soft and hard TTL (default 2 h) the cached bytes serve immediately while a single-flighted background refresh runs. After hard TTL the call blocks on the upstream.
  ([@aydasraf](https://github.com/aydasraf))
- **Maven proxy fetches only `.sha1` alongside the primary** on cache-miss. `.md5`, `.sha256`, and `.sha512` are proxied on demand only.
  ([@aydasraf](https://github.com/aydasraf))
- **First-fetch cooldown HEAD against Maven Central is opt-in.** Publish-date is populated from the primary GET's `Last-Modified` header; set `PANTERA_PUBLISH_DATE_HEAD_FALLBACK_ENABLED=true` to restore the pre-2.2.0 behaviour.
  ([@aydasraf](https://github.com/aydasraf))
- **Cold-bench perf gate (CI).** A new nightly + per-PR workflow runs the cold-bench against a fixed Maven coordinate, fails when the median build exceeds 20 s or p95 exceeds 25 s, and asserts circuit-breaker invariants (zero trips, no breaker left open at end of run) on top of the existing M3-M4 amplification checks.
  ([@aydasraf](https://github.com/aydasraf))
- **Logging hot path retuned.** The logging audit collapsed log-and-rethrow chains, removed duplicate-error sites, sanitised secret-adjacent emissions and migrated the remaining `System.out`/SLF4J writers (backfill CLI included) to the structured `EcsLogger`. The net effect on the request path is fewer allocations per emission and zero stderr leakage from the SAX parser, library bootstrap, or CLI flows.
  ([@aydasraf](https://github.com/aydasraf))

### 🔧 Bug fixes

- **Upstream non-2xx responses propagate with correct status.** 429 (with `Retry-After`), 401, 403, and 503-with-`Retry-After` are no longer collapsed to 404. Transient 5xx no longer pollutes the artifact index cache, so a brief upstream outage does not produce long-lived false negatives.
  ([@aydasraf](https://github.com/aydasraf))
- **Group resolver falls through only on genuine 404s.** Transient member errors (5xx, timeouts) retry the next member instead of stopping the walk.
  ([@aydasraf](https://github.com/aydasraf))
- **Cooldown unblock invalidates the metadata cache** so re-enabled `(artifact, version)` pairs are immediately visible to clients.
  ([@aydasraf](https://github.com/aydasraf))
- **Vert.x temporary cache directories are swept at startup.** Pantera now cleans up stale per-PID `tmp-<uuid>` directories older than one hour before booting, preventing slow accumulation on long-lived hosts.
  ([@aydasraf](https://github.com/aydasraf))
- **`COMMENT IS` migration concatenation collapsed (V130).** The `audit_log` column comment string is now a single literal — Flyway's parser tripped on the previous multi-line `||` concatenation and failed the migration on fresh installs.
  ([@aydasraf](https://github.com/aydasraf))
- **Settings-layer integration test cleanup.** `SettingsLayerIntegrationTest` now `TRUNCATE`s `audit_log` between cases instead of `DELETE` — the v2.2.0 write-once triggers on `audit_log` refuse `DELETE` by design.
  ([@aydasraf](https://github.com/aydasraf))
- **Upstream mid-body body-streaming failures map to 502, not 500.** Any `IOException`-rooted failure on the body-streaming path (Jetty `Content.Source` errors, upstream connection close while we are relaying bytes) is now rendered as `502 Bad Gateway` with `Retry-After: 2`. `VertxSliceServer.ResponseTerminator.fail` walks the cause chain up to 8 hops so wrapped Reactive-Streams / CompletableFuture failures still match. The change makes Go's `cmd/go`, the Maven Resolver, and the Docker daemon apply their built-in idempotent retry instead of treating the failure as fatal. Previously these surfaced as `500 Internal Server Error`, which most package managers treat as terminal.
  ([@aydasraf](https://github.com/aydasraf))
- **Negative cache invalidated on upload across every adapter.** A late artifact upload no longer continues to return `404` from the shared `NegativeCache`. Every upload slice — Maven, npm, PyPI, Helm, Debian, RPM, Conda, Hex, Gem, NuGet, Go, files — now calls `NegativeCacheRegistry.invalidateAfterUpload(...)` after the storage commit; the invalidation broadcasts over `CacheInvalidationPubSub` so peer nodes drop their L1 entries too. Local repos invalidate as well — they were the most common silent-404-after-publish source.
  ([@aydasraf](https://github.com/aydasraf))
- **Filtered metadata cache (`cooldown-metadata`) invalidated on upload and cooldown unblock.** The same registry pattern as negative-cache: every adapter upload slice and the cooldown unblock / unblock-all paths invalidate the filtered-metadata cache through `FilteredMetadataCacheRegistry`, so post-unblock and post-upload reads see the new artifact set instead of the pre-event filtered view.
  ([@aydasraf](https://github.com/aydasraf))
- **Circuit breaker no longer trips on `401` / `407` / `429`.** The breaker exists to protect upstreams from a thundering herd against a genuinely-broken backend; `401` and `407` are credential problems (operator misconfig, not upstream health), and `429` is the rate limiter's job. Only `5xx` and non-rejection exceptions trip the breaker now; `401` / `407` surface to the caller and are recorded in metrics, but the breaker stays closed and the next request reaches the upstream unimpeded.
  ([@aydasraf](https://github.com/aydasraf))
- **Cooldown admission inspector keyed against the actual `repo_type`** in every adapter (maven, npm, PyPI, Go, Composer, files). Previously these slices built their `RegistryBackedInspector` with hardcoded literals that didn't match the suffixed `repo_type` that `DbConsumer` writes to `artifact_publish_dates` (e.g. `gradle-proxy`, `maven-proxy`), so the registry lookup missed and the gate fell through to the no-date path even when a date was on file. Each constructor now flows the slice's `repoType` parameter into the inspector.
  ([@aydasraf](https://github.com/aydasraf))
- **Filtered-metadata envelope cache wipes L2 on cooldown policy changes.** `FilteredMetadataCache.clear()` now deletes both the in-memory L1 (Caffeine) and the Valkey L2 `metadata:*` keys. A new boot-time listener on the `cooldown` settings prefix runs `CooldownSupport.loadDbCooldownSettings` to re-apply the new policy to the in-memory `CooldownSettings` snapshot (the generic `PUT /api/v1/settings/cooldown` path only persists to the DB), invokes `MetadataFilterService.clearAll()`, and broadcasts an `invalidateAll` envelope over `CacheInvalidationPubSub` whenever an admin toggles `enabled`, changes `minimum_allowed_age`, edits a repo-type / repo-name override, or updates the SNAPSHOT knob — so the next metadata fetch re-runs the filter against the new policy instead of serving up to 12 h of stale envelope bytes.
  ([@aydasraf](https://github.com/aydasraf))
- **Publish-date source map keyed by suffixed repo types.** Maven, Gradle, and Go publish-date sources are now registered under both bare and suffixed names (`maven` + `maven-proxy`, `gradle` + `gradle-proxy`, `go` + `go-proxy`). After the 2.2.0 inspector keying fix routed lookups through the actual `repoType` (suffixed), the source map's bare-only keys produced a `source_miss` on every proxy lookup and the admission gate fell through to the no-date branch — so the first-fetch upstream HEAD never ran, regardless of the head-fallback flag. First-fetch cooldown enforcement is now restored.
  ([@aydasraf](https://github.com/aydasraf))
- **Maven `head_fallback` publish-date source defaults to ON.** `PANTERA_PUBLISH_DATE_HEAD_FALLBACK_ENABLED` now defaults to `true` so first-fetch cooldown enforcement works out of the box for Maven and Gradle proxies — without the HEAD source, the first asker of a freshly published version downloads the bytes before any subsequent request sees the publish-date row. Operators with extreme cold-walk concerns can disable via the env var.
  ([@aydasraf](https://github.com/aydasraf))

### 🔒 Security

- **RS256 JWT migration completed.** Every token-validation path requires RS256; HS256 fallback paths removed.
  ([@aydasraf](https://github.com/aydasraf))
- **Authentication-policy settings are server-enforced**, not just UI-displayed. `api_token_max_ttl_seconds` and `api_token_allow_permanent` round-trip through the admin settings API and are honoured at token-mint time.
  ([@aydasraf](https://github.com/aydasraf))
- **Path-traversal guard + Authorization stripping at the proxy entry point.** A canonicalising guard sits at the top of `BaseCachedProxySlice.response()` and rejects (with `400`) raw `..`, percent-encoded `%2e%2e` / `%252e`, NUL bytes, control characters, Windows-style backslash probes, and malformed percent-encoding — no per-adapter shortcut can bypass it. The upstream-header whitelist (User-Agent + Accept only) is now pinned by an explicit test, and `LogSanitizer` masks `Authorization`, `Cookie`, `X-API-Key`, `X-Auth-Token`, and `Proxy-Authorization` in every emitted log.
  ([@aydasraf](https://github.com/aydasraf))
- **TLS 1.2+ enforcement with Mozilla "intermediate" cipher suites** on every inbound TLS listener and every outbound HTTPS call. SSLv2 / SSLv2Hello / SSLv3 / TLS 1.0 / TLS 1.1 are rejected at the handshake stage; the cipher list excludes RC4, 3DES, NULL, EXPORT, and anonymous suites. Hostname verification is explicitly enabled on the outbound Jetty client to prevent a future code change from silently disabling it.
  ([@aydasraf](https://github.com/aydasraf))
- **Hardened response headers on every Pantera HTTP response.** `SecurityHeadersSlice` wraps the outermost server slice and injects HSTS (TLS listeners only), `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, CSP and Permissions-Policy baselines. Routes that need a relaxed value (UI's `SAMEORIGIN`, per-route CSP) keep winning — the slice yields to any header the inner slice has already emitted.
  ([@aydasraf](https://github.com/aydasraf))
- **Insert-only audit log with DB-enforced immutability.** V129 hardens `audit_log` with BEFORE UPDATE / BEFORE DELETE triggers (raise `feature_not_supported`), adds `details JSONB` / `success` / `ip_address` columns, and ships the `AuditEvent` / `AuditService` abstraction. Cooldown unblock, cooldown unblock-all, repo create/update/delete and negative-cache invalidation all write through the pipeline. Entries inherit the originating request's `trace.id`. Operators can no longer rewrite history by mistake — see the admin guide for how to rotate retention by truncating partitions.
  ([@aydasraf](https://github.com/aydasraf))
- **PGP signature verifier + keyring (scoped subset).** `PgpVerifier` (Bouncy Castle LTS), `KeyringStore` interface with `JdbcKeyringStore` (V131 `pgp_keyring` table) and `InMemoryKeyringStore` (tests, no-DB boots). Five-state `Result` (`VERIFIED` / `TAMPERED` / `UNTRUSTED_KEY` / `MISSING_SIGNATURE` / `MALFORMED`) maps cleanly to HTTP + audit outcomes. The admin REST endpoint for keyring management is deferred to a follow-up.
  ([@aydasraf](https://github.com/aydasraf))
- **Logging audit closes the secret-adjacent perimeter.** Every `EcsLogger` emission now carries a `log.source` field (`audit` / `application` / `http`) so the shipper routes each line to the right Elasticsearch index; non-ECS extension fields under `auto1.*` were renamed or dropped (`audit.action` → `event.action`, `audit.actor` → `user.name`, `repository.member` → `member.name`); the bootstrap default-credential string no longer appears in the WARN body. Defensive sanitisation pinned at every credential-bearing log site (`YamlSettings`, `JwtPasswordAuth`, `Login`, `OAuthLoginSlice`, `AdminAuthHandler`). Browser-side telemetry (`authError.ts`, OAuth callback view) sends a `{ status, code }` shape instead of dumping raw `AxiosError` / IdP error payloads. Previously-swallowed exceptions are now surfaced at `WARN` or `ERROR` with `event.outcome: failure` — operators may see a brief uptick post-deploy; this is intentional.
  ([@aydasraf](https://github.com/aydasraf))

---


## Version 2.1.4 (Hotfix)

### 🔧 Bug fixes

- **Read-only users locked out of all token-TTL options except 30 / 90 days.** `AppHeader.vue`'s token-generation dialog fetched `api_token_max_ttl_seconds` and `api_token_allow_permanent` via `GET /admin/auth-settings` on mount, but that endpoint is admin-gated — non-admin users got 403, the catch block swallowed it, and the expiry dropdown silently fell back to a hardcoded `[30, 90]` list. `/api/v1/auth/me` now embeds the two public auth-settings fields in the response under an `auth_settings` object; `AppHeader.vue` reads them from the auth store via a `watch` on `auth.user?.auth_settings` — no extra network call, no admin gate. The write-path (`PUT /admin/auth-settings`) remains admin-only.
  ([@aydasraf](https://github.com/aydasraf))
- **Auth-settings toggle was cosmetic — server never enforced it.** `AuthHandler.generateTokenEndpoint` ignored `api_token_allow_permanent` entirely, so a user could `POST /api/v1/auth/token/generate` with `{"expiry_days": 0}` and mint a permanent token even with the admin toggle off. Separately, the endpoint read the legacy `max_api_token_days` key for the TTL cap while the admin UI wrote `api_token_max_ttl_seconds` — the two never met, so flipping the slider in SettingsView had no server-side effect. Both gaps closed: permanent requests now return `400 PERMANENT_TOKENS_DISABLED` when the toggle is off; the UI-managed `api_token_max_ttl_seconds` takes precedence, falling back to the legacy key only when the UI key is unset. **Existing permanent tokens are NOT retroactively invalidated** when the toggle flips — tokens are validated against `user_tokens.expires_at`, not against the current setting. To revoke already-issued permanent tokens, use `DELETE /api/v1/auth/tokens/{id}` or `POST /api/v1/admin/revoke-user/{username}`.
  ([@aydasraf](https://github.com/aydasraf))

## Version 2.1.3

### 🔧 Bug fixes

- `.yaml` (and every other non-whitelisted file extension) Maven artifacts returned 502/404 from group repositories. `ArtifactNameParser.parseMaven` gated on a hardcoded extension whitelist (`jar|pom|xml|war|aar|ear|module|sha1|sha256|sha512|md5|asc|sig`); `.yaml`, `.json`, `.zip`, `.properties`, `.tgz`, and any future type produced a mangled artifact name that missed the index, causing full proxy fanout that couldn't find the locally-uploaded artifact. Replaced with structural detection: Maven URLs always follow `{groupId}/{artifactId}/{version}/{artifactId}-{version}[-classifier].ext`, so if the final segment starts with `{artifactId}-` it's a filename. Validated against 451,673 production artifacts including non-digit versions (Spring release trains `Arabba-SR10`, git SHAs, word versions) and Scala cross-version artifactIds (`chill_2.12`).
  ([@aydasraf](https://github.com/aydasraf))
- Nested group leaf repos (e.g. `groovy-plugins-release` inside `remote-repos` inside `libs-release`) were unreachable via index hit when Pantera had no explicit repo config for the leaf. `buildLeafMap`/`collectLeaves` silently dropped unconfigured leaves, so the `leafToMember` lookup produced an unmappable name, `targeted` came back empty, and the request fell to proxy-only fanout — skipping hosted members that actually had the artifact. Replaced the static map with `GroupMemberFlattener` which enumerates leaves at construction time and lets `locateByName()` return repo names that match the flattened member list directly. No runtime nested-group recursion, no mapping table to drift.
  ([@aydasraf](https://github.com/aydasraf))
- Circuit breaker at the group level manufactured false 5xx responses. When the index returned `groovy-plugins-release` for an artifact but that member's circuit was OPEN, the resolver skipped the member — even though the bytes were local — and returned 503 to the client. 7,733 such circuit-open/503 entries were observed in 30 minutes of production logs. Circuit breaker now only runs on the fanout path (protects upstreams from thundering herd); the targeted local read path always queries the member the index points to.
  ([@aydasraf](https://github.com/aydasraf))
- `DbArtifactIndex.locateByName` returned `List.of()` for both "row not found" and "DB error", so a transient database outage made every group request fall to proxy-only fanout and return 404 for artifacts that exist in hosted members. Return type changed to `CompletableFuture<Optional<List<String>>>` — `Optional.empty()` on `SQLException` triggers full two-phase fanout as a safety net, `Optional.of(List.of())` is the confirmed-miss case that still goes proxy-only.
  ([@aydasraf](https://github.com/aydasraf))
- `locateByName` SQL had no statement timeout. Under DB pressure or missing-index pathology the query could hang indefinitely, starving the index connection pool at 250+ req/s. Added `SET LOCAL statement_timeout = '500ms'` (configurable via `PANTERA_INDEX_LOCATE_TIMEOUT_MS`) using the same transaction-guard pattern as `searchWithLike`. Timeout surfaces as `SQLException` which already maps to `Optional.empty()` → full fanout safety net.
  ([@aydasraf](https://github.com/aydasraf))
- 3,345 "Internal server error" log entries per 30 minutes had zero stack traces, no `user.name`, no `client.ip`, no `trace.id` — admins saw a generic error message with no way to diagnose or attribute it. All error-path logging in `GroupSlice` now uses `EcsLogger.error(...).error(throwable)` to capture `error.type`/`error.message`/`error.stack_trace`, and MDC fields (user/IP/trace) propagate across async `thenCompose`/`whenComplete` callbacks via new `MdcPropagation` wrappers (CompletableFuture callbacks previously ran on pool threads with empty MDC).
  ([@aydasraf](https://github.com/aydasraf))
- Internal group-to-member fanout queries emitted 105,796 access log entries per 30 minutes — 26% of all log volume, indistinguishable from real client requests but with no `user.name`/`client.ip`/`trace.id`. `GroupSlice` now adds an `X-Pantera-Internal: true` marker header when dispatching to members; `EcsLoggingSlice` checks the header and skips access log emission (internal routing is still captured as DEBUG application logs in `GroupSlice`). The marker does not leak to upstream — all proxy slices pass `Headers.EMPTY` to the upstream HTTP client.
  ([@aydasraf](https://github.com/aydasraf))
- `event.duration` had inconsistent units — some code paths wrote nanoseconds, others wrote microseconds, others milliseconds. Both `EcsLogger.duration(long ms)` and `EcsLogEvent.duration(long ms)` removed their `* 1_000_000` conversion; every log entry now emits `event.duration` in milliseconds (Pantera convention). See logging admin guide §event.duration for the Kibana query migration (`> 5000000000` → `> 5000`).
  ([@aydasraf](https://github.com/aydasraf))
- `event.category` values used throughout the codebase (`repository`, `group`, `cache`, `cooldown`, `pypi`, `storage`, `scheduling`, etc.) were not in the ECS allowed-values list, causing dashboards filtering on ECS categories to return empty. 488 call sites across 121 files migrated: repository/http/server/docker/group/pypi/npm/maven → `web`, cache/cooldown/search/index → `database`, storage → `file`, scheduling/metrics → `process`, cluster/system → `host`, user/admin → `iam`, security → `authentication`, webhook → `network`, factory → `configuration`. See the migration table in the logging admin guide.
  ([@aydasraf](https://github.com/aydasraf))
- `DRAIN_EXECUTOR` queue overflow (4 threads, 200-entry bounded queue) logged dropped tasks at DEBUG level — silent in production where DEBUG is disabled. Each dropped drain is a potential member-response body leak. Now logged at WARN with a `DRAIN_DROP_COUNT` atomic counter exposed via `drainDropCount()` for metrics integration.
  ([@aydasraf](https://github.com/aydasraf))

### ⚡ Performance

- **Regex patterns hoisted to static finals in `ArtifactNameParser`.** Composer, Helm, Hex, Gem, PyPI filename parsers and `normalizeType` were calling `Pattern.compile(...)` / `String.replaceAll(regex, ...)` inside method bodies — on every `GroupSlice.locateByName` lookup. At 1000 req/s across mixed repo types this was roughly 500–600 ms CPU/s per core (~6–7 % CPU tax). All 6 sites replaced with `private static final Pattern` constants (three callers share a single `^(.+)-\d` pattern); behaviour verified by the full 155-case `ArtifactNameParserTest` suite.
  ([@aydasraf](https://github.com/aydasraf))
- **Jackson `ObjectMapper` / `JsonFactory` singletons in the Conda adapter.** `MergedJson.Jackson`, `JsonMaid.Jackson`, `MultiRepodata.Unique`, `CondaRepodata.Remove`/`Append`, and `AstoMergedJson` constructed `new ObjectMapper()` / `new JsonFactory()` inside request-handling loops (8 call sites). Mapper construction costs ~1–10 ms plus transient allocation for module loading and type factory setup — non-trivial on repodata paths. Replaced with `static final` singletons (Jackson documents mappers as thread-safe once configured); `JsonFactory` exposed as a constant on the `CondaRepodata` interface and reused across `MultiRepodata` and `AstoMergedJson`.
  ([@aydasraf](https://github.com/aydasraf))
- **PyPI cache-hit artifact path streams directly — no `readAllBytes()`.** `ProxySlice.afterHit` materialised every cached artifact into a `byte[]` via `stream.readAllBytes()` before wrapping it in `Content.From(data)` for the response — a 700 MB `torch` wheel hit the heap per request on cache-serve. The dead `remoteSuccess=true` save-to-storage branch (all callers pass `false`) and the `ContentAndCoords` helper are removed; the streaming `Content` is passed to `ResponseBuilder.body(...)` directly, with `Content-Length` taken from `content.size()`. Validated by the 19-case `ProxySliceTest` plus the 124-case full pypi-adapter suite. The remote-fetch leg is unchanged; it already persists inside `cache.load(...)`.
  ([@aydasraf](https://github.com/aydasraf))
- **Pre-warmed shared Jetty clients at startup.** `SharedClient.client()` called `startFuture.join()` to wait for async Jetty initialisation (SSL context, socket setup; ~100–500 ms). When the first request per upstream arrived on a Vert.x event-loop thread, the join blocked that loop for the full init — starving thousands of other requests sharing it. Added `SharedJettyClients.awaitAllStarted(Duration)` and `RepositorySlices.warmUp(Duration)`; `VertxMain` calls `slices.warmUp(Duration.ofSeconds(30))` after config load and before the first `listenOn`, so every configured repo's SharedClient is fully started before traffic is accepted. Runtime repo additions via the UPSERT event still take the lazy path; a follow-up will route that through `executeBlocking`.
  ([@aydasraf](https://github.com/aydasraf))

### 📊 Observability (log-audit hardening)

Driven by a structured audit of the production container log stream (~33K entries across `http.access`, `artifact.audit`, and application loggers). Target: no log dropped by Elasticsearch, every HTTP request correlatable end-to-end, every audit entry queryable in Kibana.

- **`event.category` normalised to ECS array on every emitter.** The codebase had two emission shapes — typed `.eventCategory(value)` (array `["web"]`) and raw `.field("event.category", value)` (string `"web"`). In the sampled logs, 8,719 of 32,865 entries (26 %) used the string shape — all from `AuditLogger`, `SpanContext.SRE2042`, and the duplicate `event.outcome` override in `OperationControl`. Elasticsearch's dynamic mapping binds the field to whichever type indexes first; the minority type then fails with `mapper_parsing_exception` and is dropped. All remaining raw-string sites switched to typed `.eventCategory(...)`. `OperationControl` also had a duplicate `.field("event.outcome", "allowed"/"denied")` overwriting `.eventOutcome(success/failure)` with non-ECS values — the allowed/denied detail is preserved as `event.reason`.
  ([@aydasraf](https://github.com/aydasraf))
- **`event.action` emitted on every `http.access` entry.** All 535 sampled access-log entries had `event.action: null` because `EcsLogEvent`'s constructor set `event.category` and `event.type` but not action. Default `event.action: "http_request"` now set in the constructor, with an overridable `action(String)` builder for specific cases (health probes, admin endpoints). Makes Kibana saved queries like `event.action: "group_lookup_miss"` usable against access logs.
  ([@aydasraf](https://github.com/aydasraf))
- **MDC propagation across `cooldown.metadata` + `npm` adapter async boundaries.** `CooldownMetadataServiceImpl.computeFilteredMetadata` and `DownloadAssetSlice.checkCacheFirst` crossed async boundaries (`CompletableFuture.supplyAsync`, RxJava `Maybe.map`) without restoring MDC. Result in sampled logs: `com.auto1.pantera.cooldown.metadata` had 0 % `trace.id` coverage on 1,459 entries; `com.auto1.pantera.npm` had 30 % on its 4,579 `cache_hit` entries. Added `MdcPropagation.withMdcSupplier(Supplier)` and `MdcPropagation.withMdcRxFunction(io.reactivex.functions.Function)` wrappers — both capture the caller's MDC and reinstall it around the callback on the worker thread. Applied at 3 continuations in `CooldownMetadataServiceImpl` and 2 in `DownloadAssetSlice`.
  ([@aydasraf](https://github.com/aydasraf))
- **SAX parser no longer leaks `[Fatal Error]` to stderr.** `UploadSlice.fixMetadataBytes` parses `maven-metadata.xml` via `jcabi-xml`'s `XMLDocument` which used the default SAX handler — that handler prints `[Fatal Error] :1:1: Content is not allowed in prolog.` to stderr before the caller's `IllegalArgumentException` fallback catches and logs the structured WARN. Two such lines were observed in sampled container stderr. Replaced with a helper that parses via `DocumentBuilder` with a silent `ErrorHandler`, then wraps in `XMLDocument`. DOCTYPE + external-entity expansion disabled as a defensive measure (XXE / billion-laughs). Catch widened to cover `SAXException` / `IOException` / `ParserConfigurationException`.
  ([@aydasraf](https://github.com/aydasraf))
- **`artifact_resolution` audit events now carry `package.name`.** `AuditLogger.resolution()` took no arguments and relied on MDC for every field; the 3 call sites in `pypi/SliceIndex` fire during RxJava render pipelines where MDC is detached from the request scope, so 6 of 8,719 sampled audit entries had null `package.name`/`repository.name`/`user.name`. Signature changed to `resolution(String packageName)`; method short-circuits when the name is empty (repo-level index queries are not audited). Call sites pass the already-in-scope `packageName` variable.
  ([@aydasraf](https://github.com/aydasraf))
- **Audit log entries now inherit the originating HTTP request's `trace.id`.** Audit events are emitted by `DbConsumer` on a background scheduler thread with a fresh MDC, so in sampled logs the 575 distinct `trace.id`s had ZERO overlap between `http.access` (535 entries) and `artifact.audit` (93 trace-carrying entries) — joining an artifact upload to its HTTP session in Kibana was impossible. Added `ArtifactEvent.traceId()` that auto-captures `MDC.get(TRACE_ID)` at construction time (zero change across the ~40 `new ArtifactEvent(...)` call sites in adapters). `DbConsumer.logArtifactPublish` restores the captured trace.id into MDC around the `AuditLogger.publish` call, with `try/finally` to leave no residue on the pooled DB-consumer thread.
  ([@aydasraf](https://github.com/aydasraf))
- **`package.checksum` (SHA-256) populated on Maven publish audits.** Previously 0 of 8,719 audit entries had `package.checksum`. `UploadSlice.generateChecksums` now returns the SHA-256 hex instead of `Void` while still writing all 4 sidecar files. `ArtifactEvent.withChecksum(String)` produces an immutable copy with the digest attached, and `AuditLogger.publish` emits the hex as `package.checksum` when non-null. Other adapters remain on the existing code path (checksum null for their publishes); extending each is a follow-up.
  ([@aydasraf](https://github.com/aydasraf))

### 🌟 New features

- **Stale-while-revalidate for proxy artifact binaries.** Enabled by default. When a proxy member's upstream fails (timeout, 5xx, connection refused) and the cached bytes are within `staleMaxAge` (default 1 hour), the proxy serves the cached artifact with `200 OK` + `X-Pantera-Stale: true` + `Age: <seconds>` (RFC 7234) headers. Age is tracked via a new `savedAt` timestamp in the metadata sidecar JSON — backwards-compatible with pre-2.1.3 sidecars (missing `savedAt` is treated as fresh on first read). Operators disable per-repo via `cache.stale_while_revalidate.enabled: false` in YAML.
  ([@aydasraf](https://github.com/aydasraf))
- **Negative cache for proxy fanout (renamed in WI-06).** Prevents thundering-herd: when all proxy members return 404 for a missing artifact, the `(scope, repoType, artifactName, artifactVersion)` tuple is cached for a short TTL (default 5 minutes) so subsequent requests return 404 instantly without a second fanout. Two-tier L1 Caffeine + L2 Valkey via the existing `NegativeCacheConfig` pattern; configurable per-deployment under `meta.caches.repo-negative` in `pantera.yml` (covers hosted/proxy/group after the WI-06 consolidation). The earlier name `meta.caches.group-negative` is still accepted with a deprecation WARN at boot — admins should rename to `repo-negative` to silence the warning; the legacy key will be removed in a future release. Falls back to in-memory L1 only (matching historical behaviour) when neither config key is present.
  ([@aydasraf](https://github.com/aydasraf))
- **Concurrent request coalescing.** When N requests arrive simultaneously for the same missing artifact, only one fanout runs — the N-1 followers wait on an `inFlightFanouts` gate and, on leader completion, re-enter `proxyOnlyFanout` to hit the freshly-populated negative cache (instant 404) or the cached proxy response. Combined with the negative cache, fully eliminates the thundering herd for missing artifacts.
  ([@aydasraf](https://github.com/aydasraf))
- **`staleMaxAge` enforcement.** `BaseCachedProxySlice.tryServeStale` now computes `age = now - savedAt` from the metadata sidecar and refuses to serve stale bytes older than `ProxyCacheConfig.staleMaxAge()`. Previously the feature was partial — age was not checked, so arbitrarily old cached bytes could be served when upstream was down. Legacy sidecar files without `savedAt` get `Instant.now()` as a fallback (treated as fresh on first read), enabling rolling upgrades without data migration.
  ([@aydasraf](https://github.com/aydasraf))
- **Token expiry dropdown in the avatar menu popup.** The "Generate API Token" dialog in `AppHeader.vue` previously used a numeric input field (0-365 days, 0 = permanent). Replaced with a `<Select>` dropdown matching `ProfileView.vue`'s pattern: 30 / 90 / 180 / 365 days / Permanent. Respects admin settings: `api_token_max_ttl_seconds` gates the numeric options, `api_token_allow_permanent` gates the Permanent option.
  ([@aydasraf](https://github.com/aydasraf))

### 🏗️ Architectural changes

- **Group resolution redesigned around a 5-path decision tree.** Previous code had tight coupling between parser accuracy, the `leafToMember` map, and the circuit breaker — any single failure produced false 5xx. New flow:
  1. **Unparseable URL** (metadata endpoints, root paths) → full two-phase fanout (hosted first, then proxy cascade).
  2. **Index DB error** → full two-phase fanout (safety net; we don't know what's in the index).
  3. **Index confirmed miss** → proxy-only fanout (hosted repos are fully indexed, so absence from index = absence from hosted). Checks negative cache first.
  4. **Index hit** → targeted local read against the member(s) the index returned. No circuit breaker (the bytes are local; skipping a known-good location creates false 5xx). No fallback fanout on 5xx (no other member has the bytes).
  5. **Index-hit orphan** (index returned a repo name not in the flattened member list) → full fanout as safety net.

  HTTP status codes: `500` for local read failure (targeted path), `502` for upstream gateway failure (fanout path), `404` for confirmed not-found. **No `503` from group resolution.** Circuit-breaker state is logged as structured application logs, never returned as HTTP status.
  ([@aydasraf](https://github.com/aydasraf))
- **Token generation UI consolidated.** Removed the generation form from the Profile page — token creation is now exclusively available via the avatar-menu popup. The Profile page retains the Active Tokens list (view / revoke existing tokens).
  ([@aydasraf](https://github.com/aydasraf))

### 🧹 Cleanup

- Deleted `leafToMember` map and its `buildLeafMap`/`collectLeaves` helpers in `RepositorySlices` — replaced by construction-time `GroupMemberFlattener.flatten()` which enumerates leaves once with cycle detection.
- Deleted `MAVEN_FILE_EXT` regex whitelist in `ArtifactNameParser` — replaced by structural filename-prefix detection.
- Deleted `RequestContext.addTo()` — a pass-through no-op retained after an earlier MDC cleanup. All 10 call sites simplified from `ctx.addTo(EcsLogger.warn(...)).log()` to direct `EcsLogger.warn(...).log()`. `RequestContext` trimmed from 4 fields to 1 (`packageName`).
- Deleted the legacy `queryAllMembersInParallel` and `queryMember` helpers — dead after the 5-path rewrite.
- Renamed `pantera-core/.../com.auto1.pantera.http.group.GroupSlice` to `RaceSlice` to resolve the naming clash with `pantera-main/.../com.auto1.pantera.group.GroupSlice`. The two classes served different purposes (low-level first-response-wins utility vs. hot-path group resolver); the rename makes intent explicit. 9 files updated (6 proxy adapters + 1 IT case + the class + its test).

### 📚 Documentation

- Logging admin guide (`docs/admin-guide/logging.md`) updated with the new ECS-compliant `event.category` value set, a migration table mapping old Pantera categories to ECS allowed values, and a note that `event.duration` is now in milliseconds (with a Kibana query conversion example — `> 1000000000` for 1s becomes `> 1000`).
- Group resolution redesign spec (`docs/superpowers/specs/2026-04-14-group-resolution-redesign.md`) documents the 5-path flow, the circuit breaker strategy, the negative cache configuration, the stale-while-revalidate design, the ECS category migration, and the deferred items (non-Maven metadata caching, cross-type repo verification).

### ✅ Testing

- `ArtifactNameParserMavenStructuralTest` (47 parameterised tests) covers `.yaml`, `.json`, `.zip`, Spring release trains, git SHAs, Scala cross-version artifactIds, classifiers, checksums, metadata endpoints, and short paths.
- `GroupSliceFlattenedResolutionTest` covers all 5 resolution paths plus the critical "index hit + member 5xx → 500 (no fanout)" invariant, concurrent-miss coalescing, and the X-Pantera-Internal marker propagation.
- `BaseCachedProxySliceStaleTest` covers stale-serve on upstream timeout/5xx, stale refusal beyond `staleMaxAge`, absent-metadata existence fallback, and SWR-disabled propagation.
- `NegativeCacheConfigTest` covers the new `fromYaml(caches, subKey)` overload for per-group config sections.

### ⚠️ Breaking changes

- `event.duration` is now emitted in **milliseconds**, not nanoseconds. Kibana saved queries comparing to nanosecond thresholds (e.g. `event.duration > 5000000000` for 5s) must be updated to the ms equivalent (`> 5000`). See logging admin guide for the migration table.
- `event.category` values migrated to the ECS allowed-values set. Dashboards and alerts filtering on old Pantera-specific categories (`repository`, `group`, `cache`, `cooldown`, `pypi`, etc.) must be updated. Mapping table in logging admin guide.
- `ArtifactIndex.locateByName` signature changed from `CompletableFuture<List<String>>` to `CompletableFuture<Optional<List<String>>>`. External implementers of `ArtifactIndex` must adopt the new return type (`Optional.of(repos)` on success, `Optional.empty()` on DB error).
- No `503` responses from group resolution. Clients that retried on `503` will now see `404` (miss), `500` (local error), or `502` (gateway error). Maven/Gradle build tooling is unaffected — they already retry on 5xx.

---

## Version 2.1.2

### 🔒 Security

- `jwt-password` and `local` auth providers were silently disabled on every request on deployments that never had their rows in the `auth_providers` table. The v2.1.0 changelog promised they are "mandatory and cannot be removed" but no Flyway migration seeded them — so `DbGatedAuth` saw the row absent, returned `false` from `isEnabled()`, and every UI-generated API token used in Basic auth failed verification *before* reaching the RS256 validator. Symptom: `/pypi` and every other main-port repo request returned 401 with a single `Failed to authenticate user` WARN showing `CachedUsers(size=0)` — no indication that `jwt-password` even existed. Fixed with V118 `seed_mandatory_auth_providers.sql` using `ON CONFLICT DO NOTHING` so existing deployments auto-heal on restart without clobbering operator choices.
  ([@aydasraf](https://github.com/aydasraf))
- Conan adapter's `ItemTokenizer` signed and verified per-item tokens with a hardcoded HMAC secret (`"some secret"`, committed to source since the Artipie fork). Anyone with repo access could forge Conan upload/download URL tokens. Migrated to RS256 using the same cluster-wide key pair as the main auth flow — keys are threaded through `RepositorySlices` from the `JwtTokens` instance, so HA nodes that share the pair continue to verify each other's tokens without any additional config.
  ([@aydasraf](https://github.com/aydasraf))
- `jwt-password` auth provider silently validated tokens against a hardcoded fallback HMAC secret. The v2.1.0 switch to RS256 asymmetric signing removed `meta.jwt.secret`, but `JwtPasswordAuthFactory` kept the old HS256 code path — when `secret` came back `null` it fell back to the literal string `"jwt-password-fallback-secret"` and only emitted a WARN. UI-generated API tokens (signed with the real RSA private key) never verified against that fallback, so every Basic-auth attempt using a UI-generated token failed, and the provider's security model was effectively a shared, publicly-known HMAC key. Factory now loads `meta.jwt.public-key-path` and builds an RS256 `JWTAuth` — same key pair as `JwtTokens`, so API tokens the user generates via the UI authenticate correctly. Missing `public-key-path` now fails fast at startup with an actionable error instead of deferring to a broken fallback.
  ([@aydasraf](https://github.com/aydasraf))
- Profile → **Active Tokens** UI leaked the user's refresh-token JTI. Every login / SSO callback / refresh cycle wrote a row to `user_tokens` with `token_type = 'refresh'` and `label = "Refresh Token"`, but `UserTokenDao.listByUser` had no `token_type` filter — so the list returned every type and the UI rendered a revocable "Refresh Token" entry alongside the user's real API tokens. A user could click the trash icon and kill their own session; worse, anyone with access to the DB-facing audit trail could infer refresh JTIs from the response. Filter is now `token_type = 'api'`. The self-service `DELETE /api/v1/auth/tokens/:id` endpoint is hardened with the same scope so the UUID cannot be used to revoke a refresh token even if guessed — refresh revocation remains available via logout and the admin revoke-user path.
  ([@aydasraf](https://github.com/aydasraf))

### 🔧 Bug fixes

- `JwtPasswordAuthFactory` double-nested `cfg.yamlMapping("meta")` but `initAuth()` already passes the `meta` mapping as `cfg`. The factory looked for `meta.meta.jwt` — which doesn't exist — got `null`, and threw `"public-key-path is not configured"` at startup. The catch in `initAuth` swallowed it as a WARN, so `jwt-password` was silently never added to the auth chain and every API-token-as-password request returned 401. Fix: `JwtSettings.fromYaml(cfg)` (no extra nesting).
  ([@aydasraf](https://github.com/aydasraf))
- Version-repair CLI (`--mode version-repair`) crashed on `artifacts_repo_name_name_version_key` unique constraint when the same artifact name had both a `version='UNKNOWN'` row and an already-correct versioned row. The batch UPDATE now includes a `NOT EXISTS` guard that skips conflicting rows instead of aborting the entire batch.
  ([@aydasraf](https://github.com/aydasraf))
- `JwtPasswordAuth` catch-all swallowed every JWT verification failure (wrong signature, expired, key mismatch) with no log. Added DEBUG-level logging with the exception message so operators can diagnose failures via `-Dlog4j.logger.com.auto1.pantera.auth=DEBUG`.
  ([@aydasraf](https://github.com/aydasraf))
- API listener fails ALB health checks when `meta.http_server.proxy_protocol: "true"` is enabled. ALB does not emit PROXYv2 (it terminates L7 and adds `X-Forwarded-For` instead), so plain `GET /` health-probe bytes were being misparsed by Pantera's PROXY decoder and the connection closed with `HAProxyProtocolException`. The target group then marked the API port unhealthy with no useful Pantera log entry. Fixed by introducing a per-listener PROXYv2 toggle for the API port — see `meta.http_server.api_proxy_protocol` below.
  ([@aydasraf](https://github.com/aydasraf))

### 🌟 New features

- New `meta.http_server.api_proxy_protocol` flag controls PROXYv2 on the API listener (typically port 8086) independently from the main + per-repo listeners. Defaults to the value of `meta.http_server.proxy_protocol` for backward compatibility — pre-2.1.2 deployments that set a single `proxy_protocol: true` keep their existing behaviour. Operators with a mixed topology (NLB → main port + ALB → API port) set `api_proxy_protocol: "false"` to keep PROXYv2 on for the NLB-fronted listeners while disabling it on the ALB-fronted API port.
  ([@aydasraf](https://github.com/aydasraf))

### 🧹 Cleanup

- Removed `JwtPasswordAuth.fromSecret(Vertx, String)` — the pre-2.1.0 HS256 entry point. Production no longer calls it (the factory now builds an RS256 `JWTAuth` directly), and the pre-2.1.2 test that exercised it was masking the broken-factory regression. `JwtPasswordAuthTest` rewritten against the committed RSA key-pair fixtures so a future sign/verify mismatch cannot hide.
  ([@aydasraf](https://github.com/aydasraf))
- Removed the HS256 `JWTAuthHandler` fallback in `AsyncApiVerticle` (`unifiedAuth == null` branch). Dead in production since 2.1.0, but a latent trap — a misconfigured deploy without RS256 keys now fails fast with an actionable error instead of silently routing every request through an unconfigured HMAC validator.
  ([@aydasraf](https://github.com/aydasraf))
- Swept docs/operator configs still referencing the removed `meta.jwt.secret` / `JWT_SECRET`: `README.md`, `docs/ha-deployment/pantera-ha.yml`, `docs/ha-deployment/docker-compose-ha.yml`, `docs/admin-guide/installation.md`, `docs/admin-guide/upgrade-procedures.md`, `docs/admin-guide/troubleshooting.md`. All now show `private-key-path` / `public-key-path` (and the matching `JWT_PRIVATE_KEY_PATH` / `JWT_PUBLIC_KEY_PATH` env vars). A fresh 2.1.2 deploy following any of these docs no longer fails at startup.
  ([@aydasraf](https://github.com/aydasraf))
- Stale Javadoc on `JwtPasswordAuth` and `JwtPasswordAuthFactory` updated from HS256 / `meta.jwt.secret` wording to the RS256 key-path configuration.
  ([@aydasraf](https://github.com/aydasraf))

### 📚 Documentation

- Configuration reference §1.8 expanded with the new `api_proxy_protocol` key and a topology note explaining why ALB and PROXYv2 are mutually exclusive.
  ([@aydasraf](https://github.com/aydasraf))
- Admin-guide configuration page gained a "Mixed NLB + ALB topology" section walking operators through the symptom (ALB target group reports unhealthy with no Pantera log) and the fix.
  ([@aydasraf](https://github.com/aydasraf))

---

## Version 2.1.1

### 🔧 Bug fixes

- Startup fails with `algid parse error, not a sequence` when the JWT private key is PEM-encoded as PKCS#1 (`-----BEGIN RSA PRIVATE KEY-----`). `RsaKeyLoader` now detects the format from the PEM header and wraps PKCS#1 in a PKCS#8 envelope in-memory; PKCS#8 keys continue to load unchanged. Supports 2048- and 4096-bit RSA. The misleading `openssl genrsa` hint in the missing-key error message has been replaced with the PKCS#8-producing `openssl genpkey` form.
  ([@aydasraf](https://github.com/aydasraf))
- `proxy_protocol: true` silently downgraded to plain HTTP because `netty-codec-haproxy` was not on the classpath. Vert.x logged `Proxy protocol support could not be enabled` at startup and then served NLB-wrapped traffic as malformed HTTP, breaking every connection behind a PROXY-v2 load balancer. Added `io.netty:netty-codec-haproxy` to `pantera-main` (version aligned with the `vertx-dependencies` BOM, currently 4.1.132.Final).
  ([@aydasraf](https://github.com/aydasraf))
- Elastic ingest pipeline rejects logs with `Duplicate field 'service.version'`. The `EcsLayout` serializer already emits `service.version`, `process.thread.name`, and the other service metadata fields; three call sites were adding them again via `.field()` and producing duplicate JSON keys. Removed the redundant emits at startup log, scheduler queue log, and blocked-thread diagnostics; the blocked-thread diagnostic now reports the target thread name in the message and under `pantera.blocked_thread.name`.
  ([@aydasraf](https://github.com/aydasraf))

### 📚 Documentation

- Configuration reference now covers scheduled scripts (`meta.crontab`), experimental HTTP/3 support, and repository filter blocks — previously only documented under the admin guide.
  ([@aydasraf](https://github.com/aydasraf))
- Admin-guide configuration page collapsed to a slim overview that defers to the reference for full key lists, eliminating duplicated YAML samples.
  ([@aydasraf](https://github.com/aydasraf))
- Design/planning documents removed from `docs/plans/`.
  ([@aydasraf](https://github.com/aydasraf))

### ✅ Testing

- `RsaKeyLoaderTest` rewritten with committed PKCS#1/PKCS#8 fixture pairs at 2048 and 4096 bits; asserts both formats yield identical key material and that the DER long-form length path is exercised for 4096-bit keys.
  ([@aydasraf](https://github.com/aydasraf))
- `ProxyProtocolV2Test` added: stands up a Vert.x HTTP server with `setUseProxyProtocol(true)`, writes a Netty-encoded PROXYv2 header over a raw socket (TCP4 + TCP6), and asserts the handler sees the client IP from the header rather than the loopback address. Double-guards the classpath — if `netty-codec-haproxy` is ever dropped, the test class itself won't load.
  ([@aydasraf](https://github.com/aydasraf))

---

## Version 2.1.0

### ⚠️ Breaking changes

- All previously issued tokens are invalidated due to signing scheme change
  ([@aydasraf](https://github.com/aydasraf))
- `meta.jwt.secret` replaced by `meta.jwt.private-key-path` + `meta.jwt.public-key-path`
  ([@aydasraf](https://github.com/aydasraf))
- Login and callback endpoints return `{ token, refresh_token, expires_in }`
  ([@aydasraf](https://github.com/aydasraf))
- Fresh installs bootstrap a default admin account requiring password change on first sign-in
  ([@aydasraf](https://github.com/aydasraf))
- `local` and `jwt-password` auth providers are mandatory and cannot be removed
  ([@aydasraf](https://github.com/aydasraf))
- UI dependencies pinned to exact versions — developers must use `npm ci`
  ([@aydasraf](https://github.com/aydasraf))

### 🌟 New features

- RS256 asymmetric JWT signing replaces the previous shared-secret scheme
  ([@aydasraf](https://github.com/aydasraf))
- Access + refresh + API token architecture with configurable lifetimes
  ([@aydasraf](https://github.com/aydasraf))
- Multi-node token revocation via blocklist with cluster-wide propagation
  ([@aydasraf](https://github.com/aydasraf))
- JTI ownership validation and token-type scope enforcement
  ([@aydasraf](https://github.com/aydasraf))
- Admin UI for auth settings and per-user token revocation
  ([@aydasraf](https://github.com/aydasraf))
- Schema-driven provider configuration UI for Okta and Keycloak
  ([@aydasraf](https://github.com/aydasraf))
- Provider lifecycle (create, enable, disable, delete) takes effect at runtime without restart
  ([@aydasraf](https://github.com/aydasraf))
- Priority-driven provider ordering with deterministic chain evaluation
  ([@aydasraf](https://github.com/aydasraf))
- Group-to-role mapping for SSO providers, independent from access-control gate
  ([@aydasraf](https://github.com/aydasraf))
- Default admin account bootstrapped on fresh installs with mandatory password change
  ([@aydasraf](https://github.com/aydasraf))
- Unified password complexity policy (server-side + client-side), minimum 12 characters
  ([@aydasraf](https://github.com/aydasraf))
- Self-service password change from user profile for local accounts
  ([@aydasraf](https://github.com/aydasraf))
- Admin password reset without requiring the target user's current password
  ([@aydasraf](https://github.com/aydasraf))
- Per-request user-enabled check in JWT filter — disabled users lose all access immediately
  ([@aydasraf](https://github.com/aydasraf))
- Structured search query syntax — `name:`, `version:`, `repo:`, `type:`, AND/OR, parentheses
  ([@aydasraf](https://github.com/aydasraf))
- Server-side search, sort, and pagination for users and roles
  ([@aydasraf](https://github.com/aydasraf))
- Quick Setup page for first-time configuration
  ([@turanmahmudov-auto1](https://github.com/turanmahmudov-auto1))
- Registry URL editable from admin settings (DB-persisted, used by Quick Setup)
  ([@aydasraf](https://github.com/aydasraf))
- Sort artifacts by name in repository browser
  ([@turanmahmudov-auto1](https://github.com/turanmahmudov-auto1))
- Filter and sort on backend for artifact listings
  ([@turanmahmudov-auto1](https://github.com/turanmahmudov-auto1))
- `Dockerfile.dev`, `docker-compose.dev.yaml`, `Makefile`, `.env.dev` for local development
  ([@turanmahmudov-auto1](https://github.com/turanmahmudov-auto1))
- PEP 691 JSON Simple API with PEP 700 upload-time metadata
  ([@aydasraf](https://github.com/aydasraf))
- PEP 503 full data attributes on hosted-repo HTML indexes
  ([@aydasraf](https://github.com/aydasraf))
- Dual-format index persistence — HTML and JSON written side-by-side on upload
  ([@aydasraf](https://github.com/aydasraf))
- Self-healing JSON cache for legacy packages without JSON index
  ([@aydasraf](https://github.com/aydasraf))
- Self-healing sidecar metadata from storage file timestamps for pre-upgrade artifacts
  ([@aydasraf](https://github.com/aydasraf))
- Yank/unyank API endpoints (PEP 592) and UI controls in artifact detail dialog
  ([@aydasraf](https://github.com/aydasraf))
- One-time metadata backfill CLI for existing packages
  ([@aydasraf](https://github.com/aydasraf))
- Version inference from dotted artifact names for file/file-proxy repos
  ([@aydasraf](https://github.com/aydasraf))
- Version repair CLI (`--mode version-repair`) for bulk-fixing UNKNOWN versions
  ([@aydasraf](https://github.com/aydasraf))
- Stored `version_sort bigint[]` generated column for natural ordering
  ([@aydasraf](https://github.com/aydasraf))
- Distributed tracing with B3 (openzipkin) and W3C Trace Context support
  ([@aydasraf](https://github.com/aydasraf))
- trace.id, span.id, span.parent.id in all log entries per SRE convention
  ([@aydasraf](https://github.com/aydasraf))
- SRE2042 validation — malformed/all-zero trace/span IDs regenerated with W3C version byte check
  ([@aydasraf](https://github.com/aydasraf))
- traceparent response header on all HTTP responses (both public and API ports)
  ([@aydasraf](https://github.com/aydasraf))
- B3 + W3C header injection into all upstream calls (all proxy adapters via JettyClientSlice, SSO, Okta)
  ([@aydasraf](https://github.com/aydasraf))
- MDC propagation across all 46 `executeBlocking` worker-thread call sites via `MdcPropagation`
  ([@aydasraf](https://github.com/aydasraf))
- Trace context middleware on API port (AsyncApiVerticle) — MDC for trace.id, span.id, client.ip
  ([@aydasraf](https://github.com/aydasraf))
- Artifact audit logging at INFO level — upload, download, delete, resolution events
  ([@aydasraf](https://github.com/aydasraf))
- Dedicated `artifact.audit` logger with ECS-structured fields
  ([@aydasraf](https://github.com/aydasraf))
- Proxy Protocol v2 support for AWS NLB on all ports (main, API, per-repo)
  ([@aydasraf](https://github.com/aydasraf))
- Hosted-first cascade — index-targeted queries try hosted members before proxies
  ([@aydasraf](https://github.com/aydasraf))
- Flyway V100–V117 — all auth, provider, user-lifecycle, cooldown, and sequence repair schema
  ([@aydasraf](https://github.com/aydasraf))
- pg_cron job definitions for materialized view refresh
  ([@aydasraf](https://github.com/aydasraf))

### 🔧 Bug fixes

- Credential cache invalidation is now cluster-wide (L1 + L2) on every password change
  ([@aydasraf](https://github.com/aydasraf))
- Authentication chain respects provider authority for local users
  ([@aydasraf](https://github.com/aydasraf))
- SSO-provisioned accounts remain eligible for SSO sign-in
  ([@aydasraf](https://github.com/aydasraf))
- Persistent inline error messaging on sign-in and SSO callback views
  ([@aydasraf](https://github.com/aydasraf))
- Generic, non-disclosing error messages across all sign-in failure paths
  ([@aydasraf](https://github.com/aydasraf))
- SSO callback view no longer auto-redirects on failure
  ([@aydasraf](https://github.com/aydasraf))
- axios interceptor no longer forces page reload on failed auth-boundary requests
  ([@aydasraf](https://github.com/aydasraf))
- Wrong current password on change-password no longer hangs the UI indefinitely
  ([@aydasraf](https://github.com/aydasraf))
- Typed SortField enum prevents injection on sort parameter
  ([@aydasraf](https://github.com/aydasraf))
- Permission-aware SQL filter replaces overfetch pattern
  ([@aydasraf](https://github.com/aydasraf))
- Proxy cache serves JSON with correct Content-Type on cache hits
  ([@aydasraf](https://github.com/aydasraf))
- Proxy cache rejects JSON responses with empty `files` array (prevents phantom package claims in groups)
  ([@aydasraf](https://github.com/aydasraf))
- Relative URLs in JSON index prevent hostname-resolution errors
  ([@aydasraf](https://github.com/aydasraf))
- PEP 691 yanked field encoding corrected to string|false per spec
  ([@aydasraf](https://github.com/aydasraf))
- Auth failure log levels reclassified — wrong password is WARN, system errors stay ERROR
  ([@aydasraf](https://github.com/aydasraf))
- Okta userinfo endpoint failures reclassified from WARN to ERROR (upstream system error)
  ([@aydasraf](https://github.com/aydasraf))
- Malformed Authorization header returns 401 instead of 500
  ([@aydasraf](https://github.com/aydasraf))
- url.original includes full path + query string, sanitized (extended: password, secret, client_secret)
  ([@aydasraf](https://github.com/aydasraf))
- Hot-path INFO logging downgraded to DEBUG (MemberSlice rewrite, cache hits, slow fetches, FORBIDDEN)
  ([@aydasraf](https://github.com/aydasraf))
- Expired cooldown blocks now invalidate the metadata cache (L1 + L2)
  ([@aydasraf](https://github.com/aydasraf))
- BIGSERIAL sequence repair after bulk backfills (V117)
  ([@aydasraf](https://github.com/aydasraf))
- SAVEPOINT isolation in DbConsumer — single-event failures no longer poison the batch
  ([@aydasraf](https://github.com/aydasraf))
- 404 log noise reduced — per-member 404s at DEBUG, aggregate miss at WARN
  ([@aydasraf](https://github.com/aydasraf))

### 🔒 Security

- UI dependencies pinned to exact versions (supply-chain hardening)
  ([@aydasraf](https://github.com/aydasraf))
- .npmrc enforces save-exact, package-lock, engine-strict
  ([@aydasraf](https://github.com/aydasraf))
- vite upgraded to patched release, clearing dev-server advisories
  ([@aydasraf](https://github.com/aydasraf))
- npm audit reports zero vulnerabilities
  ([@aydasraf](https://github.com/aydasraf))
- Java dependencies refreshed to current stable within major lines
  ([@aydasraf](https://github.com/aydasraf))
- Passwords hashed with bcrypt
  ([@aydasraf](https://github.com/aydasraf))

### 📈 Performance

- Index-miss fanout restricted to proxy-type members only
  ([@aydasraf](https://github.com/aydasraf))

---

## Version 2.0.7

### 🌟 New features

- JWT JTI allowlist — forged tokens rejected even when HMAC secret is known
  ([@aydasraf](https://github.com/aydasraf))
- Per-repo cooldown overrides with three-tier priority (per-repo > per-type > global)
  ([@aydasraf](https://github.com/aydasraf))
- `ArtifactNameParser` drives `locateByName()` for all adapters; `locate()` removed from hot path
  ([@aydasraf](https://github.com/aydasraf))
- Dark/light theme switch with corrected color palette
  ([@turanmahmudov-auto1](https://github.com/turanmahmudov-auto1))
- Sort artifacts by name in repository browser
  ([@turanmahmudov-auto1](https://github.com/turanmahmudov-auto1))

### 🔧 Bug fixes

- Auth redirect loop — API client aligned to localStorage
  ([@aydasraf](https://github.com/aydasraf))
- Dashboard zeros for non-admin users — stats and settings fetched independently
  ([@aydasraf](https://github.com/aydasraf))
- Grafana URL shown to all authenticated users
  ([@aydasraf](https://github.com/aydasraf))
- PHP Composer `DownloadArchiveSlice` returns 404 instead of 500 when artifact missing
  ([@aydasraf](https://github.com/aydasraf))

---

## Version 2.0.5

### 🔧 Bug fixes

- Cooldown unblock now invalidates metadata cache
  ([@aydasraf](https://github.com/aydasraf))
- Maven 500 for repo names containing dots (e.g. `atlassian.com`)
  ([@aydasraf](https://github.com/aydasraf))
- Proxy returns 404 (not 503) when upstream responds with 4xx
  ([@aydasraf](https://github.com/aydasraf))
- Show minutes in cooldown remaining time when < 1h
  ([@aydasraf](https://github.com/aydasraf))
- Persist Grafana URL via settings API
  ([@aydasraf](https://github.com/aydasraf))

### 🌟 New features

- pg_cron hourly DELETE job for expired cooldown rows + partial index
  ([@aydasraf](https://github.com/aydasraf))
- TB and PB tiers in dashboard storage display
  ([@aydasraf](https://github.com/aydasraf))

### 🔒 Security

- log4j 2.25.3, postgresql 42.7.7, jetty 11.0.26, commons-fileupload 1.6.0 (CVE-2025-48976), happy-dom 20.x (RCE fix)
  ([@aydasraf](https://github.com/aydasraf))

---

## Version 2.0.0

### 🌟 New features

- Complete rebrand from Artipie to Pantera — packages, classes, configs, Docker, Grafana
  ([@aydasraf](https://github.com/aydasraf))
- Vue 3 management UI with repository browser, user/role admin, dashboard
  ([@aydasraf](https://github.com/aydasraf))
- PostgreSQL-backed settings with Flyway migrations (replaces YAML-only)
  ([@aydasraf](https://github.com/aydasraf))
- HA clustering with Valkey pub/sub and multi-node state sync
  ([@aydasraf](https://github.com/aydasraf))
- Quartz scheduler for background jobs
  ([@aydasraf](https://github.com/aydasraf))
- ECS-structured JSON logging with Log4j2 EcsLayout
  ([@aydasraf](https://github.com/aydasraf))
- S3 storage optimizations (streaming, multipart upload)
  ([@aydasraf](https://github.com/aydasraf))
- Auth provider renamed from "artipie" to "local" (V102 migration)
  ([@aydasraf](https://github.com/aydasraf))

---

*Prior to v2.0.0, this project was known as [Artipie](https://github.com/artipie/artipie) (releases 0.20–0.23). See the Artipie repository for historical changelogs.*
