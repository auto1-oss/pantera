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
package com.auto1.pantera.npm.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.rx.RxStorageWrapper;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.npm.misc.AbbreviatedMetadata;
import com.auto1.pantera.npm.misc.MetadataETag;
import com.auto1.pantera.npm.proxy.json.CachedContent;
import com.auto1.pantera.npm.proxy.model.NpmPackage;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.reactivex.Flowable;
import io.reactivex.disposables.Disposable;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import javax.json.Json;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.reactivestreams.Publisher;

/**
 * Streaming packument ingest: upstream body to stored {@code meta.json},
 * {@code meta.abbreviated.json} and {@code meta.meta} through
 * {@link HttpNpmRemote} and {@link RxNpmProxyStorage}.
 *
 * <p>Proves the stored bytes are exactly what the pre-streaming String
 * path stored, the hashes match the bytes, temp files never outlive the
 * ingest, and a packument of tens of megabytes is never materialised.</p>
 *
 * @since 2.2.11
 */
final class PackumentIngestTest {

    /**
     * Upstream Last-Modified.
     */
    private static final String MODIFIED = "Tue, 24 Mar 2020 12:15:16 GMT";

    /**
     * Upstream ETag.
     */
    private static final String ETAG = "\"upstream-1\"";

    /**
     * Thread name prefix of the blocking executor.
     */
    private static final String THREADS = "npm-ingest-test-";

    /**
     * Blocking executor under test.
     */
    private ExecutorService executor;

