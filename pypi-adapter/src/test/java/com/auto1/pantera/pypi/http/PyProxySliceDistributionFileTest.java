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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.auth.Authenticator;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every type of distribution file PyPI has served is an artifact of the
 * PyPI proxy, built as the server builds it: cooldown is evaluated for its
 * project and version, the search index is told about it, and its bytes are
 * streamed to the client, not buffered like a project page.
 *
 * @since 2.2.15
 */
final class PyProxySliceDistributionFileTest {

    /**
     * Source of repository names: the negative cache is shared, so every
     * proxy of this class has a name of its own.
     */
    private static final AtomicInteger REPOS = new AtomicInteger();

    /**
     * Directory of the files at the upstream and in the proxy.
     */
    private static final String DIR = "/packages/aa/bb/";

    /**
     * Repository storage.
     */
    private Storage storage;

    /**
     * Artifact events of the proxy.
     */
    private Queue<ProxyArtifactEvent> events;

    /**
     * Cooldown, which records what it is asked.
     */
    private RecordingCooldown cooldown;

    /**
     * Upstream.
     */
    private FakeUpstream upstream;

    /**
     * Proxy under test.
     */
    private Slice proxy;

    @BeforeEach
    void init() {
        this.storage = new InMemoryStorage();
        this.events = new ConcurrentLinkedQueue<>();
        this.cooldown = new RecordingCooldown();
        this.upstream = new FakeUpstream();
        final String name = "pypi_dist_" + PyProxySliceDistributionFileTest.REPOS.incrementAndGet();
        this.proxy = new CachedPyProxySlice(
            new PyProxySlice(
                this.upstream, URI.create("https://pypi.example/simple"),
                Authenticator.ANONYMOUS, this.storage, Optional.of(this.events),
                name, "pypi-proxy", this.cooldown
            ),
            Optional.of(this.storage), Duration.ofHours(24), true,
            name, "https://pypi.example/simple", "pypi-proxy"
        );
    }

