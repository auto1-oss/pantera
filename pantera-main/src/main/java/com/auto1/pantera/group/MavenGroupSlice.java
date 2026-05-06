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
import com.auto1.pantera.http.context.ContextualExecutor;
import com.auto1.pantera.http.resilience.SingleFlight;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.log.EcsLogger;
import io.micrometer.core.instrument.DistributionSummary;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

/**
 * Maven-specific group slice with sequential-first metadata fetch.
 *
 * <p>For maven-metadata.xml requests:
 * <ul>
 *   <li>Tries members sequentially in declared order; returns the first
 *       successful response (cooldown-filtered).</li>
 *   <li>Caches the filtered winning bytes (12 hour TTL, L1+L2).</li>
 *   <li>If no member returns 200, falls back to last-known-good stale
 *       cache, otherwise 404.</li>
 * </ul>
 *
 * <p><b>v2.2.0 BREAKING:</b> previously this slice fanned out to ALL members
 * in parallel and merged every member's {@code maven-metadata.xml} version
 * list into a union. That added per-request upstream amplification with no
 * real benefit — in well-organised configs (the only kind we support) the
 * chance two members both serve the same artifact is near-zero, member
 * namespaces are disjoint, and merge collisions land back on whichever
 * member would have answered first anyway. JFrog Artifactory and Sonatype
 * Nexus virtual repos behave the same way (sequential-first, no merge).
 * Cooldown filtering is preserved on the single winning response so blocked
 * versions still never reach the client.
 *
 * <p>For all other requests:
 * <ul>
 *   <li>Returns first successful response (standard group behavior via
 *       {@link Slice} delegate).</li>
 * </ul>
 *
 * @since 1.0
 */
public final class MavenGroupSlice implements Slice {

    /**
     * Delegate group slice for non-metadata requests.
     */
    private final Slice delegate;

    /**
     * Member repository names.
     */
    private final List<String> members;

    /**
     * Slice resolver for getting member slices.
     */
    private final SliceResolver resolver;

    /**
     * Server port.
     */
    private final int port;

    /**
     * Group repository name.
     */
    private final String group;

    /**
     * Nesting depth.
     */
    private final int depth;

    /**
     * Two-tier metadata cache (L1: Caffeine, L2: Valkey).
     */
    private final GroupMetadataCache metadataCache;

    /**
     * In-flight metadata fetches keyed by {@code group:path}.
     *
     * <p>Serves as a request coalescer: when N concurrent requests arrive for
     * the same {@code maven-metadata.xml} with a cold L1+L2 cache, the first
     * installs a gate inside the {@link SingleFlight} and runs the full
     * N-member fanout + merge.  Late arrivals park on the gate and retry
     * {@link #response} once the leader completes.  On retry the L1 cache is
     * warm, so followers return immediately without touching the network.
     * The combination of coalescer + two-tier cache collapses a thundering
     * herd of N concurrent misses into exactly ONE upstream fanout + merge —
     * same pattern as {@code GroupResolver#proxyOnlyFanout}.
     *
     * <p>This coalescer deliberately does NOT share the winning {@link Response}
     * object across callers: {@link Content} is a one-shot reactive stream
     * that cannot be subscribed to twice.  Instead followers re-enter
     * {@code response()} and read the freshly-populated cache.
     *
     * <p>{@link SingleFlight} replaces the hand-rolled {@code ConcurrentHashMap}
     * dance from commit {@code b37deea2} — see §6.4 of
     * {@code docs/analysis/v2.2-target-architecture.md} and A6/A7/A8/A9 in
     * {@code v2.1.3-architecture-review.md}.
     */
    private final SingleFlight<String, Void> inFlightMetadataFetches =
        new SingleFlight<>(
            Duration.ofMinutes(5),
            10_000,
            ContextualExecutor.contextualize(ForkJoinPool.commonPool())
        );

    /**
     * Constructor.
     * @param delegate Delegate group slice
     * @param group Group repository name
     * @param members Member repository names
     * @param resolver Slice resolver
     * @param port Server port
     * @param depth Nesting depth
     */
    public MavenGroupSlice(
        final Slice delegate,
        final String group,
        final List<String> members,
        final SliceResolver resolver,
        final int port,
        final int depth
    ) {
        this(delegate, group, members, resolver, port, depth,
            new GroupMetadataCache(group),
            com.auto1.pantera.cooldown.metadata.NoopCooldownMetadataService.INSTANCE,
            "maven-group");
    }

