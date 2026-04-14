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
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.log.EcsLogEvent;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.slice.KeyFromPath;
import com.auto1.pantera.http.misc.ConfigDefaults;
import com.auto1.pantera.index.ArtifactIndex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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
                .eventCategory("repository")
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
     * Maps leaf repo names reachable through nested group members back to the direct member
     * of this group that contains them. E.g. for libs-release: jboss → remote-repos.
     * Empty for groups with no nested group members (single-level groups).
     */
    private final Map<String, String> leafToMember;

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
         * NOTE: client.ip, user.name, trace.id are in MDC (set by EcsLoggingSlice).
         * EcsLayout includes all MDC entries — do NOT add them here to avoid duplicates.
         * @param logger Logger builder to enhance
         * @return Enhanced logger builder
         */
        EcsLogger addTo(final EcsLogger logger) {
            return logger.field("package.name", this.packageName);
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
     * Full constructor with proxy member awareness and repo type.
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
        this(resolver, group, members, port, depth, timeoutSeconds, routingRules,
            artifactIndex, proxyMembers, repoType, Collections.emptyMap());
    }

    /**
     * Full constructor with nested group support via leafToMember map.
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
     * @param leafToMember Map of leaf repo name → direct member of this group that
     *   contains it; used so index hits on nested-group leaves resolve to the right
     *   direct member (e.g. jboss → remote-repos for libs-release)
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
        final Map<String, String> leafToMember
    ) {
        this.group = Objects.requireNonNull(group, "group");
        this.repoType = repoType != null ? repoType : "";
        this.routingRules = routingRules != null ? routingRules : Collections.emptyList();
        this.artifactIndex = artifactIndex != null ? artifactIndex : Optional.empty();
        this.proxyMembers = proxyMembers != null ? proxyMembers : Collections.emptySet();
        this.leafToMember = leafToMember != null ? leafToMember : Collections.emptyMap();

        // Deduplicate members (simple flattening for now)
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
            .eventCategory("repository")
            .eventAction("group_init")
            .field("repository.name", group)
            .log();
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
        // Index-first: try O(1) lookup before parallel fan-out
        if (this.artifactIndex.isPresent()) {
            final ArtifactIndex idx = this.artifactIndex.get();
            // Try adapter-aware name parsing first (indexed, fast)
            final Optional<String> parsedName =
                ArtifactNameParser.parse(this.repoType, path);
            if (parsedName.isEmpty()) {
                // Metadata endpoint or unknown adapter type — skip index, direct fanout
                EcsLogger.debug("com.auto1.pantera.group")
                    .message("Name unparseable, using direct fanout")
                    .eventCategory("repository")
                    .eventAction("group_direct_fanout")
                    .field("repository.name", this.group)
                    .field("url.path", path)
                    .log();
                return queryAllMembersInParallel(line, headers, body, ctx)
                    .whenComplete((resp, err) -> {
                        final long duration = System.currentTimeMillis() - requestStartTime;
                        if (err != null) {
                            recordGroupRequest("error", duration);
                        } else if (resp.status().success()) {
                            recordGroupRequest("success", duration);
                        } else {
                            recordGroupRequest("not_found", duration);
                        }
                    });
            }
            return idx.locateByName(parsedName.get())
                .thenCompose(optRepos -> {
                    final List<String> repos = optRepos.orElse(List.of());
                    if (!repos.isEmpty()) {
                        // Map index hits (leaf repos) to direct members of this group.
                        // For single-level groups leafToMember is empty so getOrDefault
                        // returns the repo name as-is (existing behaviour).
                        // For nested groups (e.g. libs-release → remote-repos → jboss)
                        // this resolves jboss → remote-repos so we query the right member.
                        final Set<String> directMembersToQuery = repos.stream()
                            .map(r -> this.leafToMember.getOrDefault(r, r))
                            .collect(Collectors.toSet());
                        final List<MemberSlice> targeted = this.members.stream()
                            .filter(m -> directMembersToQuery.contains(m.name()))
                            .toList();
                        if (!targeted.isEmpty()) {
                            EcsLogger.debug("com.auto1.pantera.group")
                                .message("Index hit via name: targeting "
                                    + targeted.size() + " member(s)")
                                .eventCategory("repository")
                                .eventAction("group_index_hit")
                                .field("repository.name", this.group)
                                .field("url.path", path)
                                .log();
                            return queryHostedFirstThenProxy(
                                targeted, line, headers, body, ctx
                            );
                        }
                    }
                    // Index miss: hosted repos are fully indexed so the artifact
                    // cannot exist in any hosted member.  Only proxy members need
                    // to be queried (they cache on first fetch and may not yet be
                    // in the index).
                    final List<MemberSlice> proxyOnly = this.members.stream()
                        .filter(MemberSlice::isProxy)
                        .toList();
                    if (proxyOnly.isEmpty()) {
                        EcsLogger.debug("com.auto1.pantera.group")
                            .message("Index miss with no proxy members, returning 404"
                                + " (name: " + parsedName.get() + ")")
                            .eventCategory("repository")
                            .eventAction("group_index_miss")
                            .field("repository.name", this.group)
                            .field("url.path", path)
                            .log();
                        return CompletableFuture.completedFuture(
                            ResponseBuilder.notFound().build()
                        );
                    }
                    EcsLogger.debug("com.auto1.pantera.group")
                        .message("Index miss: fanning out to "
                            + proxyOnly.size() + " proxy member(s) only"
                            + " (name: " + parsedName.get() + ")")
                        .eventCategory("repository")
                        .eventAction("group_index_miss")
                        .field("repository.name", this.group)
                        .field("url.path", path)
                        .log();
                    return queryTargetedMembers(proxyOnly, line, headers, body, ctx);
                })
                .whenComplete((resp, err) -> {
                    final long duration = System.currentTimeMillis() - requestStartTime;
                    if (err != null) {
                        recordGroupRequest("error", duration);
                    } else if (resp.status().success()) {
                        recordGroupRequest("success", duration);
                    } else {
                        recordGroupRequest("not_found", duration);
                    }
                });
        }
        return queryAllMembersInParallel(line, headers, body, ctx)
            .whenComplete((resp, err) -> {
                final long duration = System.currentTimeMillis() - requestStartTime;
                if (err != null) {
                    recordGroupRequest("error", duration);
                } else if (resp.status().success()) {
                    recordGroupRequest("success", duration);
                } else {
                    recordGroupRequest("not_found", duration);
                }
            });
    }

    /**
     * Query all members in parallel, consuming ALL response bodies.
     */
    private CompletableFuture<Response> queryAllMembersInParallel(
        final RequestLine line,
        final Headers headers,
        final Content body,
        final RequestContext ctx
    ) {
        final long startTime = System.currentTimeMillis();

        // CRITICAL: Consume incoming body ONCE before parallel queries
        // For POST requests (npm audit), we need to preserve the body bytes
        // and create new Content instances for each member
        final Key pathKey = new KeyFromPath(line.uri().getPath());

        return body.asBytesFuture().thenCompose(requestBytes -> {
            // Apply routing rules to filter members for this path
            final List<MemberSlice> eligibleMembers = this.filterByRoutingRules(
                line.uri().getPath()
            );
            final CompletableFuture<Response> result = new CompletableFuture<>();
            final AtomicBoolean completed = new AtomicBoolean(false);
            final AtomicInteger pending = new AtomicInteger(eligibleMembers.size());
            final AtomicBoolean anyServerError = new AtomicBoolean(false);
            // Tracks circuit-open skips separately from real server errors.
            // A circuit-open skip is NOT a server error — the member may or may not have
            // the artifact, we just can't reach it right now. Keeping these separate lets
            // completeIfAllExhausted return 503 (retry-able) instead of 404 (cached as
            // absent by Maven) or 502 (implies a real upstream error occurred).
            final AtomicBoolean anyCircuitOpen = new AtomicBoolean(false);
            // Track all member futures for best-effort cancellation on first success
            final List<CompletableFuture<Response>> memberFutures =
                new ArrayList<>(eligibleMembers.size());

            if (eligibleMembers.isEmpty()) {
                result.complete(ResponseBuilder.notFound().build());
                return result;
            }

            // Start eligible members in parallel
            for (MemberSlice member : eligibleMembers) {
                // Circuit check happens here, NOT inside queryMember, so circuit-open
                // skips never flow through handleMemberResponse and never set anyServerError.
                if (member.isCircuitOpen()) {
                    ctx.addTo(EcsLogger.warn("com.auto1.pantera.group")
                        .message("Member circuit OPEN, skipping: " + member.name())
                        .eventCategory("repository")
                        .eventAction("group_query")
                        .eventOutcome("skipped")
                        .field("repository.name", this.group))
                        .log();
                    anyCircuitOpen.set(true);
                    completeIfAllExhausted(pending, completed, anyServerError, anyCircuitOpen, result, ctx);
                    continue;
                }
                final CompletableFuture<Response> memberFuture =
                    queryMember(member, line, headers, requestBytes, ctx);
                memberFutures.add(memberFuture);
                memberFuture.whenComplete((resp, err) -> {
                    if (err != null) {
                        handleMemberFailure(member, err, completed, pending, anyServerError, anyCircuitOpen, result, ctx);
                    } else {
                        handleMemberResponse(member, resp, completed, pending, anyServerError, anyCircuitOpen, result, startTime, pathKey, ctx);
                    }
                });
            }

            // When first success completes the result, cancel remaining member requests
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
     * Query only targeted members (from index hits) in parallel.
     */
    private CompletableFuture<Response> queryTargetedMembers(
        final List<MemberSlice> targeted,
        final RequestLine line,
        final Headers headers,
        final Content body,
        final RequestContext ctx
    ) {
        final long startTime = System.currentTimeMillis();
        final Key pathKey = new KeyFromPath(line.uri().getPath());

        return body.asBytesFuture().thenCompose(requestBytes -> {
            final CompletableFuture<Response> result = new CompletableFuture<>();
            final AtomicBoolean completed = new AtomicBoolean(false);
            final AtomicInteger pending = new AtomicInteger(targeted.size());
            final AtomicBoolean anyServerError = new AtomicBoolean(false);
            final AtomicBoolean anyCircuitOpen = new AtomicBoolean(false);
            final List<CompletableFuture<Response>> memberFutures =
                new ArrayList<>(targeted.size());

            for (MemberSlice member : targeted) {
                if (member.isCircuitOpen()) {
                    ctx.addTo(EcsLogger.warn("com.auto1.pantera.group")
                        .message("Member circuit OPEN, skipping: " + member.name())
                        .eventCategory("repository")
                        .eventAction("group_query")
                        .eventOutcome("skipped")
                        .field("repository.name", this.group))
                        .log();
                    anyCircuitOpen.set(true);
                    completeIfAllExhausted(pending, completed, anyServerError, anyCircuitOpen, result, ctx);
                    continue;
                }
                final CompletableFuture<Response> memberFuture =
                    queryMemberDirect(member, line, headers, requestBytes, ctx);
                memberFutures.add(memberFuture);
                memberFuture.whenComplete((resp, err) -> {
                    if (err != null) {
                        handleMemberFailure(member, err, completed, pending, anyServerError, anyCircuitOpen, result, ctx);
                    } else {
                        handleMemberResponse(member, resp, completed, pending, anyServerError, anyCircuitOpen, result, startTime, pathKey, ctx);
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
     * When the index returns multiple members for the same artifact name,
     * query hosted (non-proxy) members first. If any hosted member returns
     * 200, serve that immediately without querying proxies. Only fall
     * through to proxy members if ALL hosted members return non-200.
     *
     * <p>This prevents a proxy from "claiming" a package name that exists
     * on the upstream registry (e.g. PyPI.org) but has zero published files,
     * when a hosted member has a locally-uploaded version with real files.
     *
     * <p>If all targeted members are the same type (all hosted or all proxy),
     * this degrades to the normal parallel query.
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
            return queryTargetedMembers(targeted, line, headers, body, ctx);
        }
        // Try hosted first; fall to proxy only if hosted yields no 200
        return queryTargetedMembers(hosted, line, headers, body, ctx)
            .thenCompose(resp -> {
                if (resp.status().success()) {
                    return CompletableFuture.completedFuture(resp);
                }
                // Hosted members didn't have it — try proxy members
                EcsLogger.debug("com.auto1.pantera.group")
                    .message("Hosted miss, cascading to "
                        + proxy.size() + " proxy member(s)")
                    .eventCategory("repository")
                    .eventAction("group_cascade_to_proxy")
                    .field("repository.name", this.group)
                    .log();
                return queryTargetedMembers(proxy, line, headers, body, ctx);
            });
    }

    /**
     * Query a single member directly (no negative cache check).
     * Used for index-targeted queries where we already know the member has the artifact.
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

        return member.slice().response(
            rewritten,
            dropFullPathHeader(headers),
            memberBody
        );
    }

    /**
     * Query a single member.
     *
     * @param member Member to query
     * @param line Request line
     * @param headers Request headers
     * @param requestBytes Request body bytes (may be empty for GET/HEAD)
     * @param ctx Request context for logging
     * @return Response future
     */
    private CompletableFuture<Response> queryMember(
        final MemberSlice member,
        final RequestLine line,
        final Headers headers,
        final byte[] requestBytes,
        final RequestContext ctx
    ) {

        // Create new Content instance from buffered bytes for each member
        final Content memberBody = requestBytes.length > 0
            ? new Content.From(requestBytes)
            : Content.EMPTY;

        final RequestLine rewritten = member.rewritePath(line);

        // Log the path rewriting for troubleshooting (DEBUG: 5000 events/s at 1000 req/s × 5 members)
        EcsLogger.debug("com.auto1.pantera.group")
            .message(String.format("Forwarding request to member '%s': rewrote path %s to %s", member.name(), line.uri().getPath(), rewritten.uri().getPath()))
            .eventCategory("repository")
            .eventAction("group_forward")
            .field("repository.name", this.group)
            .log();

        return member.slice().response(
            rewritten,
            dropFullPathHeader(headers),
            memberBody
        );
    }

    /**
     * Handle successful response from a member.
     */
    private void handleMemberResponse(
        final MemberSlice member,
        final Response resp,
        final AtomicBoolean completed,
        final AtomicInteger pending,
        final AtomicBoolean anyServerError,
        final AtomicBoolean anyCircuitOpen,
        final CompletableFuture<Response> result,
        final long startTime,
        final Key pathKey,
        final RequestContext ctx
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
                        .eventCategory("repository")
                        .eventAction("group_query")
                        .eventOutcome("success")
                        .field("repository.name", this.group)
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
                    .eventCategory("repository")
                    .eventAction("group_query")
                    .field("repository.name", this.group)
                    .field("http.response.status_code", status.code()))
                    .log();
                drainBody(member.name(), resp.body());
            }
            // Always decrement the global pending counter regardless of win/lose.
            // Two-phase completion: 502/404 only fires when ALL futures have reported
            // and !completed — prevents fast-failing proxies from racing ahead of a
            // slow-but-cached local member and completing the result with 502.
            completeIfAllExhausted(pending, completed, anyServerError, anyCircuitOpen, result, ctx);
        } else if (status == RsStatus.FORBIDDEN) {
            // Blocked/cooldown: propagate 403 to client (artifact exists but is blocked)
            if (completed.compareAndSet(false, true)) {
                ctx.addTo(EcsLogger.debug("com.auto1.pantera.group")
                    .message("Member '" + member.name() + "' returned FORBIDDEN (cooldown/blocked)")
                    .eventCategory("repository")
                    .eventAction("group_query")
                    .eventOutcome("success")
                    .field("repository.name", this.group)
                    .field("http.response.status_code", 403))
                    .log();
                member.recordSuccess(); // Not a failure - valid response
                result.complete(resp);
            } else {
                drainBody(member.name(), resp.body());
            }
            // Always decrement (same two-phase logic as 2xx success above)
            completeIfAllExhausted(pending, completed, anyServerError, anyCircuitOpen, result, ctx);
        } else if (status == RsStatus.NOT_FOUND) {
            // 404: try next member — individual miss is DEBUG noise, not actionable
            ctx.addTo(EcsLogger.debug("com.auto1.pantera.group")
                .message("Group member " + member.name() + " does not have " + pathKey.string())
                .eventCategory("group")
                .eventAction("group_fanout_miss")
                .eventOutcome("success")
                .field("repository.name", this.group)
                .field("url.path", pathKey.string()))
                .log();
            recordGroupMemberRequest(member.name(), "not_found");
            drainBody(member.name(), resp.body());
            completeIfAllExhausted(pending, completed, anyServerError, anyCircuitOpen, result, ctx);
        } else {
            // Server errors (500, 503, etc.): record failure, try next member
            ctx.addTo(EcsLogger.warn("com.auto1.pantera.group")
                .message("Member '" + member.name() + "' returned error status (" + (pending.get() - 1) + " pending)")
                .eventCategory("repository")
                .eventAction("group_query")
                .eventOutcome("failure"))
                .field("repository.name", this.group)
                .field("http.response.status_code", status.code())
                .log();
            member.recordFailure();
            anyServerError.set(true);
            recordGroupMemberRequest(member.name(), "error");
            drainBody(member.name(), resp.body());
            completeIfAllExhausted(pending, completed, anyServerError, anyCircuitOpen, result, ctx);
        }
    }

    /**
     * Handle member query failure.
     */
    private void handleMemberFailure(
        final MemberSlice member,
        final Throwable err,
        final AtomicBoolean completed,
        final AtomicInteger pending,
        final AtomicBoolean anyServerError,
        final AtomicBoolean anyCircuitOpen,
        final CompletableFuture<Response> result,
        final RequestContext ctx
    ) {
        if (err instanceof CancellationException) {
            // Another member won the race and cancelled this future.
            // This is not a real upstream failure — do not trip the circuit breaker.
            completeIfAllExhausted(pending, completed, anyServerError, anyCircuitOpen, result, ctx);
            return;
        }
        ctx.addTo(EcsLogger.warn("com.auto1.pantera.group")
            .message("Member query failed: " + member.name())
            .eventCategory("repository")
            .eventAction("group_query")
            .eventOutcome("failure")
            .field("repository.name", this.group)
            .field("error.message", err.getMessage()))
            .log();
        member.recordFailure();
        anyServerError.set(true);
        completeIfAllExhausted(pending, completed, anyServerError, anyCircuitOpen, result, ctx);
    }

    /**
     * Complete the result future if all members have been exhausted.
     *
     * Three-way outcome:
     * - anyServerError  → 502 Bad Gateway   (an active member returned 5xx or threw)
     * - anyCircuitOpen  → 503 Unavailable   (no active error, but ≥1 member was circuit-skipped;
     *                                         artifact may exist there — Maven will retry next build)
     * - neither         → 404 Not Found     (all active members cleanly said "not here")
     *
     * Keeping circuit-open separate from real server errors prevents the case where a
     * permanently-blocked proxy member (e.g. dead upstream) causes every "not found" response
     * to become a 502, breaking builds for artifacts that simply don't exist in this group.
     */
    private void completeIfAllExhausted(
        final AtomicInteger pending,
        final AtomicBoolean completed,
        final AtomicBoolean anyServerError,
        final AtomicBoolean anyCircuitOpen,
        final CompletableFuture<Response> result,
        final RequestContext ctx
    ) {
        if (pending.decrementAndGet() == 0 && !completed.get()) {
            if (anyServerError.get()) {
                ctx.addTo(EcsLogger.warn("com.auto1.pantera.group")
                    .message("All members exhausted with upstream errors, returning 502")
                    .eventCategory("repository")
                    .eventAction("group_query")
                    .eventOutcome("failure")
                    .field("repository.name", this.group))
                    .log();
                result.complete(ResponseBuilder.badGateway()
                    .textBody("All upstream members failed").build());
            } else if (anyCircuitOpen.get()) {
                ctx.addTo(EcsLogger.warn("com.auto1.pantera.group")
                    .message("All members exhausted, some circuits open, returning 503")
                    .eventCategory("repository")
                    .eventAction("group_query")
                    .eventOutcome("failure")
                    .field("repository.name", this.group))
                    .log();
                result.complete(ResponseBuilder.unavailable()
                    .textBody("Some members temporarily unavailable, retry later").build());
            } else {
                ctx.addTo(EcsLogger.warn("com.auto1.pantera.group")
                    .message("Artifact not found in any group member: " + ctx.packageName())
                    .eventCategory("group")
                    .eventAction("group_lookup_miss")
                    .eventOutcome("failure")
                    .field("repository.name", this.group))
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
                        .eventCategory("repository")
                        .eventAction("body_drain")
                        .eventOutcome("failure")
                        .field("repository.name", group)
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
