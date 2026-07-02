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
import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownReason;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.npm.TgzArchive;
import com.auto1.pantera.npm.cooldown.NpmCooldownResponseFactory;
import com.auto1.pantera.npm.misc.NextSafeAvailablePort;
import com.auto1.pantera.npm.proxy.NpmProxy;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import com.auto1.pantera.vertx.VertxSliceServer;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.ext.web.client.WebClient;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.json.Json;
import javax.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test cases for {@link DownloadAssetSlice}.
 */
final class DownloadAssetSliceTest {

    /**
     * Repository name.
     */
    private static final String RNAME = "my-npm";

    private static final Vertx VERTX = Vertx.vertx();

    /**
     * TgzArchive path.
     */
    private static final String TGZ =
        "@hello/simple-npm-project/-/@hello/simple-npm-project-1.0.1.tgz";

    /**
     * Server port.
     */
    private int port;

    /**
     * Queue with packages and owner names.
     */
    private Queue<ProxyArtifactEvent> packages;

    @BeforeEach
    void setUp() {
        this.port = new NextSafeAvailablePort().value();
        this.packages = new LinkedList<>();
    }

    @AfterAll
    static void tearDown() {
        DownloadAssetSliceTest.VERTX.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/ctx"})
    void obtainsFromStorage(final String pathprefix) {
        final Storage storage = new InMemoryStorage();
        this.saveFilesToStorage(storage);
        final AssetPath path = new AssetPath(pathprefix.replaceFirst("/", ""));
        try (
            VertxSliceServer server = new VertxSliceServer(
                DownloadAssetSliceTest.VERTX,
                new DownloadAssetSlice(
                    new NpmProxy(
                        storage,
                        new SliceSimple(ResponseBuilder.notFound().build())
                    ),
                    path, Optional.of(this.packages),
                    DownloadAssetSliceTest.RNAME,
                    "npm-proxy",
                    NoopCooldownService.INSTANCE,
                    noopInspector()
                ),
                this.port
            )
        ) {
            // Cache hit: the artifact was already published to the DB the
            // first time it was cached — this is a read, not a publish. No
            // ProxyArtifactEvent should be enqueued for it.
            this.performRequestAndChecks(pathprefix, server, false);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/ctx"})
    void obtainsFromRemote(final String pathprefix) {
        final AssetPath path = new AssetPath(pathprefix.replaceFirst("/", ""));
        try (
            VertxSliceServer server = new VertxSliceServer(
                DownloadAssetSliceTest.VERTX,
                new DownloadAssetSlice(
                    new NpmProxy(
                        new InMemoryStorage(),
                        new SliceSimple(
                            ResponseBuilder.ok()
                                .header(ContentType.mime("tgz"))
                                .body(new TestResource(
                                    String.format("storage/%s", DownloadAssetSliceTest.TGZ)
                                ).asBytes())
                                .build()
                        )
                    ),
                    path,
                    Optional.of(this.packages),
                    DownloadAssetSliceTest.RNAME,
                    "npm-proxy",
                    NoopCooldownService.INSTANCE,
                    noopInspector()
                ),
                this.port
            )
        ) {
            // Genuine cache miss + successful upstream fetch: the only
            // case that should enqueue a ProxyArtifactEvent (publish).
            this.performRequestAndChecks(pathprefix, server, true);
        }
    }

    /**
     * Regression test for the bug this fix closed: {@code NpmProxy.getAsset}
     * conflates the storage-existence check with the upstream fetch, so
     * before {@code DownloadAssetSlice.checkCacheFirst} was rewired onto
     * {@code NpmProxy#hasAssetInStorageAsync}, a cache miss for a genuinely
     * cooldown-blocked version would already have fetched-and-saved the
     * artifact (via the combined check-then-fetch call) BEFORE cooldown was
     * ever evaluated — cooldown could reject the response but not prevent
     * the fetch or the cache write. Asserts all three: 403 returned, the
     * upstream mock is never invoked at all, and storage stays empty.
     */
    @Test
    void blocksCooldownedFreshDownloadWithoutFetchingOrCaching() {
        CooldownResponseRegistry.instance().register("npm-proxy", new NpmCooldownResponseFactory());
        final Storage storage = new InMemoryStorage();
        final AssetPath path = new AssetPath("");
        final AtomicBoolean upstreamCalled = new AtomicBoolean(false);
        final CooldownBlock block = new CooldownBlock(
            "npm-proxy", DownloadAssetSliceTest.RNAME, "@hello/simple-npm-project", "1.0.1",
            CooldownReason.FRESH_RELEASE,
            Instant.now().minusSeconds(60),
            Instant.now().plusSeconds(3600),
            List.of()
        );
        final CooldownService blockingService = new CooldownService() {
            @Override
            public CompletableFuture<CooldownResult> evaluate(
                final CooldownRequest request, final CooldownInspector inspector
            ) {
                return CompletableFuture.completedFuture(CooldownResult.blocked(block));
            }
            @Override
            public CompletableFuture<Void> unblock(
                final String rt, final String rn, final String art,
                final String ver, final String actor
            ) {
                return CompletableFuture.completedFuture(null);
            }
            @Override
            public CompletableFuture<Void> unblockAll(
                final String rt, final String rn, final String actor
            ) {
                return CompletableFuture.completedFuture(null);
            }
            @Override
            public CompletableFuture<List<CooldownBlock>> activeBlocks(
                final String rt, final String rn
            ) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
        try (
            VertxSliceServer server = new VertxSliceServer(
                DownloadAssetSliceTest.VERTX,
                new DownloadAssetSlice(
                    new NpmProxy(
                        storage,
                        (line, headers, body) -> {
                            upstreamCalled.set(true);
                            return CompletableFuture.completedFuture(
                                ResponseBuilder.ok()
                                    .header(ContentType.mime("tgz"))
                                    .body(new TestResource(
                                        String.format("storage/%s", DownloadAssetSliceTest.TGZ)
                                    ).asBytes())
                                    .build()
                            );
                        }
                    ),
                    path,
                    Optional.of(this.packages),
                    DownloadAssetSliceTest.RNAME,
                    "npm-proxy",
                    blockingService,
                    noopInspector()
                ),
                this.port
            )
        ) {
            server.start();
            final String url = String.format(
                "http://127.0.0.1:%d/%s", this.port, DownloadAssetSliceTest.TGZ
            );
            final WebClient client = WebClient.create(DownloadAssetSliceTest.VERTX);
            final int status = client.getAbs(url).rxSend().blockingGet().statusCode();
            MatcherAssert.assertThat(
                "Blocked fresh download must return 403", status, new IsEqual<>(403)
            );
            MatcherAssert.assertThat(
                "Upstream must never be called — cooldown gate runs before any fetch",
                upstreamCalled.get(), new IsEqual<>(false)
            );
            MatcherAssert.assertThat(
                "Storage must remain empty — blocked artifact must not be cached",
                storage.exists(new Key.From(DownloadAssetSliceTest.TGZ)).join(),
                new IsEqual<>(false)
            );
            MatcherAssert.assertThat(
                "Nothing enqueued for a blocked request", this.packages.isEmpty(), new IsEqual<>(true)
            );
        }
    }

    private void performRequestAndChecks(
        final String pathprefix, final VertxSliceServer server, final boolean expectEnqueue
    ) {
        server.start();
        final String url = String.format(
            "http://127.0.0.1:%d%s/%s", this.port, pathprefix, DownloadAssetSliceTest.TGZ
        );
        final WebClient client = WebClient.create(DownloadAssetSliceTest.VERTX);
        final String tgzcontent = client.getAbs(url)
            .rxSend().blockingGet()
            .bodyAsString(StandardCharsets.ISO_8859_1.name());
        final JsonObject json = new TgzArchive(tgzcontent, false).packageJson();
        MatcherAssert.assertThat(
            "Name is parsed properly from package.json",
            json.getJsonString("name").getString(),
            new IsEqual<>("@hello/simple-npm-project")
        );
        MatcherAssert.assertThat(
            "Version is parsed properly from package.json",
            json.getJsonString("version").getString(),
            new IsEqual<>("1.0.1")
        );
        final ProxyArtifactEvent pair = this.packages.poll();
        if (expectEnqueue) {
            MatcherAssert.assertThat(
                "tgz was added to packages queue",
                pair.artifactKey().string(),
                new IsEqual<>("@hello/simple-npm-project/-/@hello/simple-npm-project-1.0.1.tgz")
            );
        } else {
            MatcherAssert.assertThat(
                "cache hit is a read, not a publish — nothing enqueued", pair, new IsEqual<>(null)
            );
        }
        MatcherAssert.assertThat(
            "Queue is empty (either never populated, or drained by the single poll() above)",
            this.packages.isEmpty()
        );
    }

    /**
     * Save files to storage from test resources.
     * @param storage Storage
     */
    private void saveFilesToStorage(final Storage storage) {
        storage.save(
            new Key.From(DownloadAssetSliceTest.TGZ),
            new Content.From(
                new TestResource(
                    String.format("storage/%s", DownloadAssetSliceTest.TGZ)
                ).asBytes()
            )
        ).join();
        storage.save(
            new Key.From(
                String.format("%s.meta", DownloadAssetSliceTest.TGZ)
            ),
            new Content.From(
                Json.createObjectBuilder()
                    .add("last-modified", "2020-05-13T16:30:30+01:00")
                    .build()
                    .toString()
                    .getBytes()
            )
        ).join();
    }

    private static CooldownInspector noopInspector() {
        return new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact, final String version) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
    }
}