    /**
     * Constructor with injectable cache (for testing).
     * @param delegate Delegate group slice
     * @param group Group repository name
     * @param members Member repository names
     * @param resolver Slice resolver
     * @param port Server port
     * @param depth Nesting depth
     * @param cache Group metadata cache to use
     */
    public MavenGroupSlice(
        final Slice delegate,
        final String group,
        final List<String> members,
        final SliceResolver resolver,
        final int port,
        final int depth,
        final GroupMetadataCache cache
    ) {
        this(delegate, group, members, resolver, port, depth, cache,
            com.auto1.pantera.cooldown.metadata.NoopCooldownMetadataService.INSTANCE,
            "maven-group");
    }

    /** Cooldown metadata service applied to the merged result. */
    private final com.auto1.pantera.cooldown.metadata.CooldownMetadataService cooldownMetadata;

    /** Repo type used for cooldown lookups (maven-group / gradle-group). */
    private final String repoType;

    /**
     * Constructor with cooldown metadata filtering.
     *
     * <p>After merging member metadata, the result is run through
     * {@link com.auto1.pantera.maven.cooldown.MavenMetadataFilter} so blocked
     * versions never reach the client. Without this, a hosted member (which
     * does not run the per-proxy cooldown filter) can re-introduce blocked
     * versions into the merged response and the client would resolve to a
     * version it cannot subsequently download (403 from the artifact gate).
     *
     * @param delegate Delegate group slice
     * @param group Group repository name
     * @param members Member repository names
     * @param resolver Slice resolver
     * @param port Server port
     * @param depth Nesting depth
     * @param cache Group metadata cache to use
     * @param cooldownMetadata Cooldown metadata filter service (NOOP to disable)
     * @param repoType Repo type ("maven-group" or "gradle-group")
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public MavenGroupSlice(
        final Slice delegate,
        final String group,
        final List<String> members,
        final SliceResolver resolver,
        final int port,
        final int depth,
        final GroupMetadataCache cache,
        final com.auto1.pantera.cooldown.metadata.CooldownMetadataService cooldownMetadata,
        final String repoType
    ) {
        this.delegate = delegate;
        this.group = group;
        this.members = members;
        this.resolver = resolver;
        this.port = port;
        this.depth = depth;
        this.metadataCache = cache;
        this.cooldownMetadata = cooldownMetadata;
        this.repoType = repoType;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String path = line.uri().getPath();
        final String method = line.method().value();

        // Only merge metadata for GET requests
        if ("GET".equals(method) && path.endsWith("maven-metadata.xml")) {
            return mergeMetadata(line, headers, body, path);
        }

        // Handle checksum requests for merged metadata
        if ("GET".equals(method) && (path.endsWith("maven-metadata.xml.sha1") || path.endsWith("maven-metadata.xml.md5"))) {
            return handleChecksumRequest(line, headers, body, path);
        }

        // All other requests use standard group behavior
        return delegate.response(line, headers, body);
    }

    /**
     * Handle checksum requests for merged metadata.
     * Computes checksum of the merged metadata and returns it.
     */
    private CompletableFuture<Response> handleChecksumRequest(
        final RequestLine line,
        final Headers headers,
        final Content body,
        final String path
    ) {
        // Determine checksum type
        final boolean isSha1 = path.endsWith(".sha1");
        final String metadataPath = path.substring(0, path.lastIndexOf('.'));

        // Get merged metadata from cache or merge it
        final RequestLine metadataLine = new RequestLine(
            line.method(),
            URI.create(metadataPath),
            line.version()
        );

        return mergeMetadata(metadataLine, headers, body, metadataPath)
            .thenApply(metadataResponse -> {
                // Extract body from metadata response
                return metadataResponse.body().asBytesFuture()
                    .thenApply(metadataBytes -> {
                        try {
                            // Compute checksum
                            final java.security.MessageDigest digest = java.security.MessageDigest.getInstance(
                                isSha1 ? "SHA-1" : "MD5"
                            );
                            final byte[] checksumBytes = digest.digest(metadataBytes);

                            // Convert to hex string
                            final String hex = java.util.HexFormat.of().formatHex(checksumBytes);

                            return ResponseBuilder.ok()
                                .header("Content-Type", "text/plain")
                                .body(hex.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                .build();
                        } catch (java.security.NoSuchAlgorithmException e) {
                            EcsLogger.error("com.auto1.pantera.maven")
                                .message("Failed to compute checksum")
                                .eventCategory("web")
                                .eventAction("checksum_compute")
                                .eventOutcome("failure")
                                .field("url.path", path)
                                .error(e)
                                .log();
                            return ResponseBuilder.internalError()
                                .textBody("Failed to compute checksum")
                                .build();
                        }
                    });
            })
            .thenCompose(future -> future);
    }

    /**
     * Merge maven-metadata.xml from all members.
     *
     * <p>Fast path: L1/L2 cache hit → return cached bytes.  Slow path: miss →
     * coalesce concurrent callers through the in-flight {@link SingleFlight}
     * so exactly one leader does the N-member fanout + merge while followers
     * park on the leader's gate and re-enter {@code response()} once the
     * cache is warm.  See {@code GroupSlice#proxyOnlyFanout} for the same
     * pattern.
     */
    private CompletableFuture<Response> mergeMetadata(
        final RequestLine line,
        final Headers headers,
        final Content body,
        final String path
    ) {
        final String cacheKey = path;

        // Check two-tier cache (L1 then L2 if miss)
        return this.metadataCache.get(cacheKey).thenCompose(cached -> {
            if (cached.isPresent()) {
                // Cache HIT (L1 or L2)
                EcsLogger.debug("com.auto1.pantera.maven")
                    .message("Returning cached merged metadata (cache hit)")
                    .eventCategory("web")
                    .eventAction("metadata_merge")
                    .eventOutcome("success")
                    .field("repository.name", this.group)
                    .field("url.path", path)
                    .log();
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .header("Content-Type", "application/xml")
                        .body(cached.get())
                        .build()
                );
            }

            // Cache MISS: coalesce concurrent callers so only one does the
            // N-member fanout + merge. Leader-vs-follower is distinguished by
            // a flag the loader sets on the caller's thread (Caffeine runs
            // the bifunction synchronously for the first absent key). The
            // leader does the real fetch + merge and returns the Response
            // directly; followers park on the gate and re-enter response()
            // once the L1 cache is warm — same pattern as
            // {@code GroupSlice#proxyOnlyFanout}. SingleFlight handles zombie
            // eviction and stack-flat completion (A6/A7/A8/A9, WI-05).
            final String dedupKey = this.group + ":" + path;
            final boolean[] isLeader = {false};
            final CompletableFuture<Void> leaderGate = new CompletableFuture<>();
            final CompletableFuture<Void> gate = this.inFlightMetadataFetches.load(
                dedupKey,
                () -> {
                    isLeader[0] = true;
                    return leaderGate;
                }
            );
            if (isLeader[0]) {
                return fetchAndMergeFromMembers(line, headers, path, cacheKey)
                    .whenComplete(
                        (resp, err) -> leaderGate.complete(null)
                    );
            }
            EcsLogger.debug("com.auto1.pantera.maven")
                .message("Coalescing with in-flight metadata fetch")
                .eventCategory("web")
                .eventAction("metadata_fetch_coalesce")
                .field("repository.name", this.group)
                .field("url.path", path)
                .log();
            // Follower: re-enter response() once the gate resolves. Swallow
            // any exception the gate might carry — the L1/L2 cache is the
            // source of truth on retry.
            return gate.exceptionally(err -> null).thenCompose(
                ignored -> this.response(line, headers, body)
            );
        });
    }

