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

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.rx.RxStorageWrapper;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.npm.proxy.model.NpmPackage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link NpmProxy#revalidate(String)}: the admin "refresh package" entry
 * point revalidates a FRESH (within-TTL) cached packument right away through
 * the conditional-refresh path, firing the packument-write hook on change.
 *
 * @since 2.2.9
 */
final class NpmProxyRevalidateTest {

    /**
     * Upstream Last-Modified value.
     */
    private static final String LAST_MODIFIED = "Tue, 24 Mar 2020 12:15:16 GMT";

    @Test
    @Timeout(30)
    void revalidatesAFreshPackumentAndFiresTheWriteHook() throws Exception {
        final String name = "asdas";
        final byte[] packument = new TestResource("json/original.json").asBytes();
        final AtomicInteger calls = new AtomicInteger();
        final Slice upstream = (line, headers, body) -> {
            calls.incrementAndGet();
            return ResponseBuilder.ok()
                .header("Last-Modified", LAST_MODIFIED)
                .header("ETag", "\"new-etag\"")
                .body(packument)
                .completedFuture();
        };
        final Storage storage = new InMemoryStorage();
        final RxNpmProxyStorage prep = new RxNpmProxyStorage(new RxStorageWrapper(storage));
        prep.save(new NpmPackage(
            name, new String(packument, StandardCharsets.UTF_8),
            new NpmPackage.Metadata(LAST_MODIFIED, OffsetDateTime.now())
        )).blockingAwait();
        prep.saveMetadataOnly(
            name,
            new NpmPackage.Metadata(LAST_MODIFIED, OffsetDateTime.now(), null, null, "\"old-etag\"")
        ).blockingAwait();
        final AtomicReference<String> hooked = new AtomicReference<>();
        final NpmProxy npm = new NpmProxy(
            storage, upstream, Duration.ofHours(12), null, hooked::set, null
        );
        final String outcome = npm.revalidate(name).get(20, TimeUnit.SECONDS);
        MatcherAssert.assertThat("outcome", outcome, new IsEqual<>("revalidated"));
        MatcherAssert.assertThat(
            "the fresh packument was revalidated against the upstream once",
            calls.get(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "a changed packument fires the envelope-invalidation hook",
            hooked.get(), new IsEqual<>(name)
        );
    }

    @Test
    @Timeout(30)
    void reportsNotFoundWhenNeitherCachedNorUpstream() throws Exception {
        final Slice upstream = (line, headers, body) ->
            ResponseBuilder.notFound().completedFuture();
        final NpmProxy npm = new NpmProxy(
            new InMemoryStorage(), upstream, Duration.ofHours(12), null, null, null
        );
        MatcherAssert.assertThat(
            npm.revalidate("missing-pkg").get(20, TimeUnit.SECONDS),
            new IsEqual<>("not_found")
        );
    }
}
