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
package com.auto1.pantera.npm.proxy.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.cooldown.metadata.NoopCooldownMetadataService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.npm.proxy.NpmProxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * A registry API path ({@code /-/...}) is not a package. Until 2.2.13 the
 * packument route took {@code /-/npm/v1/attestations/<name>@<version>} and
 * {@code /-/npm/v1/keys} for a package called
 * {@code -/npm/v1/attestations/...}: the upstream answer was stored as a
 * packument and the packument-write hook invalidated cooldown-filtered
 * metadata for that "package".
 *
 * @since 2.2.14
 */
final class NpmProxySliceRegistryApiTest {

    /**
     * An attestation bundle path as {@code npm audit signatures} asks for it.
     */
    private static final String ATTESTATIONS =
        "/-/npm/v1/attestations/@oxfmt/binding-android-arm64@0.65.0";

    @Test
    void attestationsAreForwardedAndNeverStoredAsAPackument() {
        final Storage storage = new InMemoryStorage();
        final List<String> hooked = new CopyOnWriteArrayList<>();
        final byte[] bundle = "{\"attestations\":[]}".getBytes(StandardCharsets.UTF_8);
        final Slice remote = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok().jsonBody(new String(bundle, StandardCharsets.UTF_8)).build()
        );
        final Response response = NpmProxySliceRegistryApiTest.slice(storage, remote, hooked)
            .response(
                new RequestLine(RqMethod.GET, NpmProxySliceRegistryApiTest.ATTESTATIONS),
                Headers.EMPTY, Content.EMPTY
            ).join();
        MatcherAssert.assertThat(
            "the upstream answer is relayed as it is",
            response.body().asBytesFuture().join(), new IsEqual<>(bundle)
        );
        MatcherAssert.assertThat(
            "nothing is written to the packument store",
            storage.list(Key.ROOT).join().isEmpty(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the packument-write hook is not fired for an API path",
            hooked, new IsEqual<>(List.of())
        );
    }

    @Test
    void signingKeysAreForwardedAndNeverStoredAsAPackument() {
        final Storage storage = new InMemoryStorage();
        final List<String> hooked = new CopyOnWriteArrayList<>();
        final Slice remote = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok().jsonBody("{\"keys\":[]}").build()
        );
        final Response response = NpmProxySliceRegistryApiTest.slice(storage, remote, hooked)
            .response(
                new RequestLine(RqMethod.GET, "/-/npm/v1/keys"), Headers.EMPTY, Content.EMPTY
            ).join();
        MatcherAssert.assertThat(
            "the upstream keys are relayed",
            new String(response.body().asBytesFuture().join(), StandardCharsets.UTF_8),
            new IsEqual<>("{\"keys\":[]}")
        );
        MatcherAssert.assertThat(
            "nothing is stored and no hook is fired",
            storage.list(Key.ROOT).join().isEmpty() && hooked.isEmpty(), new IsEqual<>(true)
        );
    }

    @Test
    void anotherRegistryApiPathIsNotAPackageAndIsNotSentUpstream() {
        final List<String> asked = new CopyOnWriteArrayList<>();
        final Slice remote = (line, headers, body) -> {
            asked.add(line.uri().getPath());
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        };
        final Response response = NpmProxySliceRegistryApiTest
            .slice(new InMemoryStorage(), remote, new CopyOnWriteArrayList<>())
            .response(
                new RequestLine(RqMethod.GET, "/-/whoami"), Headers.EMPTY, Content.EMPTY
            ).join();
        MatcherAssert.assertThat(
            "an API path without a route is not found",
            response.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "and is never looked up as a package upstream",
            asked, new IsEqual<>(List.of())
        );
    }

    /**
     * Proxy slice over the given storage and upstream.
     *
     * @param storage Packument store
     * @param remote Upstream
     * @param hooked Receives every packument-write hook call
     * @return Slice
     */
    private static NpmProxySlice slice(
        final Storage storage, final Slice remote, final List<String> hooked
    ) {
        return new NpmProxySlice(
            "",
            new NpmProxy(storage, remote, Duration.ofHours(12), null, hooked::add, null),
            Optional.empty(),
            "npm-proxy",
            "npm",
            NoopCooldownService.INSTANCE,
            NoopCooldownMetadataService.INSTANCE,
            remote
        );
    }
}
