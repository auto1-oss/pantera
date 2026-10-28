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
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.rx.RxStorageWrapper;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.PanteraHttpException;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.npm.proxy.http.DownloadPackageSlice;
import com.auto1.pantera.npm.proxy.http.PackagePath;
import com.auto1.pantera.npm.proxy.model.NpmPackage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * An upstream {@code 200} whose packument is not one JSON object is an
 * upstream failure: nothing is cached, a cached copy survives the refresh.
 *
 * @since 2.2.11
 */
final class NpmProxyInvalidUpstreamTest {

    private static final String NAME = "pkg";

    private static final String VALID = "{\"name\":\"pkg\",\"dist-tags\":{\"latest\":\"1.0.0\"},"
        + "\"versions\":{\"1.0.0\":{\"name\":\"pkg\",\"version\":\"1.0.0\","
        + "\"dist\":{\"tarball\":\"/pkg/-/pkg-1.0.0.tgz\"}}},"
        + "\"time\":{\"1.0.0\":\"2020-01-01T00:00:00.000Z\"}}";

    @TempDir
    private Path tmp;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        this.executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws Exception {
        // A failed ingest releases its staging file on the executor after the
        // response has completed; let it finish before @TempDir cleanup runs.
        final long left = this.settledLeftovers();
        this.executor.shutdown();
        this.executor.awaitTermination(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat("every staging file is released", left, new IsEqual<>(0L));
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "{\"name\":\"pkg\",\"versions\":{\"1.0.0\":{\"name\":\"pkg\",\"descr",
            "{\"name\":\"pkg\",\"versions\":{}",
            "{\"name\":\"pkg\",\"versions\":{,}}",
            "[{\"name\":\"pkg\"}]",
            "\"pkg\"",
            ""
        }
    )
    @Timeout(15)
    void firstFetchOfAnInvalidDocumentIsAnUpstreamFailure(final String body) throws Exception {
        final Storage delegate = new InMemoryStorage();
        final NpmProxy npm = this.proxy(delegate, new Scripted(body.getBytes(StandardCharsets.UTF_8)));
        Throwable error = null;
        try {
            npm.getPackageMetadataOnly(NAME).blockingGet();
        } catch (final RuntimeException ex) {
            error = ex;
        }
        MatcherAssert.assertThat(
            "fails as an upstream failure", NpmProxyInvalidUpstreamTest.root(error),
            new IsInstanceOf(PanteraHttpException.class)
        );
        MatcherAssert.assertThat(
            "with 502", ((PanteraHttpException) NpmProxyInvalidUpstreamTest.root(error)).status(),
            new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "nothing is stored", delegate.list(Key.ROOT).join(), new IsEqual<>(List.of())
        );
        MatcherAssert.assertThat("no temp file is left", this.settledLeftovers(), new IsEqual<>(0L));
    }

    @Test
    @Timeout(15)
    void packumentServeAnswers502ForAnInvalidFirstFetch() throws Exception {
        final Storage delegate = new InMemoryStorage();
        final Response response = new DownloadPackageSlice(
            this.proxy(delegate, new Scripted("{\"name\":\"pkg\",\"vers".getBytes(StandardCharsets.UTF_8))),
            new PackagePath("")
        ).response(new RequestLine(RqMethod.GET, "/pkg"), Headers.EMPTY, Content.EMPTY)
            .get(10, TimeUnit.SECONDS);
        response.body().asBytesFuture().join();
        MatcherAssert.assertThat("502", response.status(), new IsEqual<>(RsStatus.BAD_GATEWAY));
        MatcherAssert.assertThat(
            "nothing is stored", delegate.list(Key.ROOT).join(), new IsEqual<>(List.of())
        );
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Timeout(15)
    void refreshWithAnInvalidDocumentKeepsTheCachedCopy(final boolean etag) throws Exception {
        final Storage delegate = new InMemoryStorage();
        final Scripted origin = new Scripted(
            VALID.getBytes(StandardCharsets.UTF_8),
            VALID.substring(0, VALID.length() / 2).getBytes(StandardCharsets.UTF_8)
        );
        origin.etag(etag);
        final NpmProxyStorage storage = this.storage(delegate);
        final NpmProxy npm = new NpmProxy(storage, this.remote(origin), Duration.ofHours(1));
        npm.getPackageMetadataOnly(NAME).blockingGet();
        final byte[] content = NpmProxyInvalidUpstreamTest.bytes(delegate, "pkg/meta.json");
        final byte[] abbreviated = NpmProxyInvalidUpstreamTest.bytes(delegate, "pkg/meta.abbreviated.json");
        final NpmPackage.Metadata persisted = storage.getPackageMetadata(NAME).blockingGet();
        storage.saveMetadataOnly(
            NAME,
            new NpmPackage.Metadata(
                persisted.lastModified(),
                OffsetDateTime.now().minus(2, ChronoUnit.HOURS),
                persisted.contentHash().orElse(null),
                persisted.abbreviatedHash().orElse(null),
                persisted.upstreamEtag().orElse(null)
            )
        ).blockingAwait();
        final byte[] meta = NpmProxyInvalidUpstreamTest.bytes(delegate, "pkg/meta.meta");
        // A third upstream call proves the failed refresh finished without
        // re-arming the metadata TTL: the next serve retries it.
        NpmProxyInvalidUpstreamTest.retried(npm, origin, 3);
        MatcherAssert.assertThat(
            "cached packument kept", NpmProxyInvalidUpstreamTest.bytes(delegate, "pkg/meta.json"),
            new IsEqual<>(content)
        );
        MatcherAssert.assertThat(
            "cached abbreviated packument kept",
            NpmProxyInvalidUpstreamTest.bytes(delegate, "pkg/meta.abbreviated.json"),
            new IsEqual<>(abbreviated)
        );
        MatcherAssert.assertThat(
            "meta.meta untouched, so the next request retries the refresh",
            NpmProxyInvalidUpstreamTest.bytes(delegate, "pkg/meta.meta"),
            new IsEqual<>(meta)
        );
        MatcherAssert.assertThat(
            "the cached copy is still served",
            npm.getPackageContentStream(NAME).blockingGet().asBytesFuture().join(),
            new IsEqual<>(content)
        );
        MatcherAssert.assertThat("no temp file is left", this.settledLeftovers(), new IsEqual<>(0L));
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "bom", "invalid-utf8", "lone-surrogate", "trailing", "versions-not-object", "name-number"
        }
    )
    @Timeout(15)
    void acceptedDocumentsAreCachedByteIdentically(final String kind) throws Exception {
        final byte[] body = NpmProxyInvalidUpstreamTest.document(kind);
        final Storage delegate = new InMemoryStorage();
        this.proxy(delegate, new Scripted(body)).getPackageMetadataOnly(NAME).blockingGet();
        MatcherAssert.assertThat(
            "stored as received", NpmProxyInvalidUpstreamTest.bytes(delegate, "pkg/meta.json"),
            new IsEqual<>(body)
        );
        MatcherAssert.assertThat(
            "abbreviated only when npm can use the shape",
            NpmProxyInvalidUpstreamTest.bytes(delegate, "pkg/meta.abbreviated.json").length > 0,
            new IsEqual<>(!kind.endsWith("-object") && !kind.endsWith("-number"))
        );
    }