    @ParameterizedTest
    @CsvSource({
        "example_pkg-1.0.0.tar.gz,example-pkg,1.0.0",
        "example_pkg-1.0.0.tgz,example-pkg,1.0.0",
        "example_pkg-1.0.0.tar.bz2,example-pkg,1.0.0",
        "example_pkg-1.0.0.tbz,example-pkg,1.0.0",
        "example_pkg-1.0.0.tar.xz,example-pkg,1.0.0",
        "example_pkg-1.0.0.txz,example-pkg,1.0.0",
        "example_pkg-1.0.0.tar.Z,example-pkg,1.0.0",
        "example_pkg-1.0.0.tar,example-pkg,1.0.0",
        "example_pkg-1.0.0.zip,example-pkg,1.0.0",
        "example_pkg-1.0.0-py3-none-any.whl,example-pkg,1.0.0",
        "example_pkg-1.0.0-py2.7.egg,example-pkg,1.0.0",
        "example_pkg-1.0.0.win32.exe,example-pkg,1.0.0",
        "example_pkg-1.0.0.win-amd64-py3.8.msi,example-pkg,1.0.0",
        "example_pkg-1.0.0-1.noarch.rpm,example-pkg,1.0.0",
        "example_pkg-1.0.0-py2.7-macosx10.6.dmg,example-pkg,1.0.0",
        "example-pkg_1.0.0-1_all.deb,example-pkg,1.0.0"
    })
    @Timeout(120)
    void distributionFileIsAnArtifact(
        final String file, final String project, final String version
    ) throws Exception {
        final String path = PyProxySliceDistributionFileTest.DIR + file;
        final byte[] bytes = "distribution bytes".getBytes(StandardCharsets.UTF_8);
        this.upstream.answer(path, () -> ResponseBuilder.ok().body(bytes).build());
        final Response response = this.get(path);
        MatcherAssert.assertThat(
            "the file is served",
            response.body().asBytesFuture().get(60, TimeUnit.SECONDS), new IsEqual<>(bytes)
        );
        MatcherAssert.assertThat(
            "cooldown is asked about the project and the version of the file",
            this.cooldown.asked(), new IsEqual<>(List.of(project + " " + version))
        );
        MatcherAssert.assertThat(
            "the search index is told about the file",
            this.events.stream().map(event -> event.artifactKey().string())
                .collect(Collectors.toList()),
            new IsEqual<>(List.of(path.substring(1)))
        );
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "example_pkg-1.0.0.tar.bz2", "example_pkg-1.0.0.tgz",
            "example_pkg-1.0.0.tar.xz", "example_pkg-1.0.0.win32.exe"
        }
    )
    @Timeout(120)
    void distributionFileIsStreamedThrough(final String file) throws Exception {
        final String path = PyProxySliceDistributionFileTest.DIR + file;
        final byte[] head = "first part ".getBytes(StandardCharsets.UTF_8);
        final byte[] tail = "and the rest".getBytes(StandardCharsets.UTF_8);
        final CompletableFuture<Void> rest = new CompletableFuture<>();
        this.upstream.answer(
            path,
            () -> ResponseBuilder.ok().body(
                new Content.From(
                    Flowable.<ByteBuffer>create(
                        emitter -> {
                            emitter.onNext(ByteBuffer.wrap(head));
                            rest.thenRun(
                                () -> {
                                    emitter.onNext(ByteBuffer.wrap(tail));
                                    emitter.onComplete();
                                }
                            );
                        },
                        BackpressureStrategy.BUFFER
                    )
                )
            ).build()
        );
        try {
            // A buffered body is answered only when the upstream's body has
            // ended, which it does not before the client is answered: the
            // timeout turns buffering into a failure.
            final Response response = this.proxy.response(
                new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
            ).get(30, TimeUnit.SECONDS);
            rest.complete(null);
            final byte[] whole = Arrays.copyOf(head, head.length + tail.length);
            System.arraycopy(tail, 0, whole, head.length, tail.length);
            MatcherAssert.assertThat(
                "the client is answered while the upstream still sends the file",
                response.body().asBytesFuture().get(60, TimeUnit.SECONDS), new IsEqual<>(whole)
            );
        } finally {
            rest.complete(null);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"example_pkg-1.0.0.tar.bz2", "example_pkg-1.0.0.tar.xz"})
    @Timeout(120)
    void largeStoredDistributionFileIsServed(final String file) throws Exception {
        final String path = PyProxySliceDistributionFileTest.DIR + file;
        final byte[] bytes = new byte[11 * 1024 * 1024];
        Arrays.fill(bytes, (byte) 'x');
        this.storage.save(new Key.From(path.substring(1)), new Content.From(bytes)).join();
        final Response response = this.get(path);
        MatcherAssert.assertThat(
            "a stored file larger than a project page may be is served from the cache",
            response.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "whole",
            response.body().asBytesFuture().get(60, TimeUnit.SECONDS).length,
            new IsEqual<>(bytes.length)
        );
        MatcherAssert.assertThat(
            "without asking the upstream",
            this.upstream.asked(path), new IsEqual<>(0)
        );
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "example_pkg-1.0.0.tar.bz2", "example_pkg-1.0.0.tgz", "example_pkg-1.0.0.tbz",
            "example_pkg-1.0.0.tar.xz", "example_pkg-1.0.0.txz",
            "example_pkg-1.0.0.win32.exe", "example_pkg-1.0.0.win32.msi",
            "example_pkg-1.0.0-1.noarch.rpm", "example_pkg-1.0.0-py2.7-macosx10.6.dmg",
            "example-pkg_1.0.0-1_all.deb"
        }
    )
    @Timeout(120)
    void headOfADistributionFileIsRefusedWithoutADownload(final String file)
        throws Exception {
        final String path = PyProxySliceDistributionFileTest.DIR + file;
        this.upstream.answer(
            path, () -> ResponseBuilder.ok().body("bytes".getBytes(StandardCharsets.UTF_8)).build()
        );
        final Response response = this.proxy.response(
            new RequestLine(RqMethod.HEAD, path), Headers.EMPTY, Content.EMPTY
        ).get(60, TimeUnit.SECONDS);
        response.body().asBytesFuture().get(60, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "HEAD of a file is refused",
            response.status(), new IsEqual<>(RsStatus.METHOD_NOT_ALLOWED)
        );
        MatcherAssert.assertThat(
            "and the upstream is not asked for it",
            this.upstream.asked(path), new IsEqual<>(0)
        );
    }

    /**
     * Send a GET to the proxy under test.
     * @param path Request path
     * @return Answer
     * @throws Exception On failure or timeout
     */
    private Response get(final String path) throws Exception {
        return this.proxy.response(
            new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
        ).get(60, TimeUnit.SECONDS);
    }

    /**
     * Cooldown that allows everything and records the project and the
     * version it was asked about.
     */
    private static final class RecordingCooldown implements CooldownService {

        /**
         * Requests, as {@code <project> <version>}.
         */
        private final Queue<String> requests = new ConcurrentLinkedQueue<>();

        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector inspector
        ) {
            this.requests.add(request.artifact() + " " + request.version());
            return CompletableFuture.completedFuture(CooldownResult.allowed());
        }

        @Override
        public CompletableFuture<Void> unblock(
            final String repoType, final String repoName, final String artifact,
            final String version, final String actor
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> unblockAll(
            final String repoType, final String repoName, final String actor
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<CooldownBlock>> activeBlocks(
            final String repoType, final String repoName
        ) {
            return CompletableFuture.completedFuture(List.of());
        }

        /**
         * What cooldown was asked about.
         * @return Requests, as {@code <project> <version>}
         */
        List<String> asked() {
            return List.copyOf(this.requests);
        }
    }

    /**
     * Upstream that answers each path as it was told, whatever the host,
     * 404 for any other path, and counts the requests per path.
     */
    private static final class FakeUpstream implements ClientSlices {

        /**
         * Answers by path.
         */
        private final Map<String, Supplier<Response>> answers = new ConcurrentHashMap<>();

        /**
         * Paths asked for.
         */
        private final Queue<String> requests = new ConcurrentLinkedQueue<>();

        @Override
        public Slice http(final String host) {
            return this.slice();
        }

        @Override
        public Slice http(final String host, final int port) {
            return this.slice();
        }

        @Override
        public Slice https(final String host) {
            return this.slice();
        }

        @Override
        public Slice https(final String host, final int port) {
            return this.slice();
        }

        /**
         * Answer a path.
         * @param path Path at the upstream
         * @param answer Answer
         */
        void answer(final String path, final Supplier<Response> answer) {
            this.answers.put(path, answer);
        }

        /**
         * How often a path was asked for.
         * @param path Path at the upstream
         * @return Number of requests
         */
        int asked(final String path) {
            return (int) this.requests.stream().filter(path::equals).count();
        }

        private Slice slice() {
            return (line, headers, body) -> {
                final String path = line.uri().getPath();
                this.requests.add(path);
                return CompletableFuture.completedFuture(
                    this.answers.getOrDefault(path, () -> ResponseBuilder.notFound().build()).get()
                );
            };
        }
    }
}
