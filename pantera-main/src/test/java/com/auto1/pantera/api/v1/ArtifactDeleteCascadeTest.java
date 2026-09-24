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

import com.auto1.pantera.index.ArtifactDocument;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.index.SearchResult;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxTestContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The REST artifact/package delete keeps the search index and the format
 * metadata consistent with storage.
 *
 * @since 2.2.9
 */
final class ArtifactDeleteCascadeTest extends AsyncApiTestBase {

    /**
     * Index rows ({@code repo|path}) shared with the deployed verticle.
     */
    private static final Set<String> ROWS = ConcurrentHashMap.newKeySet();

    @Override
    protected ArtifactIndex testIndex() {
        return new RowsIndex();
    }

    /**
     * B21 follow-up: a path whose files are already gone from storage (the
     * index went stale through an earlier bug) must still lose its index
     * rows, or search keeps returning an artifact nobody can download.
     */
    @Test
    void deletingAMissingFileRemovesItsIndexRows(@TempDir final Path root,
        final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final String repo = this.repo(vertx, root, "file");
        ROWS.add(repo + "|lib/a-1.0.bin");
        ROWS.add(repo + "|lib/b-1.0.bin");
        final HttpResponse<Buffer> del = this.delete(
            vertx, repo, "artifacts", "lib/a-1.0.bin"
        );
        Assertions.assertEquals(204, del.statusCode(), "the stale rows were deleted");
        Assertions.assertFalse(
            ROWS.contains(repo + "|lib/a-1.0.bin"), "the stale row must be removed"
        );
        Assertions.assertTrue(
            ROWS.contains(repo + "|lib/b-1.0.bin"), "the other row must stay"
        );
        ctx.completeNow();
    }

    @Test
    void deletingAMissingFolderRemovesTheRowsUnderIt(@TempDir final Path root,
        final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final String repo = this.repo(vertx, root, "file");
        ROWS.add(repo + "|lib/1.0/a.bin");
        ROWS.add(repo + "|lib/1.0/b.bin");
        ROWS.add(repo + "|lib/2.0/a.bin");
        final HttpResponse<Buffer> del = this.delete(
            vertx, repo, "packages", "lib/1.0"
        );
        Assertions.assertEquals(204, del.statusCode(), "the stale rows were deleted");
        Assertions.assertFalse(
            ROWS.contains(repo + "|lib/1.0/a.bin") || ROWS.contains(repo + "|lib/1.0/b.bin"),
            "the rows under the folder must be removed"
        );
        Assertions.assertTrue(
            ROWS.contains(repo + "|lib/2.0/a.bin"), "the sibling folder's row must stay"
        );
        ctx.completeNow();
    }

    @Test
    void deletingAPathNeitherStoredNorIndexedAnswers404(@TempDir final Path root,
        final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final String repo = this.repo(vertx, root, "file");
        Assertions.assertEquals(
            404,
            this.delete(vertx, repo, "artifacts", "nothing/here.bin")
                .statusCode(),
            "artifact delete of nothing"
        );
        Assertions.assertEquals(
            404,
            this.delete(vertx, repo, "packages", "nothing")
                .statusCode(),
            "package delete of nothing"
        );
        ctx.completeNow();
    }

    /**
     * Deleting a conda package must remove it from its subdir's
     * repodata.json, or conda resolves it and fails downloading it.
     */
    @Test
    void deletingACondaPackageRemovesItFromRepodata(@TempDir final Path root,
        final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final String repo = this.repo(vertx, root, "conda");
        final Path noarch = root.resolve(repo).resolve("noarch");
        Files.createDirectories(noarch);
        Files.write(noarch.resolve("qa-1.0-0.tar.bz2"), new byte[] {1});
        Files.write(noarch.resolve("qa-2.0-0.tar.bz2"), new byte[] {1});
        Files.writeString(
            noarch.resolve("repodata.json"),
            "{\"info\":{\"subdir\":\"noarch\"},\"packages\":{"
                + "\"qa-1.0-0.tar.bz2\":{\"name\":\"qa\",\"version\":\"1.0\"},"
                + "\"qa-2.0-0.tar.bz2\":{\"name\":\"qa\",\"version\":\"2.0\"}"
                + "},\"packages.conda\":{}}"
        );
        final HttpResponse<Buffer> del = this.delete(
            vertx, repo, "artifacts", "noarch/qa-2.0-0.tar.bz2"
        );
        Assertions.assertEquals(204, del.statusCode(), "delete must succeed");
        final JsonObject repodata = new JsonObject(
            Files.readString(noarch.resolve("repodata.json"))
        );
        Assertions.assertEquals(
            Set.of("qa-1.0-0.tar.bz2"),
            repodata.getJsonObject("packages").fieldNames(),
            "only the remaining package is listed"
        );
        ctx.completeNow();
    }

