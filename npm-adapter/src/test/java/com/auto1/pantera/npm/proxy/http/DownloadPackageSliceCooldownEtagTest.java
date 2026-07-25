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
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.cooldown.metadata.CooldownMetadataService;
import com.auto1.pantera.cooldown.metadata.MetadataFilter;
import com.auto1.pantera.cooldown.metadata.MetadataParser;
import com.auto1.pantera.cooldown.metadata.MetadataRewriter;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.npm.RandomFreePort;
import com.auto1.pantera.npm.proxy.NpmProxy;
import com.auto1.pantera.vertx.VertxSliceServer;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.core.buffer.Buffer;
import io.vertx.reactivex.ext.web.client.HttpResponse;
import io.vertx.reactivex.ext.web.client.WebClient;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Regression coverage for the cooldown ETag: when cooldown filtering is
 * active the client-facing ETag MUST be a function of the filtered bytes we
 * actually serve, not of the immutable upstream content hash. Otherwise an
 * npm client that cached the filtered packument keeps getting a
 * {@code 304 Not Modified} on revalidation and never sees a version that just
 * aged out of (or was released from) cooldown — the "clear your npm cache"
 * caveat this fix removes.
 */
final class DownloadPackageSliceCooldownEtagTest {

    private static final Vertx VERTX = Vertx.vertx();

    /**
     * Package the fixture packument lives under.
     */
    private static final String PKG = "@hello/simple-npm-project";

    /**
     * Server port.
     */
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        this.port = new RandomFreePort().value();
    }

    @AfterAll
    static void tearDown() {
        DownloadPackageSliceCooldownEtagTest.VERTX.close();
    }

    @Test
    void unblockChangesEtagInsteadOfServingStale304() {
        final Storage storage = new InMemoryStorage();
        this.saveFilesToStorage(storage);
        // Filtered output while a version is under cooldown: latest 1.0.0 only.
        final AtomicReference<byte[]> filtered = new AtomicReference<>(
            packument("1.0.0", "\"1.0.0\":{}")
        );
        try (
            VertxSliceServer server = new VertxSliceServer(
                DownloadPackageSliceCooldownEtagTest.VERTX,
                new DownloadPackageSlice(
                    new NpmProxy(
                        storage,
                        new SliceSimple(ResponseBuilder.notFound().build())
                    ),
                    new PackagePath("ctx"),
                    Optional.empty(),
                    new FixedFilterService(filtered),
                    "npm",
                    "npm-proxy"
                ),
                this.port
            )
        ) {
            server.start();
            final String url = String.format(
                "http://127.0.0.1:%d/ctx/%s", this.port, PKG
            );
            final WebClient client = WebClient.create(DownloadPackageSliceCooldownEtagTest.VERTX);

            // 1) First serve while blocked → 200 with an ETag over the filtered body.
            final HttpResponse<Buffer> first = client.getAbs(url).rxSend().blockingGet();
            MatcherAssert.assertThat(
                "First serve returns 200 OK",
                first.statusCode(),
                new IsEqual<>(RsStatus.OK.code())
            );
            final String blockedEtag = first.getHeader("ETag");
            MatcherAssert.assertThat(
                "Filtered response carries an ETag",
                blockedEtag != null && !blockedEtag.isEmpty(),
                new IsEqual<>(true)
            );

            // 2) Revalidate with the same ETag while content is unchanged → 304.
            //    Proves the ETag still enables cheap conditional GETs.
            final HttpResponse<Buffer> unchanged = client.getAbs(url)
                .putHeader("If-None-Match", blockedEtag)
                .rxSend().blockingGet();
            MatcherAssert.assertThat(
                "Unchanged filtered content revalidates to 304",
                unchanged.statusCode(),
                new IsEqual<>(RsStatus.NOT_MODIFIED.code())
            );

            // 3) Unblock: the filtered output now exposes 1.0.1 as latest.
            filtered.set(packument("1.0.1", "\"1.0.0\":{},\"1.0.1\":{}"));

            // 4) Client still presents the pre-unblock ETag. It MUST get 200
            //    with the new body — not a stale 304 — and a different ETag.
            final HttpResponse<Buffer> afterUnblock = client.getAbs(url)
                .putHeader("If-None-Match", blockedEtag)
                .rxSend().blockingGet();
            MatcherAssert.assertThat(
                "Unblock busts the client cache: 200, not a stale 304",
                afterUnblock.statusCode(),
                new IsEqual<>(RsStatus.OK.code())
            );
            MatcherAssert.assertThat(
                "ETag changes when the filtered content changes",
                afterUnblock.getHeader("ETag").equals(blockedEtag),
                new IsEqual<>(false)
            );
        }
    }

    /**
     * Minimal but valid packument body for the fixture package.
     *
     * @param latest dist-tags.latest value
     * @param versionsBody comma-separated {@code "v":{}} entries for versions
     * @return packument JSON bytes
     */
    private static byte[] packument(final String latest, final String versionsBody) {
        return String.format(
            "{\"name\":\"%s\",\"dist-tags\":{\"latest\":\"%s\"},\"versions\":{%s}}",
            PKG, latest, versionsBody
        ).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Seed the upstream packument + refresh metadata the proxy needs to serve
     * the full-metadata path (matches {@code DownloadPackageSliceTest}).
     *
     * @param storage Backing storage
     */
    private void saveFilesToStorage(final Storage storage) {
        final String metajsonpath = PKG + "/meta.json";
        storage.save(
            new Key.From(metajsonpath),
            new Content.From(
                new TestResource(String.format("storage/%s", metajsonpath)).asBytes()
            )
        ).join();
        storage.save(
            new Key.From("@hello", "simple-npm-project", "meta.meta"),
            new Content.From(
                Json.createObjectBuilder()
                    .add("last-modified", "2020-05-13T16:30:30+01:00")
                    .add("last-refreshed", "2020-05-13T16:30:30+01:00")
                    .build()
                    .toString()
                    .getBytes(StandardCharsets.UTF_8)
            )
        ).join();
    }

    /**
     * Cooldown service stub returning a caller-controlled filtered body, so
     * the test can flip the filter output (block → unblock) between requests
     * without a real cooldown database. Skips the audit side-effect of the
     * default overload.
     */
    private static final class FixedFilterService implements CooldownMetadataService {

        private final AtomicReference<byte[]> body;

        FixedFilterService(final AtomicReference<byte[]> body) {
            this.body = body;
        }

        @Override
        public <T> CompletableFuture<byte[]> filterMetadata(
            final String repoType,
            final String repoName,
            final String packageName,
            final byte[] rawMetadata,
            final MetadataParser<T> parser,
            final MetadataFilter<T> filter,
            final MetadataRewriter<T> rewriter
        ) {
            return CompletableFuture.completedFuture(this.body.get());
        }

        @Override
        public <T> CompletableFuture<byte[]> filterMetadata(
            final String repoType,
            final String repoName,
            final String packageName,
            final byte[] rawMetadata,
            final MetadataParser<T> parser,
            final MetadataFilter<T> filter,
            final MetadataRewriter<T> rewriter,
            final AuditContext ctx,
            final String owner
        ) {
            return CompletableFuture.completedFuture(this.body.get());
        }

        @Override
        public void invalidate(final String repoType, final String repoName, final String packageName) {
            // no-op stub
        }

        @Override
        public void invalidateAll(final String repoType, final String repoName) {
            // no-op stub
        }

        @Override
        public void clearAll() {
            // no-op stub
        }

        @Override
        public String stats() {
            return "FixedFilterService";
        }
    }
}
