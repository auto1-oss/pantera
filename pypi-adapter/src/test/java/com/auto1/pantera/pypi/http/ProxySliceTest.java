/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.FailedCompletionStage;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.ext.KeyLastPart;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.NoopCooldownService;
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
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedList;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
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
        final boolean expectEvent = key.matches(".*\\.(whl|tar\\.gz|zip|tar\\.bz2|tar\\.Z|tar|egg)");
        MatcherAssert.assertThat(
            "Cache fallback enqueued event when artifact path detected",
            this.events.size(),
            Matchers.is(expectEvent ? 1 : 0)
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
            new PyProxyCooldownInspector()
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