    /**
     * Deleting a gem must regenerate the specs indexes and remove its
     * quick spec, or bundler resolves it and fails downloading it.
     */
    @Test
    void deletingAGemRebuildsTheIndex(@TempDir final Path root,
        final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final String repo = this.repo(vertx, root, "gem");
        final Path base = root.resolve(repo);
        final byte[] stale = "stale-index-listing-qa-1.0.0".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(base.resolve("gems"));
        Files.createDirectories(base.resolve("quick").resolve("Marshal.4.8"));
        Files.write(base.resolve("gems").resolve("qa-1.0.0.gem"), new byte[] {1});
        Files.write(base.resolve("specs.4.8"), stale);
        Files.write(base.resolve("latest_specs.4.8"), stale);
        Files.write(
            base.resolve("quick").resolve("Marshal.4.8").resolve("qa-1.0.0.gemspec.rz"), stale
        );
        final HttpResponse<Buffer> del = this.delete(
            vertx, repo, "artifacts", "gems/qa-1.0.0.gem"
        );
        Assertions.assertEquals(204, del.statusCode(), "delete must succeed");
        Assertions.assertFalse(
            new String(Files.readAllBytes(base.resolve("specs.4.8")), StandardCharsets.UTF_8)
                .contains("qa"),
            "specs.4.8 must no longer list the deleted gem"
        );
        Assertions.assertFalse(
            new String(
                Files.readAllBytes(base.resolve("latest_specs.4.8")), StandardCharsets.UTF_8
            ).contains("qa"),
            "latest_specs.4.8 must no longer list the deleted gem"
        );
        Assertions.assertTrue(
            Files.exists(base.resolve("specs.4.8.gz")), "the gzipped index is regenerated"
        );
        Assertions.assertFalse(
            Files.exists(
                base.resolve("quick").resolve("Marshal.4.8").resolve("qa-1.0.0.gemspec.rz")
            ),
            "the deleted gem's quick spec must be removed"
        );
        ctx.completeNow();
    }

    @Test
    void deletingAnNpmTarballUnpublishesItsVersion(@TempDir final Path root,
        final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final String repo = this.repo(vertx, root, "npm");
        final Path pkg = root.resolve(repo).resolve("qa-pkg");
        Files.createDirectories(pkg.resolve(".versions"));
        Files.createDirectories(pkg.resolve("-"));
        for (final String ver : new String[] {"1.0.0", "2.0.0"}) {
            Files.writeString(
                pkg.resolve(".versions").resolve(ver + ".json"),
                "{\"name\":\"qa-pkg\",\"version\":\"" + ver + "\"}"
            );
            Files.write(pkg.resolve("-").resolve("qa-pkg-" + ver + ".tgz"), new byte[] {1});
        }
        Assertions.assertEquals(
            204,
            this.delete(vertx, repo, "artifacts", "qa-pkg/-/qa-pkg-2.0.0.tgz").statusCode(),
            "delete must succeed"
        );
        Assertions.assertFalse(
            Files.exists(pkg.resolve(".versions").resolve("2.0.0.json")),
            "the deleted version must be unpublished"
        );
        Assertions.assertTrue(
            Files.exists(pkg.resolve(".versions").resolve("1.0.0.json")),
            "the other version must stay"
        );
        ctx.completeNow();
    }

    @Test
    void deletingAHelmChartRemovesItFromTheIndex(@TempDir final Path root,
        final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final String repo = this.repo(vertx, root, "helm");
        final Path base = root.resolve(repo);
        Files.createDirectories(base.resolve("qa"));
        Files.write(base.resolve("qa").resolve("qa-1.0.0.tgz"), new byte[] {1});
        Files.write(base.resolve("qa").resolve("qa-2.0.0.tgz"), new byte[] {1});
        Files.writeString(
            base.resolve("index.yaml"),
            String.join(
                "\n",
                "apiVersion: v1",
                "entries:",
                "  qa:",
                "  - {name: qa, version: 1.0.0, urls: [qa/qa-1.0.0.tgz]}",
                "  - {name: qa, version: 2.0.0, urls: [qa/qa-2.0.0.tgz]}",
                ""
            )
        );
        Assertions.assertEquals(
            204,
            this.delete(vertx, repo, "artifacts", "qa/qa-2.0.0.tgz").statusCode(),
            "delete must succeed"
        );
        final String index = Files.readString(base.resolve("index.yaml"));
        Assertions.assertFalse(index.contains("2.0.0"), "the deleted version must go: " + index);
        Assertions.assertTrue(index.contains("1.0.0"), "the other version must stay: " + index);
        ctx.completeNow();
    }