    /**
     * Temp directory for packument temp files.
     */
    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() {
        final AtomicInteger count = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(
            4,
            runnable -> {
                final Thread thread = new Thread(runnable, THREADS + count.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        );
    }

    @AfterEach
    void tearDown() {
        this.executor.shutdownNow();
    }

    @Test
    @Timeout(60)
    void storesTheSameBytesAsTheStringPathOnTheRegistryFixture() throws Exception {
        this.assertSameAsStringPath(
            "asdas",
            new String(new TestResource("json/original.json").asBytes(), StandardCharsets.UTF_8),
            new InMemoryStorage()
        );
    }

    @Test
    @Timeout(60)
    void storesTheSameBytesAsTheStringPathOnAScopedPackument() throws Exception {
        this.assertSameAsStringPath(
            "@prisma/client",
            new SyntheticPackument("@prisma/client", 300, 200).document(),
            new InMemoryStorage()
        );
    }

    @Test
    @Timeout(60)
    void storesTheSameBytesAsTheStringPathOnFileStorage(@TempDir final Path root)
        throws Exception {
        this.assertSameAsStringPath(
            "@types/node",
            new SyntheticPackument("@types/node", 120, 3000).document(),
            new FileStorage(root)
        );
    }

    @Test
    @Timeout(120)
    void neverMaterialisesALargePackument() throws Exception {
        final SyntheticPackument big = new SyntheticPackument("@big/client", 3000, 13_000);
        final DemandProbe probe = new DemandProbe();
        final RecordingStorage storage = new RecordingStorage(new InMemoryStorage(), "meta.json");
        final String name = "@big/client";
        final NpmPackage pkg = this.remote(PackumentIngestTest.ok(probe.watch(big.upstream())))
            .loadPackage(name).blockingGet();
        this.storage(storage).save(pkg).blockingAwait();
        final Recording full = storage.recorded(name + "/meta.json");
        final Recording abbreviated = storage.recorded(name + "/meta.abbreviated.json");
        final JsonObject meta = new JsonObject(
            new String(
                storage.value(new Key.From(name, "meta.meta")).join().asBytesFuture().join(),
                StandardCharsets.UTF_8
            )
        );
        MatcherAssert.assertThat(
            "the document is tens of megabytes (the guard is meaningful)",
            full.bytes.get() > 35L * 1024 * 1024,
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "upstream demand stays bounded — the body is never requested whole",
            probe.maxOutstanding.get() <= 64L,
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "meta.json reaches storage in bounded chunks",
            full.maxChunk.get() <= 64 * 1024,
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "meta.abbreviated.json reaches storage in bounded chunks",
            abbreviated.maxChunk.get() <= 64 * 1024,
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "meta.json is handed to storage with its exact size",
            full.declared.get(),
            new IsEqual<>(full.bytes.get())
        );
        MatcherAssert.assertThat(
            "meta.abbreviated.json is handed to storage with its exact size",
            abbreviated.declared.get(),
            new IsEqual<>(abbreviated.bytes.get())
        );
        MatcherAssert.assertThat(
            "meta.json is the tarball-rewritten upstream document",
            full.sha256(),
            new IsEqual<>(big.cachedSha256())
        );
        MatcherAssert.assertThat(
            "meta.json size matches the rewritten document",
            full.bytes.get(),
            new IsEqual<>(big.cachedSize())
        );
        MatcherAssert.assertThat(
            "content-hash is the SHA-256 of the stored meta.json",
            meta.getString("content-hash"),
            new IsEqual<>(full.sha256())
        );
        MatcherAssert.assertThat(
            "abbreviated-hash is the SHA-256 of the stored meta.abbreviated.json",
            meta.getString("abbreviated-hash"),
            new IsEqual<>(abbreviated.sha256())
        );
        MatcherAssert.assertThat(
            "meta.json is read for storage on the blocking executor, never the caller",
            full.offExecutor.get(),
            new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "the abbreviated packument carries every version",
            PackumentIngestTest.countVersions(
                storage.value(new Key.From(name, "meta.abbreviated.json")).join()
                    .asBytesFuture().join()
            ),
            new IsEqual<>(big.versions())
        );
        this.awaitNoTempFiles();
    }

    @Test
    @Timeout(60)
    void deletesTheTempFileWhenUpstreamFailsMidStream() throws Exception {
        final Flowable<ByteBuffer> body = Flowable.concat(
            Flowable.just(ByteBuffer.wrap("{\"name\":\"x\",\"versions\":{".getBytes(StandardCharsets.UTF_8))),
            Flowable.error(new IOException("upstream reset"))
        );
        final Throwable err = Assertions.assertThrows(
            RuntimeException.class,
            () -> this.remote(PackumentIngestTest.ok(body)).loadPackage("x").blockingGet()
        );
        MatcherAssert.assertThat(
            "the upstream failure propagates (it must not be cached as a 404)",
            err.getCause() == null ? err.getMessage() : err.getCause().getMessage(),
            new IsEqual<>("upstream reset")
        );
        this.awaitNoTempFiles();
    }

    @Test
    @Timeout(60)
    void deletesTheTempFileWhenTheIngestIsDisposed() throws Exception {
        final Flowable<ByteBuffer> body = Flowable.concat(
            Flowable.just(ByteBuffer.wrap("{\"name\":\"x\",\"readme\":\"".getBytes(StandardCharsets.UTF_8))),
            Flowable.never()
        );
        final Disposable sub = this.remote(PackumentIngestTest.ok(body)).loadPackage("x")
            .subscribe(pkg -> { }, err -> { });
        PackumentIngestTest.await(() -> this.tempFiles() == 1L, "the ingest started a temp file");
        sub.dispose();
        this.awaitNoTempFiles();
    }

    @Test
    @Timeout(60)
    void deletesBothTempFilesWhenStorageFails() throws Exception {
        final NpmPackage pkg = this.remote(
            PackumentIngestTest.ok(new SyntheticPackument("y", 20, 100).upstream())
        ).loadPackage("y").blockingGet();
        final Storage failing = new Storage.Wrap(new InMemoryStorage()) {
            @Override
            public CompletableFuture<Void> save(final Key key, final Content content) {
                return content.asBytesFuture().thenCompose(
                    ignored -> CompletableFuture.failedFuture(new IOException("disk full"))
                );
            }
        };
        Assertions.assertThrows(
            RuntimeException.class,
            () -> this.storage(failing).save(pkg).blockingAwait()
        );
        this.awaitNoTempFiles();
    }

    @Test
    @Timeout(60)
    void storesAnEmptyAbbreviatedDocumentWhenThePackumentCannotBeAbbreviated()
        throws Exception {
        final Storage storage = new InMemoryStorage();
        final String doc = "{\"name\":\"z\",\"versions\":[]}";
        final NpmPackage pkg = this.remote(
            PackumentIngestTest.ok(Flowable.just(ByteBuffer.wrap(doc.getBytes(StandardCharsets.UTF_8))))
        ).loadPackage("z").blockingGet();
        this.storage(storage).save(pkg).blockingAwait();
        final JsonObject meta = new JsonObject(
            new String(
                storage.value(new Key.From("z", "meta.meta")).join().asBytesFuture().join(),
                StandardCharsets.UTF_8
            )
        );
        MatcherAssert.assertThat(
            "meta.json is still stored",
            new String(
                storage.value(new Key.From("z", "meta.json")).join().asBytesFuture().join(),
                StandardCharsets.UTF_8
            ),
            new IsEqual<>(doc)
        );
        MatcherAssert.assertThat(
            "meta.abbreviated.json is empty, as before",
            storage.value(new Key.From("z", "meta.abbreviated.json")).join()
                .asBytesFuture().join().length,
            new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "no abbreviated-hash is recorded for an empty abbreviated document",
            meta.containsKey("abbreviated-hash"),
            new IsEqual<>(false)
        );
        this.awaitNoTempFiles();
    }

    @Test
    @Timeout(60)
    void firesThePackumentHookOnceAfterTheStreamedSave() throws Exception {
        final Storage storage = new InMemoryStorage();
        final AtomicReference<String> hooked = new AtomicReference<>();
        final AtomicInteger fired = new AtomicInteger();
        final NpmProxy npm = new NpmProxy(
            new RxNpmProxyStorage(new RxStorageWrapper(storage), null, this.executor, this.tmp),
            this.remote(PackumentIngestTest.ok(new SyntheticPackument("h", 10, 50).upstream())),
            Duration.ofHours(1),
            null,
            name -> {
                MatcherAssert.assertThat(
                    "the hook fires after meta.json is durable",
                    storage.exists(new Key.From(name, "meta.abbreviated.json")).join(),
                    new IsEqual<>(true)
                );
                hooked.set(name);
                fired.incrementAndGet();
            },
            null
        );
        npm.getPackageContentStream("h").blockingGet();
        MatcherAssert.assertThat("hook receives the package", hooked.get(), new IsEqual<>("h"));
        MatcherAssert.assertThat("hook fires once", fired.get(), new IsEqual<>(1));
        this.awaitNoTempFiles();
    }

    /**
     * Ingest {@code doc} through the streaming path and compare everything
     * stored with what the String path produced.
     *
     * @param name Package name
     * @param doc Upstream document
     * @param storage Storage to save into
     * @throws Exception On failure
     */
    private void assertSameAsStringPath(final String name, final String doc, final Storage storage)
        throws Exception {
        final byte[] expected = new CachedContent(doc, name).valueString()
            .getBytes(StandardCharsets.UTF_8);
        final byte[] upstream = doc.getBytes(StandardCharsets.UTF_8);
        final Flowable<ByteBuffer> chunks = Flowable.range(0, (upstream.length + 999) / 1000)
            .map(idx -> ByteBuffer.wrap(upstream, idx * 1000, Math.min(1000, upstream.length - idx * 1000)));
        final NpmPackage pkg = this.remote(PackumentIngestTest.ok(chunks))
            .loadPackage(name).blockingGet();
        MatcherAssert.assertThat(
            "package carries the upstream ETag",
            pkg.meta().upstreamEtag().orElse(null),
            new IsEqual<>(ETAG)
        );
        this.storage(storage).save(pkg).blockingAwait();
        final byte[] stored = storage.value(new Key.From(name, "meta.json")).join()
            .asBytesFuture().join();
        final byte[] abbreviated = storage.value(new Key.From(name, "meta.abbreviated.json")).join()
            .asBytesFuture().join();
        final JsonObject meta = new JsonObject(
            new String(
                storage.value(new Key.From(name, "meta.meta")).join().asBytesFuture().join(),
                StandardCharsets.UTF_8
            )
        );
        MatcherAssert.assertThat(
            "meta.json is byte-identical to the String path",
            new String(stored, StandardCharsets.UTF_8),
            new IsEqual<>(new String(expected, StandardCharsets.UTF_8))
        );
        MatcherAssert.assertThat(
            "content-hash is unchanged from the String path",
            meta.getString("content-hash"),
            new IsEqual<>(new MetadataETag(expected).calculate())
        );
        MatcherAssert.assertThat(
            "abbreviated-hash is the hash of the stored abbreviated document",
            meta.getString("abbreviated-hash"),
            new IsEqual<>(new MetadataETag(abbreviated).calculate())
        );
        MatcherAssert.assertThat(
            "meta.meta keeps last-modified",
            meta.getString("last-modified"),
            new IsEqual<>(MODIFIED)
        );
        MatcherAssert.assertThat(
            "meta.meta keeps the upstream ETag",
            meta.getString("upstream-etag"),
            new IsEqual<>(ETAG)
        );
        MatcherAssert.assertThat(
            "meta.abbreviated.json is JSON-equivalent to the DOM generator",
            Json.createReader(new StringReader(new String(abbreviated, StandardCharsets.UTF_8)))
                .readObject(),
            new IsEqual<>(
                new AbbreviatedMetadata(
                    Json.createReader(new StringReader(new String(expected, StandardCharsets.UTF_8)))
                        .readObject()
                ).generate()
            )
        );
        this.awaitNoTempFiles();
    }

    /**
     * Remote under test.
     *
     * @param origin Upstream
     * @return Remote
     */
    private HttpNpmRemote remote(final Slice origin) {
        return new HttpNpmRemote(origin, this.executor, this.tmp);
    }

    /**
     * Proxy storage under test.
     *
     * @param storage Backing storage
     * @return Proxy storage
     */
    private RxNpmProxyStorage storage(final Storage storage) {
        return new RxNpmProxyStorage(new RxStorageWrapper(storage), null, this.executor, this.tmp);
    }

    /**
     * Wait until every packument temp file is gone (deletion is
     * asynchronous on the executor).
     *
     * @throws Exception On timeout
     */
    private void awaitNoTempFiles() throws Exception {
        PackumentIngestTest.await(() -> this.tempFiles() == 0L, "no packument temp file remains");
    }

    /**
     * Count packument temp files.
     *
     * @return Count
     */
    private long tempFiles() {
        try (Stream<Path> files = Files.list(this.tmp)) {
            return files.count();
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Upstream that answers 200 with the given body.
     *
     * @param body Body
     * @return Slice
     */
    private static Slice ok(final Publisher<ByteBuffer> body) {
        return (line, headers, rqbody) -> ResponseBuilder.ok()
            .header("Last-Modified", MODIFIED)
            .header("ETag", ETAG)
            .body(new Content.From(body))
            .completedFuture();
    }

    /**
     * Poll for a condition.
     *
     * @param condition Condition
     * @param what Description
     * @throws Exception On timeout or interruption
     */
    private static void await(final BooleanSupplier condition, final String what) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting: " + what);
            }
            Thread.sleep(10);
        }
    }

    /**
     * Count the versions of an abbreviated packument with a token parser.
     *
     * @param doc Abbreviated packument
     * @return Number of versions
     * @throws IOException On malformed input
     */
    private static int countVersions(final byte[] doc) throws IOException {
        int count = 0;
        try (JsonParser parser = new JsonFactory().createParser(doc)) {
            parser.nextToken();
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                final boolean versions = "versions".equals(parser.currentName());
                parser.nextToken();
                if (versions) {
                    while (parser.nextToken() == JsonToken.FIELD_NAME) {
                        parser.nextToken();
                        parser.skipChildren();
                        count += 1;
                    }
                } else {
                    parser.skipChildren();
                }
            }
        }
        return count;
    }