    private static byte[] document(final String kind) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final String head = "{\"name\":\"pkg\",\"description\":\"";
        final String tail = "\",\"dist-tags\":{\"latest\":\"1.0.0\"},\"versions\":{\"1.0.0\":{\"name\":\"pkg\"}}}";
        switch (kind) {
            case "bom":
                out.writeBytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
                out.writeBytes((head + "x" + tail).getBytes(StandardCharsets.UTF_8));
                break;
            case "invalid-utf8":
                out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
                out.writeBytes(new byte[] {(byte) 0xC3, (byte) 0x28, (byte) 0xFF});
                out.writeBytes(tail.getBytes(StandardCharsets.UTF_8));
                break;
            case "lone-surrogate":
                out.writeBytes((head + "\\ud800" + tail).getBytes(StandardCharsets.UTF_8));
                break;
            case "trailing":
                out.writeBytes((head + "x" + tail + " {\"name\":\"other\"} garbage").getBytes(StandardCharsets.UTF_8));
                break;
            case "versions-not-object":
                out.writeBytes("{\"name\":\"pkg\",\"versions\":[1,2]}".getBytes(StandardCharsets.UTF_8));
                break;
            default:
                out.writeBytes("{\"name\":7,\"versions\":{}}".getBytes(StandardCharsets.UTF_8));
                break;
        }
        return out.toByteArray();
    }

    private NpmProxy proxy(final Storage delegate, final Slice origin) {
        return new NpmProxy(this.storage(delegate), this.remote(origin), Duration.ofHours(1));
    }

    private NpmProxyStorage storage(final Storage delegate) {
        return new RxNpmProxyStorage(new RxStorageWrapper(delegate), null, this.executor, this.tmp);
    }

    private NpmRemote remote(final Slice origin) {
        return new HttpNpmRemote(origin, this.executor, this.tmp);
    }

    private long leftovers() throws Exception {
        try (Stream<Path> files = Files.list(this.tmp)) {
            return files.count();
        }
    }

    /**
     * Temp files left once in-flight work settles. A background refresh
     * deletes its staging file after the upstream call the test waited for
     * has returned, so the count is polled instead of read once.
     *
     * @return Remaining temp files, 0 as soon as they are gone
     * @throws Exception On listing failure
     */
    private long settledLeftovers() throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long left = this.leftovers();
        while (left > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
            left = this.leftovers();
        }
        return left;
    }

    private static byte[] bytes(final Storage storage, final String key) {
        return storage.value(new Key.From(key)).join().asBytesFuture().join();
    }

    private static Throwable root(final Throwable error) {
        Throwable cause = error;
        while (cause != null && !(cause instanceof PanteraHttpException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * Serve until the upstream saw the given number of calls; serving in a
     * loop lets the in-flight guard of the previous refresh clear.
     */
    private static void retried(final NpmProxy npm, final Scripted origin, final int calls)
        throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (origin.calls() < calls) {
            npm.getPackageMetadataOnly(NAME).blockingGet();
            if (System.nanoTime() > deadline) {
                throw new AssertionError("refresh was not retried (calls=" + origin.calls() + ")");
            }
            Thread.sleep(5);
        }
    }

    /**
     * Upstream answering 200 with scripted bodies, the last one repeated.
     */
    private static final class Scripted implements Slice {

        private final byte[][] bodies;

        private final AtomicInteger calls = new AtomicInteger();

        private boolean etag;

        Scripted(final byte[]... bodies) {
            this.bodies = bodies.clone();
        }

        void etag(final boolean send) {
            this.etag = send;
        }

        int calls() {
            return this.calls.get();
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            final int call = this.calls.incrementAndGet();
            final ResponseBuilder builder = ResponseBuilder.ok()
                .header("Last-Modified", "Tue, 24 Mar 2020 12:15:16 GMT");
            if (this.etag) {
                builder.header("ETag", "\"etag-" + call + "\"");
            }
            return builder.body(this.bodies[Math.min(call, this.bodies.length) - 1]).completedFuture();
        }
    }
}
