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
import com.auto1.pantera.asto.FailedCompletionStage;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ValueNotFoundException;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.ext.KeyLastPart;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.auth.Authenticator;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.hm.RsHasBody;
import com.auto1.pantera.http.hm.RsHasHeaders;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.PathPrefixStripSlice;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedList;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Test for {@link ProxySlice}.
 */
class ProxySliceTest {

    private static final String USER = "pypi-user";
    private static final String PASSWORD = "secret";

    private Storage storage;
    private Queue<ProxyArtifactEvent> events;
    private Headers authorization;

    @BeforeEach
    void init() {
        this.storage = new InMemoryStorage();
        this.events = new LinkedList<>();
        this.authorization = Headers.from(new Authorization.Basic(USER, PASSWORD));
    }

    @Test
    void getsContentFromRemoteAndAddsItToCache() {
        final byte[] body = "some html".getBytes(StandardCharsets.UTF_8);
        final TestClientSlices clients = new TestClientSlices(line ->
            ResponseBuilder.internalError().build()
        );
        MatcherAssert.assertThat(
            "Returns body from remote",
            this.newProxySlice(
                new SliceSimple(
                    ResponseBuilder.ok().header(ContentType.mime("smth"))
                        .body(body)
                        .build()
                ),
                clients,
                Optional.of(this.events)
            ),
            new SliceHasResponse(
                Matchers.allOf(
                    new RsHasBody(body),
                    new RsHasHeaders(
                        ContentType.mime("smth"),
                        new Header("Content-Length", String.valueOf(body.length))
                    )
                ),
                new RequestLine(RqMethod.GET, "/index"),
                this.authorization,
                Content.EMPTY
            )
        );
        MatcherAssert.assertThat(
            "Stores index in cache",
            new BlockingStorage(this.storage).value(new Key.From("index")),
            new IsEqual<>(body)
        );
        Assertions.assertTrue(this.events.isEmpty(), "Index requests should not enqueue events");
        Assertions.assertFalse(clients.invoked(), "Mirror client should not be used for index");
    }

    @ParameterizedTest
    @CsvSource({
        "my project versions list in html,text/html,my-project",
        "my project wheel,*,my-project.whl",
        "my project zip,application/zip,my-project.zip",
        "my project tar,application/gzip,my-project.tar.gz"
    })
    void getsFromCacheOnError(final String data, final String header, final String key) {
        final byte[] body = data.getBytes(StandardCharsets.UTF_8);
        this.storage.save(new Key.From(key), new Content.From(body)).join();
        final TestClientSlices clients = new TestClientSlices(line ->
            ResponseBuilder.internalError().build()
        );
        MatcherAssert.assertThat(
            "Returns body from cache",
            this.newProxySlice(
                new SliceSimple(ResponseBuilder.internalError().build()),
                clients,
                Optional.of(this.events)
            ),
            new SliceHasResponse(
                Matchers.allOf(
                    new RsHasStatus(RsStatus.OK),
                    new RsHasBody(body),
                    new RsHasHeaders(
                        ContentType.mime(header)
                    )
                ),
                new RequestLine(RqMethod.GET, String.format("/%s", key)),
                this.authorization,
                Content.EMPTY
            )
        );
        MatcherAssert.assertThat(
            "Data stays intact in cache",
            new BlockingStorage(this.storage).value(new Key.From(key)),
            new IsEqual<>(body)
        );
        // A cache hit is a read, not a publish — the artifact was already
        // published to the DB the first time it was cached. No
        // ProxyArtifactEvent should be enqueued here regardless of path type.
        MatcherAssert.assertThat(
            "Cache fallback does not enqueue a publish event",
            this.events.size(),
            Matchers.is(0)
        );
        this.events.clear();
        Assertions.assertFalse(clients.invoked(), "Mirror client should not be used when cache hit");
    }

