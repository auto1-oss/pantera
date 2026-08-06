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
package com.auto1.pantera.group;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.UpstreamCircuitOpenException;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.cache.NegativeCacheKey;
import com.auto1.pantera.http.context.ContextualExecutor;
import com.auto1.pantera.http.fault.Fault;
import com.auto1.pantera.http.fault.FaultTranslator;
import com.auto1.pantera.http.fault.Result;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.resilience.SingleFlight;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import com.auto1.pantera.http.slice.KeyFromPath;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.index.IndexOutcome;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.auto1.pantera.http.timeout.AutoBlockRegistry;

/**
 * Group resolution engine implementing the 5-path decision tree from
 * {@code docs/analysis/v2.2-target-architecture.md} section 2.
 *
 * <p>Canonical group-resolution layer (replaces the legacy GroupSlice,
 * removed in v2.2.0). Wires together:
 * <ul>
 *   <li>{@link Fault} + {@link Result} (WI-01) for typed error paths</li>
 *   <li>{@link FaultTranslator} (WI-01) as the single HTTP-status site</li>
 *   <li>{@link SingleFlight} (WI-05) for proxy fanout coalescing</li>
 *   <li>{@link IndexOutcome} for typed index results</li>
 *   <li>{@link NegativeCache} for 404 caching</li>
 * </ul>
 *
 * <h2>Decision tree</h2>
 * <pre>
 * 1. NegativeCache.isKnown404(groupScope, type, name, ver)
 *      hit  -> 404 [PATH A]
 *      miss -> step 2
 * 2. ArtifactIndex.locateByName(name)
 *      DBFailure/Timeout -> Fault.IndexUnavailable -> 500 [PATH B]
 *      Hit -> targeted storage read [step 3]
 *      Miss -> proxy fanout [step 3']
 * 3. StorageRead -> 2xx [PATH OK]
 *      NotFound (TOCTOU) -> fall through to step 3'
 *      StorageFault -> Fault.StorageUnavailable -> 500 [PATH B]
 * 3'. Proxy fanout (only if group has proxy members)
 *      no proxies -> cache negative + 404 [PATH A]
 *      first 2xx  -> stream + cancel + drain [PATH OK]
 *      all 404    -> cache negative + 404 [PATH A]
 *      any 5xx, no 2xx -> Fault.AllProxiesFailed [PATH B -> pass-through]
 * 4. FaultTranslator.translate(result, ctx) [single translation site]
 * </pre>
 *
 * <h2>Key behaviour characteristics</h2>
 * <ul>
 *   <li><b>TOCTOU fallthrough (A11 fix):</b> Index hit + targeted member 404
 *       falls through to proxy fanout instead of returning 500.</li>
 *   <li><b>AllProxiesFailed pass-through:</b> All proxy 5xx returns the best-ranked
 *       upstream response verbatim via {@link FaultTranslator}.</li>
 *   <li><b>Typed index errors:</b> DB error returns {@link Fault.IndexUnavailable}
 *       instead of silently falling through to full fanout.</li>
 * </ul>
 *
 * @since 2.2.0
 */
public final class GroupResolver implements Slice {

    /**
     * Per-coordinate sibling-member pin TTL. Long enough to cover the gap
     * between a Maven client's {@code .pom} fetch and the immediately-following
     * {@code .pom.sha1} / {@code .jar} fetch (typically &lt;1 s); short enough
     * that a member going offline isn't sticky.
     */
    private static final Duration MEMBER_PIN_TTL = Duration.ofSeconds(60);

    /**
     * Member-pin cache size. One entry per artifact name seen in the last
     * {@link #MEMBER_PIN_TTL}. ~50 bytes per entry → ~2.5 MB worst case.
     */
    private static final long MEMBER_PIN_MAX = 50_000L;

    private final String group;
    private final List<MemberSlice> members;
    private final List<RoutingRule> routingRules;
    private final Optional<ArtifactIndex> artifactIndex;
    private final String repoType;
    private final NegativeCache negativeCache;
    private final SingleFlight<String, Void> inFlightFanouts;
    /**
     * Request-level coalescer keyed by {@code method + ' ' + path}. Pre-fix
     * (2026-06-16): a concurrent burst of same-path GETs against a group
     * with cold members raced past each other inside the per-member
     * {@code coalesceUpstream}: each request independently fanned out,
     * each member's stream-through committed the same file, and the
     * resulting atomic-rename overlap left readers with NoSuchFileException
     * at {@code FileStorage.metadata}/{@code FileChannel.open}. 8-way Gradle
     * classpath bursts produced 8/8 502s on a fresh cache. Coalescing at
     * the group entrypoint — before any member fanout — means exactly one
     * resolve runs upstream per (method, path) burst; followers receive
     * a fresh {@link Response} built from a byte[] snapshot of the leader's
     * body. Memory pressure is a function of concurrent-burst size ×
     * body size, bounded by the 2-minute in-flight TTL on {@link SingleFlight}.
     */
    private final SingleFlight<String, BufferedResponse> requestDedup;
    private final java.util.concurrent.Executor drainExecutor;

    /**
     * Snapshot of a resolved group response, safe to fan out to N
     * followers. Holds the response status, headers, and the fully
     * buffered body bytes so each follower can build its own
     * {@link Response} without re-running the resolve or sharing the
     * leader's single-subscriber publisher.
     */
    private record BufferedResponse(RsStatus status, Headers headers, byte[] body) { }
    /**
     * Per-coordinate sibling-member pin. When a request for artifact {@code X}
     * is served successfully by member {@code M}, subsequent requests for any
     * {@code X.*} sibling within {@link #MEMBER_PIN_TTL} are routed to {@code
     * M} directly, bypassing the index lookup and the fanout. This keeps
     * {@code .pom} and {@code .pom.sha1} fetches on the same upstream — the
     * race that produced the "Checksum validation failed" warnings when the
     * index was momentarily inconsistent with member-side cache state.
     */
    private final Cache<String, String> memberPin;

