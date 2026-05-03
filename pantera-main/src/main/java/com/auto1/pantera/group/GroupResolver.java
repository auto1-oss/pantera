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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
@SuppressWarnings({"PMD.TooManyMethods", "PMD.GodClass"})
public final class GroupResolver implements Slice {

    private final String group;
    private final List<MemberSlice> members;
    private final List<RoutingRule> routingRules;
    private final Optional<ArtifactIndex> artifactIndex;
    private final String repoType;
    private final Set<String> proxyMembers;
    private final NegativeCache negativeCache;
    private final SingleFlight<String, Void> inFlightFanouts;
    private final java.util.concurrent.Executor drainExecutor;
    private final MembersStrategy strategy;

    /**
     * Group fanout strategy. PARALLEL races every member at once and returns the
     * first 2xx (current behaviour, retained for federated topologies with no
     * clear member affinity). SEQUENTIAL walks members in declared order, falling
     * through on 404 or open-circuit, stopping on the first 2xx — Nexus /
     * Artifactory style. SEQUENTIAL is the default because the typical Pantera
     * deployment has a "primary" upstream (e.g. Maven Central) that holds 99% of
     * artifacts; PARALLEL multiplies upstream traffic by group size for marginal
     * latency benefit.
     */
    public enum MembersStrategy {
        PARALLEL,
        SEQUENTIAL;

        public static MembersStrategy fromYaml(final String value) {
            if (value == null || value.isBlank()) {
                return SEQUENTIAL;
            }
            return MembersStrategy.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        }
    }

