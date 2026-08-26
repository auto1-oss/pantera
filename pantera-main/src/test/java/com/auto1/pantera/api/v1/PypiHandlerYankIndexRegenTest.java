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
package com.auto1.pantera.api.v1;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.SubStorage;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.pypi.http.IndexGenerator;
import com.auto1.pantera.pypi.meta.PypiSidecar;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxTestContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves {@link PypiHandler} yank/unyank regenerate the persisted
 * PEP 503 HTML / PEP 691 JSON package index instead of only flipping the
 * sidecar (WS4-pypi.3, 2.3.0).
 *
 * <p>The index files this test reads
 * ({@code .pypi/{package}/{package}.html}/{@code .json}) are the exact
 * bytes the pypi adapter's index-serving slice returns verbatim for
 * {@code /simple/<pkg>/} once they exist — so asserting on their content
 * after calling the real HTTP yank/unyank endpoints proves the served
 * index is truthful without needing to boot the separate
 * repository-serving router.</p>
 */
final class PypiHandlerYankIndexRegenTest extends AsyncApiTestBase {

    /**
     * Package name used across this test.
     */
    private static final String PKG = "hello";

    @Test
    void yankRegeneratesIndexAndUnyankReversesIt(
        final Vertx vertx, final VertxTestContext ctx, @TempDir final Path tmp
    ) throws Exception {
        final String repo = "pypi-yank-idx";
        final WebClient client = WebClient.create(vertx);
        this.createFsRepo(client, repo, tmp);
        final Storage scoped = new SubStorage(
            new Key.From(repo), new FileStorage(tmp)
        );
        // Seed two hosted versions exactly like a real upload would:
        // distribution file + sidecar (yanked:false) + persisted index —
        // WheelSlice.response() performs the same three steps.
        seedVersion(scoped, "1.0.0");
        seedVersion(scoped, "2.0.0");
        new IndexGenerator(scoped, new Key.From(PypiHandlerYankIndexRegenTest.PKG), "")
            .generate().join();

        MatcherAssert.assertThat(
            "pre-yank index must not carry data-yanked",
            readIndexHtml(scoped).contains("data-yanked"),
            new IsEqual<>(false)
        );

        final HttpResponse<Buffer> yank = client
            .post(this.port(), AsyncApiTestBase.HOST,
                "/api/v1/pypi/" + repo + "/" + PypiHandlerYankIndexRegenTest.PKG
                    + "/2.0.0/yank")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(new JsonObject().put("reason", "security issue"))
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "yank call must succeed", yank.statusCode(), new IsEqual<>(204)
        );

        // Served index reflects the yank WITHOUT any re-upload.
        final String htmlAfterYank = readIndexHtml(scoped);
        MatcherAssert.assertThat(
            "post-yank HTML index must carry data-yanked with the reason",
            htmlAfterYank.contains("data-yanked=\"security issue\""),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the non-yanked version must still be listed",
            htmlAfterYank.contains("hello-1.0.0"),
            new IsEqual<>(true)
        );
        final String jsonAfterYank = readIndexJson(scoped);
        MatcherAssert.assertThat(
            "post-yank JSON index must mark the file as yanked",
            jsonAfterYank.contains("\"yanked\":\"security issue\""),
            new IsEqual<>(true)
        );

        final HttpResponse<Buffer> unyank = client
            .post(this.port(), AsyncApiTestBase.HOST,
                "/api/v1/pypi/" + repo + "/" + PypiHandlerYankIndexRegenTest.PKG
                    + "/2.0.0/unyank")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send()
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "unyank call must succeed", unyank.statusCode(), new IsEqual<>(204)
        );

        MatcherAssert.assertThat(
            "post-unyank HTML index must no longer carry data-yanked",
            readIndexHtml(scoped).contains("data-yanked"),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "post-unyank JSON index must report yanked:false",
            readIndexJson(scoped).contains("\"yanked\":false"),
            new IsEqual<>(true)
        );
        ctx.completeNow();
    }

    /**
     * Yanking a version with no distribution files must not fabricate a
     * persisted (phantom) index for a package that was never uploaded.
     */
    @Test
    void yankOfNonexistentVersionDoesNotCreatePhantomIndex(
        final Vertx vertx, final VertxTestContext ctx, @TempDir final Path tmp
    ) throws Exception {
        final String repo = "pypi-yank-phantom";
        final WebClient client = WebClient.create(vertx);
        this.createFsRepo(client, repo, tmp);
        final Storage scoped = new SubStorage(
            new Key.From(repo), new FileStorage(tmp)
        );

        final HttpResponse<Buffer> yank = client
            .post(this.port(), AsyncApiTestBase.HOST,
                "/api/v1/pypi/" + repo + "/ghost/9.9.9/yank")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send()
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "yank of a nonexistent version stays a no-op success",
            yank.statusCode(), new IsEqual<>(204)
        );
        MatcherAssert.assertThat(
            "no phantom index must be persisted for an unpublished package",
            scoped.exists(new Key.From(".pypi", "ghost", "ghost.html")).join(),
            new IsEqual<>(false)
        );
        ctx.completeNow();
    }

    private void createFsRepo(
        final WebClient client, final String repo, final Path tmp
    ) throws Exception {
        final HttpResponse<Buffer> put = client
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/" + repo)
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(new JsonObject()
                .put("repo", new JsonObject()
                    .put("type", "pypi")
                    .put("storage", new JsonObject()
                        .put("type", "fs").put("path", tmp.toString()))))
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "repo creation must succeed", put.statusCode(), new IsEqual<>(200)
        );
    }

    private static void seedVersion(final Storage scoped, final String version) {
        final Key file = new Key.From(
            PypiHandlerYankIndexRegenTest.PKG, version,
            PypiHandlerYankIndexRegenTest.PKG + "-" + version + ".tar.gz"
        );
        scoped.save(file, new Content.From(("dist-" + version).getBytes(StandardCharsets.UTF_8)))
            .join();
        PypiSidecar.write(
            scoped, file, null, Instant.now().truncatedTo(ChronoUnit.MICROS)
        ).join();
    }

    private static String readIndexHtml(final Storage scoped) {
        return readBody(
            scoped,
            new Key.From(
                ".pypi", PypiHandlerYankIndexRegenTest.PKG,
                PypiHandlerYankIndexRegenTest.PKG + ".html"
            )
        );
    }

    private static String readIndexJson(final Storage scoped) {
        return readBody(
            scoped,
            new Key.From(
                ".pypi", PypiHandlerYankIndexRegenTest.PKG,
                PypiHandlerYankIndexRegenTest.PKG + ".json"
            )
        );
    }

    private static String readBody(final Storage scoped, final Key key) {
        return new String(
            scoped.value(key).thenCompose(Content::asBytesFuture).join(),
            StandardCharsets.UTF_8
        );
    }
}
