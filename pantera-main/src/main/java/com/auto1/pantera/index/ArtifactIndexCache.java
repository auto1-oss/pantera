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
package com.auto1.pantera.index;

import com.auto1.pantera.cache.ArtifactIndexCacheConfig;
import com.auto1.pantera.cache.CacheInvalidationPubSub;
import com.auto1.pantera.http.log.EcsLogger;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Two-tier (L1 Caffeine + L2 Valkey) cache around
 * {@link ArtifactIndex#locateByName(String)} with cluster-aware <em>surgical</em>
 * invalidation.
 *
 * <p>Sized and TTL'd via {@link ArtifactIndexCacheConfig}; absence of YAML
 * configuration preserves the v2.2.0 defaults (positive 50k entries / 10
 * min TTL, negative 50k / 30s). Both tiers can opt in to a Valkey L2 via
 * the standard {@code caches.artifact-index-positive.valkey} and
 * {@code caches.artifact-index-negative.valkey} blocks.
 *
 * <h2>Surgical invalidation</h2>
 *
 * <p>Older versions of this cache called {@link #invalidate(String)} after
 * every upload, which dropped <em>both</em> the positive and negative entries
 * for the artifact name. That forced a DB round-trip on the next read even
 * when the only change was "now there's one more repo that serves this
 * artifact". The current API expresses uploads and deletes as deltas:
 *
 * <ul>
 *   <li>{@link #recordUpload(String, String)} — append {@code repoName} to
 *       the positive entry (creating it if absent) and drop the negative
 *       entry. No DB hit on the next read.</li>
 *   <li>{@link #recordDelete(String, String)} — remove {@code repoName} from
 *       the positive entry; drop the entry entirely when the list becomes
 *       empty.</li>
 *   <li>{@link #invalidate(String)} — escape hatch that drops both tiers.
 *       Useful for admin / unknown-state recovery; <b>not</b> the right
 *       primitive for normal upload-driven invalidation.</li>
 * </ul>
 *
 * <h2>Cluster propagation</h2>
 *
 * <p>When wired with a {@link CacheInvalidationPubSub}, each delta is
 * broadcast over the existing {@code pantera:cache:invalidate} channel under
 * namespace {@code artifact-index}. The message body encodes the operation
 * — {@code "upload|<name>|<repoName>"}, {@code "delete|<name>|<repoName>"},
 * {@code "invalidate|<name>"} — so peer nodes can apply the same delta to
 * their local L1 without round-tripping to the DB. Self-messages are filtered
 * by the pub/sub layer's {@code instanceId} check.
 *
 * @since 2.2.0
 */
public final class ArtifactIndexCache implements ArtifactIndex {

    /** Pub/sub namespace used to scope artifact-index deltas. */
    static final String PUBSUB_NAMESPACE = "artifact-index";

    /** L2 Redis key prefix for the positive set (members are UTF-8 repo names). */
    private static final String L2_POSITIVE_PREFIX = "aix:p:";

    /** L2 Redis key prefix for the negative sentinel (value is {@code 1}). */
    private static final String L2_NEGATIVE_PREFIX = "aix:n:";

    /** Sentinel byte for the L2 negative key (presence-only). */
    private static final byte[] L2_NEGATIVE_VALUE = { (byte) '1' };

    /** Pub/sub op codes. */
    private static final String OP_UPLOAD = "upload";
    private static final String OP_DELETE = "delete";
    private static final String OP_INVALIDATE = "invalidate";

    private final ArtifactIndex delegate;
    private final ArtifactIndexCacheConfig positiveConfig;
    private final ArtifactIndexCacheConfig negativeConfig;
    private final Cache<String, List<String>> positive;
    private final Cache<String, Boolean> negative;
    private final ConcurrentMap<String, CompletableFuture<Optional<List<String>>>> inFlight;
    private final RedisAsyncCommands<String, byte[]> l2;
    private final CacheInvalidationPubSub pubSub;

    /**
     * Convenience constructor for tests and single-instance deployments
     * without Valkey: defaults, L1 only, no pub/sub.
     *
     * @param delegate underlying {@link ArtifactIndex}
     */
    public ArtifactIndexCache(final ArtifactIndex delegate) {
        this(
            delegate,
            ArtifactIndexCacheConfig.positiveDefaults(),
            ArtifactIndexCacheConfig.negativeDefaults(),
            Optional.empty(),
            Optional.empty()
        );
    }

    /**
     * Full constructor used by the production wiring in {@code YamlSettings}.
     *
     * @param delegate underlying {@link ArtifactIndex}
     * @param positiveConfig settings for the positive tier
     * @param negativeConfig settings for the negative tier
     * @param l2 optional Valkey commands for the shared L2 tier; absent
     *     means L1-only operation
     * @param pubSub optional pub/sub bus for cross-node delta propagation;
     *     absent means single-instance operation
     */
    public ArtifactIndexCache(
        final ArtifactIndex delegate,
        final ArtifactIndexCacheConfig positiveConfig,
        final ArtifactIndexCacheConfig negativeConfig,
        final Optional<RedisAsyncCommands<String, byte[]>> l2,
        final Optional<CacheInvalidationPubSub> pubSub
    ) {
        this.delegate = delegate;
        this.positiveConfig = positiveConfig;
        this.negativeConfig = negativeConfig;
        this.positive = Caffeine.newBuilder()
            .maximumSize(positiveConfig.l1MaxSize())
            .expireAfterWrite(positiveConfig.l1Ttl())
            .recordStats()
            .build();
        this.negative = Caffeine.newBuilder()
            .maximumSize(negativeConfig.l1MaxSize())
            .expireAfterWrite(negativeConfig.l1Ttl())
            .recordStats()
            .build();
        this.inFlight = new ConcurrentHashMap<>();
        this.l2 = l2.orElse(null);
        this.pubSub = pubSub.orElse(null);
        if (this.pubSub != null) {
            this.pubSub.subscribe(PUBSUB_NAMESPACE, this::applyRemoteDelta);
        }
    }

    @Override
    public CompletableFuture<Optional<List<String>>> locateByName(final String artifactName) {
        if (artifactName == null || artifactName.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        final List<String> hit = this.positive.getIfPresent(artifactName);
        if (hit != null) {
            return CompletableFuture.completedFuture(Optional.of(hit));
        }
        if (this.negative.getIfPresent(artifactName) != null) {
            return CompletableFuture.completedFuture(Optional.of(Collections.emptyList()));
        }
        if (this.l2 != null) {
            return this.l2Lookup(artifactName);
        }
        return this.dbLookup(artifactName);
    }

    /**
     * Try L2 (Valkey) before falling through to the DB. Promotes any L2 hit
     * back into L1 so subsequent reads on this node stay local.
     */
    private CompletableFuture<Optional<List<String>>> l2Lookup(final String artifactName) {
        final long timeoutMs = Math.max(
            this.positiveConfig.l2Timeout().toMillis(),
            this.negativeConfig.l2Timeout().toMillis()
        );
        return this.l2.smembers(L2_POSITIVE_PREFIX + artifactName)
            .toCompletableFuture()
            .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .handle((members, err) -> {
                if (err != null) {
                    logL2Failure("smembers", artifactName, err);
                    return null;
                }
                return members;
            })
            .thenCompose(members -> {
                if (members != null && !members.isEmpty()) {
                    final List<String> decoded = decodeMembers(members);
                    this.positive.put(artifactName, decoded);
                    return CompletableFuture.completedFuture(Optional.of(decoded));
                }
                // No positive hit — try negative.
                return this.l2.exists(L2_NEGATIVE_PREFIX + artifactName)
                    .toCompletableFuture()
                    .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .handle((count, err) -> count == null ? 0L : count)
                    .thenCompose(count -> {
                        if (count != null && count > 0) {
                            this.negative.put(artifactName, Boolean.TRUE);
                            return CompletableFuture.completedFuture(
                                Optional.of(Collections.<String>emptyList())
                            );
                        }
                        return this.dbLookup(artifactName);
                    });
            });
    }

    /**
     * Fall through to the underlying {@link ArtifactIndex} with in-flight
     * coalescing so concurrent lookups for the same name share one DB call.
     * Result is cached in both L1 and (when configured) L2.
     */
    private CompletableFuture<Optional<List<String>>> dbLookup(final String artifactName) {
        final CompletableFuture<Optional<List<String>>>[] created = new CompletableFuture[1];
        final CompletableFuture<Optional<List<String>>> shared =
            this.inFlight.computeIfAbsent(artifactName, name -> {
                final CompletableFuture<Optional<List<String>>> promise = new CompletableFuture<>();
                created[0] = promise;
                return promise;
            });
        if (created[0] != null) {
            this.dispatchAndPopulate(artifactName, created[0]);
        }
        return shared;
    }

    private void dispatchAndPopulate(
        final String name,
        final CompletableFuture<Optional<List<String>>> promise
    ) {
        this.delegate.locateByName(name).whenComplete((opt, err) -> {
            // Remove from in-flight BEFORE completing the promise so any waiter
            // that wakes up and re-queries sees a clean map. Safe here because
            // this callback runs outside any computeIfAbsent on `inFlight`.
            this.inFlight.remove(name);
            if (err == null && opt != null && opt.isPresent()) {
                final List<String> result = opt.get();
                if (result.isEmpty()) {
                    this.negative.put(name, Boolean.TRUE);
                    this.l2WriteNegative(name);
                } else {
                    this.positive.put(name, result);
                    this.l2WritePositive(name, result);
                }
            }
            // Optional.empty() means DB error — do NOT cache, but DO complete
            // the promise so callers can fall back to fanout.
            if (err != null) {
                promise.completeExceptionally(err);
            } else {
                promise.complete(opt == null ? Optional.empty() : opt);
            }
        });
    }

    /**
     * Surgical: register that {@code repoName} now contains the artifact
     * identified by {@code artifactName}. Appends to the positive entry
     * (creating it if absent), drops the negative entry, and broadcasts
     * the delta over pub/sub so other nodes apply the same change.
     *
     * <p>Idempotent: re-registering an existing (name, repoName) pair is
     * a no-op for cache state.
     *
     * @param artifactName artifact identifier
     * @param repoName repository that just received this artifact
     */
    public void recordUpload(final String artifactName, final String repoName) {
        if (artifactName == null || artifactName.isEmpty()
            || repoName == null || repoName.isEmpty()) {
            return;
        }
        this.applyUploadLocal(artifactName, repoName);
        this.l2RecordUpload(artifactName, repoName);
        this.publish(OP_UPLOAD, artifactName, repoName);
    }

    /**
     * Surgical: register that {@code repoName} no longer contains the
     * artifact identified by {@code artifactName}. Removes {@code repoName}
     * from the positive entry; if the resulting list is empty the entry
     * is dropped (next read goes to the DB, which is the right behaviour
     * since the cache no longer knows of any holder).
     *
     * @param artifactName artifact identifier
     * @param repoName repository the artifact was just removed from
     */
    public void recordDelete(final String artifactName, final String repoName) {
        if (artifactName == null || artifactName.isEmpty()
            || repoName == null || repoName.isEmpty()) {
            return;
        }
        this.applyDeleteLocal(artifactName, repoName);
        this.l2RecordDelete(artifactName, repoName);
        this.publish(OP_DELETE, artifactName, repoName);
    }

    /**
     * Escape hatch: drop both the positive and negative entry for
     * {@code artifactName} and broadcast the invalidation to peers. Prefer
     * {@link #recordUpload}/{@link #recordDelete} for normal flows so peers
     * stay warm; reach for this only when the cache state is unknown.
     *
     * @param artifactName artifact identifier
     */
    public void invalidate(final String artifactName) {
        if (artifactName == null || artifactName.isEmpty()) {
            return;
        }
        this.positive.invalidate(artifactName);
        this.negative.invalidate(artifactName);
        this.l2Delete(artifactName);
        this.publish(OP_INVALIDATE, artifactName, null);
    }

    /**
     * Diagnostics for the admin/stats endpoint.
     *
     * @return human-readable stats string covering both cache tiers
     */
    public String stats() {
        return "positive=" + this.positive.stats() + " negative=" + this.negative.stats();
    }

    // ---- local L1 mutation ----

    private void applyUploadLocal(final String name, final String repoName) {
        this.positive.asMap().compute(name, (k, existing) -> {
            if (existing == null) {
                return List.of(repoName);
            }
            if (existing.contains(repoName)) {
                return existing;
            }
            final List<String> next = new ArrayList<>(existing.size() + 1);
            next.addAll(existing);
            next.add(repoName);
            return List.copyOf(next);
        });
        this.negative.invalidate(name);
    }

    private void applyDeleteLocal(final String name, final String repoName) {
        this.positive.asMap().compute(name, (k, existing) -> {
            if (existing == null || !existing.contains(repoName)) {
                return existing;
            }
            if (existing.size() == 1) {
                return null;
            }
            final List<String> next = new ArrayList<>(existing.size() - 1);
            for (final String r : existing) {
                if (!r.equals(repoName)) {
                    next.add(r);
                }
            }
            return List.copyOf(next);
        });
    }

    // ---- L2 write paths (fire-and-forget with logging) ----

    private void l2RecordUpload(final String name, final String repoName) {
        if (this.l2 == null) {
            return;
        }
        final byte[] member = repoName.getBytes(StandardCharsets.UTF_8);
        this.l2.sadd(L2_POSITIVE_PREFIX + name, member)
            .toCompletableFuture()
            .thenCompose(ignored -> this.l2.expire(
                L2_POSITIVE_PREFIX + name,
                this.positiveConfig.l2Ttl().getSeconds()
            ).toCompletableFuture())
            .exceptionally(err -> {
                logL2Failure("sadd/expire", name, err);
                return null;
            });
        this.l2.del(L2_NEGATIVE_PREFIX + name)
            .toCompletableFuture()
            .exceptionally(err -> {
                logL2Failure("del-negative", name, err);
                return null;
            });
    }

    private void l2RecordDelete(final String name, final String repoName) {
        if (this.l2 == null) {
            return;
        }
        final byte[] member = repoName.getBytes(StandardCharsets.UTF_8);
        this.l2.srem(L2_POSITIVE_PREFIX + name, member)
            .toCompletableFuture()
            .thenCompose(ignored -> this.l2.scard(L2_POSITIVE_PREFIX + name).toCompletableFuture())
            .thenAccept(card -> {
                if (card != null && card == 0L) {
                    this.l2.del(L2_POSITIVE_PREFIX + name);
                }
            })
            .exceptionally(err -> {
                logL2Failure("srem", name, err);
                return null;
            });
    }

    private void l2WritePositive(final String name, final List<String> repos) {
        if (this.l2 == null || repos.isEmpty()) {
            return;
        }
        final byte[][] members = new byte[repos.size()][];
        for (int i = 0; i < repos.size(); i++) {
            members[i] = repos.get(i).getBytes(StandardCharsets.UTF_8);
        }
        this.l2.sadd(L2_POSITIVE_PREFIX + name, members)
            .toCompletableFuture()
            .thenCompose(ignored -> this.l2.expire(
                L2_POSITIVE_PREFIX + name,
                this.positiveConfig.l2Ttl().getSeconds()
            ).toCompletableFuture())
            .exceptionally(err -> {
                logL2Failure("warm-sadd", name, err);
                return null;
            });
    }

    private void l2WriteNegative(final String name) {
        if (this.l2 == null) {
            return;
        }
        this.l2.setex(
            L2_NEGATIVE_PREFIX + name,
            this.negativeConfig.l2Ttl().getSeconds(),
            L2_NEGATIVE_VALUE
        ).toCompletableFuture()
            .exceptionally(err -> {
                logL2Failure("setex-negative", name, err);
                return null;
            });
    }

    private void l2Delete(final String name) {
        if (this.l2 == null) {
            return;
        }
        this.l2.del(L2_POSITIVE_PREFIX + name, L2_NEGATIVE_PREFIX + name)
            .toCompletableFuture()
            .exceptionally(err -> {
                logL2Failure("invalidate-del", name, err);
                return null;
            });
    }

    // ---- pub/sub fan-out + receive ----

    private void publish(final String op, final String name, final String repoName) {
        if (this.pubSub == null) {
            return;
        }
        final String key = repoName == null
            ? op + "|" + name
            : op + "|" + name + "|" + repoName;
        this.pubSub.publish(PUBSUB_NAMESPACE, key);
    }

    /**
     * Apply a delta received from another node's pub/sub broadcast.
     * Self-messages are filtered upstream by {@link CacheInvalidationPubSub}.
     * Only L1 is touched here — the publishing node has already updated
     * the shared L2 tier (or is single-instance L1-only too).
     */
    private void applyRemoteDelta(final String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        final String[] parts = key.split("\\|", 3);
        if (parts.length < 2) {
            return;
        }
        final String op = parts[0];
        final String name = parts[1];
        switch (op) {
            case OP_UPLOAD:
                if (parts.length == 3) {
                    this.applyUploadLocal(name, parts[2]);
                }
                break;
            case OP_DELETE:
                if (parts.length == 3) {
                    this.applyDeleteLocal(name, parts[2]);
                }
                break;
            case OP_INVALIDATE:
                this.positive.invalidate(name);
                this.negative.invalidate(name);
                break;
            default:
                // Unknown op — ignore. Forward-compatible with future delta
                // codes.
                break;
        }
    }

    // ---- helpers ----

    private static List<String> decodeMembers(final Set<byte[]> members) {
        return members.stream()
            .map(b -> new String(b, StandardCharsets.UTF_8))
            .sorted()
            .collect(Collectors.toUnmodifiableList());
    }

    private static void logL2Failure(final String op, final String name, final Throwable err) {
        final Throwable cause = err instanceof TimeoutException ? err : unwrap(err);
        EcsLogger.debug("com.auto1.pantera.index")
            .message("ArtifactIndexCache L2 " + op + " failed; "
                + "continuing on L1 only")
            .eventCategory("database")
            .eventAction("artifact_index_cache_l2")
            .eventOutcome("failure")
            .field("package.name", name)
            .error(cause)
            .log();
    }

    private static Throwable unwrap(final Throwable err) {
        Throwable t = err;
        // Bounded walk — depth cap also prevents pathological self-causing
        // exception cycles without needing reference comparison.
        for (int depth = 0; depth < 16 && t.getCause() != null; depth++) {
            t = t.getCause();
        }
        return t;
    }

    // ---- Pass-through ArtifactIndex methods (delegate everything else) ----

    @Override
    public CompletableFuture<Void> index(final ArtifactDocument doc) {
        return this.delegate.index(doc);
    }

    @Override
    public CompletableFuture<Void> remove(final String repoName, final String artifactPath) {
        return this.delegate.remove(repoName, artifactPath);
    }

    @Override
    public CompletableFuture<Integer> removePrefix(final String repoName, final String pathPrefix) {
        return this.delegate.removePrefix(repoName, pathPrefix);
    }

    @Override
    public CompletableFuture<SearchResult> search(
        final String query, final int maxResults, final int offset
    ) {
        return this.delegate.search(query, maxResults, offset);
    }

    @Override
    public CompletableFuture<SearchResult> search(
        final String query, final int maxResults, final int offset,
        final String repoType, final String repoName,
        final String sortBy, final boolean sortAsc
    ) {
        return this.delegate.search(query, maxResults, offset, repoType, repoName, sortBy, sortAsc);
    }

    @Override
    public CompletableFuture<List<String>> locate(final String artifactPath) {
        return this.delegate.locate(artifactPath);
    }

    @Override
    public boolean isWarmedUp() {
        return this.delegate.isWarmedUp();
    }

    @Override
    public void setWarmedUp() {
        this.delegate.setWarmedUp();
    }

    @Override
    public CompletableFuture<java.util.Map<String, Object>> getStats() {
        return this.delegate.getStats();
    }

    @Override
    public CompletableFuture<Void> indexBatch(final java.util.Collection<ArtifactDocument> docs) {
        return this.delegate.indexBatch(docs);
    }

    @Override
    public void close() throws IOException {
        this.delegate.close();
    }
}
