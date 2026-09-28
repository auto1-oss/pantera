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
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.auth.BasicAuthenticator;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.SliceSimple;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression test for the PyPI mirror credential-forwarding SSRF:
 * the proxy records every absolute {@code /packages/} link it sees in an
 * upstream simple index and later dials that host to fetch the file — before
 * 2.2.9 wrapped in the CONFIGURED upstream authenticator, so a malicious or
 * compromised index could point a link at itself and harvest the upstream
 * Basic credentials. Credentials must go only to the upstream host (or its
 * parent domain / allowlist); any other mirror is fetched anonymously.
 *
 * @since 2.2.9
 */
final class ProxySliceMirrorCredentialTest {

    private static final String LINK = "/packages/aa/bb/pkg-1.0.0-py3-none-any.whl";

    @Test
    void mirrorOnAForeignHostIsFetchedWithoutTheUpstreamCredentials() {
        final AtomicReference<Headers> seen = new AtomicReference<>();
        this.fetchThroughMirror(
            URI.create("https://index.private.example/simple/"),
            "https://evil.attacker.net" + LINK,
            seen
        );
        MatcherAssert.assertThat(
            "the configured upstream credentials must NOT be sent to a mirror on a foreign host",
            seen.get().values(Authorization.NAME).isEmpty(), new IsEqual<>(true)
        );
    }

    @Test
    void mirrorOnTheUpstreamHostStillReceivesTheCredentials() {
        final AtomicReference<Headers> seen = new AtomicReference<>();
        this.fetchThroughMirror(
            URI.create("https://index.private.example/simple/"),
            "https://index.private.example" + LINK,
            seen
        );
        MatcherAssert.assertThat(
            "a mirror link on the configured upstream host must still carry the credentials",
            seen.get().values(Authorization.NAME).isEmpty(), new IsEqual<>(false)
        );
    }

    /**
     * Serve an index whose only link points at {@code link}, then request the
     * package so the proxy dials the mirror; the headers of that dial are
     * captured into {@code seen}.
     */
    private void fetchThroughMirror(
        final URI upstream, final String link, final AtomicReference<Headers> seen
    ) {
        final Storage storage = new InMemoryStorage();
        final String html = String.format(
            "<html><body><a href=\"%s#sha256=abc\">pkg</a></body></html>", link
        );
        final ClientSlices clients = new RecordingClients(seen);
        final ProxySlice slice = new ProxySlice(
            clients,
            new BasicAuthenticator("bob", "12345"),
            new SliceSimple(ResponseBuilder.ok().htmlBody(html, StandardCharsets.UTF_8).build()),
            storage,
            new FromStorageCache(storage),
            Optional.empty(),
            "my-pypi-proxy",
            "pypi-proxy",
            NoopCooldownService.INSTANCE,
            new com.auto1.pantera.publishdate.RegistryBackedInspector(
                "pypi", com.auto1.pantera.publishdate.PublishDateRegistries.instance()
            ),
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            ),
            java.time.Duration.ofHours(12),
            upstream
        );
        final Headers caller = Headers.from(new Authorization.Basic("client", "pw"));
        slice.response(
            new RequestLine(RqMethod.GET, "/my-pypi-proxy/requests/"), caller, Content.EMPTY
        ).toCompletableFuture().join();
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/my-pypi-proxy" + LINK), caller, Content.EMPTY
        ).toCompletableFuture().join();
        response.body().asBytesFuture().join();
    }

    /**
     * Client slices that record the headers of the mirror dial.
     */
    private static final class RecordingClients implements ClientSlices {

        private final AtomicReference<Headers> seen;

        RecordingClients(final AtomicReference<Headers> seen) {
            this.seen = seen;
        }

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

        private Slice slice() {
            return (line, headers, body) -> {
                this.seen.set(headers);
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .body("package".getBytes(StandardCharsets.UTF_8))
                        .build()
                );
            };
        }
    }
}
