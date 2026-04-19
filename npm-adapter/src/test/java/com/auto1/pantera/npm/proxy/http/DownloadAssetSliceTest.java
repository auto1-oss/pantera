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
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.npm.TgzArchive;
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
            this.performRequestAndChecks(pathprefix, server);
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
            this.performRequestAndChecks(pathprefix, server);
        }
    }

    private void performRequestAndChecks(final String pathprefix, final VertxSliceServer server) {
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
        MatcherAssert.assertThat(
            "tgz was added to packages queue",
            pair.artifactKey().string(),
            new IsEqual<>("@hello/simple-npm-project/-/@hello/simple-npm-project-1.0.1.tgz")
        );
        MatcherAssert.assertThat(
            "Queue is empty after poll() (only one element was added)", this.packages.isEmpty()
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