    /**
     * Tracks how far demand runs ahead of delivery on the upstream body.
     */
    private static final class DemandProbe {

        /**
         * Requested minus delivered.
         */
        private final AtomicLong outstanding = new AtomicLong();

        /**
         * Highest outstanding demand seen.
         */
        private final AtomicLong maxOutstanding = new AtomicLong();

        /**
         * Watch a body.
         *
         * @param body Body
         * @return Watched body
         */
        Flowable<ByteBuffer> watch(final Flowable<ByteBuffer> body) {
            return body
                .doOnNext(chunk -> this.outstanding.decrementAndGet())
                .doOnRequest(
                    count -> {
                        final long now = this.outstanding.updateAndGet(
                            prev -> count == Long.MAX_VALUE || prev + count < 0
                                ? Long.MAX_VALUE : prev + count
                        );
                        this.maxOutstanding.accumulateAndGet(now, Math::max);
                    }
                );
        }
    }

    /**
     * What one storage key received.
     */
    private static final class Recording {

        /**
         * Size the content declared.
         */
        private final AtomicLong declared = new AtomicLong(-1L);

        /**
         * Bytes received.
         */
        private final AtomicLong bytes = new AtomicLong();

        /**
         * Largest chunk received.
         */
        private final AtomicInteger maxChunk = new AtomicInteger();

