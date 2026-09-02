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
package com.auto1.pantera.http.client.egress;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression tests for {@link EgressFilteringResolver}: the Jetty
 * client resolves every destination (including each redirect hop) through
 * this resolver, so a hostname that RESOLVES into a denied range — the
 * DNS-rebinding shape of the SSRF — is refused after resolution, not just
 * by name. Uses a stub delegate resolver; no DNS.
 *
 * @since 2.2.9
 */
final class EgressFilteringResolverTest {

    @Test
    void hostResolvingToMetadataAddressIsRefused() throws Exception {
        final EgressFilteringResolver resolver = new EgressFilteringResolver(
            EgressPolicy.defaults(),
            stub(List.of(new InetSocketAddress(InetAddress.getByName("169.254.169.254"), 80)))
        );
        final CompletableFuture<List<InetSocketAddress>> out = new CompletableFuture<>();
        resolver.resolve("evil.example", 80, Map.of(), promise(out));
        MatcherAssert.assertThat(
            "a host resolving only to the metadata address must fail to resolve",
            out.isCompletedExceptionally(), new IsEqual<>(true)
        );
    }

    @Test
    void deniedAddressesAreDroppedButAllowedOnesKept() throws Exception {
        final InetSocketAddress good = new InetSocketAddress(InetAddress.getByName("93.184.216.34"), 443);
        final InetSocketAddress bad = new InetSocketAddress(InetAddress.getByName("169.254.169.254"), 443);
        final EgressFilteringResolver resolver = new EgressFilteringResolver(
            EgressPolicy.defaults(), stub(List.of(bad, good))
        );
        final CompletableFuture<List<InetSocketAddress>> out = new CompletableFuture<>();
        resolver.resolve("mixed.example", 443, Map.of(), promise(out));
        MatcherAssert.assertThat(
            "the denied address must be dropped and the public one kept",
            out.get(5, TimeUnit.SECONDS), new IsEqual<>(List.of(good))
        );
    }

    @Test
    void metadataHostnameIsRefusedBeforeDelegating() {
        final boolean[] delegated = {false};
        final EgressFilteringResolver resolver = new EgressFilteringResolver(
            EgressPolicy.defaults(),
            (host, port, ctx, promise) -> {
                delegated[0] = true;
                promise.succeeded(List.of());
            }
        );
        final CompletableFuture<List<InetSocketAddress>> out = new CompletableFuture<>();
        resolver.resolve("metadata.google.internal", 80, Map.of(), promise(out));
        MatcherAssert.assertThat(
            "the metadata hostname must be refused by name",
            out.isCompletedExceptionally(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "a name-denied host must never reach DNS",
            delegated[0], new IsEqual<>(false)
        );
    }

    private static SocketAddressResolver stub(final List<InetSocketAddress> answer) {
        return (host, port, ctx, promise) -> promise.succeeded(answer);
    }

    private static Promise<List<InetSocketAddress>> promise(
        final CompletableFuture<List<InetSocketAddress>> out
    ) {
        return new Promise<>() {
            @Override
            public void succeeded(final List<InetSocketAddress> result) {
                out.complete(result);
            }

            @Override
            public void failed(final Throwable failure) {
                out.completeExceptionally(failure);
            }
        };
    }
}