    /**
     * Full constructor.
     *
     * @param group Group repository name
     * @param members Flattened member slices with circuit breakers
     * @param routingRules Routing rules for path-based member selection
     * @param artifactIndex Optional artifact index for O(log n) lookups
     * @param repoType Repository type for name parsing
     * @param proxyMembers Names of proxy repository members
     * @param negativeCache Negative cache for 404 results
     * @param drainExecutor Per-repo drain executor from {@link com.auto1.pantera.http.resilience.RepoBulkhead}
     * @param strategy Group fanout strategy (PARALLEL or SEQUENTIAL)
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public GroupResolver(
        final String group,
        final List<MemberSlice> members,
        final List<RoutingRule> routingRules,
        final Optional<ArtifactIndex> artifactIndex,
        final String repoType,
        final Set<String> proxyMembers,
        final NegativeCache negativeCache,
        final java.util.concurrent.Executor drainExecutor,
        final MembersStrategy strategy
    ) {
        this.group = Objects.requireNonNull(group, "group");
        this.members = Objects.requireNonNull(members, "members");
        this.routingRules = routingRules != null ? routingRules : Collections.emptyList();
        this.artifactIndex = artifactIndex != null ? artifactIndex : Optional.empty();
        this.repoType = repoType != null ? repoType : "";
        this.proxyMembers = proxyMembers != null ? proxyMembers : Collections.emptySet();
        this.negativeCache = Objects.requireNonNull(negativeCache, "negativeCache");
        this.drainExecutor = Objects.requireNonNull(drainExecutor, "drainExecutor");
        this.strategy = strategy == null ? MembersStrategy.SEQUENTIAL : strategy;
        this.inFlightFanouts = new SingleFlight<>(
            Duration.ofMinutes(5),
            10_000,
            ContextualExecutor.contextualize(ForkJoinPool.commonPool())
        );
    }

    /**
     * Backward-compat constructor (retains the pre-strategy signature so existing
     * test stubs and any non-RepositorySlices caller keep compiling). Defaults to
     * {@link MembersStrategy#SEQUENTIAL}.
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public GroupResolver(
        final String group,
        final List<MemberSlice> members,
        final List<RoutingRule> routingRules,
        final Optional<ArtifactIndex> artifactIndex,
        final String repoType,
        final Set<String> proxyMembers,
        final NegativeCache negativeCache,
        final java.util.concurrent.Executor drainExecutor
    ) {
        this(group, members, routingRules, artifactIndex, repoType,
            proxyMembers, negativeCache, drainExecutor, MembersStrategy.SEQUENTIAL);
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
     * @param strategy Group fanout strategy (PARALLEL or SEQUENTIAL)
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public GroupResolver(
        final SliceResolver resolver,
        final String group,
        final List<String> memberNames,
        final int port,
        final int depth,
        final long timeoutSeconds,
        final List<RoutingRule> routingRules,
        final Optional<ArtifactIndex> artifactIndex,
        final Set<String> proxyMembers,
        final String repoType,
        final NegativeCache negativeCache,
        final Function<String, AutoBlockRegistry> registrySupplier,
        final java.util.concurrent.Executor repoDrainExecutor,
        final MembersStrategy strategy
    ) {
        this(
            group,
            buildMembers(resolver, memberNames, port, proxyMembers, registrySupplier),
            routingRules,
            artifactIndex,
            repoType,
            proxyMembers,
            negativeCache,
            repoDrainExecutor,
            strategy
        );
    }

    /**
     * Backward-compat wiring constructor (defaults to SEQUENTIAL).
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public GroupResolver(
        final SliceResolver resolver,
        final String group,
        final List<String> memberNames,
        final int port,
        final int depth,
        final long timeoutSeconds,
        final List<RoutingRule> routingRules,
        final Optional<ArtifactIndex> artifactIndex,
        final Set<String> proxyMembers,
        final String repoType,
        final NegativeCache negativeCache,
        final Function<String, AutoBlockRegistry> registrySupplier,
        final java.util.concurrent.Executor repoDrainExecutor
    ) {
        this(resolver, group, memberNames, port, depth, timeoutSeconds,
            routingRules, artifactIndex, proxyMembers, repoType,
            negativeCache, registrySupplier, repoDrainExecutor,
            MembersStrategy.SEQUENTIAL);
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

        if (this.members.isEmpty()) {
            return CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            );
        }

        recordRequestStart();
        final long requestStartTime = System.currentTimeMillis();

        return resolve(line, headers, body, path)
            .whenComplete((resp, err) -> recordMetrics(resp, err, requestStartTime));
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
        // ---- No index configured → full two-phase fanout ----
        if (this.artifactIndex.isEmpty()) {
            return fullTwoPhaseFanout(line, headers, body);
        }

        final ArtifactIndex idx = this.artifactIndex.get();
        final Optional<String> parsedName = ArtifactNameParser.parse(this.repoType, path);
        if (parsedName.isEmpty()) {
            EcsLogger.debug("com.auto1.pantera.group")
                .message("Name unparseable, using full two-phase fanout")
                .eventCategory("web")
                .eventAction("group_direct_fanout")
                .field("url.path", path)
                .log();
            return fullTwoPhaseFanout(line, headers, body);
        }

        final String artifactName = parsedName.get();

        // ---- STEP 1: Negative cache check ----
        // Best-effort version extraction from the URL so the admin UI has a
        // real Version column. Uses NegativeCacheKey.fromPath solely to parse
        // the path; we keep our own (ArtifactNameParser-derived) artifactName
        // to stay consistent with the index lookup format.
        final String parsedVersion = NegativeCacheKey
            .fromPath(this.group, this.repoType, path).artifactVersion();
        final NegativeCacheKey negCacheKey = new NegativeCacheKey(
            this.group, this.repoType, artifactName, parsedVersion
        );
        if (this.negativeCache.isKnown404(negCacheKey)) {
            EcsLogger.debug("com.auto1.pantera.group")
                .message("Negative cache hit, returning 404 without DB query")
                .eventCategory("database")
                .eventAction("group_negative_cache_hit")
                .field("url.path", path)
                .log();
            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        }

        // ---- STEP 2: Query index ----
        return idx.locateByName(artifactName)
            .thenApply(IndexOutcome::fromLegacy)
            .exceptionally(ex -> new IndexOutcome.DBFailure(ex, "locateByName:" + artifactName))
            .thenCompose(outcome -> handleIndexOutcome(
                outcome, line, headers, body, path, artifactName, negCacheKey
            ));
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
        return switch (outcome) {
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
                .log();
            return fullTwoPhaseFanout(line, headers, body);
        }
        EcsLogger.debug("com.auto1.pantera.group")
            .message("Index hit via name: targeting " + targeted.size() + " member(s)")
            .eventCategory("web")
            .eventAction("group_index_hit")
            .field("url.path", path)
            .log();

        return body.asBytesFuture().thenCompose(requestBytes -> {
            final CompletableFuture<Response> result = new CompletableFuture<>();
            final AtomicBoolean completed = new AtomicBoolean(false);
            final AtomicInteger pending = new AtomicInteger(targeted.size());
            final AtomicBoolean anyServerError = new AtomicBoolean(false);
            final AtomicBoolean anyNotFound = new AtomicBoolean(false);
            final List<CompletableFuture<Response>> memberFutures = new ArrayList<>(targeted.size());

            for (final MemberSlice member : targeted) {
                final CompletableFuture<Response> mf = queryMemberDirect(
                    member, line, headers, requestBytes
                );
                memberFutures.add(mf);
                mf.whenComplete((resp, err) -> {
                    if (err != null) {
                        if (!(err instanceof CancellationException)) {
                            member.recordFailure();
                            anyServerError.set(true);
                        }
                        completeTargetedIfAllExhausted(
                            pending, completed, anyServerError, anyNotFound, result
                        );
                    } else {
                        handleTargetedMemberResponse(
                            member, resp, completed, pending, anyServerError,
                            anyNotFound, result, memberFutures
                        );
                    }
                });
            }

            // When the targeted read completes, check for TOCTOU fallthrough
            return result.thenCompose(resp -> {
                // Cancel remaining futures
                for (final CompletableFuture<Response> f : memberFutures) {
                    if (!f.isDone()) {
                        f.cancel(true);
                    }
                }
                if (resp.status().success()
                    || resp.status() == RsStatus.NOT_MODIFIED
                    || resp.status() == RsStatus.FORBIDDEN) {
                    return CompletableFuture.completedFuture(resp);
                }
                if (resp.status() == RsStatus.NOT_FOUND) {
                    // TOCTOU drift: index said it exists but member says 404.
                    // Fall through to proxy fanout (A11 fix).
                    EcsLogger.debug("com.auto1.pantera.group")
                        .message("TOCTOU drift: index hit but member returned 404, "
                            + "falling through to proxy fanout")
                        .eventCategory("web")
                        .eventAction("group_toctou_fallthrough")
                        .field("url.path", line.uri().getPath())
                        .log();
                    return proxyOnlyFanout(line, headers, body, artifactName, negCacheKey);
                }
                if (resp.status().serverError()) {
                    // Targeted member 5xx: return StorageUnavailable.
                    // The bytes are supposed to be local -- this is a real local failure.
                    return CompletableFuture.completedFuture(
                        FaultTranslator.translate(
                            new Fault.StorageUnavailable(null, line.uri().getPath()),
                            null
                        )
                    );
                }
                return CompletableFuture.completedFuture(resp);
            });
        });
    }

    /**
     * Handle a response from a targeted member (index hit path).
     */
    private void handleTargetedMemberResponse(
        final MemberSlice member,
        final Response resp,
        final AtomicBoolean completed,
        final AtomicInteger pending,
        final AtomicBoolean anyServerError,
        final AtomicBoolean anyNotFound,
        final CompletableFuture<Response> result,
        final List<CompletableFuture<Response>> memberFutures
    ) {
        final RsStatus status = resp.status();
        if (status == RsStatus.OK || status == RsStatus.PARTIAL_CONTENT
            || status == RsStatus.NOT_MODIFIED) {
            if (completed.compareAndSet(false, true)) {
                member.recordSuccess();
                result.complete(resp);
            } else {
                drainBody(member.name(), resp.body());
            }
            completeTargetedIfAllExhausted(pending, completed, anyServerError, anyNotFound, result);
        } else if (status == RsStatus.FORBIDDEN) {
            if (completed.compareAndSet(false, true)) {
                member.recordSuccess();
                result.complete(resp);
            } else {
                drainBody(member.name(), resp.body());
            }
            completeTargetedIfAllExhausted(pending, completed, anyServerError, anyNotFound, result);
        } else if (status == RsStatus.NOT_FOUND) {
            anyNotFound.set(true);
            drainBody(member.name(), resp.body());
            completeTargetedIfAllExhausted(pending, completed, anyServerError, anyNotFound, result);
        } else {
            member.recordFailure();
            anyServerError.set(true);
            drainBody(member.name(), resp.body());
            completeTargetedIfAllExhausted(pending, completed, anyServerError, anyNotFound, result);
        }
    }