    /**
     * Try members in declared order; return the first successful response
     * (cooldown-filtered) as the group's authoritative
     * {@code maven-metadata.xml}. Only the coalescer leader runs this path;
     * followers re-enter {@link #response} after the leader populates the
     * cache.
     *
     * <p>v2.2.0 sequential-first replacement for the previous fanout+merge —
     * see class javadoc. Cooldown filter applies to the single winning
     * response so behaviour is unchanged from a client perspective for the
     * 99 %+ of artifacts that live in a single member.
     */
    private CompletableFuture<Response> fetchAndMergeFromMembers(
        final RequestLine line,
        final Headers headers,
        final String path,
        final String cacheKey
    ) {
        // CRITICAL: Consume original body to prevent OneTimePublisher errors.
        // GET requests for maven-metadata.xml have empty bodies, but Content
        // is still reference-counted.
        return CompletableFuture.completedFuture((byte[]) null).thenCompose(ignored -> {
            final long fetchStartTime = System.currentTimeMillis();
            return tryMembersSequentially(line, headers, path, cacheKey, 0, fetchStartTime)
                .exceptionally(err -> {
                    final Throwable cause = err.getCause() != null ? err.getCause() : err;
                    EcsLogger.error("com.auto1.pantera.maven")
                        .message("Failed to fetch metadata sequentially")
                        .eventCategory("web")
                        .eventAction("metadata_fetch")
                        .eventOutcome("failure")
                        .field("repository.name", this.group)
                        .field("url.path", path)
                        .error(cause)
                        .log();
                    return ResponseBuilder.internalError()
                        .textBody("Failed to fetch metadata: " + cause.getMessage())
                        .build();
                });
        });
    }