    /**
     * Full constructor.
     *
     * <p><b>Sequential-only fanout.</b> Members are tried in declared order;
     * the first 2xx wins; subsequent members are only consulted on 404 from
     * the previous one. There is no parallel mode. The YAML parser tolerates
     * an unrecognised {@code members_strategy} key for forward-compat with
     * pre-2.2.0 configs but the value is discarded silently.
     *
     * @param group Group repository name
     * @param members Flattened member slices with circuit breakers
     * @param routingRules Routing rules for path-based member selection
     * @param artifactIndex Optional artifact index for O(log n) lookups
     * @param repoType Repository type for name parsing
     * @param proxyMembers Names of proxy repository members
     * @param negativeCache Negative cache for 404 results
     * @param drainExecutor Per-repo drain executor from {@link com.auto1.pantera.http.resilience.RepoBulkhead}
     */
    public GroupResolver(
        final String group,
        final List<MemberSlice> members,
        final List<RoutingRule> routingRules,
        final Optional<ArtifactIndex> artifactIndex,
        final String repoType,
        final Set<String> proxyMembers, // NOPMD UnusedFormalParameter - public API; reserved (proxyMembers list now consulted via the buildMembers wiring path)
        final NegativeCache negativeCache,
        final java.util.concurrent.Executor drainExecutor
    ) {
        this.group = Objects.requireNonNull(group, "group");
        this.members = Objects.requireNonNull(members, "members");
        this.routingRules = routingRules != null ? routingRules : Collections.emptyList();
        this.artifactIndex = artifactIndex != null ? artifactIndex : Optional.empty();
        this.repoType = repoType != null ? repoType : "";
        this.negativeCache = Objects.requireNonNull(negativeCache, "negativeCache");
        this.drainExecutor = Objects.requireNonNull(drainExecutor, "drainExecutor");
        this.inFlightFanouts = new SingleFlight<>(
            Duration.ofMinutes(5),
            10_000,
            ContextualExecutor.contextualize(ForkJoinPool.commonPool())
        );
        this.requestDedup = new SingleFlight<>(
            Duration.ofMinutes(2),
            4_096,
            ContextualExecutor.contextualize(ForkJoinPool.commonPool())
        );
        this.memberPin = Caffeine.newBuilder()
            .maximumSize(MEMBER_PIN_MAX)
            .expireAfterWrite(MEMBER_PIN_TTL)
            .build();
    }

    /**
     * Wiring-site-friendly constructor.
     *
     * <p>Accepts member repository <em>names</em> and builds the
     * {@link MemberSlice} list inline via {@code resolver.slice(...)} so that
     * call-sites in {@code RepositorySlices} do not need to duplicate the
     * member-wrapping logic.  Delegates to the member-accepting constructor
     * above.
     *
     * <p>The {@code depth} parameter is accepted for API compatibility but
     * ignored (group nesting is resolved upstream).
     *
     * @param resolver Slice resolver/cache used to materialize member slices
     * @param group Group repository name
     * @param memberNames Member repository names (deduplicated, order preserved)
     * @param port Server port passed to the slice resolver
     * @param depth Nesting depth (accepted and ignored for API compat)
     * @param timeoutSeconds Timeout hint (unused here, preserved for API compat)
     * @param routingRules Routing rules for path-based member selection
     * @param artifactIndex Optional artifact index for O(log n) lookups
     * @param proxyMembers Names of proxy repository members
     * @param repoType Repository type for name parsing
     * @param negativeCache Pre-constructed negative cache
     * @param registrySupplier Function mapping member name to its shared
     *                         {@link AutoBlockRegistry} (may be {@code null})
     * @param repoDrainExecutor Per-repo drain executor
     */
    public GroupResolver(
        final SliceResolver resolver,
        final String group,
        final List<String> memberNames,
        final int port,
        final int depth, // NOPMD UnusedFormalParameter - public API; reserved for legacy depth-limited recursion (unused since v2.2.0 sequential-only fanout)
        final long timeoutSeconds, // NOPMD UnusedFormalParameter - public API; reserved for legacy timeout (unused since v2.2.0 sequential-only fanout)
        final List<RoutingRule> routingRules,
        final Optional<ArtifactIndex> artifactIndex,
        final Set<String> proxyMembers,
        final String repoType,
        final NegativeCache negativeCache,
        final Function<String, AutoBlockRegistry> registrySupplier,
        final java.util.concurrent.Executor repoDrainExecutor
    ) {
        this(
            group,
            buildMembers(resolver, memberNames, port, proxyMembers, registrySupplier),
            routingRules,
            artifactIndex,
            repoType,
            proxyMembers,
            negativeCache,
            repoDrainExecutor
        );
    }

    /**
     * Build the flattened {@link MemberSlice} list from member names:
     * deduplicate preserving order, then wrap each name with either the
     * shared-registry 4-arg {@link MemberSlice} constructor (when the supplier
     * returns non-null) or the 3-arg variant (when the supplier is null or
     * returns null).
     */
    private static List<MemberSlice> buildMembers(
        final SliceResolver resolver,
        final List<String> memberNames,
        final int port,
        final Set<String> proxyMembers,
        final Function<String, AutoBlockRegistry> registrySupplier
    ) {
        final Set<String> safeProxies = proxyMembers != null
            ? proxyMembers : Collections.emptySet();
        final Function<String, AutoBlockRegistry> supplier =
            registrySupplier != null ? registrySupplier : n -> null;
        final List<MemberSlice> out = new ArrayList<>();
        for (final String name : new LinkedHashSet<>(memberNames)) {
            final AutoBlockRegistry reg = supplier.apply(name);
            if (reg != null) {
                out.add(new MemberSlice(
                    name,
                    resolver.slice(new Key.From(name), port, 0),
                    reg,
                    safeProxies.contains(name)
                ));
            } else {
                out.add(new MemberSlice(
                    name,
                    resolver.slice(new Key.From(name), port, 0),
                    safeProxies.contains(name)
                ));
            }
        }
        return out;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String method = line.method().value();
        final String path = line.uri().getPath();

        final boolean isReadOperation = "GET".equals(method) || "HEAD".equals(method);
        final boolean isNpmAudit = "POST".equals(method) && path.contains("/-/npm/v1/security/");
        if (!isReadOperation && !isNpmAudit) {
            return CompletableFuture.completedFuture(
                ResponseBuilder.methodNotAllowed().build()
            );
        }

        // Reject Maven/Gradle version-range coordinates that leaked into the
        // artifact path (e.g. a misconfigured dependency requesting
        // `graphql-utils-[,7.2079-test-1).jar`). The range metacharacters
        // `[ ] ( )` never appear in a valid Maven/Gradle artifact path, but
        // GroupResolver is the shared response() for EVERY group type
        // (npm/gem/go/pypi/docker/file/php-group too) — a file-group upload
        // can legitimately be named "backup[v2].zip" or "summary(final).pdf".
        // Scope the guard to Maven/Gradle-shaped groups only, where these
        // characters are unambiguously a malformed version range. Forwarding
        // such a request makes upstreams 502 on the unescaped brackets, and
        // the walk would then recordFailure() against a HEALTHY member —
        // fabricated evidence that can trip its circuit breaker
        // (breaker-cascade). Answer 404 here, before any index lookup or
        // member is touched.
        final boolean isMavenShaped =
            "maven-group".equals(this.repoType) || "gradle-group".equals(this.repoType);
        if (isMavenShaped && containsVersionRangeSyntax(path)) {
            EcsLogger.debug("com.auto1.pantera.group")
                .message("Rejected malformed version-range artifact path, returning 404")
                .eventCategory("web")
                .eventAction("group_malformed_range_path")
                .field("repository.name", this.group)
                .field("url.path", path)
                .field("log.source", "application")
                .log();
            return CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            );
        }