    /**
     * Complete the targeted-read result when all members are exhausted.
     * Returns an intermediate Response that the caller interprets:
     * - 404 signals TOCTOU fallthrough
     * - 5xx signals StorageUnavailable
     */
    private static void completeTargetedIfAllExhausted(
        final AtomicInteger pending,
        final AtomicBoolean completed,
        final AtomicBoolean anyServerError,
        final AtomicBoolean anyNotFound,
        final CompletableFuture<Response> result
    ) {
        if (pending.decrementAndGet() == 0 && !completed.get()) {
            if (anyServerError.get()) {
                result.complete(ResponseBuilder.internalError()
                    .textBody("Targeted member read failed").build());
            } else {
                // All 404 (TOCTOU case)
                result.complete(ResponseBuilder.notFound().build());
            }
        }
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
                .log();
            return executeProxyFanout(fanoutMembers, line, headers, body, negCacheKey)
                .whenComplete((resp, err) -> leaderGate.complete(null));
        }
        EcsLogger.debug("com.auto1.pantera.group")
            .message("Coalescing with in-flight fanout for " + artifactName)
            .eventCategory("web")
            .eventAction("group_fanout_coalesce")
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
        final NegativeCacheKey negCacheKey
    ) {
        return body.asBytesFuture().thenCompose(requestBytes -> {
            final CompletableFuture<Response> result = new CompletableFuture<>();
            final AtomicBoolean completed = new AtomicBoolean(false);
            final AtomicInteger pending = new AtomicInteger(fanoutMembers.size());
            final List<Fault.MemberOutcome> outcomes =
                Collections.synchronizedList(new ArrayList<>(fanoutMembers.size()));
            final List<CompletableFuture<Response>> memberFutures =
                new ArrayList<>(fanoutMembers.size());

            for (final MemberSlice member : fanoutMembers) {
                if (member.isCircuitOpen()) {
                    outcomes.add(Fault.MemberOutcome.threw(
                        member.name(), Fault.MemberOutcome.Kind.CIRCUIT_OPEN, null
                    ));
                    completeProxyIfAllExhausted(
                        pending, completed, outcomes, result, negCacheKey
                    );
                    continue;
                }
                final CompletableFuture<Response> mf = queryMemberDirect(
                    member, line, headers, requestBytes
                );
                memberFutures.add(mf);
                mf.whenComplete((resp, err) -> {
                    if (err != null) {
                        handleProxyMemberFailure(
                            member, err, completed, pending, outcomes, result, negCacheKey
                        );
                    } else {
                        handleProxyMemberResponse(
                            member, resp, completed, pending, outcomes, result,
                            negCacheKey, memberFutures
                        );
                    }
                });
            }

            return result;
        });
    }

    /**
     * Handle a response from a proxy member in the fanout.
     */
    private void handleProxyMemberResponse(
        final MemberSlice member,
        final Response resp,
        final AtomicBoolean completed,
        final AtomicInteger pending,
        final List<Fault.MemberOutcome> outcomes,
        final CompletableFuture<Response> result,
        final NegativeCacheKey negCacheKey,
        final List<CompletableFuture<Response>> memberFutures
    ) {
        final RsStatus status = resp.status();
        if (status == RsStatus.OK || status == RsStatus.PARTIAL_CONTENT
            || status == RsStatus.NOT_MODIFIED) {
            outcomes.add(Fault.MemberOutcome.responded(
                member.name(), Fault.MemberOutcome.Kind.OK, resp
            ));
            if (completed.compareAndSet(false, true)) {
                member.recordSuccess();
                // Cancel remaining futures
                for (final CompletableFuture<Response> f : memberFutures) {
                    if (!f.isDone()) {
                        f.cancel(true);
                    }
                }
                result.complete(resp);
            } else {
                drainBody(member.name(), resp.body());
            }
            completeProxyIfAllExhausted(pending, completed, outcomes, result, negCacheKey);
        } else if (status == RsStatus.NOT_FOUND) {
            outcomes.add(Fault.MemberOutcome.responded(
                member.name(), Fault.MemberOutcome.Kind.NOT_FOUND, resp
            ));
            drainBody(member.name(), resp.body());
            completeProxyIfAllExhausted(pending, completed, outcomes, result, negCacheKey);
        } else {
            // 5xx or other error status
            outcomes.add(Fault.MemberOutcome.responded(
                member.name(), Fault.MemberOutcome.Kind.FIVE_XX, resp
            ));
            member.recordFailure();
            // Do NOT drain body -- FaultTranslator may pass it through
            completeProxyIfAllExhausted(pending, completed, outcomes, result, negCacheKey);
        }
    }

    /**
     * Handle member query failure in the proxy fanout.
     */
    private void handleProxyMemberFailure(
        final MemberSlice member,
        final Throwable err,
        final AtomicBoolean completed,
        final AtomicInteger pending,
        final List<Fault.MemberOutcome> outcomes,
        final CompletableFuture<Response> result,
        final NegativeCacheKey negCacheKey
    ) {
        if (err instanceof CancellationException) {
            outcomes.add(Fault.MemberOutcome.threw(
                member.name(), Fault.MemberOutcome.Kind.CANCELLED, err
            ));
        } else {
            outcomes.add(Fault.MemberOutcome.threw(
                member.name(), Fault.MemberOutcome.Kind.EXCEPTION, err
            ));
            member.recordFailure();
        }
        completeProxyIfAllExhausted(pending, completed, outcomes, result, negCacheKey);
    }

    /**
     * Complete the proxy fanout result when all members are exhausted.
     *
     * <p>Policy:
     * <ul>
     *   <li>All 404 / circuit-open / cancelled -> cache negative + 404 [PATH A]</li>
     *   <li>Any 5xx / exception (no 2xx) -> AllProxiesFailed [PATH B]</li>
     * </ul>
     */
    private void completeProxyIfAllExhausted(
        final AtomicInteger pending,
        final AtomicBoolean completed,
        final List<Fault.MemberOutcome> outcomes,
        final CompletableFuture<Response> result,
        final NegativeCacheKey negCacheKey
    ) {
        if (pending.decrementAndGet() == 0 && !completed.get()) {
            final boolean anyFiveXxOrException = outcomes.stream()
                .anyMatch(o -> o.kind() == Fault.MemberOutcome.Kind.FIVE_XX
                    || o.kind() == Fault.MemberOutcome.Kind.EXCEPTION);
            if (anyFiveXxOrException) {
                // PATH B: AllProxiesFailed -- pass-through best 5xx
                final Optional<Fault.AllProxiesFailed.ProxyFailure> winning =
                    FaultTranslator.pickWinningFailure(outcomes);
                final Fault.AllProxiesFailed fault = new Fault.AllProxiesFailed(
                    this.group, List.copyOf(outcomes), winning
                );
                result.complete(FaultTranslator.translate(fault, null));
            } else {
                // PATH A: all 404 / skipped / cancelled -- cache negative
                this.negativeCache.cacheNotFound(negCacheKey);
                EcsLogger.debug("com.auto1.pantera.group")
                    .message("All proxies returned 404, caching negative result")
                    .eventCategory("database")
                    .eventAction("group_negative_cache_populate")
                    .log();
                result.complete(ResponseBuilder.notFound().build());
            }
        }
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
        final List<MemberSlice> eligible = filterByRoutingRules(line.uri().getPath());
        if (eligible.isEmpty()) {
            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        }
        return queryHostedFirstThenProxy(eligible, line, headers, body);
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
     * Query a list of members in parallel -- the classic fanout path.
     * Used for full two-phase fanout only (not the indexed path).
     */
    private CompletableFuture<Response> queryTargetedMembers(
        final List<MemberSlice> targeted,
        final RequestLine line,
        final Headers headers,
        final Content body,
        final boolean isTargetedLocalRead
    ) {
        return body.asBytesFuture().thenCompose(requestBytes -> {
            final CompletableFuture<Response> result = new CompletableFuture<>();
            final AtomicBoolean completed = new AtomicBoolean(false);
            final AtomicInteger pending = new AtomicInteger(targeted.size());
            final AtomicBoolean anyServerError = new AtomicBoolean(false);
            final List<CompletableFuture<Response>> memberFutures =
                new ArrayList<>(targeted.size());

            for (final MemberSlice member : targeted) {
                if (!isTargetedLocalRead && member.isCircuitOpen()) {
                    completeFanoutIfAllExhausted(
                        pending, completed, anyServerError, result, isTargetedLocalRead
                    );
                    continue;
                }
                final CompletableFuture<Response> mf = queryMemberDirect(
                    member, line, headers, requestBytes
                );
                memberFutures.add(mf);
                mf.whenComplete((resp, err) -> {
                    if (err != null) {
                        if (!(err instanceof CancellationException)) {
                            member.recordFailure();
                            anyServerError.set(true);
                        }
                        completeFanoutIfAllExhausted(
                            pending, completed, anyServerError, result, isTargetedLocalRead
                        );
                    } else {
                        handleFanoutMemberResponse(
                            member, resp, completed, pending, anyServerError,
                            result, isTargetedLocalRead, memberFutures
                        );
                    }
                });
            }

            result.whenComplete((resp, err) -> {
                for (final CompletableFuture<Response> f : memberFutures) {
                    if (!f.isDone()) {
                        f.cancel(true);
                    }
                }
            });

            return result;
        });
    }

    /**
     * Handle a response from a member in the fanout path.
     */
    private void handleFanoutMemberResponse(
        final MemberSlice member,
        final Response resp,
        final AtomicBoolean completed,
        final AtomicInteger pending,
        final AtomicBoolean anyServerError,
        final CompletableFuture<Response> result,
        final boolean isTargetedLocalRead,
        final List<CompletableFuture<Response>> memberFutures
    ) {
        final RsStatus status = resp.status();
        if (status == RsStatus.OK || status == RsStatus.PARTIAL_CONTENT
            || status == RsStatus.NOT_MODIFIED) {
            if (completed.compareAndSet(false, true)) {
                member.recordSuccess();
                result.complete(resp);
            } else {
                drainBody(member.name(), resp.body());
            }
            completeFanoutIfAllExhausted(
                pending, completed, anyServerError, result, isTargetedLocalRead
            );
        } else if (status == RsStatus.FORBIDDEN) {
            if (completed.compareAndSet(false, true)) {
                member.recordSuccess();
                result.complete(resp);
            } else {
                drainBody(member.name(), resp.body());
            }
            completeFanoutIfAllExhausted(
                pending, completed, anyServerError, result, isTargetedLocalRead
            );
        } else if (status == RsStatus.NOT_FOUND) {
            drainBody(member.name(), resp.body());
            completeFanoutIfAllExhausted(
                pending, completed, anyServerError, result, isTargetedLocalRead
            );
        } else {
            member.recordFailure();
            anyServerError.set(true);
            drainBody(member.name(), resp.body());
            completeFanoutIfAllExhausted(
                pending, completed, anyServerError, result, isTargetedLocalRead
            );
        }
    }

    /**
     * Complete the fanout result when all members are exhausted.
     */
    private static void completeFanoutIfAllExhausted(
        final AtomicInteger pending,
        final AtomicBoolean completed,
        final AtomicBoolean anyServerError,
        final CompletableFuture<Response> result,
        final boolean isTargetedLocalRead
    ) {
        if (pending.decrementAndGet() == 0 && !completed.get()) {
            if (anyServerError.get()) {
                if (isTargetedLocalRead) {
                    result.complete(ResponseBuilder.internalError()
                        .textBody("Targeted member read failed").build());
                } else {
                    result.complete(ResponseBuilder.badGateway()
                        .textBody("All upstream members failed").build());
                }
            } else {
                result.complete(ResponseBuilder.notFound().build());
            }
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
    private void drainBody(final String memberName, final Content body) {
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
                .filter(h -> !h.getKey().equalsIgnoreCase("X-FullPath"))
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
        if (err != null) {
            recordGroupRequest("error", duration);
        } else if (resp.status().success()) {
            recordGroupRequest("success", duration);
        } else {
            recordGroupRequest("not_found", duration);
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
}