        /**
         * Chunks delivered on a thread that is not the blocking executor.
         */
        private final AtomicInteger offExecutor = new AtomicInteger();

        /**
         * Running digest.
         */
        private final MessageDigest digest;

        /**
         * Ctor.
         */
        Recording() {
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (final java.security.NoSuchAlgorithmException ex) {
                throw new IllegalStateException(ex);
            }
        }

        /**
         * Record a chunk.
         *
         * @param chunk Chunk
         */
        synchronized void record(final ByteBuffer chunk) {
            this.bytes.addAndGet(chunk.remaining());
            this.maxChunk.accumulateAndGet(chunk.remaining(), Math::max);
            this.digest.update(chunk.duplicate());
            if (!Thread.currentThread().getName().startsWith(THREADS)) {
                this.offExecutor.incrementAndGet();
            }
        }

        /**
         * SHA-256 hex of what was received.
         *
         * @return Hex
         */
        synchronized String sha256() throws CloneNotSupportedException {
            return HexFormat.of().formatHex(((MessageDigest) this.digest.clone()).digest());
        }
    }

    /**
     * Storage that records what each save receives; the body of the
     * discarded key is counted and hashed but not kept.
     */
    private static final class RecordingStorage extends Storage.Wrap {

        /**
         * Recordings by key.
         */
        private final Map<String, Recording> recordings = new ConcurrentHashMap<>();

        /**
         * Key suffix whose body is not kept.
         */
        private final String discard;

        /**
         * Ctor.
         *
         * @param delegate Delegate
         * @param discard Key suffix whose body is not kept
         */
        RecordingStorage(final Storage delegate, final String discard) {
            super(delegate);
            this.discard = discard;
        }

        /**
         * Recording of a key.
         *
         * @param key Key
         * @return Recording
         */
        Recording recorded(final String key) {
            return this.recordings.get(key);
        }

        @Override
        public CompletableFuture<Void> save(final Key key, final Content content) {
            final Recording rec = new Recording();
            rec.declared.set(content.size().orElse(-1L));
            this.recordings.put(key.string(), rec);
            final Flowable<ByteBuffer> recorded = Flowable.fromPublisher(content)
                .doOnNext(rec::record);
            final CompletableFuture<Void> res;
            if (key.string().endsWith("/" + this.discard)) {
                res = recorded.ignoreElements()
                    .to(hu.akarnokd.rxjava2.interop.CompletableInterop.await())
                    .toCompletableFuture()
                    .thenCompose(ignored -> super.save(key, Content.EMPTY));
            } else {
                res = super.save(key, new Content.From(content.size(), recorded));
            }
            return res;
        }
    }
}
