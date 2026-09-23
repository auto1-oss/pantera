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
        final AuditCapture capture = AuditCapture.install();
        try {
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
                size = capture.accessSize();
                Thread.sleep(5);
            }
            MatcherAssert.assertThat(
                String.valueOf(size), new IsEqual<>(String.valueOf(ZIP.length))
            );
        } finally {
            capture.remove();
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
        processor.execute(null);
        MatcherAssert.assertThat(events.poll().size(), new IsEqual<>((long) ZIP.length));
    }

    /**
     * Captures {@code artifact.audit} records.
     */
    private static final class AuditCapture
        extends org.apache.logging.log4j.core.appender.AbstractAppender {

        private static final String NAME = "ComposerDistSizeAuditCapture";

        private final List<org.apache.logging.log4j.core.LogEvent> events =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        private AuditCapture() {
            super(NAME, null, null, true,
                org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
        }

        static AuditCapture install() {
            final AuditCapture capture = new AuditCapture();
            capture.start();
            final org.apache.logging.log4j.core.LoggerContext lc =
                (org.apache.logging.log4j.core.LoggerContext)
                    org.apache.logging.log4j.LogManager.getContext(false);
            final org.apache.logging.log4j.core.config.Configuration cfg = lc.getConfiguration();
            // This module has no log4j2 test config (root level ERROR), so
            // give the audit logger its own INFO config for the capture.
            final org.apache.logging.log4j.core.config.LoggerConfig audit =
                new org.apache.logging.log4j.core.config.LoggerConfig(
                    "artifact.audit", org.apache.logging.log4j.Level.INFO, false
                );
            audit.addAppender(capture, null, null);
            cfg.addLogger("artifact.audit", audit);
            lc.updateLoggers();
            return capture;
        }

        void remove() {
            final org.apache.logging.log4j.core.LoggerContext lc =
                (org.apache.logging.log4j.core.LoggerContext)
                    org.apache.logging.log4j.LogManager.getContext(false);
            final org.apache.logging.log4j.core.config.Configuration cfg = lc.getConfiguration();
            cfg.removeLogger("artifact.audit");
            this.stop();
            lc.updateLoggers();
        }

        Object accessSize() {
            synchronized (this.events) {
                for (final org.apache.logging.log4j.core.LogEvent event : this.events) {
                    if (event.getMessage()
                        instanceof org.apache.logging.log4j.message.MapMessage<?, ?> map
                        && "artifact_access".equals(String.valueOf(map.getData().get("event.action")))
                        && "success".equals(String.valueOf(map.getData().get("event.outcome")))) {
                        return map.getData().get("package.size");
                    }
                }
            }
            return null;
        }

        @Override
        public void append(final org.apache.logging.log4j.core.LogEvent event) {
            this.events.add(event.toImmutable());
        }
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
