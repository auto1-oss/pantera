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
package com.auto1.pantera.composer.http.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import io.reactivex.Flowable;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A proxied dist whose upstream sends no Content-Length (chunked, as GitHub
 * zipballs are) must still be audited and indexed with its real size.
 *
 * @since 2.2.9
 */
final class ComposerProxyDistSizeTest {

    /**
     * Dist body.
     */
    private static final byte[] ZIP = "0123456789-zip-bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    @Timeout(20)
    void chunkedDistIsAuditedWithItsCommittedSize() throws Exception {
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(
            new Key.From("acme/widget.json"),
            new Content.From((
                "{\"packages\":{\"acme/widget\":{\"1.0.0\":{\"version\":\"1.0.0\","
                    + "\"dist\":{\"url\":\"https://upstream.example/w.zip\"}}}}}"
            ).getBytes(StandardCharsets.UTF_8))
        ).join();
        // Chunked: the body publisher has no known size.
        final Slice upstream = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok().body(
                new Content.From(Flowable.just(ByteBuffer.wrap(ZIP)))
            ).build()
        );
        // LogCapture attaches to the logger instance the code logs through.
        // A capture that registered its own logger configuration saw nothing
        // when an earlier test of this module had already captured the audit
        // logger: log4j keeps the configuration that is there.
        try (LogCapture capture = LogCapture.of("artifact.audit")) {
            final Response resp = new ProxyDownloadSlice(
                upstream, null, URI.create("https://upstream.example"), Optional.empty(),
                "php_proxy", "php-proxy", storage, NoopCooldownService.INSTANCE, new NoDates()
            ).response(
                new RequestLine(RqMethod.GET, "/dist/acme/widget/1.0.0.zip"),
                Headers.EMPTY, Content.EMPTY
            ).get(10, TimeUnit.SECONDS);
            resp.body().asBytesFuture().get(10, TimeUnit.SECONDS);
            Object size = null;
            while (size == null) {
                size = ComposerProxyDistSizeTest.accessSize(capture);
                Thread.sleep(5);
            }
            MatcherAssert.assertThat(
                String.valueOf(size), new IsEqual<>(String.valueOf(ZIP.length))
            );
        }
    }

    @Test
    void processorRecordsTheSizeOfTheCachedDist() {
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(
            new Key.From("dist", "acme", "widget", "1.0.0.zip"), new Content.From(ZIP)
        ).join();
        final Queue<ProxyArtifactEvent> packages = new ConcurrentLinkedQueue<>();
        packages.add(
            new ProxyArtifactEvent(new Key.From("acme/widget", "1.0.0"), "php_proxy", "alice")
        );
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final ComposerProxyPackageProcessor processor = new ComposerProxyPackageProcessor();
        processor.setStorage(storage);
        processor.setPackages(packages);
        processor.setEvents(events);
        processor.run();
        MatcherAssert.assertThat(events.poll().size(), new IsEqual<>((long) ZIP.length));
    }

    /**
     * Size of the dist in the record of its successful download.
     * @param capture Captured audit records
     * @return {@code package.size}, or null while the record is not written
     */
    private static Object accessSize(final LogCapture capture) {
        Object size = null;
        for (final java.util.Map<String, Object> event : capture.action("artifact_access")) {
            if ("success".equals(String.valueOf(event.get("event.outcome")))) {
                size = event.get("package.size");
                break;
            }
        }
        return size;
    }

    /**
     * Inspector with no release dates.
     */
    private static final class NoDates implements CooldownInspector {
        @Override
        public CompletableFuture<Optional<Instant>> releaseDate(
            final String artifact, final String version
        ) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<List<CooldownDependency>> dependencies(
            final String artifact, final String version
        ) {
            return CompletableFuture.completedFuture(List.of());
        }
    }
}
