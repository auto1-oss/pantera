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
import com.auto1.pantera.cache.NegativeCacheConfig;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.log.EcsLogEvent;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import com.auto1.pantera.http.slice.KeyFromPath;
import com.auto1.pantera.index.ArtifactIndex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.auto1.pantera.http.trace.MdcPropagation;
import org.slf4j.MDC;

/**
 * High-performance group/virtual repository slice.
 * 
 * <p>Drop-in replacement for old batched implementation with:
 * <ul>
 *   <li>Flat member list - nested groups deduplicated at construction</li>
 *   <li>Full parallelism - ALL members queried simultaneously</li>
 *   <li>Resource safety - ALL response bodies consumed (winner + losers)</li>
 *   <li>Race-safe - AtomicBoolean for winner selection</li>
 *   <li>Fast-fail - first successful response wins immediately</li>
 *   <li>Failure isolation - circuit breakers per member</li>
 * </ul>
 * 
 * <p>Performance: 250+ req/s, p50=50ms, p99=300ms, zero leaks
 * 
 * @since 1.18.22
 */
public final class GroupSlice implements Slice {

    /**
     * Background executor for draining non-winning member response bodies.
     * Decoupled from the result path: drain failures and backpressure never affect
     * the winning response delivered to the client.
     *
     * <p>4 threads, bounded queue of 200. When full, new drain tasks are logged and dropped.
     * Each thread is daemon so it does not prevent JVM shutdown.
     */
    private static final ExecutorService DRAIN_EXECUTOR;