    /**
     * Sequentially try {@code members[idx]}; on 200 apply the cooldown filter
     * + cache, on 404/5xx drain the body and recurse to the next member.
     * Once the index walks past the end, fall back to the last-known-good
     * stale cache, then 404.
     */
    private CompletableFuture<Response> tryMembersSequentially(
        final RequestLine line,
        final Headers headers,
        final String path,
        final String cacheKey,
        final int idx,
        final long fetchStartTime
    ) {
        if (idx >= this.members.size()) {
            return staleFallbackOrNotFound(path, cacheKey, fetchStartTime);
        }
        final String member = this.members.get(idx);
        final Slice memberSlice = this.resolver.slice(
            new Key.From(member), this.port, this.depth + 1
        );
        // Member slices are wrapped in TrimPathSlice which expects paths
        // with the member name as the first segment.
        final RequestLine memberLine = rewritePath(line, member);
        return memberSlice.response(memberLine, dropFullPathHeader(headers), Content.EMPTY)
            .thenCompose(resp -> {
                if (resp.status() == RsStatus.OK) {
                    return readResponseBody(resp.body())
                        .thenCompose(rawBytes -> {
                            this.recordMemberBodySize(rawBytes.length);
                            return applyCooldownFilter(path, rawBytes)
                                .thenApply(filteredBytes -> {
                                    final long fetchDuration =
                                        System.currentTimeMillis() - fetchStartTime;
                                    // Cache the filtered winning bytes so
                                    // late callers + the L2 cache see the
                                    // post-cooldown view, never an
                                    // unfiltered blob that pre-dates the
                                    // current cooldown policy.
                                    MavenGroupSlice.this.metadataCache.put(
                                        cacheKey, filteredBytes
                                    );
                                    recordMetadataOperation("fetch", fetchDuration);
                                    if (fetchDuration > 500) {
                                        EcsLogger.debug("com.auto1.pantera.maven")
                                            .message("Slow sequential metadata fetch")
                                            .eventCategory("web")
                                            .eventAction("metadata_fetch")
                                            .eventOutcome("success")
                                            .field("repository.name", this.group)
                                            .field("url.path", path)
                                            .field("event.duration", fetchDuration)
                                            .field("repository.member", member)
                                            .log();
                                    }
                                    return ResponseBuilder.ok()
                                        .header("Content-Type", "application/xml")
                                        .body(filteredBytes)
                                        .build();
                                });
                        });
                }
                // Non-OK: drain body to release upstream connection, then
                // recurse to the next member. 404 is normal (artifact not
                // present in this member); 5xx logs at WARN and falls
                // through identically — the next member may still answer.
                if (resp.status() != RsStatus.NOT_FOUND) {
                    EcsLogger.warn("com.auto1.pantera.maven")
                        .message("Member returned non-OK status; trying next")
                        .eventCategory("web")
                        .eventAction("metadata_fetch")
                        .eventOutcome("failure")
                        .field("repository.name", this.group)
                        .field("repository.member", member)
                        .field("http.response.status_code", resp.status().code())
                        .field("url.path", path)
                        .log();
                }
                return resp.body().asBytesFuture()
                    .thenCompose(drained -> tryMembersSequentially(
                        line, headers, path, cacheKey, idx + 1, fetchStartTime
                    ));
            })
            .exceptionally(err -> {
                EcsLogger.warn("com.auto1.pantera.maven")
                    .message("Member fetch threw; trying next: " + member)
                    .eventCategory("web")
                    .eventAction("metadata_fetch")
                    .eventOutcome("failure")
                    .field("repository.name", this.group)
                    .field("repository.member", member)
                    .field("url.path", path)
                    .error(err)
                    .log();
                return null;
            })
            .thenCompose(resp -> {
                if (resp != null) {
                    return CompletableFuture.completedFuture(resp);
                }
                // Exception path collapsed to null — try next.
                return tryMembersSequentially(
                    line, headers, path, cacheKey, idx + 1, fetchStartTime
                );
            });
    }