    @Test
    void deletingANugetVersionRemovesItFromTheVersionList(@TempDir final Path root,
        final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final String repo = this.repo(vertx, root, "nuget");
        final Path pkg = root.resolve(repo).resolve("qa.pkg");
        for (final String ver : new String[] {"1.0.0", "2.0.0"}) {
            Files.createDirectories(pkg.resolve(ver));
            Files.write(pkg.resolve(ver).resolve("qa.pkg." + ver + ".nupkg"), new byte[] {1});
            Files.write(pkg.resolve(ver).resolve("qa.pkg.nuspec"), new byte[] {1});
        }
        Files.writeString(pkg.resolve("index.json"), "{\"versions\":[\"1.0.0\",\"2.0.0\"]}");
        Assertions.assertEquals(
            204,
            this.delete(vertx, repo, "packages", "qa.pkg/2.0.0").statusCode(),
            "delete must succeed"
        );
        Assertions.assertEquals(
            "{\"versions\":[\"1.0.0\"]}", Files.readString(pkg.resolve("index.json")),
            "only the remaining version is listed"
        );
        ctx.completeNow();
    }

    @Test
    void deletingAGoVersionRemovesItFromTheList(@TempDir final Path root,
        final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final String repo = this.repo(vertx, root, "go");
        final Path dir = root.resolve(repo).resolve("example.com").resolve("qa").resolve("@v");
        Files.createDirectories(dir);
        for (final String ver : new String[] {"v1.0.0", "v1.1.0"}) {
            Files.write(dir.resolve(ver + ".zip"), new byte[] {1});
            Files.write(dir.resolve(ver + ".mod"), new byte[] {1});
        }
        Files.writeString(dir.resolve("list"), "v1.0.0\nv1.1.0\n");
        Assertions.assertEquals(
            204,
            this.delete(vertx, repo, "artifacts", "example.com/qa/@v/v1.1.0.zip").statusCode(),
            "delete must succeed"
        );
        Assertions.assertEquals(
            "v1.0.0\n", Files.readString(dir.resolve("list")),
            "only the remaining version is listed"
        );
        ctx.completeNow();
    }

    /**
     * Create a repository on a filesystem storage.
     * @param vertx Vertx
     * @param root Storage root
     * @param type Repository type
     * @return Repository name
     * @throws Exception On error
     */
    private String repo(final Vertx vertx, final Path root, final String type)
        throws Exception {
        final String name = "qa-fx-" + type + "-"
            + UUID.randomUUID().toString().substring(0, 8);
        final JsonObject body = new JsonObject().put(
            "repo",
            new JsonObject().put("type", type)
                .put("storage", new JsonObject().put("type", "fs").put("path", root.toString()))
        );
        Assertions.assertEquals(
            200,
            WebClient.create(vertx)
                .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/" + name)
                .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
                .sendJsonObject(body)
                .toCompletionStage().toCompletableFuture()
                .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS)
                .statusCode(),
            "repository must be created"
        );
        return name;
    }

    /**
     * Send a delete.
     * @param vertx Vertx
     * @param repo Repository
     * @param kind {@code artifacts} or {@code packages}
     * @param path Path
     * @return Response
     * @throws Exception On error
     */
    private HttpResponse<Buffer> delete(
        final Vertx vertx, final String repo, final String kind, final String path
    ) throws Exception {
        return WebClient.create(vertx)
            .delete(
                this.port(), AsyncApiTestBase.HOST,
                "/api/v1/repositories/" + repo + "/" + kind
            )
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(new JsonObject().put("path", path))
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
    }

    /**
     * In-memory index holding {@code repo|path} rows.
     */
    private static final class RowsIndex implements ArtifactIndex {

        @Override
        public CompletableFuture<Void> index(final ArtifactDocument doc) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> remove(final String repo, final String path) {
            ROWS.remove(repo + "|" + path);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Integer> removeByPath(final String repo, final String path) {
            final String exact = repo + "|" + path;
            final String under = exact + "/";
            int removed = 0;
            for (final String row : List.copyOf(ROWS)) {
                if (row.equals(exact) || row.startsWith(under)) {
                    ROWS.remove(row);
                    removed += 1;
                }
            }
            return CompletableFuture.completedFuture(removed);
        }

        @Override
        public CompletableFuture<SearchResult> search(
            final String query, final int max, final int offset
        ) {
            return CompletableFuture.completedFuture(SearchResult.EMPTY);
        }

        @Override
        public CompletableFuture<List<String>> locate(final String path) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public void close() {
            // nothing to close
        }
    }
}