    static {
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            4, 4,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200),
            r -> {
                final Thread t = new Thread(r, "group-drain-" + System.identityHashCode(r));
                t.setDaemon(true);
                return t;
            },
            (r, executor) -> EcsLogger.debug("com.auto1.pantera.group")
                .message("Drain queue full, discarding drain task")
                .eventCategory("network")
                .eventAction("body_drain")
                .eventOutcome("skipped")
                .log()
        );
        DRAIN_EXECUTOR = pool;
        EcsLogger.info("com.auto1.pantera.group")
            .message("GroupSlice drain executor initialised (4 threads, queue=200)")
            .eventCategory("configuration")
            .eventAction("group_init")
            .log();
    }

    /**
     * Group repository name.
     */
    private final String group;

    /**
     * Flattened member slices with circuit breakers.
     */
    private final List<MemberSlice> members;

    /**
     * Routing rules for directing paths to specific members.
     */
    private final List<RoutingRule> routingRules;

    /**
     * Optional artifact index for O(1) group lookups.
     */
    private final Optional<ArtifactIndex> artifactIndex;

    /**
     * Repository type for adapter-aware name parsing (e.g., "maven-group", "npm-group").
     * Used by {@link ArtifactNameParser} to extract artifact name from URL path.
     */
    private final String repoType;

    /**
     * Names of members that are proxy repositories.
     * Proxy members must always be queried on index miss because their
     * content is only indexed after being cached.
     */
    private final Set<String> proxyMembers;

    /**
     * Negative cache for proxy fanout results.
     * <p>Key: {@code Key.From("groupName:artifactName")}. Presence = confirmed 404 from all proxies.
     * Prevents thundering herd when many clients request a missing artifact concurrently.
     * TTL-based expiry — stale entries self-correct within the TTL window.
     * <p>Backed by the shared two-tier {@link NegativeCache} (L1 Caffeine + L2 Valkey
     * when configured under {@code meta.caches.group-negative}).  Defaults to the
     * in-memory 5 min TTL, 10K-entry single-tier cache when YAML wiring is absent.
     */
    private final NegativeCache negativeCache;

    /**
     * Request context for enhanced logging (client IP, username, trace ID, package).
     * @param clientIp Client IP address from X-Forwarded-For or X-Real-IP
     * @param username Username from Authorization header (optional)
     * @param traceId Trace ID from MDC for distributed tracing
     * @param packageName Package/artifact being requested
     */
    private record RequestContext(String clientIp, String username, String traceId, String packageName) {
        /**
         * Extract request context from headers and path.
         * @param headers Request headers
         * @param path Request path (package name)
         * @return RequestContext with extracted values
         */
        static RequestContext from(final Headers headers, final String path) {
            final String clientIp = EcsLogEvent.extractClientIp(headers, null);
            // Try MDC first (set by auth middleware after authentication)
            // then fall back to header extraction (Basic auth only)
            // Don't default to "anonymous" - leave null if no user is authenticated
            String username = MDC.get(EcsMdc.USER_NAME);
            if (username == null || username.isEmpty()) {
                username = EcsLogEvent.extractUsername(headers).orElse(null);
            }
            final String traceId = MDC.get(EcsMdc.TRACE_ID);
            return new RequestContext(clientIp, username, traceId != null ? traceId : "none", path);
        }

        /**
         * Add context fields to an EcsLogger builder.
         * NOTE: client.ip, user.name, trace.id, repository.name, package.name
         * are in MDC (set by EcsLoggingSlice / adapter slices).
         * EcsLayout includes all MDC entries — do NOT add them here to avoid duplicates.
         * @param logger Logger builder to enhance
         * @return Enhanced logger builder
         */
        EcsLogger addTo(final EcsLogger logger) {
            return logger;
        }
    }

    /**
     * Constructor (maintains old API for drop-in compatibility).
     *
     * @param resolver Slice resolver/cache
     * @param group Group repository name
     * @param members Member repository names
     * @param port Server port
     */
    public GroupSlice(
        final SliceResolver resolver,
        final String group,
        final List<String> members,
        final int port
    ) {
        this(resolver, group, members, port, 0, 0,
            Collections.emptyList(), Optional.empty(), Collections.emptySet());
    }

    /**
     * Constructor with depth (for API compatibility, depth ignored).
     *
     * @param resolver Slice resolver/cache
     * @param group Group repository name
     * @param members Member repository names
     * @param port Server port
     * @param depth Nesting depth (ignored)
     */
    public GroupSlice(
        final SliceResolver resolver,
        final String group,
        final List<String> members,
        final int port,
        final int depth
    ) {
        this(resolver, group, members, port, depth, 0,
            Collections.emptyList(), Optional.empty(), Collections.emptySet(), "");
    }

    /**
     * Constructor with depth and timeout.
     */
    public GroupSlice(
        final SliceResolver resolver,
        final String group,
        final List<String> members,
        final int port,
        final int depth,
        final long timeoutSeconds
    ) {
        this(resolver, group, members, port, depth, timeoutSeconds,
            Collections.emptyList(), Optional.empty(), Collections.emptySet(), "");
    }

    /**
     * Constructor with depth, timeout, routing rules, and artifact index (backward compatible).
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public GroupSlice(
        final SliceResolver resolver,
        final String group,
        final List<String> members,
        final int port,
        final int depth,
        final long timeoutSeconds,
        final List<RoutingRule> routingRules,
        final Optional<ArtifactIndex> artifactIndex
    ) {
        this(resolver, group, members, port, depth, timeoutSeconds,
            routingRules, artifactIndex, Collections.emptySet(), "");
    }

    /**
     * Backward-compatible constructor without repoType.
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public GroupSlice(
        final SliceResolver resolver,
        final String group,
        final List<String> members,
        final int port,
        final int depth,
        final long timeoutSeconds,
        final List<RoutingRule> routingRules,
        final Optional<ArtifactIndex> artifactIndex,
        final Set<String> proxyMembers
    ) {
        this(resolver, group, members, port, depth, timeoutSeconds,
            routingRules, artifactIndex, proxyMembers, "");
    }

    /**
     * Backward-compatible constructor that builds a default in-memory negative cache.
     * See {@link #defaultNegativeCache(String)} for default parameters
     * (5 min TTL, 10K entries, L1-only).
     *
     * @param resolver Slice resolver/cache
     * @param group Group repository name
     * @param members Member repository names
     * @param port Server port
     * @param depth Nesting depth (ignored)
     * @param timeoutSeconds Timeout for member requests
     * @param routingRules Routing rules for path-based member selection
     * @param artifactIndex Optional artifact index for O(1) lookups
     * @param proxyMembers Names of members that are proxy repositories
     * @param repoType Repository type for name parsing (e.g., "maven-group")
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public GroupSlice(
        final SliceResolver resolver,
        final String group,
        final List<String> members,
        final int port,
        final int depth,
        final long timeoutSeconds,
        final List<RoutingRule> routingRules,
        final Optional<ArtifactIndex> artifactIndex,
        final Set<String> proxyMembers,
        final String repoType
    ) {
        this(
            resolver, group, members, port, depth, timeoutSeconds,
            routingRules, artifactIndex, proxyMembers, repoType,
            defaultNegativeCache(group)
        );
    }

    /**
     * Full constructor with proxy member awareness, repo type, and an explicit
     * {@link NegativeCache}.  Lets callers inject a YAML-configured two-tier cache
     * (L1 Caffeine + L2 Valkey) loaded via
     * {@link NegativeCacheConfig#fromYaml(com.amihaiemil.eoyaml.YamlMapping, String)}.
     *
     * @param resolver Slice resolver/cache
     * @param group Group repository name
     * @param members Member repository names
     * @param port Server port
     * @param depth Nesting depth (ignored)
     * @param timeoutSeconds Timeout for member requests
     * @param routingRules Routing rules for path-based member selection
     * @param artifactIndex Optional artifact index for O(1) lookups
     * @param proxyMembers Names of members that are proxy repositories
     * @param repoType Repository type for name parsing (e.g., "maven-group")
     * @param negativeCache Pre-constructed negative cache (e.g. YAML-driven two-tier)
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public GroupSlice(
        final SliceResolver resolver,
        final String group,
        final List<String> members,
        final int port,
        final int depth,
        final long timeoutSeconds,
        final List<RoutingRule> routingRules,
        final Optional<ArtifactIndex> artifactIndex,
        final Set<String> proxyMembers,
        final String repoType,
        final NegativeCache negativeCache
    ) {
        this.group = Objects.requireNonNull(group, "group");
        this.repoType = repoType != null ? repoType : "";
        this.routingRules = routingRules != null ? routingRules : Collections.emptyList();
        this.artifactIndex = artifactIndex != null ? artifactIndex : Optional.empty();
        this.proxyMembers = proxyMembers != null ? proxyMembers : Collections.emptySet();
        this.negativeCache = negativeCache != null
            ? negativeCache
            : defaultNegativeCache(this.group);

        // Deduplicate members while preserving order
        final List<String> flatMembers = new ArrayList<>(new LinkedHashSet<>(members));

        // Create MemberSlice wrappers with circuit breakers and proxy flags
        this.members = flatMembers.stream()
            .map(name -> new MemberSlice(
                name,
                resolver.slice(new Key.From(name), port, 0),
                this.proxyMembers.contains(name)
            ))
            .toList();

        EcsLogger.debug("com.auto1.pantera.group")
            .message("GroupSlice initialized with members (" + this.members.size() + " unique, " + members.size() + " total, " + this.proxyMembers.size() + " proxies)")
            .eventCategory("configuration")
            .eventAction("group_init")
            .log();
    }

    /**
     * Build the default in-memory-only negative cache used when no YAML wiring
     * is supplied.  Matches the pre-YAML behaviour exactly: 5 min TTL, 10K entries,
     * no Valkey.  Kept as a static helper so tests and callers without settings
     * access still get a working cache.
     *
     * @param group Group name used as the {@code repoName} for cache-key isolation
     * @return L1-only negative cache (5 min TTL, 10K entries)
     */
    private static NegativeCache defaultNegativeCache(final String group) {
        final NegativeCacheConfig config = new NegativeCacheConfig(
            java.time.Duration.ofMinutes(5),
            10_000,
            false,
            NegativeCacheConfig.DEFAULT_L1_MAX_SIZE,
            NegativeCacheConfig.DEFAULT_L1_TTL,
            NegativeCacheConfig.DEFAULT_L2_MAX_SIZE,
            NegativeCacheConfig.DEFAULT_L2_TTL
        );
        return new NegativeCache(
            "group-negative",
            group != null ? group : "default",
            config
        );
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String method = line.method().value();
        final String path = line.uri().getPath();

        // Allow read operations (GET, HEAD)
        // Allow POST for npm audit endpoints (/-/npm/v1/security/*)
        final boolean isReadOperation = "GET".equals(method) || "HEAD".equals(method);
        final boolean isNpmAudit = "POST".equals(method) && path.contains("/-/npm/v1/security/");

        if (!isReadOperation && !isNpmAudit) {
            return CompletableFuture.completedFuture(
                ResponseBuilder.methodNotAllowed().build()
            );
        }

        if (this.members.isEmpty()) {
            return CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            );
        }

        // Extract request context for enhanced logging
        final RequestContext ctx = RequestContext.from(headers, path);

        recordRequestStart();
        final long requestStartTime = System.currentTimeMillis();

        // ---- Path 1: No index configured OR unparseable URL → full two-phase fanout ----
        if (this.artifactIndex.isEmpty()) {
            return fullTwoPhaseFanout(line, headers, body, ctx)
                .whenComplete(MdcPropagation.withMdcBiConsumer(
                    (resp, err) -> recordMetrics(resp, err, requestStartTime)
                ));
        }
        final ArtifactIndex idx = this.artifactIndex.get();
        final Optional<String> parsedName =
            ArtifactNameParser.parse(this.repoType, path);
        if (parsedName.isEmpty()) {
            // Metadata endpoint / root path / unknown adapter → safety net
            EcsLogger.debug("com.auto1.pantera.group")
                .message("Name unparseable, using full two-phase fanout")
                .eventCategory("web")
                .eventAction("group_direct_fanout")
                .field("url.path", path)
                .log();
            return fullTwoPhaseFanout(line, headers, body, ctx)
                .whenComplete(MdcPropagation.withMdcBiConsumer(
                    (resp, err) -> recordMetrics(resp, err, requestStartTime)
                ));
        }

        // ---- Path 2: Query index ----
        return idx.locateByName(parsedName.get())
            .thenCompose(MdcPropagation.withMdc(optRepos -> {
                if (optRepos.isEmpty()) {
                    // DB error → full two-phase fanout safety net
                    EcsLogger.warn("com.auto1.pantera.group")
                        .message("Index DB error, using full fanout safety net")
                        .eventCategory("database")
                        .eventAction("group_index_error")
                        .eventOutcome("failure")
                        .field("url.path", path)
                        .log();
                    return fullTwoPhaseFanout(line, headers, body, ctx);
                }
                final List<String> repos = optRepos.get();
                if (repos.isEmpty()) {
                    // Confirmed miss → proxy-only fanout
                    return proxyOnlyFanout(line, headers, body, ctx, parsedName.get());
                }
                // ---- Path 3: Index hit → targeted local read ----
                return targetedLocalRead(repos, line, headers, body, ctx);
            }))
            .whenComplete(MdcPropagation.withMdcBiConsumer(
                (resp, err) -> recordMetrics(resp, err, requestStartTime)
            ));
    }

    private void recordMetrics(
        final Response resp, final Throwable err, final long startTime
    ) {
        final long duration = System.currentTimeMillis() - startTime;
        if (err != null) {
            recordGroupRequest("error", duration);
        } else if (resp.status().success()) {
            recordGroupRequest("success", duration);
        } else {
            recordGroupRequest("not_found", duration);
        }
    }

    /**
     * Path 3: Index hit → query the member(s) directly.
     *
     * <p>No circuit breaker check.  No fallback fanout on 5xx.  Artifact bytes
     * are local (hosted upload or proxy cache) — if the targeted member fails,
     * no one else has them, so we surface a genuine 500 to the client.
     *
     * <p>404 is treated as authoritative (stale index scenario) and returned
     * as-is — we do NOT fall back to a proxy, because the index says the
     * artifact lives on this member.
     */
    private CompletableFuture<Response> targetedLocalRead(
        final List<String> repos,
        final RequestLine line, final Headers headers, final Content body,
        final RequestContext ctx
    ) {
        final Set<String> wanted = new HashSet<>(repos);
        final List<MemberSlice> targeted = this.members.stream()
            .filter(m -> wanted.contains(m.name()))
            .toList();
        if (targeted.isEmpty()) {
            EcsLogger.warn("com.auto1.pantera.group")
                .message("Index hit but no matching member in flattened list — safety net")
                .eventCategory("web")
                .eventAction("group_index_orphan")
                .eventOutcome("failure")
                .field("url.path", line.uri().getPath())
                .log();
            return fullTwoPhaseFanout(line, headers, body, ctx);
        }
        EcsLogger.debug("com.auto1.pantera.group")
            .message("Index hit via name: targeting "
                + targeted.size() + " member(s)")
            .eventCategory("web")
            .eventAction("group_index_hit")
            .field("url.path", line.uri().getPath())
            .log();
        return queryTargetedMembers(targeted, line, headers, body, ctx, true);
    }

    /**
     * Path 4: Index confirmed miss → proxy-only fanout.
     *
     * <p>Hosted repos are fully indexed, so absence from the index means
     * absence from hosted — we only query proxy members (whose content is
     * indexed lazily on first cache).
     */
    private CompletableFuture<Response> proxyOnlyFanout(
        final RequestLine line, final Headers headers, final Content body,
        final RequestContext ctx, final String artifactName
    ) {
        final Key cacheKey = new Key.From(this.group + ":" + artifactName);
        if (this.negativeCache.isNotFound(cacheKey)) {
            EcsLogger.debug("com.auto1.pantera.group")
                .message("Negative cache hit — returning 404 without fanout")
                .eventCategory("database")
                .eventAction("group_negative_cache_hit")
                .field("url.path", line.uri().getPath())
                .log();
            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        }
        final List<MemberSlice> proxyOnly = this.members.stream()
            .filter(MemberSlice::isProxy)
            .toList();
        if (proxyOnly.isEmpty()) {
            EcsLogger.debug("com.auto1.pantera.group")
                .message("Index miss with no proxy members, returning 404"
                    + " (name: " + artifactName + ")")
                .eventCategory("web")
                .eventAction("group_index_miss")
                .field("url.path", line.uri().getPath())
                .log();
            return CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            );
        }
        EcsLogger.debug("com.auto1.pantera.group")
            .message("Index miss: fanning out to "
                + proxyOnly.size() + " proxy member(s) only"
                + " (name: " + artifactName + ")")
            .eventCategory("network")
            .eventAction("group_index_miss")
            .field("url.path", line.uri().getPath())
            .log();
        return queryTargetedMembers(proxyOnly, line, headers, body, ctx, false)
            .thenApply(MdcPropagation.withMdcFunction(resp -> {
                if (resp.status() == RsStatus.NOT_FOUND) {
                    this.negativeCache.cacheNotFound(cacheKey);
                    EcsLogger.debug("com.auto1.pantera.group")
                        .message("Cached negative result for artifact")
                        .eventCategory("database")
                        .eventAction("group_negative_cache_populate")
                        .log();
                }
                return resp;
            }));
    }

    /**
     * Path 5: Full two-phase fanout — hosted first, then proxy.
     *
     * <p>Used as a safety net when the artifact name could not be parsed or
     * the index DB returned an error.  Applies routing rules.
     */
    private CompletableFuture<Response> fullTwoPhaseFanout(
        final RequestLine line, final Headers headers, final Content body,
        final RequestContext ctx
    ) {
        final List<MemberSlice> eligible = this.filterByRoutingRules(line.uri().getPath());
        if (eligible.isEmpty()) {
            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        }
        return queryHostedFirstThenProxy(eligible, line, headers, body, ctx);
    }

    /**
     * Query a list of members in parallel.
     *
     * <p>When {@code isTargetedLocalRead} is {@code true} (path 3 — index hit),
     * the circuit breaker is bypassed and a 5xx from any member surfaces as a
     * {@code 500 Internal Error} to the client (no fallback bytes elsewhere).
     *
     * <p>When {@code false} (path 4 / path 5 — fanout), circuit-open members are
     * skipped and a 5xx from every member surfaces as {@code 502 Bad Gateway}.
     */
    private CompletableFuture<Response> queryTargetedMembers(
        final List<MemberSlice> targeted,
        final RequestLine line,
        final Headers headers,
        final Content body,
        final RequestContext ctx,
        final boolean isTargetedLocalRead
    ) {
        final long startTime = System.currentTimeMillis();
        final Key pathKey = new KeyFromPath(line.uri().getPath());

        return body.asBytesFuture().thenCompose(requestBytes -> {
            final CompletableFuture<Response> result = new CompletableFuture<>();
            final AtomicBoolean completed = new AtomicBoolean(false);
            final AtomicInteger pending = new AtomicInteger(targeted.size());
            final AtomicBoolean anyServerError = new AtomicBoolean(false);
            final List<CompletableFuture<Response>> memberFutures =
                new ArrayList<>(targeted.size());

            for (MemberSlice member : targeted) {
                // Circuit breaker applies only on the fanout path.  On the
                // targeted local read path, the index says the bytes live on
                // this exact member — we MUST attempt the read and surface any
                // failure instead of masking it with a "circuit open" skip.
                if (!isTargetedLocalRead && member.isCircuitOpen()) {
                    ctx.addTo(EcsLogger.warn("com.auto1.pantera.group")
                        .message("Member circuit OPEN, skipping: " + member.name())
                        .eventCategory("network")
                        .eventAction("group_query")
                        .eventOutcome("skipped")
                        .field("destination.address", member.name()))
                        .log();
                    completeIfAllExhausted(
                        pending, completed, anyServerError, result, ctx, isTargetedLocalRead
                    );
                    continue;
                }
                final CompletableFuture<Response> memberFuture =
                    queryMemberDirect(member, line, headers, requestBytes, ctx);
                memberFutures.add(memberFuture);
                memberFuture.whenComplete((resp, err) -> {
                    if (err != null) {
                        handleMemberFailure(
                            member, err, completed, pending, anyServerError, result, ctx, isTargetedLocalRead
                        );
                    } else {
                        handleMemberResponse(
                            member, resp, completed, pending, anyServerError, result, startTime, pathKey, ctx, isTargetedLocalRead
                        );
                    }
                });
            }

            result.whenComplete((resp, err) -> {
                for (CompletableFuture<Response> future : memberFutures) {
                    if (!future.isDone()) {
                        future.cancel(true);
                    }
                }
            });

            return result;
        });
    }

    /**
     * Full two-phase fanout: query hosted members first; if all miss,
     * cascade to proxy members.
     *
     * <p>This is the safety-net path (unparseable URL, DB error, or index
     * returned names not present in the flattened member list).  Running
     * hosted first prevents a proxy from "claiming" a package name that
     * exists on the upstream registry (e.g. PyPI.org) but has zero published
     * files, when a hosted member has a locally-uploaded version with real
     * files.
     *
     * <p>If all members are the same type (all hosted or all proxy), this
     * degrades to a single parallel query.
     *
     * <p>Always runs on the fanout path ({@code isTargetedLocalRead=false}):
     * circuit-open members are skipped and any 5xx surfaces as 502.
     */
    private CompletableFuture<Response> queryHostedFirstThenProxy(
        final List<MemberSlice> targeted,
        final RequestLine line,
        final Headers headers,
        final Content body,
        final RequestContext ctx
    ) {
        final List<MemberSlice> hosted = targeted.stream()
            .filter(m -> !m.isProxy())
            .toList();
        final List<MemberSlice> proxy = targeted.stream()
            .filter(MemberSlice::isProxy)
            .toList();
        // No partition possible — use standard parallel query
        if (hosted.isEmpty() || proxy.isEmpty()) {
            return queryTargetedMembers(targeted, line, headers, body, ctx, false);
        }
        // Try hosted first; fall to proxy only if hosted yields no 200
        return queryTargetedMembers(hosted, line, headers, body, ctx, false)
            .thenCompose(MdcPropagation.withMdc(resp -> {
                if (resp.status().success()) {
                    return CompletableFuture.completedFuture(resp);
                }
                // Hosted members didn't have it — try proxy members
                EcsLogger.debug("com.auto1.pantera.group")
                    .message("Hosted miss, cascading to "
                        + proxy.size() + " proxy member(s)")
                    .eventCategory("network")
                    .eventAction("group_cascade_to_proxy")
                    .log();
                return queryTargetedMembers(proxy, line, headers, body, ctx, false);
            }));
    }

    /**
     * Query a single member directly (no negative cache check).
     * Used for index-targeted queries where we already know the member has the artifact.
     *
     * <p>Adds {@value EcsLoggingSlice#INTERNAL_ROUTING_HEADER} to the member request so
     * that the member's {@code EcsLoggingSlice} suppresses its access log entry.
     * The header is group-internal and does NOT leak to upstream remotes because proxy
     * slice implementations forward {@code Headers.EMPTY} to their upstream clients.
     */
    private CompletableFuture<Response> queryMemberDirect(
        final MemberSlice member,
        final RequestLine line,
        final Headers headers,
        final byte[] requestBytes,
        final RequestContext ctx
    ) {

        final Content memberBody = requestBytes.length > 0
            ? new Content.From(requestBytes)
            : Content.EMPTY;

        final RequestLine rewritten = member.rewritePath(line);
        final Headers memberHeaders = dropFullPathHeader(headers)
            .copy()
            .add(new Header(EcsLoggingSlice.INTERNAL_ROUTING_HEADER, "true"));

        return member.slice().response(
            rewritten,
            memberHeaders,
            memberBody
        );
    }

    /**
     * Handle a response from a member.
     *
     * <p>See {@link #completeIfAllExhausted} for the final status code policy
     * when all members are exhausted without a winner.
     */
    private void handleMemberResponse(
        final MemberSlice member,
        final Response resp,
        final AtomicBoolean completed,
        final AtomicInteger pending,
        final AtomicBoolean anyServerError,
        final CompletableFuture<Response> result,
        final long startTime,
        final Key pathKey,
        final RequestContext ctx,
        final boolean isTargetedLocalRead
    ) {
        final RsStatus status = resp.status();

        // Success: 200 OK, 206 Partial Content, or 304 Not Modified
        if (status == RsStatus.OK || status == RsStatus.PARTIAL_CONTENT || status == RsStatus.NOT_MODIFIED) {
            if (completed.compareAndSet(false, true)) {
                final long latency = System.currentTimeMillis() - startTime;
                // Only log slow responses
                if (latency > 1000) {
                    ctx.addTo(EcsLogger.warn("com.auto1.pantera.group")
                        .message("Slow member response: " + member.name())
                        .eventCategory("network")
                        .eventAction("group_query")
                        .eventOutcome("success")
                        .field("destination.address", member.name())
                        .duration(latency))
                        .log();
                }
                member.recordSuccess();
                recordSuccess(member.name(), latency);
                recordGroupMemberRequest(member.name(), "success");
                recordGroupMemberLatency(member.name(), "success", latency);
                result.complete(resp);
            } else {
                ctx.addTo(EcsLogger.debug("com.auto1.pantera.group")
                    .message("Member '" + member.name() + "' returned success but another member already won")
                    .eventCategory("network")
                    .eventAction("group_query")
                    .field("destination.address", member.name())
                    .field("http.response.status_code", status.code()))
                    .log();
                drainBody(member.name(), resp.body());
            }
            // Always decrement the global pending counter regardless of win/lose.
            // Two-phase completion: 502/404 only fires when ALL futures have reported
            // and !completed — prevents fast-failing proxies from racing ahead of a
            // slow-but-cached local member and completing the result with 502.
            completeIfAllExhausted(pending, completed, anyServerError, result, ctx, isTargetedLocalRead);
        } else if (status == RsStatus.FORBIDDEN) {
            // Blocked/cooldown: propagate 403 to client (artifact exists but is blocked)
            if (completed.compareAndSet(false, true)) {
                ctx.addTo(EcsLogger.debug("com.auto1.pantera.group")
                    .message("Member '" + member.name() + "' returned FORBIDDEN (cooldown/blocked)")
                    .eventCategory("network")
                    .eventAction("group_query")
                    .eventOutcome("success")
                    .field("destination.address", member.name())
                    .field("http.response.status_code", 403))
                    .log();
                member.recordSuccess(); // Not a failure - valid response
                result.complete(resp);
            } else {
                drainBody(member.name(), resp.body());
            }
            // Always decrement (same two-phase logic as 2xx success above)
            completeIfAllExhausted(pending, completed, anyServerError, result, ctx, isTargetedLocalRead);
        } else if (status == RsStatus.NOT_FOUND) {
            // 404: try next member — individual miss is DEBUG noise, not actionable
            ctx.addTo(EcsLogger.debug("com.auto1.pantera.group")
                .message("Group member " + member.name() + " does not have " + pathKey.string())
                .eventCategory("web")
                .eventAction("group_fanout_miss")
                .eventOutcome("success")
                .field("destination.address", member.name())
                .field("url.path", pathKey.string()))
                .log();
            recordGroupMemberRequest(member.name(), "not_found");
            drainBody(member.name(), resp.body());
            completeIfAllExhausted(pending, completed, anyServerError, result, ctx, isTargetedLocalRead);
        } else {
            // Server errors (500, 503, etc.): record failure, try next member
            ctx.addTo(EcsLogger.warn("com.auto1.pantera.group")
                .message("Member '" + member.name() + "' returned error status (" + (pending.get() - 1) + " pending)")
                .eventCategory("network")
                .eventAction("group_query")
                .eventOutcome("failure")
                .field("event.reason", "HTTP " + status.code() + " from member")
                .field("destination.address", member.name())
                .field("http.response.status_code", status.code()))
                .log();
            member.recordFailure();
            anyServerError.set(true);
            recordGroupMemberRequest(member.name(), "error");
            drainBody(member.name(), resp.body());
            completeIfAllExhausted(pending, completed, anyServerError, result, ctx, isTargetedLocalRead);
        }
    }

    /**
     * Handle member query failure (exception thrown).
     */
    private void handleMemberFailure(
        final MemberSlice member,
        final Throwable err,
        final AtomicBoolean completed,
        final AtomicInteger pending,
        final AtomicBoolean anyServerError,
        final CompletableFuture<Response> result,
        final RequestContext ctx,
        final boolean isTargetedLocalRead
    ) {
        if (err instanceof CancellationException) {
            // Another member won the race and cancelled this future.
            // This is not a real upstream failure — do not trip the circuit breaker.
            completeIfAllExhausted(pending, completed, anyServerError, result, ctx, isTargetedLocalRead);
            return;
        }
        ctx.addTo(EcsLogger.warn("com.auto1.pantera.group")
            .message("Member query failed: " + member.name())
            .eventCategory("network")
            .eventAction("group_query")
            .eventOutcome("failure")
            .error(err)
            .field("event.reason", "Member request threw " + err.getClass().getSimpleName())
            .field("destination.address", member.name()))
            .log();
        member.recordFailure();
        anyServerError.set(true);
        completeIfAllExhausted(pending, completed, anyServerError, result, ctx, isTargetedLocalRead);
    }

    /**
     * Complete the result future if all members have been exhausted.
     *
     * <p>Final status code policy:
     * <ul>
     *   <li><b>Targeted local read</b> (index hit) + any 5xx → {@code 500 Internal Error}
     *       — artifact bytes are local, nobody else has them, this is a real local failure.</li>
     *   <li><b>Fanout</b> (miss / DB error / unparseable) + any 5xx → {@code 502 Bad Gateway}
     *       — we ARE proxying, so a bad upstream is correctly a bad gateway.</li>
     *   <li>All members cleanly 404 → {@code 404 Not Found}.</li>
     * </ul>
     *
     * <p>Note: 503 is <em>no longer</em> emitted by group resolution.  The old
     * {@code anyCircuitOpen → 503} path has been removed; circuit-open skips
     * on the fanout path simply cause the request to fall through to other
     * members or, if all are skipped/miss, produce a plain 404.
     */
    private void completeIfAllExhausted(
        final AtomicInteger pending,
        final AtomicBoolean completed,
        final AtomicBoolean anyServerError,
        final CompletableFuture<Response> result,
        final RequestContext ctx,
        final boolean isTargetedLocalRead
    ) {
        if (pending.decrementAndGet() == 0 && !completed.get()) {
            if (anyServerError.get()) {
                if (isTargetedLocalRead) {
                    ctx.addTo(EcsLogger.warn("com.auto1.pantera.group")
                        .message("Targeted member failed on index hit, returning 500")
                        .eventCategory("web")
                        .eventAction("group_query")
                        .eventOutcome("failure")
                        .field("event.reason", "Index-hit member failed; bytes are local but read errored — no fallback")
                        .field("http.response.status_code", 500))
                        .log();
                    result.complete(ResponseBuilder.internalError()
                        .textBody("Targeted member read failed").build());
                } else {
                    ctx.addTo(EcsLogger.warn("com.auto1.pantera.group")
                        .message("All members exhausted with upstream errors, returning 502")
                        .eventCategory("network")
                        .eventAction("group_query")
                        .eventOutcome("failure")
                        .field("event.reason", "All proxy upstreams returned 5xx or threw")
                        .field("http.response.status_code", 502))
                        .log();
                    result.complete(ResponseBuilder.badGateway()
                        .textBody("All upstream members failed").build());
                }
            } else {
                ctx.addTo(EcsLogger.warn("com.auto1.pantera.group")
                    .message("Artifact not found in any group member: " + ctx.packageName())
                    .eventCategory("web")
                    .eventAction("group_lookup_miss")
                    .eventOutcome("failure"))
                    .log();
                recordNotFound();
                result.complete(ResponseBuilder.notFound().build());
            }
        }
    }

    /**
     * Drain response body on the background drain executor to prevent connection leak.
     *
     * <p>Fully decoupled from the result path: submitted to {@link #DRAIN_EXECUTOR} and
     * returns immediately. Drain failures and backpressure never block or affect the
     * winning response delivered to the client. Uses streaming discard to avoid OOM on
     * large responses (e.g., npm typescript ~30MB).
     */
    private void drainBody(final String memberName, final Content body) {
        final String group = this.group;
        DRAIN_EXECUTOR.execute(() ->
            body.subscribe(new org.reactivestreams.Subscriber<>() {
                @Override
                public void onSubscribe(final org.reactivestreams.Subscription sub) {
                    sub.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(final java.nio.ByteBuffer item) {
                    // Discard bytes - do not accumulate
                }

                @Override
                public void onError(final Throwable err) {
                    EcsLogger.debug("com.auto1.pantera.group")
                        .message("Failed to drain response body: " + memberName)
                        .eventCategory("network")
                        .eventAction("body_drain")
                        .eventOutcome("failure")
                        .field("destination.address", memberName)
                        .field("error.message", err.getMessage())
                        .log();
                }

                @Override
                public void onComplete() {
                    // Body fully consumed - connection returned to pool
                }
            })
        );
    }

    private static Headers dropFullPathHeader(final Headers headers) {
        return new Headers(
            headers.asList().stream()
                .filter(h -> !h.getKey().equalsIgnoreCase("X-FullPath"))
                .toList()
        );
    }

    // Metrics helpers
    
    private void recordRequestStart() {
        final com.auto1.pantera.metrics.GroupSliceMetrics metrics =
            com.auto1.pantera.metrics.GroupSliceMetrics.instance();
        if (metrics != null) {
            metrics.recordRequest(this.group);
        }
    }
    
    private void recordSuccess(final String member, final long latency) {
        final com.auto1.pantera.metrics.GroupSliceMetrics metrics =
            com.auto1.pantera.metrics.GroupSliceMetrics.instance();
        if (metrics != null) {
            metrics.recordSuccess(this.group, member, latency);
            metrics.recordBatch(this.group, this.members.size(), latency);
        }
    }
    
    private void recordNotFound() {
        final com.auto1.pantera.metrics.GroupSliceMetrics metrics =
            com.auto1.pantera.metrics.GroupSliceMetrics.instance();
        if (metrics != null) {
            metrics.recordNotFound(this.group);
        }
    }

    private void recordGroupRequest(final String result, final long duration) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordGroupRequest(this.group, result);
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordGroupResolutionDuration(this.group, duration);
        }
    }

    private void recordGroupMemberRequest(final String memberName, final String result) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordGroupMemberRequest(this.group, memberName, result);
        }
    }

    private void recordGroupMemberLatency(final String memberName, final String result, final long latencyMs) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordGroupMemberLatency(this.group, memberName, result, latencyMs);
        }
    }

    /**
     * Filter members by routing rules for the given path.
     * If no routing rules are configured, all members are returned.
     * Members with matching routing rules are included. Members with
     * no routing rules also participate (default: include).
     *
     * @param path Request path
     * @return Filtered list of members to query
     */
    private List<MemberSlice> filterByRoutingRules(final String path) {
        if (this.routingRules.isEmpty()) {
            return this.members;
        }
        // Collect members that have explicit routing rules
        final Set<String> ruledMembers = this.routingRules.stream()
            .map(RoutingRule::member)
            .collect(Collectors.toSet());
        // Collect members whose rules match this path
        final Set<String> matchedMembers = this.routingRules.stream()
            .filter(rule -> rule.matches(path))
            .map(RoutingRule::member)
            .collect(Collectors.toSet());
        // Include: members with matching rules + members with no rules (default include)
        return this.members.stream()
            .filter(m -> matchedMembers.contains(m.name())
                || !ruledMembers.contains(m.name()))
            .toList();
    }
}
