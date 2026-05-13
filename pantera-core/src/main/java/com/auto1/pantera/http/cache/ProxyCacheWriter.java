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
package com.auto1.pantera.http.cache;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.context.RequestContext;
import com.auto1.pantera.http.fault.Fault;
import com.auto1.pantera.http.fault.Fault.ChecksumAlgo;
import com.auto1.pantera.http.fault.Result;
import com.auto1.pantera.http.log.EcsLogger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.reactivex.Flowable;
import io.reactivex.schedulers.Schedulers;
import org.reactivestreams.Publisher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Single-source-of-truth writer that lands a primary proxy artifact and every
 * declared sidecar digest into the cache as an atomic, self-consistent pair.
 *
 * <p><b>Contract</b>
 * <ol>
 *   <li>Stream the primary upstream body into a local NIO temp file while
 *       computing all four digests (MD5, SHA-1, SHA-256, SHA-512) in a single
 *       pass — one {@link MessageDigest} per algorithm updated from the same
 *       chunk. Heap usage is bounded by the chunk size, not the artifact size.</li>
 *   <li>Fetch each declared sidecar concurrently. Sidecars are small
 *       (typically &lt;200 bytes) and fully buffered.</li>
 *   <li>For every sidecar that returns 200, compare the trimmed-lowercased hex
 *       body against the locally-computed digest for that algorithm.</li>
 *   <li>Any disagreement rejects the entire write: the temp file is deleted,
 *       nothing lands in the cache, the call returns
 *       {@code Result.err(new Fault.UpstreamIntegrity(...))}.</li>
 *   <li>All sidecars absent-or-match: save the primary first (via
 *       {@link Storage#save(Key, Content)} which itself renames atomically on
 *       {@code FileStorage}), then every sidecar. A partial failure after the
 *       primary is persisted is compensated by deleting whatever has been
 *       written — callers see a single {@code StorageUnavailable} fault and the
 *       cache ends up empty for this key, as if the write never happened.</li>
 * </ol>
 *
 * <p><b>Atomicity gap vs the {@code Storage} contract.</b> The {@link Storage}
 * interface has no multi-key transaction. {@code FileStorage.save} already
 * uses a "write to {@code .tmp/UUID}, then rename into place" sequence, so each
 * individual file is atomic with respect to concurrent readers, but the
 * <i>pair</i> (primary + sidecar) is only eventually-consistent during the
 * small window between the two renames. We save the primary before any
 * sidecar so a concurrent reader never sees a sidecar without its primary;
 * the opposite direction (primary without sidecar) is harmless — Maven
 * either falls back to the computed checksum or re-requests the sidecar.
 * The integrity audit tool ({@link IntegrityAuditor}) provides the
 * post-hoc heal for the narrow race where a sidecar write fails after the
 * primary landed; operators run it periodically.
 *
 * <p><b>Observability.</b> Emits Tier-4 {@code EcsLogger} events on
 * {@code com.auto1.pantera.cache} for every outcome with
 * {@code event.action=cache_write} and {@code event.outcome} in
 * {@code success | integrity_failure | partial_failure}. When a non-null
 * {@link MeterRegistry} is supplied, increments
 * {@code pantera.proxy.cache.integrity_failure} and
 * {@code pantera.proxy.cache.write_partial_failure} counters tagged with
 * {@code repo} and (for integrity failures) {@code algo}.
 *
 * @since 2.2.0
 */
public final class ProxyCacheWriter {

    /** Chunk size for streaming the primary body into the temp file. */
    private static final int CHUNK_SIZE = 64 * 1024;

    /** Shared hex formatter for digest comparison. */
    private static final HexFormat HEX = HexFormat.of();

    /**
     * Sidecar algorithms that are NOT load-bearing for the serve path.
     * Maven Central (and most upstreams) only publishes {@code .sha1} and
     * {@code .md5}; {@code .sha256} / {@code .sha512} usually 404 in
     * 200-300ms — and {@link #verifyOnly} previously waited on those slow
     * 404s before serving the primary. That alone added ~20s to a 720-
     * artifact cold {@code mvn dependency:resolve} (300ms × 720 ÷ 5 maven
     * worker threads ≈ 43s, overlap-amortized).
     *
     * <p>{@code MD5} is also deferred (Phase 7 perf bench, 2026-05): the
     * {@code .sha1} sidecar already proves primary integrity, so blocking
     * the foreground response on a second redundant integrity check costs
     * one extra upstream RTT per primary artifact for no additional
     * security guarantee. {@code .md5} still verifies + persists in the
     * background; clients that ask for the {@code .md5} after the fact
     * read it from cache (or, in the narrow window before the deferred
     * fetch completes, fall through to the standard cache-miss path which
     * proxies it through).</p>
     *
     * <p>For algos in this set the writer fires the upstream sidecar fetch
     * but does NOT block the {@link VerifiedArtifact} return on it. If the
     * upstream eventually returns 200 we verify the claim against the
     * already-computed digest and persist the sidecar in the background;
     * disagreement is logged + counted but does not retroactively reject
     * the primary (the primary integrity is already proven by the required
     * sidecars below).</p>
     *
     * <p>If a deployment requires strict {@code .md5}/{@code .sha256}/{@code .sha512}
     * blocking, use {@link #writeAndVerify(Key, String, Supplier, Map, Set,
     * RequestContext)} and pass {@link java.util.Collections#emptySet()} to
     * make every supplied sidecar load-bearing.</p>
     */
    private static final EnumSet<ChecksumAlgo> NON_BLOCKING_DEFAULT =
        EnumSet.of(ChecksumAlgo.MD5, ChecksumAlgo.SHA256, ChecksumAlgo.SHA512);

    /** No-op {@link CacheWriteEvent} consumer used when no callback is supplied. */
    private static final Consumer<CacheWriteEvent> NO_OP_ON_WRITE = event -> { };

    /** Repository name used in log fields and metric tags. */
    private final String repoName;

    /** Backing storage receiving the primary + sidecars. */
    private final Storage cache;

    /** Optional metrics registry; null disables metrics. */
    private final MeterRegistry metrics;

    /**
     * Optional post-write callback (Phase-3 extension point). Fires once per
     * successful primary cache-write; callback exceptions are caught + logged
     * and never propagate to the cache-write path. Defaults to a no-op.
     */
    private final Consumer<CacheWriteEvent> onWrite;

    /**
     * Ctor.
     *
     * @param cache    Storage receiving the primary artifact and its sidecars.
     * @param repoName Repository name, emitted as {@code repository.name} in
     *                 log events and {@code repo} in metric tags.
     * @param metrics  Optional meter registry. May be {@code null} if the
     *                 caller does not want metrics.
     * @param onWrite  Optional post-write callback. May be {@code null} (
     *                 treated as no-op). Throwables propagated from the
     *                 callback are caught + logged and do NOT affect the
     *                 cache-write outcome.
     */
    public ProxyCacheWriter(
        final Storage cache,
        final String repoName,
        final MeterRegistry metrics,
        final Consumer<CacheWriteEvent> onWrite
    ) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.repoName = Objects.requireNonNull(repoName, "repoName");
        this.metrics = metrics;
        this.onWrite = onWrite == null ? NO_OP_ON_WRITE : onWrite;
    }

    /**
     * Ctor with metrics; no explicit post-write callback. Falls back to the
     * shared callback installed in {@link CacheWriteCallbackRegistry} so a
     * future cache-write consumer wires in here too without surgery on
     * each adapter that constructs its own writer (Maven CachedProxySlice,
     * Go CachedProxySlice, Pypi CachedPyProxySlice, Composer CachedProxySlice).
     *
     * @param cache    Storage receiving the primary artifact and its sidecars.
     * @param repoName Repository name, emitted as {@code repository.name} in
     *                 log events and {@code repo} in metric tags.
     * @param metrics  Optional meter registry. May be {@code null} if the
     *                 caller does not want metrics.
     */
    public ProxyCacheWriter(
        final Storage cache, final String repoName, final MeterRegistry metrics
    ) {
        this(cache, repoName, metrics, CacheWriteCallbackRegistry.instance().sharedCallback());
    }

    /**
     * Convenience ctor without metrics or explicit post-write callback.
     * Falls back to the shared callback installed in
     * {@link CacheWriteCallbackRegistry}.
     *
     * @param cache    Storage.
     * @param repoName Repository name.
     */
    public ProxyCacheWriter(final Storage cache, final String repoName) {
        this(cache, repoName, null, CacheWriteCallbackRegistry.instance().sharedCallback());
    }

    /**
     * Write a primary artifact + every declared sidecar into the cache
     * atomically (per §9.5 of the v2.2 target architecture).
     *
     * @param primaryKey      Cache key of the primary artifact.
     * @param upstreamUri     Informational URI recorded on integrity failures.
     * @param fetchPrimary    Supplier that opens a fresh upstream stream. Must
     *                        not be {@code null}; is invoked exactly once.
     * @param fetchSidecars   Concurrent suppliers per algorithm that each
     *                        return {@code Optional.empty()} when the upstream
     *                        does not serve that sidecar (404 / IO error).
     * @param ctx             Request context used to attach {@code trace.id}
     *                        to log events; may be {@code null}.
     * @return A stage that completes with {@link Result.Ok} on a clean write,
     *         or {@link Result.Err} carrying {@link Fault.UpstreamIntegrity}
     *         (sidecar disagreed) or {@link Fault.StorageUnavailable}
     *         (atomic-move failed). Never throws; exceptions are captured as
     *         {@code Err}.
     */
    public CompletionStage<Result<Void>> writeWithSidecars(
        final Key primaryKey,
        final String upstreamUri,
        final Supplier<CompletionStage<InputStream>> fetchPrimary,
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> fetchSidecars,
        final RequestContext ctx
    ) {
        Objects.requireNonNull(primaryKey, "primaryKey");
        Objects.requireNonNull(fetchPrimary, "fetchPrimary");
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> sidecarFetchers =
            fetchSidecars == null ? Collections.emptyMap() : fetchSidecars;
        final Path tempFile;
        try {
            tempFile = Files.createTempFile("pantera-proxy-", ".tmp");
        } catch (final IOException ex) {
            return CompletableFuture.completedFuture(
                Result.err(new Fault.StorageUnavailable(ex, primaryKey.string()))
            );
        }
        return fetchPrimary.get()
            .thenCompose(stream -> this.streamPrimary(stream, tempFile))
            .thenCompose(digests -> this.fetchAndVerify(
                primaryKey, upstreamUri, tempFile, digests, sidecarFetchers, ctx
            ))
            .exceptionally(err -> {
                deleteQuietly(tempFile);
                final Throwable cause = unwrap(err);
                // Surface the underlying cause so 503 isn't a black box. The
                // outer caller maps this to ResponseBuilder.unavailable() with
                // no log of its own; without this, every cache-write failure
                // (upstream timeout, integrity reject, storage disk-full) is
                // indistinguishable in the access logs.
                EcsLogger.warn("com.auto1.pantera.cache")
                    .message("Proxy cache write failed; surfacing as 503")
                    .eventCategory("web")
                    .eventAction("cache_write")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("url.path", primaryKey.string())
                    .error(cause)
                    .log();
                return Result.err(new Fault.StorageUnavailable(
                    cause, primaryKey.string()
                ));
            });
    }

    /**
     * Write + verify without committing; returns a {@link VerifiedArtifact}
     * that the caller can serve from immediately, then commit async.
     *
     * <p>Uses the default {@link #NON_BLOCKING_DEFAULT} non-blocking set
     * ({@code SHA256}, {@code SHA512}). To override, call
     * {@link #writeAndVerify(Key, String, Supplier, Map, Set, RequestContext)}.</p>
     */
    public CompletionStage<Result<VerifiedArtifact>> writeAndVerify(
        final Key primaryKey,
        final String upstreamUri,
        final Supplier<CompletionStage<InputStream>> fetchPrimary,
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> fetchSidecars,
        final RequestContext ctx
    ) {
        return this.writeAndVerify(
            primaryKey, upstreamUri, fetchPrimary, fetchSidecars,
            NON_BLOCKING_DEFAULT, ctx
        );
    }

    /**
     * Write + verify without committing; returns a {@link VerifiedArtifact}
     * that the caller can serve from immediately, then commit async.
     *
     * @param nonBlockingAlgos sidecar algorithms whose upstream fetch must NOT
     *     block the {@link VerifiedArtifact} return — they save themselves in
     *     the background if/when the upstream eventually responds.
     */
    public CompletionStage<Result<VerifiedArtifact>> writeAndVerify(
        final Key primaryKey,
        final String upstreamUri,
        final Supplier<CompletionStage<InputStream>> fetchPrimary,
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> fetchSidecars,
        final Set<ChecksumAlgo> nonBlockingAlgos,
        final RequestContext ctx
    ) {
        Objects.requireNonNull(primaryKey, "primaryKey");
        Objects.requireNonNull(fetchPrimary, "fetchPrimary");
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> sidecarFetchers =
            fetchSidecars == null ? Collections.emptyMap() : fetchSidecars;
        final Set<ChecksumAlgo> nonBlocking = nonBlockingAlgos == null
            ? Collections.emptySet() : nonBlockingAlgos;
        final Path tempFile;
        try {
            tempFile = Files.createTempFile("pantera-proxy-", ".tmp");
        } catch (final IOException ex) {
            return CompletableFuture.completedFuture(
                Result.err(new Fault.StorageUnavailable(ex, primaryKey.string()))
            );
        }
        // Phase 7.5 perf (2026-05): fire blocking sidecar fetches IN PARALLEL
        // with the primary fetch instead of waiting for the primary to finish
        // streaming before kicking off the sidecar request. The primary +
        // .sha1 are independent upstream resources sharing the same H2 pool;
        // serialising them added one full RTT (~30 ms) per cache miss, which
        // measured as 7-8 s of wall time across a 263-cache-miss cold mvn
        // walk (Phase 7.5 profiler: pre_process_branch_sum 31.45 s / 478
        // calls). Non-blocking sidecars (md5, sha256, sha512 by default) are
        // already deferred — only blocking sidecars (sha1) need to overlap.
        final Map<ChecksumAlgo, CompletableFuture<SidecarFetch>> blockingSidecarFutures =
            new EnumMap<>(ChecksumAlgo.class);
        for (final Map.Entry<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> entry
                : sidecarFetchers.entrySet()) {
            if (nonBlocking.contains(entry.getKey())) {
                continue;
            }
            blockingSidecarFutures.put(
                entry.getKey(),
                entry.getValue().get()
                    .toCompletableFuture()
                    .thenApply(opt -> new SidecarFetch(
                        entry.getKey(), opt.map(ProxyCacheWriter::readSmall)
                    ))
                    .exceptionally(err -> new SidecarFetch(
                        entry.getKey(), Optional.empty()
                    ))
            );
        }
        return fetchPrimary.get()
            .thenCompose(stream -> this.streamPrimary(stream, tempFile))
            .thenCompose(digests -> this.verifyOnly(
                primaryKey, upstreamUri, tempFile, digests,
                sidecarFetchers, nonBlocking, blockingSidecarFutures, ctx
            ))
            .exceptionally(err -> {
                deleteQuietly(tempFile);
                final Throwable cause = unwrap(err);
                EcsLogger.warn("com.auto1.pantera.cache")
                    .message("Proxy cache write-and-verify failed")
                    .eventCategory("web")
                    .eventAction("cache_write")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("url.path", primaryKey.string())
                    .error(cause)
                    .log();
                return Result.err(new Fault.StorageUnavailable(
                    cause, primaryKey.string()
                ));
            });
    }

    /**
     * Stream-through cache write (Track 4): tee the upstream body into the
     * client response Publisher AND the local temp file in a single pass.
     *
     * <p>Unlike {@link #writeAndVerify}, this method does NOT block the
     * client on draining the upstream body before returning. The returned
     * {@link StreamedArtifact#body()} is a {@link Content} the caller passes
     * directly to {@code ResponseBuilder.body(...)}; once the response is
     * committed, Jetty subscribes and bytes flow upstream &rarr; tee &rarr;
     * client as they arrive. Each chunk is simultaneously written to a
     * temp file and fed into every {@link MessageDigest}. When the stream
     * completes, the writer awaits the already-in-flight blocking sidecar
     * fetches (Phase 7.5 parallel-prefetch trick), compares hex, and either
     * commits the primary + sidecars (sidecar-first per Track 3) or drops
     * the temp file with an integrity-failure log + metric.
     *
     * <p><b>Always-verify invariant for the CACHE is preserved.</b> A
     * primary lands on disk only after the upstream {@code .sha1} agrees
     * with the bytes we just emitted. A mismatch means the client received
     * unverified bytes (which Maven re-checks against its own digest of
     * what it downloaded — same semantics as Nexus/JFrog stream-through),
     * but the cache stays empty for that key so the next request re-fetches
     * cleanly. The trade-off the user accepted in Track 4 design: faster
     * time-to-first-byte vs. ability to refuse the response on mismatch.
     *
     * <p>The {@link StreamedArtifact#verificationOutcome()} future is
     * fire-and-forget for the proxy hot path — the caller does NOT block on
     * it. It exists for tests and integration code that wants to observe
     * the final commit result.
     *
     * @param primaryKey      Cache key of the primary artifact.
     * @param upstreamUri     Informational URI recorded on integrity failures.
     * @param upstreamSize    Optional Content-Length from the upstream
     *                        response; forwarded to the response body so
     *                        Jetty emits an exact-size response when known.
     * @param upstreamBody    Upstream response body publisher. Subscribed
     *                        exactly once by the tee; must not have been
     *                        consumed by the caller.
     * @param fetchSidecars   Per-algorithm sidecar fetchers (typically just
     *                        {@code SHA1} in the Maven adapter).
     * @param nonBlockingAlgos Algorithms whose sidecar fetch must NOT block
     *                        the post-stream verify-and-commit (default
     *                        when null: {@link #NON_BLOCKING_DEFAULT}). These
     *                        sidecars are fired asynchronously and persisted
     *                        in the background if upstream returns 200.
     * @param ctx             Request context (trace id), may be null.
     * @return Stage that completes synchronously with a {@link StreamedArtifact}
     *         except in the narrow case where temp file creation itself fails;
     *         in that case the caller receives Err(StorageUnavailable) and the
     *         upstream body is left unsubscribed for the caller to drain.
     */
    public CompletionStage<Result<StreamedArtifact>> streamThroughAndCommit(
        final Key primaryKey,
        final String upstreamUri,
        final Optional<Long> upstreamSize,
        final Publisher<ByteBuffer> upstreamBody,
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> fetchSidecars,
        final Set<ChecksumAlgo> nonBlockingAlgos,
        final RequestContext ctx
    ) {
        Objects.requireNonNull(primaryKey, "primaryKey");
        Objects.requireNonNull(upstreamBody, "upstreamBody");
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> fetchers =
            fetchSidecars == null ? Collections.emptyMap() : fetchSidecars;
        final Set<ChecksumAlgo> nonBlocking = nonBlockingAlgos == null
            ? NON_BLOCKING_DEFAULT : nonBlockingAlgos;
        final Path tempFile;
        try {
            tempFile = Files.createTempFile("pantera-proxy-", ".tmp");
        } catch (final IOException ex) {
            return CompletableFuture.completedFuture(
                Result.err(new Fault.StorageUnavailable(ex, primaryKey.string()))
            );
        }
        final FileChannel channel; // NOPMD CloseResource - closed asynchronously inside doOnComplete/doOnError/doOnCancel terminal callbacks; try-with-resources would close it before any tee chunk could be written
        try {
            channel = FileChannel.open(
                tempFile,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (final IOException ex) {
            deleteQuietly(tempFile);
            return CompletableFuture.completedFuture(
                Result.err(new Fault.StorageUnavailable(ex, primaryKey.string()))
            );
        }
        // Phase 7.5 parallel-prefetch trick (same as writeAndVerify): fire
        // every blocking sidecar fetch BEFORE we start consuming the primary
        // body. By the time the tee onComplete fires, the .sha1 future is
        // typically already resolved — the verify step adds zero RTT on the
        // critical path.
        final Map<ChecksumAlgo, CompletableFuture<SidecarFetch>> blockingFutures =
            new EnumMap<>(ChecksumAlgo.class);
        for (final Map.Entry<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> entry
                : fetchers.entrySet()) {
            if (nonBlocking.contains(entry.getKey())) {
                continue;
            }
            blockingFutures.put(
                entry.getKey(),
                entry.getValue().get()
                    .toCompletableFuture()
                    .thenApply(opt -> new SidecarFetch(
                        entry.getKey(), opt.map(ProxyCacheWriter::readSmall)
                    ))
                    .exceptionally(err -> new SidecarFetch(
                        entry.getKey(), Optional.empty()
                    ))
            );
        }
        final Map<ChecksumAlgo, MessageDigest> digests = createDigests();
        final AtomicLong size = new AtomicLong();
        // Idempotent terminal guard: doOnComplete / doOnError / doOnCancel
        // can race in pathological subscriber implementations. Only the
        // first terminal event runs cleanup / verify-and-commit; the others
        // become no-ops.
        final AtomicBoolean terminated = new AtomicBoolean();
        final CompletableFuture<Result<Void>> verifyDone = new CompletableFuture<>();
        final Flowable<ByteBuffer> teed = Flowable.fromPublisher(upstreamBody)
            // Move the file-write + digest work off the upstream HTTP I/O
            // thread so a slow disk does not back-pressure the upstream
            // connection. Schedulers.io grows on demand and is RxJava's
            // standard pool for blocking I/O.
            .observeOn(Schedulers.io())
            .doOnNext(buf -> {
                final ByteBuffer dup = buf.duplicate();
                final byte[] arr = new byte[dup.remaining()];
                dup.get(arr);
                for (final MessageDigest md : digests.values()) {
                    md.update(arr);
                }
                final ByteBuffer write = ByteBuffer.wrap(arr);
                while (write.hasRemaining()) {
                    channel.write(write);
                }
                size.addAndGet(arr.length);
            })
            .doOnComplete(() -> {
                if (!terminated.compareAndSet(false, true)) {
                    return;
                }
                closeQuietly(channel);
                final Map<ChecksumAlgo, String> computedHex = finalizeDigests(digests);
                this.streamCompleteVerifyAndCommit(
                    primaryKey, upstreamUri, tempFile, computedHex,
                    fetchers, nonBlocking, blockingFutures, ctx,
                    size.get(), verifyDone
                );
            })
            .doOnError(err -> {
                if (!terminated.compareAndSet(false, true)) {
                    return;
                }
                closeQuietly(channel);
                deleteQuietly(tempFile);
                EcsLogger.warn("com.auto1.pantera.cache")
                    .message("Stream-through upstream error; cache not populated")
                    .eventCategory("web")
                    .eventAction("cache_write")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("url.path", primaryKey.string())
                    .field("trace.id", traceId(ctx))
                    .error(err)
                    .log();
                verifyDone.complete(Result.err(new Fault.StorageUnavailable(
                    unwrap(err), primaryKey.string()
                )));
            })
            .doOnCancel(() -> {
                if (!terminated.compareAndSet(false, true)) {
                    return;
                }
                closeQuietly(channel);
                deleteQuietly(tempFile);
                EcsLogger.debug("com.auto1.pantera.cache")
                    .message("Stream-through cancelled by client; cache not populated")
                    .eventCategory("web")
                    .eventAction("cache_write")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("url.path", primaryKey.string())
                    .field("trace.id", traceId(ctx))
                    .log();
                verifyDone.complete(Result.err(new Fault.StorageUnavailable(
                    new IOException("client disconnected mid-stream"),
                    primaryKey.string()
                )));
            });
        final Content body = new Content.From(upstreamSize, teed);
        return CompletableFuture.completedFuture(
            Result.ok(new StreamedArtifact(body, verifyDone))
        );
    }

    /**
     * Post-stream verification + commit dispatched from the tee's
     * {@code doOnComplete}. Awaits the in-flight blocking sidecar futures,
     * compares each claim to the locally-computed digest, and either commits
     * (sidecar-first per Track 3) or drops the temp file with an integrity
     * log. All outcomes complete {@code outcome}; no exceptions are leaked.
     */
    private void streamCompleteVerifyAndCommit(
        final Key primaryKey,
        final String upstreamUri,
        final Path tempFile,
        final Map<ChecksumAlgo, String> computed,
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> fetchers,
        final Set<ChecksumAlgo> nonBlocking,
        final Map<ChecksumAlgo, CompletableFuture<SidecarFetch>> blockingFutures,
        final RequestContext ctx,
        final long size,
        final CompletableFuture<Result<Void>> outcome
    ) {
        for (final ChecksumAlgo algo : fetchers.keySet()) {
            if (nonBlocking.contains(algo)) {
                this.dispatchDeferredSidecar(
                    primaryKey, upstreamUri, algo, fetchers.get(algo), computed, ctx
                );
            }
        }
        @SuppressWarnings("unchecked")
        final CompletableFuture<SidecarFetch>[] futures =
            blockingFutures.values().toArray(new CompletableFuture[0]);
        CompletableFuture.allOf(futures).whenComplete((ignored, awaitErr) -> {
            if (awaitErr != null) {
                deleteQuietly(tempFile);
                outcome.complete(Result.err(new Fault.StorageUnavailable(
                    unwrap(awaitErr), primaryKey.string()
                )));
                return;
            }
            final Map<ChecksumAlgo, byte[]> sidecars = new EnumMap<>(ChecksumAlgo.class);
            for (final CompletableFuture<SidecarFetch> f : futures) {
                final SidecarFetch fetch = f.join();
                fetch.bytes().ifPresent(b -> sidecars.put(fetch.algo(), b));
            }
            for (final Map.Entry<ChecksumAlgo, byte[]> entry : sidecars.entrySet()) {
                final ChecksumAlgo algo = entry.getKey();
                final String claim = normaliseSidecar(entry.getValue());
                final String have = computed.get(algo);
                if (!claim.equals(have)) {
                    deleteQuietly(tempFile);
                    this.logStreamIntegrityFailure(
                        primaryKey, upstreamUri, algo, claim, have, ctx
                    );
                    outcome.complete(Result.err(new Fault.UpstreamIntegrity(
                        upstreamUri == null ? primaryKey.string() : upstreamUri,
                        algo, claim, have
                    )));
                    return;
                }
            }
            this.commitStreamed(primaryKey, tempFile, size, sidecars, ctx, outcome);
        });
    }

    /**
     * Commit a stream-through artifact: sidecars first, primary last (Track 3
     * atomic commit order). The bytes are read from the temp file into memory
     * exactly once and re-used for both the primary save and the {@code onWrite}
     * callback (Track 4 +sibling-prefetch). Temp file is deleted on success
     * and on any failure.
     */
    private void commitStreamed(
        final Key primaryKey,
        final Path tempFile,
        final long size,
        final Map<ChecksumAlgo, byte[]> sidecars,
        final RequestContext ctx,
        final CompletableFuture<Result<Void>> outcome
    ) {
        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(tempFile);
        } catch (final IOException ex) {
            deleteQuietly(tempFile);
            outcome.complete(Result.err(new Fault.StorageUnavailable(
                ex, primaryKey.string()
            )));
            return;
        }
        final boolean hasCallback = this.onWrite != NO_OP_ON_WRITE
            && !CacheWriteCallbackRegistry.instance().isNoOp(this.onWrite);
        this.saveSidecars(primaryKey, sidecars)
            .thenCompose(ignored ->
                this.cache.save(primaryKey, new Content.From(bytes))
            )
            .whenComplete((ignored, err) -> {
                if (err == null) {
                    if (hasCallback) {
                        this.fireOnWrite(primaryKey, tempFile, size);
                    }
                    deleteQuietly(tempFile);
                    this.logSuccess(primaryKey, sidecars.keySet(), ctx);
                    outcome.complete(Result.ok(null));
                } else {
                    deleteQuietly(tempFile);
                    this.rollbackAfterPartialFailure(
                        primaryKey, sidecars.keySet(), err, ctx
                    );
                    outcome.complete(Result.err(new Fault.StorageUnavailable(
                        unwrap(err), primaryKey.string()
                    )));
                }
            });
    }

    /**
     * Emit an integrity-failure log + metric for a stream-through mismatch.
     * The message explicitly notes the client already received the unverified
     * bytes — operators reading the log should know the cache stayed empty
     * but the in-flight response was already committed.
     */
    private void logStreamIntegrityFailure(
        final Key primaryKey,
        final String upstreamUri,
        final ChecksumAlgo algo,
        final String sidecarClaim,
        final String computed,
        final RequestContext ctx
    ) {
        final String tag = algo.name().toLowerCase(Locale.ROOT);
        EcsLogger.error("com.auto1.pantera.cache")
            .message(
                "Stream-through integrity mismatch — bytes were served to client"
                + " but NOT committed to cache (algo=" + tag
                + ", sidecar_claim=" + sidecarClaim
                + ", computed=" + computed + ")"
            )
            .eventCategory("web")
            .eventAction("cache_write")
            .eventOutcome("integrity_failure")
            .field("repository.name", this.repoName)
            .field("url.path", primaryKey.string())
            .field("url.full", upstreamUri == null ? primaryKey.string() : upstreamUri)
            .field("trace.id", traceId(ctx))
            .log();
        this.incrementIntegrityFailure(tag);
    }

    /** Finalize every {@link MessageDigest} into a stable hex map. */
    private static Map<ChecksumAlgo, String> finalizeDigests(
        final Map<ChecksumAlgo, MessageDigest> digests
    ) {
        final Map<ChecksumAlgo, String> out = new EnumMap<>(ChecksumAlgo.class);
        for (final Map.Entry<ChecksumAlgo, MessageDigest> entry : digests.entrySet()) {
            out.put(entry.getKey(), HEX.formatHex(entry.getValue().digest()));
        }
        return out;
    }

    /** Close a file channel without surfacing IO errors (best effort). */
    private static void closeQuietly(final FileChannel channel) {
        try {
            channel.close();
        } catch (final IOException ex) {
            EcsLogger.debug("com.auto1.pantera.cache")
                .message("Failed to close stream-through temp channel")
                .error(ex)
                .log();
        }
    }

    /**
     * Stream the upstream body into {@code tempFile} while computing all four
     * digests in a single pass.
     *
     * @param stream   Upstream body.
     * @param tempFile Destination.
     * @return Stage yielding hex-encoded digests for every algorithm.
     */
    private CompletionStage<Map<ChecksumAlgo, String>> streamPrimary(
        final InputStream stream, final Path tempFile
    ) {
        return CompletableFuture.supplyAsync(() -> {
            final Map<ChecksumAlgo, MessageDigest> digests = createDigests();
            try (InputStream in = stream;
                 FileChannel channel = FileChannel.open(
                     tempFile,
                     StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING
                 )) {
                final byte[] chunk = new byte[CHUNK_SIZE];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    for (final MessageDigest md : digests.values()) {
                        md.update(chunk, 0, read);
                    }
                    final ByteBuffer buf = ByteBuffer.wrap(chunk, 0, read);
                    while (buf.hasRemaining()) {
                        channel.write(buf);
                    }
                }
                // Intentionally NO fsync. The cache is a regenerable mirror of
                // upstream — if a crash leaves the temp file unflushed we'll
                // refetch on the next miss. fsync per primary added 5-10s
                // wall on macOS APFS to a 500-artifact cold mvn walk
                // (Phase 7 acceptance bench debug, 2026-05).
            } catch (final IOException ex) {
                throw new PrimaryStreamException(ex);
            }
            final Map<ChecksumAlgo, String> out = new EnumMap<>(ChecksumAlgo.class);
            for (final Map.Entry<ChecksumAlgo, MessageDigest> entry : digests.entrySet()) {
                out.put(entry.getKey(), HEX.formatHex(entry.getValue().digest()));
            }
            return out;
        });
    }

    /**
     * Fetch every declared sidecar, verify, commit or reject.
     */
    private CompletionStage<Result<Void>> fetchAndVerify(
        final Key primaryKey,
        final String upstreamUri,
        final Path tempFile,
        final Map<ChecksumAlgo, String> computed,
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> sidecarFetchers,
        final RequestContext ctx
    ) {
        final List<ChecksumAlgo> algos = new ArrayList<>(sidecarFetchers.keySet());
        @SuppressWarnings("unchecked")
        final CompletableFuture<SidecarFetch>[] futures =
            new CompletableFuture[algos.size()];
        for (int i = 0; i < algos.size(); i++) {
            final ChecksumAlgo algo = algos.get(i);
            futures[i] = sidecarFetchers.get(algo).get()
                .toCompletableFuture()
                .thenApply(opt -> new SidecarFetch(algo, opt.map(ProxyCacheWriter::readSmall)))
                .exceptionally(err -> new SidecarFetch(algo, Optional.empty()));
        }
        return CompletableFuture.allOf(futures).thenCompose(ignored -> {
            final Map<ChecksumAlgo, byte[]> sidecars = new EnumMap<>(ChecksumAlgo.class);
            for (final CompletableFuture<SidecarFetch> f : futures) {
                final SidecarFetch fetch = f.join();
                fetch.bytes().ifPresent(b -> sidecars.put(fetch.algo(), b));
            }
            for (final Map.Entry<ChecksumAlgo, byte[]> entry : sidecars.entrySet()) {
                final ChecksumAlgo algo = entry.getKey();
                final String claim = normaliseSidecar(entry.getValue());
                final String have = computed.get(algo);
                if (!claim.equals(have)) {
                    return this.rejectIntegrity(
                        primaryKey, upstreamUri, tempFile, algo, claim, have, ctx
                    );
                }
            }
            return this.commit(primaryKey, tempFile, sidecars, ctx);
        });
    }

    /**
     * Fetch sidecars and verify, but do NOT commit. Returns a
     * {@link VerifiedArtifact} on success. Sidecars in {@code nonBlocking}
     * are fired in parallel but their futures do NOT gate the return — they
     * verify and persist themselves once the upstream eventually responds.
     */
    private CompletionStage<Result<VerifiedArtifact>> verifyOnly(
        final Key primaryKey,
        final String upstreamUri,
        final Path tempFile,
        final Map<ChecksumAlgo, String> computed,
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> sidecarFetchers,
        final Set<ChecksumAlgo> nonBlocking,
        final Map<ChecksumAlgo, CompletableFuture<SidecarFetch>> blockingSidecarFutures,
        final RequestContext ctx
    ) {
        final List<ChecksumAlgo> deferredAlgos = new ArrayList<>();
        for (final ChecksumAlgo algo : sidecarFetchers.keySet()) {
            if (nonBlocking.contains(algo)) {
                deferredAlgos.add(algo);
            }
        }
        for (final ChecksumAlgo algo : deferredAlgos) {
            this.dispatchDeferredSidecar(
                primaryKey, upstreamUri, algo, sidecarFetchers.get(algo), computed, ctx
            );
        }
        // Phase 7.5 perf: blocking sidecar fetches were started in parallel
        // with the primary fetch in writeAndVerify(); just wait on the
        // already-in-flight futures here.
        @SuppressWarnings("unchecked")
        final CompletableFuture<SidecarFetch>[] futures =
            blockingSidecarFutures.values()
                .toArray(new CompletableFuture[0]);
        return CompletableFuture.allOf(futures).thenCompose(ignored -> {
            final Map<ChecksumAlgo, byte[]> sidecars = new EnumMap<>(ChecksumAlgo.class);
            for (final CompletableFuture<SidecarFetch> f : futures) {
                final SidecarFetch fetch = f.join();
                fetch.bytes().ifPresent(b -> sidecars.put(fetch.algo(), b));
            }
            for (final Map.Entry<ChecksumAlgo, byte[]> entry : sidecars.entrySet()) {
                final ChecksumAlgo algo = entry.getKey();
                final String claim = normaliseSidecar(entry.getValue());
                final String have = computed.get(algo);
                if (!claim.equals(have)) {
                    return this.rejectIntegrity(
                        primaryKey, upstreamUri, tempFile, algo, claim, have, ctx
                    ).thenApply(r -> r.map(v -> (VerifiedArtifact) null));
                }
            }
            final long size;
            try {
                size = Files.size(tempFile);
            } catch (final IOException ex) {
                deleteQuietly(tempFile);
                return CompletableFuture.completedFuture(
                    Result.<VerifiedArtifact>err(
                        new Fault.StorageUnavailable(ex, primaryKey.string())
                    )
                );
            }
            return CompletableFuture.completedFuture(
                Result.ok(new VerifiedArtifact(
                    tempFile, size, sidecars, primaryKey, ctx, this
                ))
            );
        });
    }

    /**
     * Background path for {@link #NON_BLOCKING_DEFAULT} sidecars: fire the
     * upstream fetch but don't gate the primary serve on it. When the
     * upstream eventually responds, verify the claim against the
     * already-computed digest and persist the sidecar to storage. A
     * disagreement is logged + counted (so operators retain visibility) but
     * does NOT retroactively reject the primary — the primary's integrity is
     * already proven by the synchronously-verified sidecars (sha1 / md5 in
     * the default config). 404 / IO error → no-op (sidecar absent upstream).
     */
    private void dispatchDeferredSidecar(
        final Key primaryKey,
        final String upstreamUri,
        final ChecksumAlgo algo,
        final Supplier<CompletionStage<Optional<InputStream>>> fetcher,
        final Map<ChecksumAlgo, String> computed,
        final RequestContext ctx
    ) {
        fetcher.get()
            .toCompletableFuture()
            .thenApply(opt -> opt.map(ProxyCacheWriter::readSmall))
            .thenCompose(maybeBytes -> {
                if (maybeBytes.isEmpty()) {
                    return CompletableFuture.completedFuture(null);
                }
                final byte[] bytes = maybeBytes.get();
                final String claim = normaliseSidecar(bytes);
                final String have = computed.get(algo);
                if (!claim.equals(have)) {
                    final String tag = algo.name().toLowerCase(Locale.ROOT);
                    EcsLogger.warn("com.auto1.pantera.cache")
                        .message(
                            "Deferred sidecar disagrees with computed digest;"
                            + " primary already served (algo=" + tag
                            + ", sidecar_claim=" + claim
                            + ", computed=" + have + ")"
                        )
                        .eventCategory("web")
                        .eventAction("cache_write")
                        .eventOutcome("integrity_failure")
                        .field("repository.name", this.repoName)
                        .field("url.path", primaryKey.string())
                        .field("url.full", upstreamUri == null
                            ? primaryKey.string() : upstreamUri)
                        .field("trace.id", traceId(ctx))
                        .log();
                    this.incrementIntegrityFailure(tag);
                    return CompletableFuture.completedFuture(null);
                }
                return this.cache.save(
                    sidecarKey(primaryKey, algo), new Content.From(bytes)
                ).toCompletableFuture();
            })
            .exceptionally(err -> {
                EcsLogger.debug("com.auto1.pantera.cache")
                    .message("Deferred sidecar fetch/save failed; primary unaffected")
                    .eventCategory("web")
                    .eventAction("cache_write")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("url.path", primaryKey.string())
                    .error(err)
                    .log();
                return null;
            });
    }

    /**
     * Commit a previously verified artifact to storage. Reads the temp file
     * eagerly so the response Flowable (which also reads the temp file) is
     * not racing with temp-file deletion.
     *
     * <p>When a callback is installed (per-instance or via
     * {@link CacheWriteCallbackRegistry}) we materialise a dedicated
     * callback-owned temp file from the in-memory byte buffer before firing
     * {@link #fireOnWrite}; the original {@code artifact.tempFile()} is
     * owned by the response Flowable's disposer and may be deleted before
     * the onWrite consumer schedules its read. The callback temp file is
     * deleted right after the consumer returns.</p>
     *
     * <p><b>Fast path:</b> when both the per-instance callback AND the
     * registry's shared callback are no-ops, the materialise step is
     * skipped entirely &mdash; no {@code Files.createTempFile + write +
     * deleteQuietly} on the proxy hot path.</p>
     */
    CompletionStage<Result<Void>> commitVerified(final VerifiedArtifact artifact) {
        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(artifact.tempFile());
        } catch (final IOException ex) {
            return CompletableFuture.completedFuture(
                Result.err(new Fault.StorageUnavailable(ex, artifact.primaryKey().string()))
            );
        }
        // Fast path: when no callback is installed (per-instance or
        // shared), skip the synchronous temp-file materialisation that
        // would otherwise hit the disk for nothing on every cache write.
        final boolean hasCallback = this.onWrite != NO_OP_ON_WRITE
            && !CacheWriteCallbackRegistry.instance().isNoOp(this.onWrite);
        // Write a callback-owned copy so the consumer sees a stable file
        // regardless of when the response Flowable disposer runs. Skipped
        // when no callback would observe it.
        final Path callbackFile = hasCallback
            ? materialiseCallbackTempFile(bytes, artifact.primaryKey())
            : null;
        // Track 3 atomic commit: sidecars FIRST, primary LAST. By writing
        // sidecars before the primary, any reader that observes the primary
        // on disk is guaranteed to find every matching sidecar — the
        // previously-possible "primary present without .sha1" window is
        // eliminated. If the primary write fails or the JVM crashes
        // between the two phases, only orphaned sidecars remain; the next
        // request finds no primary, re-fetches from upstream, and a fresh
        // ProxyCacheWriter run overwrites the orphans with consistent
        // values. Orphans are therefore harmless and self-healing.
        return this.saveSidecars(artifact.primaryKey(), artifact.sidecars())
            .thenCompose(ignored -> this.cache.save(artifact.primaryKey(), new Content.From(bytes)))
            .handle((ignored, err) -> {
                if (err == null) {
                    if (hasCallback) {
                        // Use the callback-owned file (still on disk)
                        // instead of the response Flowable's temp file
                        // (which may have been disposed by the time we
                        // land here).
                        this.fireOnWrite(
                            artifact.primaryKey(),
                            callbackFile == null ? artifact.tempFile() : callbackFile,
                            artifact.size()
                        );
                        if (callbackFile != null) {
                            deleteQuietly(callbackFile);
                        }
                    }
                    this.logSuccess(artifact.primaryKey(), artifact.sidecars().keySet(), artifact.ctx());
                    return Result.<Void>ok(null);
                }
                if (callbackFile != null) {
                    deleteQuietly(callbackFile);
                }
                this.rollbackAfterPartialFailure(
                    artifact.primaryKey(), artifact.sidecars().keySet(), err, artifact.ctx()
                );
                return Result.<Void>err(new Fault.StorageUnavailable(
                    unwrap(err), artifact.primaryKey().string()
                ));
            });
    }

    /**
     * Write the in-memory bytes to a fresh temp file dedicated to the
     * onCacheWrite consumer. Returns {@code null} on IO failure (callback
     * falls back to the response Flowable's path with the existing
     * best-effort lifetime contract).
     */
    private static Path materialiseCallbackTempFile(final byte[] bytes, final Key key) {
        try {
            final Path tmp = Files.createTempFile("pantera-prefetch-", ".bin");
            Files.write(tmp, bytes);
            return tmp;
        } catch (final IOException ex) {
            EcsLogger.debug("com.auto1.pantera.cache")
                .message("Failed to materialise onCacheWrite temp file; using response Flowable path")
                .field("url.path", key.string())
                .error(ex)
                .log();
            return null;
        }
    }

    /**
     * Emit an integrity-failure log + metric and return Err. Does NOT write
     * anything to the cache; the temp file is deleted.
     */
    private CompletionStage<Result<Void>> rejectIntegrity(
        final Key primaryKey,
        final String upstreamUri,
        final Path tempFile,
        final ChecksumAlgo algo,
        final String sidecarClaim,
        final String computed,
        final RequestContext ctx
    ) {
        deleteQuietly(tempFile);
        final String tag = algo.name().toLowerCase(Locale.ROOT);
        EcsLogger.error("com.auto1.pantera.cache")
            .message("Upstream sidecar disagrees with computed digest; rejecting cache write"
                + " (algo=" + tag
                + ", sidecar_claim=" + sidecarClaim
                + ", computed=" + computed + ")")
            .eventCategory("web")
            .eventAction("cache_write")
            .eventOutcome("integrity_failure")
            .field("repository.name", this.repoName)
            .field("url.path", primaryKey.string())
            .field("url.full", upstreamUri)
            .field("trace.id", traceId(ctx))
            .log();
        this.incrementIntegrityFailure(tag);
        return CompletableFuture.completedFuture(
            Result.err(new Fault.UpstreamIntegrity(
                upstreamUri == null ? primaryKey.string() : upstreamUri,
                algo,
                sidecarClaim,
                computed
            ))
        );
    }

    /**
     * Atomically save primary + every sidecar to the cache. On any failure
     * after the primary lands, delete whatever has been written and return
     * Err(StorageUnavailable).
     */
    private CompletionStage<Result<Void>> commit(
        final Key primaryKey,
        final Path tempFile,
        final Map<ChecksumAlgo, byte[]> sidecars,
        final RequestContext ctx
    ) {
        final long size;
        try {
            size = Files.size(tempFile);
        } catch (final IOException ex) {
            deleteQuietly(tempFile);
            return CompletableFuture.completedFuture(
                Result.err(new Fault.StorageUnavailable(ex, primaryKey.string()))
            );
        }
        final Content primaryContent;
        try {
            primaryContent = new Content.From(
                Optional.of(size),
                io.reactivex.Flowable.using(
                    () -> FileChannel.open(tempFile, StandardOpenOption.READ),
                    chan -> io.reactivex.Flowable.generate(emitter -> {
                        final ByteBuffer buf = ByteBuffer.allocate(CHUNK_SIZE);
                        final int read = chan.read(buf);
                        if (read < 0) {
                            emitter.onComplete();
                        } else {
                            buf.flip();
                            emitter.onNext(buf);
                        }
                    }),
                    FileChannel::close
                )
            );
        } catch (final RuntimeException ex) {
            deleteQuietly(tempFile);
            return CompletableFuture.completedFuture(
                Result.err(new Fault.StorageUnavailable(ex, primaryKey.string()))
            );
        }
        return this.cache.save(primaryKey, primaryContent)
            .thenCompose(ignored -> this.saveSidecars(primaryKey, sidecars))
            .handle((ignored, err) -> {
                if (err == null) {
                    // Fire onWrite BEFORE deleting the temp file: the
                    // CacheWriteEvent.bytesOnDisk() must still be a valid
                    // path at the moment the consumer receives it.
                    this.fireOnWrite(primaryKey, tempFile, size);
                    deleteQuietly(tempFile);
                    this.logSuccess(primaryKey, sidecars.keySet(), ctx);
                    return Result.<Void>ok(null);
                }
                deleteQuietly(tempFile);
                this.rollbackAfterPartialFailure(primaryKey, sidecars.keySet(), err, ctx);
                return Result.<Void>err(new Fault.StorageUnavailable(
                    unwrap(err), primaryKey.string()
                ));
            });
    }

    /**
     * Save every sidecar in parallel via {@link CompletableFuture#allOf}.
     * Sidecars are tiny (40-128 byte hex strings) and independent, so
     * parallel saves yield ~4x speedup on 4-sidecar artifacts (Maven).
     */
    private CompletableFuture<Void> saveSidecars(
        final Key primaryKey, final Map<ChecksumAlgo, byte[]> sidecars
    ) {
        final CompletableFuture<?>[] saves = sidecars.entrySet().stream()
            .map(entry -> this.cache.save(
                sidecarKey(primaryKey, entry.getKey()),
                new Content.From(entry.getValue())
            ))
            .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(saves);
    }

    /**
     * Called when the atomic move of primary or sidecar has failed after the
     * primary may have already landed. Deletes the primary + any sidecar that
     * made it, so a subsequent GET re-fetches cleanly via this writer.
     */
    private void rollbackAfterPartialFailure(
        final Key primaryKey,
        final Collection<ChecksumAlgo> sidecarAlgos,
        final Throwable cause,
        final RequestContext ctx
    ) {
        this.cache.delete(primaryKey).exceptionally(ignored -> null);
        for (final ChecksumAlgo algo : sidecarAlgos) {
            this.cache.delete(sidecarKey(primaryKey, algo)).exceptionally(ignored -> null);
        }
        EcsLogger.error("com.auto1.pantera.cache")
            .message("Cache write partial failure; rolled back primary + sidecars")
            .eventCategory("web")
            .eventAction("cache_write")
            .eventOutcome("partial_failure")
            .field("repository.name", this.repoName)
            .field("url.path", primaryKey.string())
            .field("trace.id", traceId(ctx))
            .error(unwrap(cause))
            .log();
        if (this.metrics != null) {
            Counter.builder("pantera.proxy.cache.write_partial_failure")
                .tags(Tags.of("repo", this.repoName))
                .register(this.metrics)
                .increment();
        }
    }

    /**
     * Invoke the configured {@link Consumer} with a fresh
     * {@link CacheWriteEvent}. Any throwable from the consumer is caught
     * and logged at WARN — it MUST NOT propagate, otherwise the cache-
     * write outcome would be tied to the consumer's correctness.
     *
     * @param primaryKey Cache key of the primary artifact just written.
     * @param tempFile   Filesystem path of the source bytes.
     * @param size       Size in bytes of the primary.
     */
    private void fireOnWrite(final Key primaryKey, final Path tempFile, final long size) {
        try {
            this.onWrite.accept(new CacheWriteEvent(
                this.repoName, primaryKey.string(), tempFile, size, Instant.now()
            ));
        } catch (final Throwable thrown) {
            EcsLogger.warn("com.auto1.pantera.http.cache")
                .message("ProxyCacheWriter onWrite callback threw: " + thrown.getMessage())
                .field("repository.name", this.repoName)
                .field("url.path", primaryKey.string())
                .error(thrown)
                .log();
        }
    }

    /** Emit the success event with the sidecar set actually written. */
    private void logSuccess(
        final Key primaryKey, final Collection<ChecksumAlgo> sidecars, final RequestContext ctx
    ) {
        EcsLogger.info("com.auto1.pantera.cache")
            .message("Proxy cache write with verified sidecars (algos="
                + algoList(sidecars) + ")")
            .eventCategory("web")
            .eventAction("cache_write")
            .eventOutcome("success")
            .field("repository.name", this.repoName)
            .field("url.path", primaryKey.string())
            .field("trace.id", traceId(ctx))
            .log();
    }

    /** Increment the integrity-failure metric, if metrics are wired. */
    private void incrementIntegrityFailure(final String algoTag) {
        if (this.metrics == null) {
            return;
        }
        Counter.builder("pantera.proxy.cache.integrity_failure")
            .tags(Tags.of("repo", this.repoName, "algo", algoTag))
            .register(this.metrics)
            .increment();
    }

    // ===== helpers =====

    /**
     * Create a {@link Content} backed by a temp file for immediate serving.
     * The disposer closes the channel AND deletes the temp file, so this
     * Content owns the temp file lifecycle.
     */
    static Content contentFromTempFile(final Path tempFile, final long size) {
        return new Content.From(
            Optional.of(size),
            io.reactivex.Flowable.using(
                () -> FileChannel.open(tempFile, StandardOpenOption.READ),
                chan -> io.reactivex.Flowable.generate(emitter -> {
                    final ByteBuffer buf = ByteBuffer.allocate(CHUNK_SIZE);
                    final int read = chan.read(buf);
                    if (read < 0) {
                        emitter.onComplete();
                    } else {
                        buf.flip();
                        emitter.onNext(buf);
                    }
                }),
                chan -> {
                    chan.close();
                    deleteQuietly(tempFile);
                }
            )
        );
    }

    /** Construct the sidecar key from a primary key + algo extension. */
    static Key sidecarKey(final Key primary, final ChecksumAlgo algo) {
        return new Key.From(primary.string() + sidecarExtension(algo));
    }

    /** File-system extension for each sidecar algorithm. */
    static String sidecarExtension(final ChecksumAlgo algo) {
        return switch (algo) {
            case MD5 -> ".md5";
            case SHA1 -> ".sha1";
            case SHA256 -> ".sha256";
            case SHA512 -> ".sha512";
        };
    }

    /** Sidecar bodies may include file paths or trailing whitespace. */
    static String normaliseSidecar(final byte[] body) {
        final String raw = new String(body, java.nio.charset.StandardCharsets.UTF_8).trim();
        // Some upstreams emit "hex *filename" or "hex  filename" — keep the hex
        final int sp = firstWhitespace(raw);
        final String hex = sp < 0 ? raw : raw.substring(0, sp);
        return hex.toLowerCase(Locale.ROOT);
    }

    private static int firstWhitespace(final String raw) {
        for (int i = 0; i < raw.length(); i++) {
            if (Character.isWhitespace(raw.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Render a collection of algos as a stable, sorted list for logging. */
    private static List<String> algoList(final Collection<ChecksumAlgo> algos) {
        return algos.stream()
            .sorted()
            .map(a -> a.name().toLowerCase(Locale.ROOT))
            .toList();
    }

    /** Read a small payload (sidecar body) into memory. */
    static byte[] readSmall(final InputStream in) {
        try (InputStream src = in) {
            return src.readAllBytes();
        } catch (final IOException ex) {
            throw new PrimaryStreamException(ex);
        }
    }

    private static Map<ChecksumAlgo, MessageDigest> createDigests() {
        final Map<ChecksumAlgo, MessageDigest> map = new EnumMap<>(ChecksumAlgo.class);
        try {
            map.put(ChecksumAlgo.MD5, MessageDigest.getInstance("MD5"));
            map.put(ChecksumAlgo.SHA1, MessageDigest.getInstance("SHA-1"));
            map.put(ChecksumAlgo.SHA256, MessageDigest.getInstance("SHA-256"));
            map.put(ChecksumAlgo.SHA512, MessageDigest.getInstance("SHA-512"));
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Required digest algorithm missing", ex);
        }
        return map;
    }

    private static void deleteQuietly(final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (final IOException ex) {
            EcsLogger.debug("com.auto1.pantera.cache")
                .message("Failed to delete temp file")
                .field("file.path", path.toString())
                .error(ex)
                .log();
        }
    }

    private static Throwable unwrap(final Throwable err) {
        Throwable cur = err;
        while (cur instanceof java.util.concurrent.CompletionException
            && cur.getCause() != null && cur.getCause() != cur) { // NOPMD CompareObjectsWithEquals - intentional identity check (cycle guard for self-causing exception)
            cur = cur.getCause();
        }
        if (cur instanceof PrimaryStreamException && cur.getCause() != null) {
            return cur.getCause();
        }
        return cur;
    }

    private static String traceId(final RequestContext ctx) {
        return ctx == null ? null : ctx.traceId();
    }

    /** Tuple type for collecting per-algo sidecar fetches. */
    private record SidecarFetch(ChecksumAlgo algo, Optional<byte[]> bytes) {
    }

    /**
     * Track 4 stream-through return value: a teed body to hand to
     * {@code ResponseBuilder.body(...)} plus a fire-and-forget future that
     * completes when the post-stream verify + commit decision lands.
     *
     * <p>The proxy hot path does NOT block on {@code verificationOutcome} —
     * the response is committed to Jetty as soon as the upstream Publisher
     * emits its first byte. {@code verificationOutcome} is exposed so tests
     * and integration callers can observe whether the cache was populated
     * (commit success), left empty by upstream integrity disagreement, or
     * left empty by a mid-stream error.
     *
     * @param body                  Teed response body, ready for
     *                              {@code ResponseBuilder.body(...)}.
     * @param verificationOutcome   Completes with the final commit/verify
     *                              outcome; never throws, captures errors
     *                              as {@link Result.Err}.
     */
    public record StreamedArtifact(
        Content body,
        CompletionStage<Result<Void>> verificationOutcome
    ) {
    }

    /** A verified-but-not-yet-committed artifact that can be served immediately. */
    public record VerifiedArtifact(
        Path tempFile,
        long size,
        Map<ChecksumAlgo, byte[]> sidecars,
        Key primaryKey,
        RequestContext ctx,
        ProxyCacheWriter writer
    ) {
        /** Create a {@link Content} from the temp file for immediate serving. */
        public Content contentFromTempFile() {
            return ProxyCacheWriter.contentFromTempFile(this.tempFile, this.size);
        }

        /** Commit the verified artifact to storage asynchronously. */
        public CompletionStage<Result<Void>> commitAsync() {
            return this.writer.commitVerified(this);
        }
    }

    /**
     * Internal wrapping exception for IO errors encountered in the streaming
     * primary-write phase. Unwrapped before the user sees anything.
     */
    private static final class PrimaryStreamException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        PrimaryStreamException(final Throwable cause) {
            super(cause);
        }
    }

    // =================================================================
    // Integrity auditor — healing stale pairs (WI-07 admin tool / §9.5)
    // =================================================================

    /**
     * Scans a {@link Storage} for primary artifacts whose cached sidecar
     * disagrees with the re-computed digest of the primary bytes.
     *
     * <p>Runs in dry-run mode by default — emitting one Tier-4 WARN per
     * mismatch plus a summary — or fix mode where the offending primary +
     * every sidecar is deleted so the next client request repopulates through
     * {@link ProxyCacheWriter}.
     *
     * @since 2.2.0
     */
    public static final class IntegrityAuditor {

        /** Primary artifact extensions we know have sidecars. */
        private static final List<String> PRIMARY_EXTENSIONS = List.of(
            ".pom", ".jar", ".war", ".aar", ".ear",
            ".tgz", ".tar.gz", ".whl", ".zip"
        );

        /** Sidecar extensions that imply "ignore this entry as a primary". */
        private static final List<String> SIDECAR_EXTENSIONS = List.of(
            ".md5", ".sha1", ".sha256", ".sha512", ".asc", ".sig"
        );

        /** Algorithm by file extension, for fast lookup in the scanner. */
        private static final Map<String, ChecksumAlgo> ALGO_BY_EXT = Map.of(
            ".md5", ChecksumAlgo.MD5,
            ".sha1", ChecksumAlgo.SHA1,
            ".sha256", ChecksumAlgo.SHA256,
            ".sha512", ChecksumAlgo.SHA512
        );

        private IntegrityAuditor() {
            // static utility
        }

        /**
         * Run the audit over {@code storage}.
         *
         * @param storage  Storage to scan (file-backed storage recommended).
         * @param repoName Tag attached to log events.
         * @param fix      If {@code true}, evict primary + every sidecar when
         *                 a mismatch is found; if {@code false}, report only.
         * @return Report containing counts + every offender.
         */
        public static Report run(
            final Storage storage, final String repoName, final boolean fix
        ) {
            final Collection<Key> keys;
            try {
                keys = storage.list(Key.ROOT).join();
            } catch (final Exception ex) {
                throw new IllegalStateException("Unable to list storage", ex);
            }
            final List<Mismatch> mismatches = new ArrayList<>();
            int scanned = 0;
            for (final Key key : keys) {
                final String path = key.string();
                if (isSidecar(path) || !isPrimary(path)) {
                    continue;
                }
                scanned++;
                final Mismatch found = auditOne(storage, key, repoName, fix);
                if (found != null) {
                    mismatches.add(found);
                }
            }
            EcsLogger.info("com.auto1.pantera.cache")
                .message("Cache integrity audit complete"
                    + " (scanned=" + scanned
                    + ", mismatches=" + mismatches.size()
                    + ", fix=" + fix + ")")
                .eventCategory("file")
                .eventAction("integrity_audit")
                .eventOutcome(mismatches.isEmpty() ? "success" : "failure")
                .field("repository.name", repoName)
                .log();
            return new Report(scanned, mismatches, fix);
        }

        /**
         * Audit a single primary key. Returns a {@link Mismatch} when at least
         * one sidecar disagrees; {@code null} otherwise.
         */
        private static Mismatch auditOne(
            final Storage storage, final Key primary,
            final String repoName, final boolean fix
        ) {
            final Map<ChecksumAlgo, String> computed;
            try {
                computed = computeDigests(storage, primary);
            } catch (final Exception ex) {
                EcsLogger.warn("com.auto1.pantera.cache")
                    .message("Integrity audit: failed to read primary")
                    .eventCategory("file")
                    .eventAction("integrity_audit")
                    .eventOutcome("failure")
                    .field("repository.name", repoName)
                    .field("url.path", primary.string())
                    .error(ex)
                    .log();
                return null;
            }
            final List<AlgoMismatch> per = new ArrayList<>();
            final List<Key> sidecarsPresent = new ArrayList<>();
            for (final Map.Entry<String, ChecksumAlgo> ext : ALGO_BY_EXT.entrySet()) {
                final Key sidecarKey = new Key.From(primary.string() + ext.getKey());
                final boolean present;
                try {
                    present = storage.exists(sidecarKey).join();
                } catch (final Exception ex) {
                    continue;
                }
                if (!present) {
                    continue;
                }
                sidecarsPresent.add(sidecarKey);
                final byte[] claimBytes;
                try {
                    claimBytes = storage.value(sidecarKey).join().asBytes();
                } catch (final Exception ex) {
                    continue;
                }
                final String claim = normaliseSidecar(claimBytes);
                final String have = computed.get(ext.getValue());
                if (!claim.equals(have)) {
                    per.add(new AlgoMismatch(ext.getValue(), claim, have));
                }
            }
            if (per.isEmpty()) {
                return null;
            }
            for (final AlgoMismatch m : per) {
                EcsLogger.warn("com.auto1.pantera.cache")
                    .message("Cache integrity mismatch detected"
                        + " (algo=" + m.algo().name().toLowerCase(Locale.ROOT)
                        + ", sidecar_claim=" + m.sidecarClaim()
                        + ", computed=" + m.computed() + ")")
                    .eventCategory("file")
                    .eventAction("integrity_audit")
                    .eventOutcome("failure")
                    .field("repository.name", repoName)
                    .field("url.path", primary.string())
                    .log();
            }
            if (fix) {
                evict(storage, primary, sidecarsPresent, repoName);
            }
            return new Mismatch(primary, per);
        }

        private static Map<ChecksumAlgo, String> computeDigests(
            final Storage storage, final Key key
        ) throws IOException {
            final Map<ChecksumAlgo, MessageDigest> digests = createDigests();
            final byte[] bytes;
            try {
                bytes = storage.value(key).join().asBytes();
            } catch (final Exception ex) {
                throw new IOException("read failed: " + key.string(), ex);
            }
            for (final MessageDigest md : digests.values()) {
                md.update(bytes);
            }
            final Map<ChecksumAlgo, String> out = new EnumMap<>(ChecksumAlgo.class);
            for (final Map.Entry<ChecksumAlgo, MessageDigest> entry : digests.entrySet()) {
                out.put(entry.getKey(), HEX.formatHex(entry.getValue().digest()));
            }
            return out;
        }

        private static void evict(
            final Storage storage, final Key primary,
            final Collection<Key> sidecars, final String repoName
        ) {
            try {
                storage.delete(primary).join();
            } catch (final Exception ex) {
                EcsLogger.warn("com.auto1.pantera.cache")
                    .message("Failed to evict primary during integrity fix")
                    .field("repository.name", repoName)
                    .field("url.path", primary.string())
                    .error(ex)
                    .log();
            }
            for (final Key sidecar : sidecars) {
                try {
                    storage.delete(sidecar).join();
                } catch (final Exception ex) {
                    // Best-effort cleanup; do not abort.
                    EcsLogger.debug("com.auto1.pantera.cache")
                        .message("Failed to evict sidecar during integrity fix")
                        .field("url.path", sidecar.string())
                        .error(ex)
                        .log();
                }
            }
            EcsLogger.info("com.auto1.pantera.cache")
                .message("Integrity fix: evicted mismatched pair")
                .eventCategory("file")
                .eventAction("integrity_audit")
                .eventOutcome("success")
                .field("repository.name", repoName)
                .field("url.path", primary.string())
                .log();
        }

        private static boolean isPrimary(final String path) {
            final String lower = path.toLowerCase(Locale.ROOT);
            for (final String ext : PRIMARY_EXTENSIONS) {
                if (lower.endsWith(ext)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isSidecar(final String path) {
            final String lower = path.toLowerCase(Locale.ROOT);
            for (final String ext : SIDECAR_EXTENSIONS) {
                if (lower.endsWith(ext)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Summary result of an audit run.
         *
         * @param scanned    Number of primary files examined.
         * @param mismatches Per-primary detail on offenders.
         * @param fixed      {@code true} if the run was executed with fix=true.
         */
        public record Report(int scanned, List<Mismatch> mismatches, boolean fixed) {
            /** @return {@code true} if no mismatches were found. */
            public boolean clean() {
                return this.mismatches.isEmpty();
            }
        }

        /**
         * One primary artifact + every sidecar that disagreed with it.
         *
         * @param primary    Primary cache key.
         * @param algorithms One entry per mismatched sidecar algorithm.
         */
        public record Mismatch(Key primary, List<AlgoMismatch> algorithms) {
        }

        /**
         * One (primary, algorithm) pair with the disagreement detail.
         *
         * @param algo         Sidecar algorithm whose hex disagreed.
         * @param sidecarClaim Hex declared by the cached sidecar.
         * @param computed     Hex recomputed over the cached primary bytes.
         */
        public record AlgoMismatch(ChecksumAlgo algo, String sidecarClaim, String computed) {
        }
    }
}