    /**
     * All members exhausted: return last-known-good stale bytes if the L2
     * cache has them, otherwise 404.
     */
    private CompletableFuture<Response> staleFallbackOrNotFound(
        final String path,
        final String cacheKey,
        final long fetchStartTime
    ) {
        final long fetchDuration = System.currentTimeMillis() - fetchStartTime;
        return this.metadataCache.getStale(cacheKey)
            .thenApply(stale -> {
                if (stale.isPresent()) {
                    EcsLogger.warn("com.auto1.pantera.maven")
                        .message("Returning stale metadata (all members 404)")
                        .eventCategory("web")
                        .eventAction("metadata_fetch")
                        .eventOutcome("success")
                        .field("event.reason", "stale_cache_fallback")
                        .field("repository.name", this.group)
                        .field("url.path", path)
                        .field("event.duration", fetchDuration)
                        .log();
                    return ResponseBuilder.ok()
                        .header("Content-Type", "application/xml")
                        .body(stale.get())
                        .build();
                }
                EcsLogger.warn("com.auto1.pantera.maven")
                    .message("No member returned metadata and no stale fallback")
                    .eventCategory("web")
                    .eventAction("metadata_fetch")
                    .eventOutcome("failure")
                    .field("repository.name", this.group)
                    .field("url.path", path)
                    .field("event.duration", fetchDuration)
                    .log();
                return ResponseBuilder.notFound().build();
            });
    }

    /**
     * Run the merged metadata through the cooldown filter so any version that
     * is currently blocked by a per-artifact cooldown (whether it survived
     * because a hosted member's metadata is unfiltered, or because a stale
     * cached blob from before the policy change leaked through) is stripped
     * before reaching the client.
     *
     * <p>If the filter throws {@code AllVersionsBlockedException}, the merged
     * bytes are returned unmodified rather than failing the request — the
     * caller's metadata cache+serve flow then matches the proxy slice's
     * fall-through behaviour. Any other failure is logged and we return the
     * unfiltered bytes (defence in depth: a filter bug must not break the
     * group response).
     */
    private CompletableFuture<byte[]> applyCooldownFilter(
        final String path, final byte[] mergedBytes
    ) {
        if (this.cooldownMetadata
            instanceof com.auto1.pantera.cooldown.metadata.NoopCooldownMetadataService) {
            return CompletableFuture.completedFuture(mergedBytes);
        }
        final Optional<String> pkgOpt =
            new com.auto1.pantera.maven.cooldown.MavenMetadataRequestDetector()
                .extractPackageName(path);
        if (pkgOpt.isEmpty()) {
            return CompletableFuture.completedFuture(mergedBytes);
        }
        // Convert slashed groupId/artifactId path (e.g. "com/google/guava/guava")
        // to the dotted Maven coordinate ("com.google.guava.guava") that the
        // publish-date sources and cooldown DB blocks both use as the canonical
        // key. Without this, MavenHeadSource.fetch() splits on the last dot
        // and returns empty (no dot in the slashed form), the inspector returns
        // empty, cooldown.evaluate() fails open, and nothing gets filtered.
        final String dottedPackage = pkgOpt.get().replace('/', '.');
        // Base repo type ("maven" or "gradle") — the cooldown SPI is keyed
        // by base type, not the group suffix. Without the strip,
        // settings.enabledFor("maven-group") returns false and the filter
        // is skipped entirely.
        final String baseType = this.repoType.endsWith("-group")
            ? this.repoType.substring(0, this.repoType.length() - "-group".length())
            : this.repoType;
        // Inspector wired to the global publish-date registry so the filter
        // can resolve "is this version too fresh?" without requiring a
        // pre-existing per-member block in the cooldown DB. At the group
        // layer there's no canonical "member repo" to look up blocks
        // under — every member's blocks are independent — so we evaluate
        // by publish date directly.
        final com.auto1.pantera.cooldown.api.CooldownInspector inspector =
            new com.auto1.pantera.publishdate.RegistryBackedInspector(
                baseType,
                com.auto1.pantera.publishdate.PublishDateRegistries.instance()
            );
        return this.cooldownMetadata.filterMetadata(
            baseType,
            this.group,
            dottedPackage,
            mergedBytes,
            new com.auto1.pantera.maven.cooldown.MavenMetadataParser(),
            new com.auto1.pantera.maven.cooldown.MavenMetadataFilter(),
            new com.auto1.pantera.maven.cooldown.MavenMetadataRewriter(),
            Optional.of(inspector)
        ).exceptionally(err -> {
            EcsLogger.warn("com.auto1.pantera.maven")
                .message("Cooldown filter on merged group metadata failed; "
                    + "serving unfiltered bytes")
                .eventCategory("database")
                .eventAction("metadata_filter")
                .eventOutcome("failure")
                .field("repository.name", this.group)
                .field("url.path", path)
                .error(err)
                .log();
            return mergedBytes;
        });
    }

