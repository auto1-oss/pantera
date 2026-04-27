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

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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

    /** Repository name used in log fields and metric tags. */
    private final String repoName;

    /** Backing storage receiving the primary + sidecars. */
    private final Storage cache;

    /** Optional metrics registry; null disables metrics. */
    private final MeterRegistry metrics;

    /**
     * Ctor.
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
        this.cache = Objects.requireNonNull(cache, "cache");
        this.repoName = Objects.requireNonNull(repoName, "repoName");
        this.metrics = metrics;
    }

    /**
     * Convenience ctor without metrics.
     *
     * @param cache    Storage.
     * @param repoName Repository name.
     */
    public ProxyCacheWriter(final Storage cache, final String repoName) {
        this(cache, repoName, null);
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
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.CyclomaticComplexity"})
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
     */
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.CyclomaticComplexity"})
    public CompletionStage<Result<VerifiedArtifact>> writeAndVerify(
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
            .thenCompose(digests -> this.verifyOnly(
                primaryKey, upstreamUri, tempFile, digests, sidecarFetchers, ctx
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
     * Stream the upstream body into {@code tempFile} while computing all four
     * digests in a single pass.
     *
     * @param stream   Upstream body.
     * @param tempFile Destination.
     * @return Stage yielding hex-encoded digests for every algorithm.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
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
                channel.force(true);
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
    @SuppressWarnings({"PMD.CognitiveComplexity", "PMD.CyclomaticComplexity"})
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
     * {@link VerifiedArtifact} on success.
     */
    @SuppressWarnings({"PMD.CognitiveComplexity", "PMD.CyclomaticComplexity"})
    private CompletionStage<Result<VerifiedArtifact>> verifyOnly(
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

    /** Commit a previously verified artifact to storage. */
    CompletionStage<Result<Void>> commitVerified(final VerifiedArtifact artifact) {
        return this.commit(
            artifact.primaryKey(),
            artifact.tempFile(),
            artifact.sidecars(),
            artifact.ctx()
        );
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
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.CognitiveComplexity"})
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
                deleteQuietly(tempFile);
                if (err == null) {
                    this.logSuccess(primaryKey, sidecars.keySet(), ctx);
                    return Result.<Void>ok(null);
                }
                this.rollbackAfterPartialFailure(primaryKey, sidecars.keySet(), err, ctx);
                return Result.<Void>err(new Fault.StorageUnavailable(
                    unwrap(err), primaryKey.string()
                ));
            });
    }

    /**
     * Save every sidecar sequentially; stop on first failure. Sidecars are
     * tiny so sequential writes cost nothing.
     */
    private CompletableFuture<Void> saveSidecars(
        final Key primaryKey, final Map<ChecksumAlgo, byte[]> sidecars
    ) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (final Map.Entry<ChecksumAlgo, byte[]> entry : sidecars.entrySet()) {
            final Key sidecarKey = sidecarKey(primaryKey, entry.getKey());
            final byte[] body = entry.getValue();
            chain = chain.thenCompose(ignored ->
                this.cache.save(sidecarKey, new Content.From(body))
            );
        }
        return chain;
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

    /** Create a {@link Content} backed by a temp file for immediate serving. */
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
                FileChannel::close
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
            && cur.getCause() != null && cur.getCause() != cur) {
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
        @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.CognitiveComplexity"})
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
        @SuppressWarnings("PMD.AvoidCatchingGenericException")
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