        if (this.members.isEmpty()) {
            return CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            );
        }

        recordRequestStart();
        final long requestStartTime = System.currentTimeMillis();

        // Coalesce concurrent same-path GET requests at the group entrypoint.
        // Without this, an N-way mvn/gradle/uv parallel resolve all hit the
        // per-member coalesceUpstream independently, multiple stream-throughs
        // atomic-rename the same file, and readers race with NoSuchFileException
        // at FileStorage.metadata / FileChannel.open (RCA-1, 2026-06-16).
        //
        // HEAD is intentionally NOT deduped:
        //   * HEAD does not trigger storage.save, so there is no body-race
        //     to coalesce.
        //   * HEAD responses must carry the same Content-Length the matching
        //     GET would have set (RFC 7231), with an EMPTY body. Our
        //     bufferResponse drains resp.body() into a byte[] and rebuilds
        //     the response via ResponseBuilder.body(byte[]), which
        //     unconditionally overwrites Content-Length with bytes.length.
        //     For HEAD that means Content-Length: 0 — Docker's daemon then
        //     errors out with "unable to fetch descriptor (sha256:…) which
        //     reports content size of zero: invalid argument" when probing
        //     a manifest by digest. Skipping dedup keeps the upstream-set
        //     Content-Length intact.
        // POST (npm-audit) also bypasses dedup — request bodies matter
        // per-caller.
        final boolean coalesce = "GET".equals(method);
        if (!coalesce) {
            return resolve(line, headers, body, path)
                .whenComplete((resp, err) -> recordMetrics(resp, err, requestStartTime));
        }
        final String dedupKey = method + " " + path;
        return this.requestDedup.load(
            dedupKey,
            () -> resolve(line, headers, body, path).thenCompose(this::bufferResponse)
        ).thenApply(buf -> ResponseBuilder.from(buf.status())
            .headers(buf.headers())
            .body(buf.body())
            .build()
        ).whenComplete((resp, err) -> recordMetrics(resp, err, requestStartTime));
    }

    /**
     * Whether {@code path} carries Maven version-range metacharacters
     * ({@code [ ] ( )}). Their presence means a version range (e.g.
     * {@code [,7.2079-test-1)}) leaked into the artifact coordinate — typically
     * from a misconfigured Gradle dependency — which is never a valid artifact
     * request. Such paths must not reach members: upstreams 502 on the
     * unescaped brackets and the walk would convict a healthy member.
     *
     * @param path Request path
     * @return {@code true} if any range metacharacter is present
     */
    private static boolean containsVersionRangeSyntax(final String path) {
        for (int idx = 0; idx < path.length(); idx++) {
            final char chr = path.charAt(idx);
            if (chr == '[' || chr == ']' || chr == '(' || chr == ')') {
                return true;
            }
        }
        return false;
    }

    /**
     * Drain the leader's response body into a byte[] snapshot followers
     * can rebuild fresh {@link Response}s from. The leader's body is a
     * single-subscriber publisher; only one subscriber can consume it, so
     * the leader buffers once and shares the bytes.
     */
    private CompletableFuture<BufferedResponse> bufferResponse(final Response resp) {
        return resp.body().asBytesFuture()
            .thenApply(bytes -> new BufferedResponse(resp.status(), resp.headers(), bytes));
    }

    /**
     * Core resolution logic implementing the 5-path decision tree.
     */
    private CompletableFuture<Response> resolve(
        final RequestLine line,
        final Headers headers,
        final Content body,
        final String path
    ) {
        // Phase 7.5 profiler: total resolve() wall — should track
        // pantera_group_resolution_duration_seconds_sum, but with
        // strictly-equal counts so per-phase ratios are honest.
        final long resolveStartNs = System.nanoTime();
        // ---- No index configured → full two-phase fanout ----
        if (this.artifactIndex.isEmpty()) {
            return fullTwoPhaseFanout(line, headers, body)
                .whenComplete((r, e) -> recordPhase("resolve_total", resolveStartNs));
        }

        final ArtifactIndex idx = this.artifactIndex.get(); // NOPMD CloseResource - ArtifactIndex is a long-lived shared service; lifecycle owned by RepositorySlices
        final Optional<String> parsedName = ArtifactNameParser.parse(this.repoType, path);
        if (parsedName.isEmpty()) {
            EcsLogger.debug("com.auto1.pantera.group")
                .message("Name unparseable, using full two-phase fanout")
                .eventCategory("web")
                .eventAction("group_direct_fanout")
                .field("url.path", path)
                .field("log.source", "application")
                .log();
            return fullTwoPhaseFanout(line, headers, body)
                .whenComplete((r, e) -> recordPhase("resolve_total", resolveStartNs));
        }

        final String artifactName = parsedName.get();

        // ---- STEP 1: Negative cache check ----
        // Best-effort version extraction from the URL so the admin UI has a
        // real Version column. Uses NegativeCacheKey.fromPath solely to parse
        // the path; we keep our own (ArtifactNameParser-derived) artifactName
        // to stay consistent with the index lookup format.
        final long negCacheStartNs = System.nanoTime();
        final String parsedVersion = NegativeCacheKey
            .fromPath(this.group, this.repoType, path).artifactVersion();
        final NegativeCacheKey negCacheKey = new NegativeCacheKey(
            this.group, this.repoType, artifactName, parsedVersion
        );
        final boolean known404 = this.negativeCache.isKnown404(negCacheKey);
        recordPhase("negative_cache_check", negCacheStartNs);
        if (known404) {
            EcsLogger.debug("com.auto1.pantera.group")
                .message("Negative cache hit, returning 404 without DB query")
                .eventCategory("database")
                .eventAction("group_negative_cache_hit")
                .field("url.path", path)
                .field("log.source", "application")
                .log();
            recordPhase("resolve_total", resolveStartNs);
            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        }

        // ---- STEP 1.5: Sibling-member pin ----
        // If this same artifactName was served successfully within the last
        // MEMBER_PIN_TTL, route directly to that member. This eliminates the
        // window where a .pom resolves to member A (via fanout) and the
        // immediately-following .pom.sha1 — fetched before the index has
        // caught up — resolves to a different member, producing a body /
        // sidecar pair from two different upstreams. On TOCTOU drift the
        // pinned-member 404 falls through to proxy fanout via
        // {@link #targetedLocalRead}'s standard path.
        final String pinnedRepo = this.memberPin.getIfPresent(artifactName);
        if (pinnedRepo != null
            && this.members.stream().anyMatch(m -> m.name().equals(pinnedRepo))) {
            EcsLogger.debug("com.auto1.pantera.group")
                .message("Sibling-pin hit: routing " + artifactName
                    + " to " + pinnedRepo)
                .eventCategory("web")
                .eventAction("group_sibling_pin_hit")
                .field("url.path", path)
                .field("repository.name", pinnedRepo)
                .field("log.source", "application")
                .log();
            return targetedLocalRead(
                List.of(pinnedRepo), line, headers, body, path,
                artifactName, negCacheKey
            ).whenComplete((r, e) -> recordPhase("resolve_total", resolveStartNs));
        }

        // ---- STEP 2: Query index ----
        // Phase 7.5 profiler: time the index lookup itself, separate from
        // the downstream targeted/fanout work. Recorded both on success
        // and on failure paths so the sum / count metric is honest.
        final long indexStartNs = System.nanoTime();
        return idx.locateByName(artifactName)
            .thenApply(IndexOutcome::fromLegacy)
            .exceptionally(ex -> new IndexOutcome.DBFailure(ex, "locateByName:" + artifactName))
            .thenCompose(outcome -> {
                recordPhase("index_lookup", indexStartNs);
                return handleIndexOutcome(
                    outcome, line, headers, body, path, artifactName, negCacheKey
                );
            }).whenComplete((r, e) -> recordPhase("resolve_total", resolveStartNs));
    }

    /**
     * Phase 7.5 profiler helper — record a phase end-time delta against
     * {@link com.auto1.pantera.metrics.MicrometerMetrics#recordHandlerPhaseDuration}.
     * Cheap when metrics are not initialized (single static volatile read).
     *
     * @param phase   phase name tag (e.g. {@code "index_lookup"})
     * @param startNs nanoTime captured at phase start
     */
    private void recordPhase(final String phase, final long startNs) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordHandlerPhaseDuration(this.group, phase, System.nanoTime() - startNs);
        }
    }

    /**
     * Branch on the index outcome.
     */
    private CompletableFuture<Response> handleIndexOutcome(
        final IndexOutcome outcome,
        final RequestLine line,
        final Headers headers,
        final Content body,
        final String path,
        final String artifactName,
        final NegativeCacheKey negCacheKey
    ) {
        return switch (outcome) { // NOPMD SwitchDensity - exhaustive IndexOutcome sealed-type dispatch; per-branch logging required
            case IndexOutcome.Hit hit -> targetedLocalRead(
                hit.repos(), line, headers, body, path, artifactName, negCacheKey
            );
            case IndexOutcome.Miss miss -> proxyOnlyFanout(
                line, headers, body, artifactName, negCacheKey
            );
            case IndexOutcome.Timeout t -> {
                EcsLogger.warn("com.auto1.pantera.group")
                    .message("Index query timed out, returning 500")
                    .eventCategory("database")
                    .eventAction("group_index_timeout")
                    .eventOutcome("failure")
                    .field("url.path", path)
                    .field("log.source", "application")
                    .log();
                yield CompletableFuture.completedFuture(
                    FaultTranslator.translate(
                        new Fault.IndexUnavailable(t.cause(), "locateByName:" + artifactName),
                        null
                    )
                );
            }
            case IndexOutcome.DBFailure db -> {
                EcsLogger.warn("com.auto1.pantera.group")
                    .message("Index DB error, returning 500")
                    .eventCategory("database")
                    .eventAction("group_index_error")
                    .eventOutcome("failure")
                    .field("url.path", path)
                    .field("log.source", "application")
                    .log();
                yield CompletableFuture.completedFuture(
                    FaultTranslator.translate(
                        new Fault.IndexUnavailable(db.cause(), db.query()),
                        null
                    )
                );
            }
        };
    }

    /**
     * STEP 3: Index hit -- targeted local read.
     *
     * <p>On 404 from the targeted member (TOCTOU drift, A11 fix), falls through
     * to proxy fanout instead of returning 500 -- this is the key behaviour
     * change from the old GroupSlice.
     */
    private CompletableFuture<Response> targetedLocalRead(
        final List<String> repos,
        final RequestLine line,
        final Headers headers,
        final Content body,
        final String path,
        final String artifactName,
        final NegativeCacheKey negCacheKey
    ) {
        // Phase 7.5 profiler: time the targeted local-read path end-to-end
        // including the (possible) TOCTOU fallthrough into proxy fanout.
        final long phaseStartNs = System.nanoTime();
        return targetedLocalReadInternal(
            repos, line, headers, body, path, artifactName, negCacheKey
        ).whenComplete((r, e) -> recordPhase("targeted_local_read", phaseStartNs));
    }

    private CompletableFuture<Response> targetedLocalReadInternal(
        final List<String> repos,
        final RequestLine line,
        final Headers headers,
        final Content body,
        final String path,
        final String artifactName,
        final NegativeCacheKey negCacheKey
    ) {
        final Set<String> wanted = new HashSet<>(repos);
        final List<MemberSlice> targeted = this.members.stream()
            .filter(m -> wanted.contains(m.name()))
            .toList();
        if (targeted.isEmpty()) {
            EcsLogger.debug("com.auto1.pantera.group")
                .message("Index hit references repo not in flattened member list, "
                    + "falling through to full fanout")
                .eventCategory("web")
                .eventAction("group_index_orphan")
                .field("url.path", path)
                .field("log.source", "application")
                .log();
            return fullTwoPhaseFanout(line, headers, body);
        }
        EcsLogger.debug("com.auto1.pantera.group")
            .message("Index hit via name: targeting " + targeted.size() + " member(s)")
            .eventCategory("web")
            .eventAction("group_index_hit")
            .field("url.path", path)
            .field("log.source", "application")
            .log();

        // Sequential-only fanout (v2.2.0). Walk targeted members in declared
        // order. The TOCTOU postprocessing below only inspects the response
        // status, so we plug the sequential result into the same .thenCompose
        // chain. isTargetedLocalRead=true bypasses open-circuit gating because
        // index-hit reads are authoritative on hosted state — a tripped
        // breaker against the hosted member would otherwise mask the
        // index-vs-storage drift behind a fanout.
        return querySequentially(targeted, line, headers, body, true, artifactName)
            .thenCompose(resp -> {
                if (resp.status().success()
                    || resp.status() == RsStatus.NOT_MODIFIED
                    || resp.status() == RsStatus.FORBIDDEN) {
                    return CompletableFuture.completedFuture(resp);
                }
                if (resp.status() == RsStatus.NOT_FOUND) {
                    EcsLogger.debug("com.auto1.pantera.group")
                        .message("TOCTOU drift (sequential): index hit but no "
                            + "member returned bytes, falling through to proxy fanout")
                        .eventCategory("web")
                        .eventAction("group_toctou_fallthrough")
                        .field("url.path", line.uri().getPath())
                        .field("log.source", "application")
                        .log();
                    return proxyOnlyFanout(line, headers, body, artifactName, negCacheKey);
                }
                if (resp.status().serverError()) {
                    return CompletableFuture.completedFuture(
                        FaultTranslator.translate(
                            new Fault.StorageUnavailable(null, line.uri().getPath()),
                            null
                        )
                    );
                }
                return CompletableFuture.completedFuture(resp);
            });
    }

    /**
     * STEP 3': Proxy-only fanout.
     *
     * <p>Called when:
     * <ul>
     *   <li>Index returns Miss (artifact not in any hosted repo)</li>
     *   <li>Index hit but targeted member 404 (TOCTOU drift)</li>
     * </ul>
     *
     * <p>Skipping hosted members is the optimization that keeps the group
     * fast — it relies on the artifact_index being authoritative for
     * "what's in hosted". Upload-side index maintenance must be synchronous
     * for this to be safe; otherwise a freshly-uploaded artifact whose
     * event hasn't yet been consumed by {@code DbConsumer} will not appear
     * in the index, fanout will skip hosted, and the request 404s.
     */
    private CompletableFuture<Response> proxyOnlyFanout(
        final RequestLine line,
        final Headers headers,
        final Content body,
        final String artifactName,
        final NegativeCacheKey negCacheKey
    ) {
        final long phaseStartNs = System.nanoTime();
        return proxyOnlyFanoutInternal(line, headers, body, artifactName, negCacheKey)
            .whenComplete((r, e) -> recordPhase("proxy_only_fanout", phaseStartNs));
    }

    private CompletableFuture<Response> proxyOnlyFanoutInternal(
        final RequestLine line,
        final Headers headers,
        final Content body,
        final String artifactName,
        final NegativeCacheKey negCacheKey
    ) {
        final List<MemberSlice> fanoutMembers = this.members.stream()
            .filter(MemberSlice::isProxy)
            .toList();
        if (fanoutMembers.isEmpty()) {
            this.negativeCache.cacheNotFound(negCacheKey);
            EcsLogger.debug("com.auto1.pantera.group")
                .message("No proxy members, caching 404 and returning")
                .eventCategory("web")
                .eventAction("group_index_miss")
                .field("url.path", line.uri().getPath())
                .field("log.source", "application")
                .log();
            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        }

        // Request coalescing via SingleFlight
        final String dedupKey = this.group + ":" + artifactName;
        final boolean[] isLeader = {false};
        final CompletableFuture<Void> leaderGate = new CompletableFuture<>();
        final CompletableFuture<Void> gate = this.inFlightFanouts.load(
            dedupKey,
            () -> {
                isLeader[0] = true;
                return leaderGate;
            }
        );
        if (isLeader[0]) {
            EcsLogger.debug("com.auto1.pantera.group")
                .message("Index miss: fanning out to "
                    + fanoutMembers.size() + " proxy member(s)")
                .eventCategory("network")
                .eventAction("group_index_miss")
                .field("url.path", line.uri().getPath())
                .field("log.source", "application")
                .log();
            return executeProxyFanout(fanoutMembers, line, headers, body, artifactName, negCacheKey)
                .whenComplete((resp, err) -> leaderGate.complete(null));
        }
        EcsLogger.debug("com.auto1.pantera.group")
            .message("Coalescing with in-flight fanout for " + artifactName)
            .eventCategory("web")
            .eventAction("group_fanout_coalesce")
            .field("log.source", "application")
            .log();
        return gate.exceptionally(err -> null)
            .thenCompose(ignored -> proxyOnlyFanout(line, headers, body, artifactName, negCacheKey));
    }

    /**
     * Execute the proxy fanout, returning the result with Fault-typed errors.
     */
    private CompletableFuture<Response> executeProxyFanout(
        final List<MemberSlice> fanoutMembers,
        final RequestLine line,
        final Headers headers,
        final Content body,
        final String artifactName,
        final NegativeCacheKey negCacheKey
    ) {
        // Sequential-only fanout (v2.2.0). The previous parallel branch and
        // its outcome-aggregation helpers (handleProxyMemberResponse,
        // handleProxyMemberFailure, completeProxyIfAllExhausted) are removed;
        // querySequentially walks members in declared order and FaultTranslator
        // is invoked here on a 5xx terminal to preserve the X-Pantera-Fault
        // header behaviour of the legacy parallel path.
        return querySequentially(fanoutMembers, line, headers, body, false, artifactName)
            .thenApply(resp -> {
                if (resp.status().serverError()
                    && !resp.headers().values(UpstreamCircuitOpenException.HEADER).isEmpty()) {
                    // Circuit-skip terminal (503 + Retry-After + marker) or a
                    // member's marked fast-fail: pass through verbatim. It is
                    // neither an AllProxiesFailed fault (nothing actually
                    // failed) nor a 404 (nothing said "does not exist"), so
                    // no fault translation and no negative-cache write.
                    return resp;
                }
                if (resp.status().serverError()) {
                    // Sequential walk reached terminal 5xx: any member 5xx in
                    // the walk -> AllProxiesFailed wrapped through
                    // FaultTranslator. querySequentially does not carry per-
                    // member outcomes through; pass an empty outcomes list so
                    // FaultTranslator picks no winning failure (the synthesized
                    // 502 response stays as the body).
                    final Fault.AllProxiesFailed fault = new Fault.AllProxiesFailed(
                        this.group, java.util.List.of(), java.util.Optional.empty()
                    );
                    return FaultTranslator.translate(fault, null);
                }
                if (resp.status() == RsStatus.NOT_FOUND) {
                    // Fix 2: a member may launder a non-authoritative upstream
                    // 4xx (rate-limit / 403 / 429 / 410) into a 404 to satisfy
                    // the multi-remote race contract, marking it with
                    // NegativeCache.SKIP_HEADER. Do NOT negative-cache such a
                    // 404 — the artifact may exist and the upstream was merely
                    // throttling; caching it would produce a long-lived false
                    // 404. Still return the 404 to the client.
                    if (resp.headers().values(
                            com.auto1.pantera.http.cache.NegativeCache.SKIP_HEADER).isEmpty()) {
                        this.negativeCache.cacheNotFound(negCacheKey);
                        EcsLogger.debug("com.auto1.pantera.group")
                            .message("All proxies returned 404, caching negative result")
                            .eventCategory("database")
                            .eventAction("group_negative_cache_populate")
                            .field("log.source", "application")
                            .log();
                    } else {
                        EcsLogger.debug("com.auto1.pantera.group")
                            .message("Member 404 marked non-authoritative "
                                + "(upstream throttle); not negative-caching")
                            .eventCategory("database")
                            .eventAction("group_negative_cache_skip_unverified")
                            .field("log.source", "application")
                            .log();
                    }
                }
                return resp;
            });
    }

    /**
     * Full two-phase fanout -- safety net when artifact name cannot be parsed
     * or index is not configured. Hosted members first, then proxy.
     */
    private CompletableFuture<Response> fullTwoPhaseFanout(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final long phaseStartNs = System.nanoTime();
        final List<MemberSlice> eligible = filterByRoutingRules(line.uri().getPath());
        if (eligible.isEmpty()) {
            recordPhase("full_two_phase_fanout", phaseStartNs);
            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        }
        return queryHostedFirstThenProxy(eligible, line, headers, body)
            .whenComplete((r, e) -> recordPhase("full_two_phase_fanout", phaseStartNs));
    }

    /**
     * Two-phase: hosted first, then proxy.
     */
    private CompletableFuture<Response> queryHostedFirstThenProxy(
        final List<MemberSlice> targeted,
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final List<MemberSlice> hosted = targeted.stream()
            .filter(m -> !m.isProxy())
            .toList();
        final List<MemberSlice> proxy = targeted.stream()
            .filter(MemberSlice::isProxy)
            .toList();
        if (hosted.isEmpty() || proxy.isEmpty()) {
            return queryTargetedMembers(targeted, line, headers, body, false);
        }
        return queryTargetedMembers(hosted, line, headers, body, false)
            .thenCompose(resp -> {
                if (resp.status().success()) {
                    return CompletableFuture.completedFuture(resp);
                }
                return queryTargetedMembers(proxy, line, headers, body, false);
            });
    }

    /**
     * Query a list of members sequentially (v2.2.0: parallel mode removed).
     * Used for full two-phase fanout only (not the indexed path); thin wrapper
     * around {@link #querySequentially}.
     */
    private CompletableFuture<Response> queryTargetedMembers(
        final List<MemberSlice> targeted,
        final RequestLine line,
        final Headers headers,
        final Content body,
        final boolean isTargetedLocalRead
    ) {
        return querySequentially(targeted, line, headers, body, isTargetedLocalRead, null);
    }

    /**
     * Sequential member walk: try each member in declared order, return the
     * first 2xx (or 403, which is authoritative); 404 -> continue; 5xx /
     * other failures -> record on the member's circuit and continue.
     * Open-circuit members are skipped without an upstream call. Mirrors
     * Nexus / JFrog group-resolution semantics. When every member exhausts:
     * return 502 if any 5xx was observed, else 404.
     *
     * @param targeted             Members in declared YAML order.
     * @param line                 Request line forwarded to {@link
     *                             #queryMemberDirect}.
     * @param headers              Request headers.
     * @param body                 Request body (buffered once into a byte[]
     *                             so it can be replayed to each member).
     * @param isTargetedLocalRead  When true, open-circuit gating is bypassed
     *                             (mirrors the parallel path's behaviour for
     *                             local-read fallbacks).
     */
    private CompletableFuture<Response> querySequentially(
        final List<MemberSlice> targeted,
        final RequestLine line,
        final Headers headers,
        final Content body,
        final boolean isTargetedLocalRead,
        final String pinArtifactName
    ) {
        return body.asBytesFuture().thenCompose(requestBytes -> {
            final CompletableFuture<Response> result = new CompletableFuture<>();
            tryNextSequentialMember(
                targeted.iterator(), line, headers, requestBytes,
                isTargetedLocalRead, new WalkState(), result, pinArtifactName
            );
            return result;
        });
    }

    private void tryNextSequentialMember(
        final java.util.Iterator<MemberSlice> iter,
        final RequestLine line,
        final Headers headers,
        final byte[] requestBytes,
        final boolean isTargetedLocalRead,
        final WalkState walk,
        final CompletableFuture<Response> result,
        final String pinArtifactName
    ) {
        if (!iter.hasNext()) {
            if (walk.anyServerError.get()) {
                result.complete(ResponseBuilder.from(RsStatus.BAD_GATEWAY).build());
            } else if (walk.skippedOpen.get()) {
                // At least one member was skipped with its circuit open and
                // nobody answered — "temporarily unavailable" is the only
                // honest answer. Returning 404 here poisoned the negative
                // cache with "does not exist" for artifacts that were merely
                // unreachable (2.2.0 breaker-cascade RCA).
                result.complete(this.circuitSkippedTerminal(walk, line));
            } else if (walk.anyUnverified.get()) {
                // Fix 2: every member 404'd but at least one 404 was a laundered
                // upstream throttle, not an authoritative absence. Return 404 but
                // re-carry the marker so the caller does not negative-cache it.
                // WS8 Bug B5: still reuse a captured member 404 body when one
                // exists, same as the plain-404 branch below.
                result.complete(
                    walk.notFoundResponse()
                        .header(com.auto1.pantera.http.cache.NegativeCache.SKIP_HEADER, "true")
                        .build()
                );
            } else {
                // WS8 Bug B5: every member 404'd -- reuse the first member's
                // own honest 404 body/headers (captured above as each 404 was
                // observed) instead of manufacturing a bare empty one. This is
                // the terminal that a live /<pkg>/<bad-version> lookup against
                // npm_group (and, via RaceSlice, npm_proxy) actually reaches.
                result.complete(walk.notFoundResponse().build());
            }
            return;
        }
        final MemberSlice member = iter.next();
        if (!isTargetedLocalRead && member.isCircuitOpen()) {
            walk.skippedOpen.set(true);
            walk.noteRetryAfter(member.retryAfterSeconds());
            // Circuit-open member: probe its warm cache before moving on
            // (Nexus-style serve-cached-while-blocked). Cache-only probes
            // never reach the upstream and record nothing on the member's
            // health window — a cache hit says nothing about upstream health.
            final long probeStartMs = System.currentTimeMillis();
            queryMemberCacheOnly(member, line, headers, requestBytes)
                .whenComplete((resp, err) -> {
                    if (err == null && resp != null && resp.status().success()) {
                        recordMemberOutcome(
                            member, "success",
                            System.currentTimeMillis() - probeStartMs
                        );
                        EcsLogger.info("com.auto1.pantera.group")
                            .message("Circuit-open member served from warm cache")
                            .eventCategory("web")
                            .eventAction("group_member_cache_only_hit")
                            .eventOutcome("success")
                            .field("repository.name", this.group)
                            .field("member.name", member.name())
                            .field("url.path", line.uri().getPath())
                            .field("log.source", "application")
                            .log();
                        result.complete(resp);
                        return;
                    }
                    if (resp != null) {
                        drainBody(resp.body());
                    }
                    tryNextSequentialMember(iter, line, headers, requestBytes,
                        isTargetedLocalRead, walk, result, pinArtifactName);
                });
            return;
        }
        final long memberStartMs = System.currentTimeMillis();
        queryMemberDirect(member, line, headers, requestBytes).whenComplete((resp, err) -> {
            final long memberLatency = System.currentTimeMillis() - memberStartMs;
            if (err != null) {
                if (!(err instanceof java.util.concurrent.CancellationException)) {
                    member.recordFailure();
                    walk.anyServerError.set(true);
                    recordMemberOutcome(member, "error", memberLatency);
                }
                tryNextSequentialMember(iter, line, headers, requestBytes,
                    isTargetedLocalRead, walk, result, pinArtifactName);
                return;
            }
            final RsStatus status = resp.status();
            if (status == RsStatus.OK || status == RsStatus.PARTIAL_CONTENT
                || status == RsStatus.NOT_MODIFIED || status == RsStatus.FORBIDDEN) {
                member.recordSuccess();
                recordMemberOutcome(member, "success", memberLatency);
                // Record the winning member for sibling pinning. NOT_MODIFIED
                // and FORBIDDEN also count — they confirm authoritative
                // ownership by this member. The cache TTL keeps the pin
                // bounded so a member going offline doesn't strand
                // subsequent requests.
                if (pinArtifactName != null) {
                    this.memberPin.put(pinArtifactName, member.name());
                }
                result.complete(resp);
                return;
            }
            if (status == RsStatus.NOT_FOUND) {
                // Fix 2: propagate a member's non-authoritative 404 marker
                // (upstream throttle laundered into a 404) so the terminal 404
                // synthesized on walk exhaustion re-carries it and the caller
                // skips negative-caching a possibly-existing artifact.
                if (!resp.headers().values(
                        com.auto1.pantera.http.cache.NegativeCache.SKIP_HEADER).isEmpty()) {
                    walk.anyUnverified.set(true);
                }
                recordMemberOutcome(member, "not_found", memberLatency);
                // RCA-6 (v2.2.0): keep member fall-through investigable. A
                // maven_proxy 404 silently falling through to groovy once hid
                // group upstream-amplification during perf diagnosis. Logged at
                // DEBUG, not INFO: a 404 fall-through is the normal group
                // cache-miss path and fires on nearly every proxied pull, so it
                // must not sit in steady-state logs — enable DEBUG on
                // com.auto1.pantera.group to trace it. Genuine 5xx member
                // failures remain WARN below.
                EcsLogger.debug("com.auto1.pantera.group")
                    .message("Group member returned 404, trying next")
                    .eventCategory("web")
                    .eventAction("group_member_fallthrough")
                    .field("repository.name", this.group)
                    .field("member.name", member.name())
                    .field("http.response.status_code", 404)
                    .field("url.path", line.uri().getPath())
                    .field("log.source", "application")
                    .log();
                // WS8 Bug B5: capture this member's own 404 body (e.g. a
                // proxy member's honest "version not found" JSON) instead of
                // the old fire-and-forget drainBody(), so the terminal 404
                // built once every member is exhausted (below) can reuse it
                // rather than manufacturing a bare empty-body response.
                resp.body().asBytesFuture().whenComplete((bytes, drainErr) -> {
                    if (drainErr == null && bytes.length > 0) {
                        walk.noteNotFoundBody(resp.headers(), bytes);
                    }
                    tryNextSequentialMember(iter, line, headers, requestBytes,
                        isTargetedLocalRead, walk, result, pinArtifactName);
                });
                return;
            }
            // Outbound-breaker fast-fail (X-Pantera-Circuit-Open marker):
            // the member never failed — the HTTP client's breaker refused
            // the call locally. Skip WITHOUT recordFailure(): counting
            // these convicted every member of a group on one upstream 5xx
            // (breaker-cascade RCA, fabricated-evidence amplification).
            if (status.serverError() && !resp.headers()
                .values(UpstreamCircuitOpenException.HEADER).isEmpty()) {
                drainBody(resp.body());
                walk.skippedOpen.set(true);
                walk.noteRetryAfter(parseRetryAfterSeconds(resp));
                recordMemberOutcome(member, "circuit_open", -1L);
                EcsLogger.info("com.auto1.pantera.group")
                    .message("Member's upstream circuit is open, skipping without conviction")
                    .eventCategory("network")
                    .eventAction("group_member_circuit_skip")
                    .field("repository.name", this.group)
                    .field("member.name", member.name())
                    .field("url.path", line.uri().getPath())
                    .field("log.source", "application")
                    .log();
                tryNextSequentialMember(iter, line, headers, requestBytes,
                    isTargetedLocalRead, walk, result, pinArtifactName);
                return;
            }
            // Other 4xx or any 5xx -> record failure, cascade.
            drainBody(resp.body());
            member.recordFailure();
            walk.anyServerError.set(true);
            recordMemberOutcome(member, "error", memberLatency);
            EcsLogger.warn("com.auto1.pantera.group")
                .message("Group member returned non-2xx, trying next")
                .eventCategory("web")
                .eventAction("group_member_fallthrough")
                .eventOutcome("failure")
                .field("repository.name", this.group)
                .field("member.name", member.name())
                .field("http.response.status_code", status.code())
                .field("url.path", line.uri().getPath())
                .field("log.source", "application")
                .log();
            tryNextSequentialMember(iter, line, headers, requestBytes,
                isTargetedLocalRead, walk, result, pinArtifactName);
        });
    }

    /**
     * Cache-only probe of a circuit-open member: the member slice serves a
     * warm cache entry or answers 404 without contacting its upstream
     * (honoured by {@code BaseCachedProxySlice}; slices that predate the
     * header simply do a normal lookup whose fast-fail is then treated as
     * a skip by the marker branch above).
     */
    private CompletableFuture<Response> queryMemberCacheOnly(
        final MemberSlice member,
        final RequestLine line,
        final Headers headers,
        final byte[] requestBytes
    ) {
        final Content memberBody = requestBytes.length > 0
            ? new Content.From(requestBytes)
            : Content.EMPTY;
        final RequestLine rewritten = member.rewritePath(line);
        final Headers memberHeaders = dropFullPathHeader(headers)
            .copy()
            .add(new Header(EcsLoggingSlice.INTERNAL_ROUTING_HEADER, "true"))
            .add(new Header(
                com.auto1.pantera.http.cache.BaseCachedProxySlice.CACHE_ONLY_HEADER, "true"
            ));
        return member.slice().response(rewritten, memberHeaders, memberBody);
    }

    /**
     * Terminal for a walk where at least one member was skipped
     * circuit-open and nobody answered: 503 + Retry-After + the
     * circuit-open marker. Deliberately NOT 404 — 404 asserts absence
     * and gets negative-cached, outliving the outage.
     */
    private Response circuitSkippedTerminal(final WalkState walk, final RequestLine line) {
        final long retry = Math.max(5L, walk.retryAfterHint.get());
        EcsLogger.warn("com.auto1.pantera.group")
            .message(
                "All remaining group members circuit-open — returning 503, Retry-After "
                    + retry + "s"
            )
            .eventCategory("network")
            .eventAction("group_all_members_circuit_open")
            .eventOutcome("failure")
            .field("repository.name", this.group)
            .field("url.path", line.uri().getPath())
            .field("log.source", "application")
            .log();
        return ResponseBuilder.from(RsStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After", Long.toString(retry))
            .header(UpstreamCircuitOpenException.HEADER, "true")
            .textBody("All group members are temporarily unavailable (upstream circuit open)")
            .build();
    }

    /**
     * Parse a delta-seconds {@code Retry-After} from a member response;
     * 0 when absent or unparseable.
     */
    private static long parseRetryAfterSeconds(final Response resp) {
        final java.util.List<String> values = resp.headers().values("Retry-After");
        if (values.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(values.get(0).trim());
        } catch (final NumberFormatException ignored) {
            return 0L;
        }
    }

    /**
     * Mutable state threaded through one sequential member walk:
     * whether a genuine 5xx/exception was observed, whether any member
     * was skipped with its circuit open, and the largest Retry-After
     * hint seen (member block remainder or marker response header).
     */
    private static final class WalkState {
        /** A member genuinely 5xx'd or threw. */
        private final java.util.concurrent.atomic.AtomicBoolean anyServerError =
            new java.util.concurrent.atomic.AtomicBoolean(false);

        /** A member was skipped due to an open circuit (either layer). */
        private final java.util.concurrent.atomic.AtomicBoolean skippedOpen =
            new java.util.concurrent.atomic.AtomicBoolean(false);

        /**
         * A member's 404 was marked non-authoritative (upstream throttle
         * laundered into a 404 via {@link NegativeCache#SKIP_HEADER}). When the
         * whole walk falls through to a terminal 404, the marker is re-attached
         * so the caller does NOT negative-cache a possibly-existing artifact.
         */
        private final java.util.concurrent.atomic.AtomicBoolean anyUnverified =
            new java.util.concurrent.atomic.AtomicBoolean(false);

        /** Largest Retry-After hint observed, seconds. */
        private final java.util.concurrent.atomic.AtomicLong retryAfterHint =
            new java.util.concurrent.atomic.AtomicLong(0L);

        /**
         * First member's own honest 404 body/headers captured during the walk
         * (WS8 Bug B5), reused as the walk's terminal 404 instead of
         * manufacturing an empty one once every member is exhausted.
         */
        private final java.util.concurrent.atomic.AtomicReference<NotFoundSnapshot> notFoundSnapshot =
            new java.util.concurrent.atomic.AtomicReference<>();

        /**
         * Track the largest positive Retry-After hint.
         * @param seconds Hint in seconds; ignored when not positive.
         */
        void noteRetryAfter(final long seconds) {
            if (seconds > 0L) {
                this.retryAfterHint.accumulateAndGet(seconds, Math::max);
            }
        }

        /**
         * Capture the first non-empty 404 body/headers seen during the walk.
         * CAS — only the FIRST member's 404 body is kept; later ones are
         * dropped (they were already drained into {@code bytes} by the caller
         * before this is invoked, so nothing leaks).
         *
         * @param headers Member's response headers.
         * @param bytes Member's response body bytes.
         */
        void noteNotFoundBody(final Headers headers, final byte[] bytes) {
            this.notFoundSnapshot.compareAndSet(null, new NotFoundSnapshot(headers, bytes));
        }

        /**
         * Build a 404 {@link ResponseBuilder} for the walk terminal: reuses
         * the first captured member 404 body/headers (WS8 Bug B5) when one
         * exists, otherwise falls back to the bare empty-body 404. Strips any
         * pre-existing {@link NegativeCache#SKIP_HEADER} from the captured
         * headers so the caller can decide fresh whether to re-attach it.
         *
         * @return A 404 builder, pre-populated with a body when one was captured.
         */
        ResponseBuilder notFoundResponse() {
            final NotFoundSnapshot snapshot = this.notFoundSnapshot.get();
            if (snapshot == null) {
                return ResponseBuilder.notFound();
            }
            final Headers filtered = new Headers(
                snapshot.headers.asList().stream()
                    .filter(h -> !NegativeCache.SKIP_HEADER.equalsIgnoreCase(h.getKey()))
                    .toList()
            );
            return ResponseBuilder.notFound().headers(filtered).body(snapshot.bytes);
        }
    }

    /**
     * Snapshot of a group member's own honest 404 response body, captured so
     * the sequential walk's terminal 404 can reuse it (WS8 Bug B5) instead of
     * manufacturing an empty one after every member has 404'd.
     */
    private static final class NotFoundSnapshot {
        private final Headers headers;
        private final byte[] bytes;

        NotFoundSnapshot(final Headers headers, final byte[] bytes) {
            this.headers = headers;
            this.bytes = bytes; // NOPMD ArrayIsStoredDirectly - private capture; bytes are an already-drained immutable HTTP body
        }
    }

    /**
     * Query a single member directly.
     */
    private CompletableFuture<Response> queryMemberDirect(
        final MemberSlice member,
        final RequestLine line,
        final Headers headers,
        final byte[] requestBytes
    ) {
        final Content memberBody = requestBytes.length > 0
            ? new Content.From(requestBytes)
            : Content.EMPTY;
        final RequestLine rewritten = member.rewritePath(line);
        final Headers memberHeaders = dropFullPathHeader(headers)
            .copy()
            .add(new Header(EcsLoggingSlice.INTERNAL_ROUTING_HEADER, "true"));
        return member.slice().response(rewritten, memberHeaders, memberBody);
    }

    /**
     * Drain response body on per-repo background executor from {@link com.auto1.pantera.http.resilience.RepoBulkhead}.
     */
    private void drainBody(final Content body) {
        this.drainExecutor.execute(() ->
            body.subscribe(new org.reactivestreams.Subscriber<>() {
                @Override
                public void onSubscribe(final org.reactivestreams.Subscription sub) {
                    sub.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(final java.nio.ByteBuffer item) {
                    // Discard
                }

                @Override
                public void onError(final Throwable err) {
                    // Drain failures are not actionable
                }

                @Override
                public void onComplete() {
                    // Body fully consumed
                }
            })
        );
    }

    private static Headers dropFullPathHeader(final Headers headers) {
        return new Headers(
            headers.asList().stream()
                .filter(h -> !"X-FullPath".equalsIgnoreCase(h.getKey()))
                .toList()
        );
    }

    private List<MemberSlice> filterByRoutingRules(final String path) {
        if (this.routingRules.isEmpty()) {
            return this.members;
        }
        final Set<String> ruledMembers = this.routingRules.stream()
            .map(RoutingRule::member)
            .collect(Collectors.toSet());
        final Set<String> matchedMembers = this.routingRules.stream()
            .filter(rule -> rule.matches(path))
            .map(RoutingRule::member)
            .collect(Collectors.toSet());
        return this.members.stream()
            .filter(m -> matchedMembers.contains(m.name())
                || !ruledMembers.contains(m.name()))
            .toList();
    }

    // ---- Metrics helpers ----

    /**
     * Record one member's outcome (and latency when meaningful) on the
     * pantera.group.member.* meters. These went silent when the parallel
     * fanout was replaced by the sequential walk in 2.2.0 — the group
     * dashboard's member panels chart exactly these series.
     *
     * @param member    Member that was queried.
     * @param result    success / not_found / error / circuit_open.
     * @param latencyMs Wall latency; pass a negative value to skip the
     *                  latency timer (skips have no meaningful latency).
     */
    private void recordMemberOutcome(
        final MemberSlice member, final String result, final long latencyMs
    ) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordGroupMemberRequest(this.group, member.name(), result);
            if (latencyMs >= 0L) {
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                    .recordGroupMemberLatency(this.group, member.name(), result, latencyMs);
            }
        }
    }

    private void recordRequestStart() {
        final com.auto1.pantera.metrics.GroupResolverMetrics metrics =
            com.auto1.pantera.metrics.GroupResolverMetrics.instance();
        if (metrics != null) {
            metrics.recordRequest(this.group);
        }
    }

    private void recordMetrics(
        final Response resp, final Throwable err, final long startTime
    ) {
        final long duration = System.currentTimeMillis() - startTime;
        final String result;
        if (err != null) {
            result = "error";
        } else if (resp.status().success()) {
            result = "success";
        } else {
            result = "not_found";
        }
        recordGroupRequest(result, duration);
    }

    private void recordGroupRequest(final String result, final long duration) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordGroupRequest(this.group, result);
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordGroupResolutionDuration(this.group, duration);
        }
    }
}