    @Test
    void returnsNotFoundWhenRemoteReturnedBadRequest() {
        MatcherAssert.assertThat(
            "Status 400 returned",
            this.newProxySlice(
                new SliceSimple(ResponseBuilder.badRequest().build()),
                new TestClientSlices(line -> ResponseBuilder.badRequest().build()),
                Optional.of(this.events)
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.NOT_FOUND),
                new RequestLine(RqMethod.GET, "/any"),
                this.authorization,
                Content.EMPTY
            )
        );
        MatcherAssert.assertThat(
            "Cache storage is empty",
            this.storage.list(Key.ROOT).join().isEmpty(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat("Queue is empty", this.events.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
        "My_Project,my-project",
        "My.Project.whl,My.Project.whl",
        "Johns.Project.tar.gz,Johns.Project.tar.gz",
        "AnotherIndex,anotherindex"
    })
    void normalisesNamesWhenNecessary(final String line, final String key) {
        final byte[] body = "python artifact".getBytes(StandardCharsets.UTF_8);
        final TestClientSlices clients = new TestClientSlices(l ->
            ResponseBuilder.internalError().build()
        );
        MatcherAssert.assertThat(
            "Returns body from remote",
            this.newProxySlice(
                new SliceSimple(
                    ResponseBuilder.ok().header(ContentType.mime("smth"))
                        .body(body)
                        .build()
                ),
                clients,
                Optional.empty()
            ),
            new SliceHasResponse(
                Matchers.allOf(
                    new RsHasBody(body),
                    new RsHasHeaders(
                        ContentType.mime("smth"),
                        new Header("Content-Length", String.valueOf(body.length))
                    )
                ),
                new RequestLine(RqMethod.GET, String.format("/%s", line)),
                this.authorization,
                Content.EMPTY
            )
        );
        MatcherAssert.assertThat(
            "Stores content in cache",
            new BlockingStorage(this.storage).value(new Key.From(key)),
            new IsEqual<>(body)
        );
        Assertions.assertFalse(clients.invoked());
    }

    @Test
    void returnsNotFoundOnRemoteAndCacheError() {
        final TestClientSlices clients = new TestClientSlices(line ->
            ResponseBuilder.internalError().build()
        );
        MatcherAssert.assertThat(
            "Status 400 returned",
            this.newProxySlice(
                new SliceSimple(ResponseBuilder.badRequest().build()),
                cacheFailing(),
                clients,
                Optional.empty()
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.NOT_FOUND),
                new RequestLine(RqMethod.GET, "/anything"),
                this.authorization,
                Content.EMPTY
            )
        );
        MatcherAssert.assertThat(
            "Cache storage is empty",
            this.storage.list(Key.ROOT).join().isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    void enqueuesEventWithReleaseInfoForArtifacts() {
        final byte[] data = "wheel body".getBytes(StandardCharsets.UTF_8);
        final Instant released = Instant.parse("2024-03-01T10:15:30Z");
        final String filename = "example_project-1.2.3-py3-none-any.whl";
        final Headers headers = Headers.from(
            new Authorization.Basic(USER, PASSWORD)
        );
        final TestClientSlices clients = new TestClientSlices(line ->
            ResponseBuilder.internalError().build()
        );
        MatcherAssert.assertThat(
            "Returns body from remote",
            this.newProxySlice(
                new SliceSimple(
                    ResponseBuilder.ok()
                        .header(ContentType.mime("application/octet-stream"))
                        .header(
                            new Header(
                                "Last-Modified",
                                DateTimeFormatter.RFC_1123_DATE_TIME.format(released.atZone(ZoneOffset.UTC))
                            )
                        )
                        .body(data)
                        .build()
                ),
                clients,
                Optional.of(this.events)
            ),
            new SliceHasResponse(
                Matchers.allOf(
                    new RsHasStatus(RsStatus.OK),
                    new RsHasBody(data)
                ),
                // Use a generic artifact path that does not rely on /packages/ routing,
                // since /packages/ is now reserved for CDN mirrors (files.pythonhosted.org).
                new RequestLine(RqMethod.GET, String.format("/%s", filename)),
                headers,
                Content.EMPTY
            )
        );
        MatcherAssert.assertThat("Event was enqueued", this.events.size(), Matchers.is(1));
        final ProxyArtifactEvent event = this.events.peek();
        MatcherAssert.assertThat("Owner recorded", event.ownerLogin(), Matchers.equalTo(USER));
        MatcherAssert.assertThat("Repository name recorded", event.repoName(), Matchers.equalTo("my-pypi-proxy"));
        MatcherAssert.assertThat(
            "Release timestamp stored",
            event.releaseMillis(),
            Matchers.equalTo(Optional.of(released.toEpochMilli()))
        );
        MatcherAssert.assertThat(
            "Artifact key contains filename",
            new KeyLastPart(event.artifactKey()).get(),
            Matchers.equalTo(filename)
        );
    }

    @Test
    void rewritesUpstreamPackageLinksToProxyPath() {
        final String upstream =
            "https://files.pythonhosted.org/packages/aa/bb/pkg-1.0.0-py3-none-any.whl#sha256=abc";
        final String html = String.format(
            "<html><body><a href=\"%s\">pkg</a></body></html>", upstream
        );
        final TestClientSlices clients = new TestClientSlices(line ->
            ResponseBuilder.ok().body(Content.EMPTY).build()
        );
        final ProxySlice slice = this.newProxySlice(
            new SliceSimple(
                ResponseBuilder.ok().htmlBody(html, StandardCharsets.UTF_8).build()
            ),
            clients,
            Optional.of(this.events)
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/my-pypi-proxy/requests/"),
            this.authorization,
            Content.EMPTY
        ).toCompletableFuture().join();
        final String body = new String(response.body().asBytes(), StandardCharsets.UTF_8);
        MatcherAssert.assertThat(
            body,
            Matchers.containsString(
                "href=\"/my-pypi-proxy/packages/aa/bb/pkg-1.0.0-py3-none-any.whl#sha256=abc\""
            )
        );
        Assertions.assertFalse(clients.invoked(), "Mirror fetch should not happen for index");
    }

    @Test
    void fetchesPackageViaMirrorMapping() throws Exception {
        final byte[] pkg = "package".getBytes(StandardCharsets.UTF_8);
        final String upstream =
            "https://files.pythonhosted.org/packages/aa/bb/pkg-1.0.0-py3-none-any.whl#sha256=abc";
        final String html = String.format(
            "<html><body><a href=\"%s\">pkg</a></body></html>", upstream
        );
        final TestClientSlices clients = new TestClientSlices(line ->
            ResponseBuilder.ok().body(new Content.From(pkg)).build()
        );
        final ProxySlice slice = this.newProxySlice(
            new SliceSimple(ResponseBuilder.ok().htmlBody(html, StandardCharsets.UTF_8).build()),
            clients,
            Optional.of(this.events)
        );
        slice.response(
            new RequestLine(RqMethod.GET, "/my-pypi-proxy/requests/"),
            this.authorization,
            Content.EMPTY
        ).toCompletableFuture().join();
        final Response pkgResp = slice.response(
            new RequestLine(RqMethod.GET, "/my-pypi-proxy/packages/aa/bb/pkg-1.0.0-py3-none-any.whl"),
            this.authorization,
            Content.EMPTY
        ).toCompletableFuture().join();
        Assertions.assertTrue(clients.invoked(), "Mirror client must be used");
        MatcherAssert.assertThat(clients.host(), Matchers.equalTo("files.pythonhosted.org"));
        MatcherAssert.assertThat(
            clients.lastLine().uri().getPath(),
            Matchers.equalTo("/packages/aa/bb/pkg-1.0.0-py3-none-any.whl")
        );
        // Consume body to trigger StreamThroughCache background save
        pkgResp.body().asBytesFuture().join();
        // Wait for async storage save to complete
        Thread.sleep(200);
        final byte[] cached = new BlockingStorage(this.storage)
            .value(new Key.From("my-pypi-proxy/packages/aa/bb/pkg-1.0.0-py3-none-any.whl"));
        MatcherAssert.assertThat(cached, Matchers.equalTo(pkg));
    }

    @Test
    void returnsNotFoundWhenIndexHasNoLinks() {
        final String html = "<!DOCTYPE html><html><body><h1>Links for hello</h1></body></html>";
        final TestClientSlices clients = new TestClientSlices(line -> ResponseBuilder.ok().build());
        final ProxySlice slice = this.newProxySlice(
            new SliceSimple(ResponseBuilder.ok().htmlBody(html, StandardCharsets.UTF_8).build()),
            clients,
            Optional.of(this.events)
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/my-pypi-proxy/hello/"),
            this.authorization,
            Content.EMPTY
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(response.status(), Matchers.is(RsStatus.NOT_FOUND));
        Assertions.assertFalse(clients.invoked(), "Remote fetch must not be triggered");
    }

    @Test
    void returnsNotFoundForTrimmedPathIndexWithoutLinks() {
        final String html = "<!DOCTYPE html><html><body><h1>Links for hello</h1></body></html>";
        final TestClientSlices clients = new TestClientSlices(line -> ResponseBuilder.ok().build());
        final ProxySlice slice = this.newProxySlice(
            new SliceSimple(ResponseBuilder.ok().htmlBody(html, StandardCharsets.UTF_8).build()),
            clients,
            Optional.of(this.events)
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/hello/"),
            this.authorization,
            Content.EMPTY
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(response.status(), Matchers.is(RsStatus.NOT_FOUND));
        Assertions.assertFalse(clients.invoked(), "Remote fetch must not be triggered");
    }

    @Test
    void fetchesMetadataViaMirrorMapping() {
        final String upstream =
            "https://files.pythonhosted.org/packages/aa/bb/pkg-1.0.0-py3-none-any.whl#sha256=abc";
        final String html = String.format(
            "<html><body><a href=\"%s\">pkg</a></body></html>", upstream
        );
        final TestClientSlices clients = new TestClientSlices(line ->
            ResponseBuilder.ok().body(Content.EMPTY).build()
        );
        final ProxySlice slice = this.newProxySlice(
            new SliceSimple(ResponseBuilder.ok().htmlBody(html, StandardCharsets.UTF_8).build()),
            clients,
            Optional.of(this.events)
        );
        slice.response(
            new RequestLine(RqMethod.GET, "/my-pypi-proxy/requests/"),
            this.authorization,
            Content.EMPTY
        ).toCompletableFuture().join();
        slice.response(
            new RequestLine(RqMethod.GET, "/my-pypi-proxy/packages/aa/bb/pkg-1.0.0-py3-none-any.whl.metadata"),
            this.authorization,
            Content.EMPTY
        ).toCompletableFuture().join();
        Assertions.assertTrue(clients.invoked(), "Mirror client must be used for metadata");
        MatcherAssert.assertThat(
            clients.lastLine().uri().getPath(),
            Matchers.equalTo("/packages/aa/bb/pkg-1.0.0-py3-none-any.whl.metadata")
        );
    }

    @Test
    void fetchesPackageViaMirrorMappingWithoutRepoPrefix() {
        final byte[] pkg = "trimmed".getBytes(StandardCharsets.UTF_8);
        final String upstream =
            "https://files.pythonhosted.org/packages/aa/bb/pkg-2.0.0-py3-none-any.whl#sha256=def";
        final String html = String.format(
            "<html><body><a href=\"%s\">pkg</a></body></html>", upstream
        );
        final TestClientSlices clients = new TestClientSlices(line ->
            ResponseBuilder.ok().body(new Content.From(pkg)).build()
        );
        final ProxySlice slice = this.newProxySlice(
            new SliceSimple(ResponseBuilder.ok().htmlBody(html, StandardCharsets.UTF_8).build()),
            clients,
            Optional.of(this.events)
        );
        slice.response(
            new RequestLine(RqMethod.GET, "/my-pypi-proxy/project/"),
            this.authorization,
            Content.EMPTY
        ).toCompletableFuture().join();
        clients.reset();
        slice.response(
            new RequestLine(RqMethod.GET, "/packages/aa/bb/pkg-2.0.0-py3-none-any.whl"),
            this.authorization,
            Content.EMPTY
        ).toCompletableFuture().join();
        Assertions.assertTrue(clients.invoked(), "Mirror client must be used for trimmed path");
        MatcherAssert.assertThat(clients.host(), Matchers.equalTo("files.pythonhosted.org"));
        MatcherAssert.assertThat(
            clients.lastLine().uri().getPath(),
            Matchers.equalTo("/packages/aa/bb/pkg-2.0.0-py3-none-any.whl")
        );
    }

    @Test
    void fetchesMetadataViaMirrorMappingWithoutRepoPrefix() {
        final String upstream =
            "https://files.pythonhosted.org/packages/aa/bb/pkg-2.1.0-py3-none-any.whl#sha256=abc";
        final String html = String.format(
            "<html><body><a href=\"%s\">pkg</a></body></html>", upstream
        );
        final TestClientSlices clients = new TestClientSlices(line ->
            ResponseBuilder.ok().body(Content.EMPTY).build()
        );
        final ProxySlice slice = this.newProxySlice(
            new SliceSimple(ResponseBuilder.ok().htmlBody(html, StandardCharsets.UTF_8).build()),
            clients,
            Optional.of(this.events)
        );
        slice.response(
            new RequestLine(RqMethod.GET, "/my-pypi-proxy/project/"),
            this.authorization,
            Content.EMPTY
        ).toCompletableFuture().join();
        clients.reset();
        slice.response(
            new RequestLine(RqMethod.GET, "/packages/aa/bb/pkg-2.1.0-py3-none-any.whl.metadata"),
            this.authorization,
            Content.EMPTY
        ).toCompletableFuture().join();
        Assertions.assertTrue(clients.invoked(), "Mirror client must be used for trimmed metadata");
        MatcherAssert.assertThat(
            clients.lastLine().uri().getPath(),
            Matchers.equalTo("/packages/aa/bb/pkg-2.1.0-py3-none-any.whl.metadata")
        );
    }

    @Test
    void nonArtifactRaceRefetchesFromUpstreamInsteadOf404() {
        // The cached index entry is evicted between exists() and the direct
        // read (TOCTOU with DiskCache eviction / rollback), so the read throws
        // ValueNotFoundException. serveNonArtifact must refetch from upstream,
        // NOT return a spurious 404 for an index that still exists upstream.
        this.storage = new Storage.Wrap(new InMemoryStorage()) {
            @Override
            public CompletableFuture<Boolean> exists(final Key key) {
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public CompletableFuture<Content> value(final Key key) {
                return CompletableFuture.failedFuture(
                    new ValueNotFoundException(key, new NoSuchFileException(key.string()))
                );
            }

            @Override
            public CompletableFuture<? extends Meta> metadata(final Key key) {
                return CompletableFuture.failedFuture(
                    new ValueNotFoundException(key, new NoSuchFileException(key.string()))
                );
            }
        };
        final byte[] body = "index-html-body".getBytes(StandardCharsets.UTF_8);
        final TestClientSlices clients = new TestClientSlices(line ->
            ResponseBuilder.internalError().build()
        );
        MatcherAssert.assertThat(
            "A vanished cache entry must refetch from upstream, not 404",
            this.newProxySlice(
                new SliceSimple(
                    ResponseBuilder.ok().header(ContentType.mime("text/html"))
                        .body(body)
                        .build()
                ),
                clients,
                Optional.of(this.events)
            ),
            new SliceHasResponse(
                Matchers.allOf(
                    new RsHasStatus(RsStatus.OK),
                    new RsHasBody(body)
                ),
                new RequestLine(RqMethod.GET, "/my-project"),
                this.authorization,
                Content.EMPTY
            )
        );
    }

    @ParameterizedTest
    @CsvSource({
        "/my-pypi-proxy/requests/",
        "/simple/requests/"
    })
    void indexResponsesVaryOnAccept(final String path) {
        // The same index URL answers HTML or PEP 691 JSON depending on
        // Accept, so shared caches must key on it (B93).
        final String html =
            "<html><body><a href=\"requests-1.0.0.tar.gz#sha256=abc\">r</a></body></html>";
        final Response response = this.newProxySlice(
            new SliceSimple(
                ResponseBuilder.ok().htmlBody(html, StandardCharsets.UTF_8).build()
            ),
            new TestClientSlices(line -> ResponseBuilder.ok().build()),
            Optional.of(this.events)
        ).response(
            new RequestLine(RqMethod.GET, path), this.authorization, Content.EMPTY
        ).toCompletableFuture().join();
        response.body().asBytes();
        MatcherAssert.assertThat(
            response.headers().values("Vary"),
            new IsEqual<>(java.util.List.of("Accept"))
        );
    }

    @ParameterizedTest
    @CsvSource({"/simple/", "/simple", "/"})
    void declinesTheRootProjectIndexWithoutFetchingUpstream(final String path) {
        // The root /simple/ index lists every project upstream: pypi.org's
        // is ~46 MB. It must be answered cheaply and consistently, never
        // fetched, buffered or cached. Wired as in RepositorySlices, where
        // the "simple" alias is stripped and the root reaches ProxySlice as "/".
        final AtomicInteger upstream = new AtomicInteger();
        final byte[] huge = new byte[11 * 1024 * 1024];
        final Slice slice = new PathPrefixStripSlice(this.newProxySlice(
            (line, headers, body) -> {
                upstream.incrementAndGet();
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok().body(huge).build()
                );
            },
            new TestClientSlices(
                line -> {
                    upstream.incrementAndGet();
                    return ResponseBuilder.ok().body(huge).build();
                }
            ),
            Optional.of(this.events)
        ), "simple");
        for (int attempt = 1; attempt <= 3; attempt += 1) {
            final Response response = slice.response(
                new RequestLine(RqMethod.GET, path), this.authorization, Content.EMPTY
            ).toCompletableFuture().join();
            MatcherAssert.assertThat(
                String.format("request %d is declined with 404", attempt),
                response.status(), new IsEqual<>(RsStatus.NOT_FOUND)
            );
            MatcherAssert.assertThat(
                String.format("request %d names the reason", attempt),
                response.headers().values("X-Pantera-Reason"),
                new IsEqual<>(java.util.List.of("not_implemented"))
            );
        }
        MatcherAssert.assertThat(
            "the upstream is never asked for the root index",
            upstream.get(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "nothing is cached for the root index",
            this.storage.list(Key.ROOT).join(), new IsEqual<>(java.util.List.of())
        );
    }

    @Test
    void proxiesPerVersionJsonApiToJsonUpstream() {
        // /pypi/<pkg>/<ver>/json must be served by the PyPI JSON API
        // upstream, not the simple mirror (which 404s it) (B92).
        final byte[] json = ("{\"info\":{\"name\":\"six\",\"version\":\"1.16.0\"},"
            + "\"urls\":[{\"filename\":\"six-1.16.0.tar.gz\","
            + "\"upload_time_iso_8601\":\"2021-05-05T14:18:18.000000Z\"}]}")
            .getBytes(StandardCharsets.UTF_8);
        final ProxySlice slice = new ProxySlice(
            new TestClientSlices(line -> ResponseBuilder.ok().build()),
            Authenticator.ANONYMOUS,
            new SliceSimple(ResponseBuilder.notFound().build()),
            this.storage,
            new FromStorageCache(this.storage),
            Optional.of(this.events),
            "my-pypi-proxy",
            "pypi-proxy",
            NoopCooldownService.INSTANCE,
            new com.auto1.pantera.publishdate.RegistryBackedInspector(
                "pypi", com.auto1.pantera.publishdate.PublishDateRegistries.instance()
            ),
            (line, headers, body) -> CompletableFuture.completedFuture(
                "/pypi/six/1.16.0/json".equals(line.uri().getPath())
                    ? ResponseBuilder.ok().jsonBody(new String(json, StandardCharsets.UTF_8))
                        .build()
                    : ResponseBuilder.notFound().build()
            )
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/pypi/six/1.16.0/json"),
            this.authorization,
            Content.EMPTY
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "per-version JSON must be answered by the JSON API upstream",
            response.status(),
            new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "the upstream document must be forwarded",
            new String(response.body().asBytes(), StandardCharsets.UTF_8),
            Matchers.containsString("\"version\":\"1.16.0\"")
        );
    }

    private ProxySlice newProxySlice(
        final Slice upstream,
        final TestClientSlices clients,
        final Optional<Queue<ProxyArtifactEvent>> queue
    ) {
        return this.newProxySlice(
            upstream,
            new FromStorageCache(this.storage),
            clients,
            queue
        );
    }

    private ProxySlice newProxySlice(
        final Slice upstream,
        final Cache cache,
        final TestClientSlices clients,
        final Optional<Queue<ProxyArtifactEvent>> queue
    ) {
        return new ProxySlice(
            clients,
            Authenticator.ANONYMOUS,
            upstream,
            this.storage,
            cache,
            queue,
            "my-pypi-proxy",
            "pypi-proxy",
            NoopCooldownService.INSTANCE,
            new com.auto1.pantera.publishdate.RegistryBackedInspector(
                "pypi", com.auto1.pantera.publishdate.PublishDateRegistries.instance()
            ),
            // jsonApiUpstream — tests only exercise simple-API and artifact paths;
            // a stub that always 404s suffices since none of these tests touch
            // /pypi/{pkg}/{ver}/json
            (line, headers, body) -> java.util.concurrent.CompletableFuture.completedFuture(
                com.auto1.pantera.http.ResponseBuilder.notFound().build()
            )
        );
    }

    private static Cache cacheFailing() {
        return (key, remote, control) ->
            new FailedCompletionStage<>(
                new IllegalStateException("Failed to obtain item from cache")
            );
    }

    private static final class TestClientSlices implements ClientSlices {

        private final Function<RequestLine, Response> responder;
        private boolean invoked;
        private boolean secure;
        private String host;
        private Integer port;
        private RequestLine last;

        TestClientSlices(final Function<RequestLine, Response> responder) {
            this.responder = responder;
        }

        boolean invoked() {
            return this.invoked;
        }

        String host() {
            return this.host;
        }

        RequestLine lastLine() {
            return this.last;
        }

        @Override
        public Slice http(final String host) {
            return this.slice(false, host, null);
        }

        @Override
        public Slice http(final String host, final int port) {
            return this.slice(false, host, port);
        }

        @Override
        public Slice https(final String host) {
            return this.slice(true, host, null);
        }

        @Override
        public Slice https(final String host, final int port) {
            return this.slice(true, host, port);
        }

        private Slice slice(
            final boolean secure,
            final String host,
            final Integer port
        ) {
            return (line, headers, body) -> {
                this.invoked = true;
                this.secure = secure;
                this.host = host;
                this.port = port;
                this.last = line;
                return CompletableFuture.completedFuture(this.responder.apply(line));
            };
        }

        void reset() {
            this.invoked = false;
            this.secure = false;
            this.host = null;
            this.port = null;
            this.last = null;
        }
    }
}