    /**
     * Alert-only histogram of per-member metadata body size. Never
     * rejects; outliers are surfaced via the histogram's high quantiles.
     * No-op when {@link com.auto1.pantera.metrics.MicrometerMetrics} is
     * not initialised (e.g. unit tests).
     */
    private void recordMemberBodySize(final long bytes) {
        if (!com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            return;
        }
        DistributionSummary.builder("pantera.maven.group.member_metadata_size_bytes")
            .description("Maven group member maven-metadata.xml body size (alert-only)")
            .baseUnit("bytes")
            .tags("repo_name", this.group)
            .register(
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance().getRegistry()
            )
            .record(bytes);
    }

    /**
     * Read entire response body into byte array.
     */
    private CompletableFuture<byte[]> readResponseBody(final Content content) {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        
        content.subscribe(new org.reactivestreams.Subscriber<>() {
            private org.reactivestreams.Subscription subscription;
            
            @Override
            public void onSubscribe(final org.reactivestreams.Subscription sub) {
                this.subscription = sub;
                sub.request(Long.MAX_VALUE);
            }
            
            @Override
            public void onNext(final ByteBuffer buffer) {
                final byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                try {
                    baos.write(bytes);
                } catch (Exception e) {
                    result.completeExceptionally(e);
                    this.subscription.cancel();
                }
            }
            
            @Override
            public void onError(final Throwable err) {
                result.completeExceptionally(err);
            }
            
            @Override
            public void onComplete() {
                result.complete(baos.toByteArray());
            }
        });
        
        return result;
    }

    /**
     * Drop X-FullPath header to avoid recursion detection issues.
     */
    private static Headers dropFullPathHeader(final Headers headers) {
        return new Headers(
            headers.asList().stream()
                .filter(h -> !"X-FullPath".equalsIgnoreCase(h.getKey()))
                .toList()
        );
    }

    /**
     * Rewrite request path to include member repository name.
     *
     * <p>Member slices are wrapped in TrimPathSlice which expects paths with member prefix.
     * This method adds the member prefix to the path.
     *
     * <p>Example: /org/apache/maven/plugins/maven-metadata.xml → /member/org/apache/maven/plugins/maven-metadata.xml
     *
     * @param original Original request line
     * @param member Member repository name to prefix
     * @return Rewritten request line with member prefix
     */
    private static RequestLine rewritePath(final RequestLine original, final String member) {
        final URI uri = original.uri();
        final String raw = uri.getRawPath();
        final String base = raw.startsWith("/") ? raw : "/" + raw;
        final String prefix = "/" + member + "/";

        // Avoid double-prefixing
        final String path = base.startsWith(prefix) ? base : ("/" + member + base);

        final StringBuilder full = new StringBuilder(path);
        if (uri.getRawQuery() != null) {
            full.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            full.append('#').append(uri.getRawFragment());
        }

        try {
            return new RequestLine(
                original.method(),
                new URI(full.toString()),
                original.version()
            );
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Failed to rewrite path", ex);
        }
    }

    private void recordMetadataOperation(final String operation, final long duration) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordMetadataOperation(this.group, "maven", operation);
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordMetadataGenerationDuration(this.group, "maven", duration);
        }
    }

}
