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

import com.auto1.pantera.cache.StoragesCache;
import com.auto1.pantera.repojob.RepoJobRunner;
import com.auto1.pantera.repojob.RepoStorages;
import com.auto1.pantera.repojob.StoragePurgeStore;
import com.auto1.pantera.settings.repo.DbRepositories;
import com.auto1.pantera.settings.repo.RepoKeys;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Repository rename, delete, restore and orphan purge through the REST API,
 * with the purge run against a real filesystem storage.
 *
 * @since 2.2.14
 */
@SuppressWarnings("PMD.TooManyMethods")
final class RepoLifecycleApiTest extends AsyncApiTestBase {

    /**
     * Root of the filesystem storage the repositories of a test share.
     */
    @TempDir
    Path root;

    @BeforeEach
    void clean() throws Exception {
        try (Connection conn = AsyncApiTestBase.sharedDs().getConnection();
            Statement stmt = conn.createStatement()) {
            stmt.execute(
                "TRUNCATE repo_jobs, repository_tombstones, repository_aliases, repositories,"
                    + " artifacts RESTART IDENTITY CASCADE"
            );
        }
    }

    @AfterEach
    void reset() {
        RepoKeys.install(null);
    }

    @Test
    void deleteAnswersAtOnceAndThePurgeRemovesOnlyThatRepositorysFiles(final Vertx vertx)
        throws Exception {
        final WebClient client = WebClient.create(vertx);
        this.create(client, "del-data");
        this.create(client, "del-data-2");
        final Path mine = this.file("del-data", "a/b/secret.txt");
        final Path sibling = this.file("del-data-2", "keep.txt");
        final HttpResponse<Buffer> del = this.send(
            client, HttpMethod.DELETE, "/api/v1/repositories/del-data?retention_hours=0", null
        );
        MatcherAssert.assertThat(
            "the delete is accepted", del.statusCode(), new IsEqual<>(202)
        );
        MatcherAssert.assertThat(
            "the repository is gone before any file is removed",
            List.of(
                this.send(client, HttpMethod.GET, "/api/v1/repositories/del-data", null).statusCode(),
                Files.exists(mine) ? 1 : 0
            ),
            new IsEqual<>(List.of(404, 1))
        );
        this.drain();
        MatcherAssert.assertThat(
            "the purge removed the files and the directories they left empty",
            Files.exists(this.root.resolve("del-data")), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "a repository whose name starts the same keeps its files",
            Files.exists(sibling), new IsEqual<>(true)
        );
        final JsonObject job = this.send(
            client, HttpMethod.GET,
            "/api/v1/repository-jobs/" + del.bodyAsJsonObject().getLong("job_id"), null
        ).bodyAsJsonObject();
        MatcherAssert.assertThat(
            "the job reports what it removed",
            List.of(job.getString("state"), String.valueOf(job.getLong("objects_done"))),
            new IsEqual<>(List.of("done", "1"))
        );
    }

    @Test
    void deletedRepositoryCanBeRestoredUntilItsPurgeStarts(final Vertx vertx) throws Exception {
        final WebClient client = WebClient.create(vertx);
        this.create(client, "undo");
        final Path data = this.file("undo", "pkg.bin");
        final HttpResponse<Buffer> del = this.send(
            client, HttpMethod.DELETE, "/api/v1/repositories/undo?retention_hours=5", null
        );
        this.drain();
        MatcherAssert.assertThat(
            "inside the restore window nothing is purged", Files.exists(data), new IsEqual<>(true)
        );
        final JsonArray deleted = this.send(
            client, HttpMethod.GET, "/api/v1/deleted-repositories", null
        ).bodyAsJsonObject().getJsonArray("items");
        MatcherAssert.assertThat(
            "the deleted repository is listed",
            deleted.getJsonObject(0).getString("name"), new IsEqual<>("undo")
        );
        final HttpResponse<Buffer> restore = this.send(
            client, HttpMethod.POST, "/api/v1/deleted-repositories/undo/restore", null
        );
        MatcherAssert.assertThat(
            "the restore succeeds", restore.statusCode(), new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "the repository exists again",
            this.send(client, HttpMethod.GET, "/api/v1/repositories/undo", null).statusCode(),
            new IsEqual<>(200)
        );
        final long job = del.bodyAsJsonObject().getLong("job_id");
        MatcherAssert.assertThat(
            "its purge is cancelled",
            this.send(client, HttpMethod.GET, "/api/v1/repository-jobs/" + job, null)
                .bodyAsJsonObject().getString("state"),
            new IsEqual<>("cancelled")
        );
        MatcherAssert.assertThat(
            "a cancelled purge cannot be started",
            this.send(client, HttpMethod.POST, "/api/v1/repository-jobs/" + job + "/start", null)
                .statusCode(),
            new IsEqual<>(409)
        );
        this.drain();
        MatcherAssert.assertThat(
            "and its files are still there", Files.exists(data), new IsEqual<>(true)
        );
    }

    @Test
    void restoreWindowCanBeEndedEarlyAndThenRestoreIsRefused(final Vertx vertx) throws Exception {
        final WebClient client = WebClient.create(vertx);
        this.create(client, "now");
        final Path data = this.file("now", "pkg.bin");
        final long job = this.send(
            client, HttpMethod.DELETE, "/api/v1/repositories/now", null
        ).bodyAsJsonObject().getLong("job_id");
        MatcherAssert.assertThat(
            "the purge is started on request",
            this.send(client, HttpMethod.POST, "/api/v1/repository-jobs/" + job + "/start", null)
                .statusCode(),
            new IsEqual<>(200)
        );
        this.drain();
        MatcherAssert.assertThat(
            "the files are gone", Files.exists(data), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "a purged repository cannot be restored",
            this.send(client, HttpMethod.POST, "/api/v1/deleted-repositories/now/restore", null)
                .statusCode(),
            new IsEqual<>(404)
        );
    }

    @Test
    void deleteOfGroupMemberIsRefusedUntilForced(final Vertx vertx) throws Exception {
        final WebClient client = WebClient.create(vertx);
        this.create(client, "member");
        this.send(
            client, HttpMethod.PUT, "/api/v1/repositories/all",
            new JsonObject().put(
                "repo",
                new JsonObject().put("type", "file-group")
                    .put("members", new JsonArray().add("member"))
            )
        );
        final HttpResponse<Buffer> refused = this.send(
            client, HttpMethod.DELETE, "/api/v1/repositories/member", null
        );
        MatcherAssert.assertThat(
            "a group member is not deleted silently", refused.statusCode(), new IsEqual<>(409)
        );
        MatcherAssert.assertThat(
            "the answer names the group",
            refused.bodyAsJsonObject().getString("message"), new StringContains("all")
        );
        final HttpResponse<Buffer> forced = this.send(
            client, HttpMethod.DELETE, "/api/v1/repositories/member?force=true", null
        );
        MatcherAssert.assertThat(
            "forced, it is deleted and the answer says which groups changed",
            List.of(
                String.valueOf(forced.statusCode()),
                forced.bodyAsJsonObject().getJsonArray("removed_from_groups").encode()
            ),
            new IsEqual<>(List.of("202", "[\"all\"]"))
        );
        MatcherAssert.assertThat(
            "the group no longer lists it",
            this.value("SELECT config -> 'repo' ->> 'members' FROM repositories WHERE name = 'all'"),
            new IsEqual<>("[]")
        );
    }

    @Test
    void deleteRejectsAnInvalidRestoreWindow(final Vertx vertx) throws Exception {
        final WebClient client = WebClient.create(vertx);
        this.create(client, "keepme");
        MatcherAssert.assertThat(
            "a negative window is refused",
            this.send(
                client, HttpMethod.DELETE, "/api/v1/repositories/keepme?retention_hours=-1", null
            ).statusCode(),
            new IsEqual<>(400)
        );
        MatcherAssert.assertThat(
            "a non-numeric window is refused",
            this.send(
                client, HttpMethod.DELETE, "/api/v1/repositories/keepme?retention_hours=soon", null
            ).statusCode(),
            new IsEqual<>(400)
        );
        MatcherAssert.assertThat(
            "the repository was not deleted",
            this.send(client, HttpMethod.GET, "/api/v1/repositories/keepme", null).statusCode(),
            new IsEqual<>(200)
        );
    }

    @Test
    void renameMovesNoFileAndTheRepositoryIsBrowsableUnderItsNewName(final Vertx vertx)
        throws Exception {
        final WebClient client = WebClient.create(vertx);
        this.create(client, "before");
        final Path data = this.file("before", "dir/pkg.bin");
        final HttpResponse<Buffer> move = this.send(
            client, HttpMethod.PUT, "/api/v1/repositories/before/move",
            new JsonObject().put("new_name", "after").put("keep_old_name_days", 7)
        );
        MatcherAssert.assertThat(
            "the rename answers with the repository's unchanged key",
            List.of(
                String.valueOf(move.statusCode()),
                move.bodyAsJsonObject().getString("repo_key"),
                move.bodyAsJsonObject().getString("name")
            ),
            new IsEqual<>(List.of("200", "before", "after"))
        );
        MatcherAssert.assertThat(
            "the file did not move", Files.exists(data), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "no directory was created for the new name",
            Files.exists(this.root.resolve("after")), new IsEqual<>(false)
        );
        // What the server installs at boot: names resolve to keys.
        RepoKeys.install(
            new DbRepositories(AsyncApiTestBase.sharedDs(), new StoragesCache(), false)
        );
        final HttpResponse<Buffer> tree = this.send(
            client, HttpMethod.GET, "/api/v1/repositories/after/tree?path=dir", null
        );
        MatcherAssert.assertThat(
            "the file browser shows the files under the new name",
            tree.bodyAsString(), new StringContains("pkg.bin")
        );
        MatcherAssert.assertThat(
            "a new repository cannot take the old name while it still serves the renamed one",
            this.send(
                client, HttpMethod.PUT, "/api/v1/repositories/before",
                RepoLifecycleApiTest.fileRepo(this.root.toString())
            ).statusCode(),
            new IsEqual<>(409)
        );
    }

    @Test
    void renameIsRefusedOntoAnExistingNameAndForBadInput(final Vertx vertx) throws Exception {
        final WebClient client = WebClient.create(vertx);
        this.create(client, "one");
        this.create(client, "two");
        MatcherAssert.assertThat(
            "the target exists",
            this.send(
                client, HttpMethod.PUT, "/api/v1/repositories/one/move",
                new JsonObject().put("new_name", "two")
            ).statusCode(),
            new IsEqual<>(409)
        );
        MatcherAssert.assertThat(
            "the source does not exist",
            this.send(
                client, HttpMethod.PUT, "/api/v1/repositories/ghost/move",
                new JsonObject().put("new_name", "three")
            ).statusCode(),
            new IsEqual<>(404)
        );
        MatcherAssert.assertThat(
            "the alias period is out of range",
            this.send(
                client, HttpMethod.PUT, "/api/v1/repositories/one/move",
                new JsonObject().put("new_name", "three").put("keep_old_name_days", 9999)
            ).statusCode(),
            new IsEqual<>(400)
        );
        MatcherAssert.assertThat(
            "nothing was renamed",
            this.send(client, HttpMethod.GET, "/api/v1/repositories/one", null).statusCode(),
            new IsEqual<>(200)
        );
    }

    @Test
    void newRepositoryDoesNotAdoptFilesLeftUnderItsNameAndTheyCanBePurged(final Vertx vertx)
        throws Exception {
        final WebClient client = WebClient.create(vertx);
        // Left behind by a delete made before 2.2.14.
        final Path leftover = this.file("reused", "old/secret.txt");
        this.create(client, "reused");
        MatcherAssert.assertThat(
            "the new repository gets a directory of its own",
            this.value("SELECT repo_key FROM repositories WHERE name = 'reused'"),
            new IsEqual<>("reused~2")
        );
        final JsonArray orphans = this.send(
            client, HttpMethod.GET, "/api/v1/repository-orphans", null
        ).bodyAsJsonObject().getJsonArray("items");
        MatcherAssert.assertThat(
            "the leftover directory is reported as unowned, with its object count",
            List.of(
                String.valueOf(orphans.size()),
                orphans.getJsonObject(0).getString("directory"),
                String.valueOf(orphans.getJsonObject(0).getInteger("objects"))
            ),
            new IsEqual<>(List.of("1", "reused", "1"))
        );
        final String storage = orphans.getJsonObject(0).getString("storage");
        MatcherAssert.assertThat(
            "a directory a repository owns cannot be purged as an orphan",
            this.send(
                client, HttpMethod.POST, "/api/v1/repository-orphans/purge",
                new JsonObject().put("storage", storage).put("directory", "reused~2")
            ).statusCode(),
            new IsEqual<>(409)
        );
        MatcherAssert.assertThat(
            "the unowned directory can",
            this.send(
                client, HttpMethod.POST, "/api/v1/repository-orphans/purge",
                new JsonObject().put("storage", storage).put("directory", "reused")
            ).statusCode(),
            new IsEqual<>(202)
        );
        this.drain();
        MatcherAssert.assertThat(
            "its files are removed", Files.exists(leftover), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "and it is no longer reported",
            this.send(client, HttpMethod.GET, "/api/v1/repository-orphans", null)
                .bodyAsJsonObject().getJsonArray("items").size(),
            new IsEqual<>(0)
        );
    }

    @Test
    void recreatedNameNeverSeesTheDeletedRepositorysFiles(final Vertx vertx) throws Exception {
        final WebClient client = WebClient.create(vertx);
        this.create(client, "again");
        final Path old = this.file("again", "old.bin");
        this.send(client, HttpMethod.DELETE, "/api/v1/repositories/again", null);
        this.create(client, "again");
        MatcherAssert.assertThat(
            "while the old data waits for its purge the new repository uses another directory",
            this.value("SELECT repo_key FROM repositories WHERE name = 'again'"),
            new IsEqual<>("again~2")
        );
        MatcherAssert.assertThat(
            "the old files are not touched by the re-creation",
            Files.exists(old), new IsEqual<>(true)
        );
    }

    @Test
    void unownedDirectoryOfAStorageSpelledTwoWaysIsReportedOnce(final Vertx vertx)
        throws Exception {
        final WebClient client = WebClient.create(vertx);
        this.create(client, "plain");
        MatcherAssert.assertThat(
            "a repository whose storage path ends with a slash is created",
            this.send(
                client, HttpMethod.PUT, "/api/v1/repositories/slashed",
                RepoLifecycleApiTest.fileRepo(this.root.toString() + "/")
            ).statusCode(),
            new IsEqual<>(200)
        );
        this.file("left-behind", "old.bin");
        final JsonArray orphans = this.send(
            client, HttpMethod.GET, "/api/v1/repository-orphans", null
        ).bodyAsJsonObject().getJsonArray("items");
        MatcherAssert.assertThat(
            "one directory, one row: both repositories use the same storage",
            orphans.stream().map(o -> ((JsonObject) o).getString("directory")).toList(),
            new IsEqual<>(List.of("left-behind"))
        );
    }

    /**
     * Create a file repository on the shared storage root.
     * @param client HTTP client
     * @param name Repository name
     * @throws Exception On error
     */
    private void create(final WebClient client, final String name) throws Exception {
        MatcherAssert.assertThat(
            "repository " + name + " is created",
            this.send(
                client, HttpMethod.PUT, "/api/v1/repositories/" + name,
                RepoLifecycleApiTest.fileRepo(this.root.toString())
            ).statusCode(),
            new IsEqual<>(200)
        );
    }

    /**
     * Write a file into a storage directory.
     * @param dir Top-level directory (a repository key)
     * @param path Path inside it
     * @return The file
     * @throws Exception On error
     */
    private Path file(final String dir, final String path) throws Exception {
        final Path file = this.root.resolve(dir).resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "data");
        return file;
    }

    /**
     * Run every due repository job, as a node's runner would, with no wait
     * for in-flight requests.
     */
    private void drain() {
        try (RepoJobRunner runner = new RepoJobRunner(
            AsyncApiTestBase.sharedDs(),
            new RepoStorages(new StoragesCache()).andThen(StoragePurgeStore::new),
            "test-node",
            new RepoJobRunner.Timing(
                Duration.ofHours(1), Duration.ofMinutes(10), Duration.ZERO, Duration.ofHours(1),
                Duration.ofMillis(1), Duration.ofHours(1)
            )
        )) {
            runner.drain();
        }
    }

    /**
     * Send a request and wait for the answer.
     * @param client HTTP client
     * @param method Method
     * @param path Path and query
     * @param body JSON body, nullable
     * @return Response
     * @throws Exception On error
     */
    private HttpResponse<Buffer> send(
        final WebClient client, final HttpMethod method, final String path, final JsonObject body
    ) throws Exception {
        final HttpRequest<Buffer> request = client
            .request(method, this.port(), AsyncApiTestBase.HOST, path)
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN);
        return (body == null ? request.send() : request.sendJsonObject(body))
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
    }

    /**
     * Single value of a query.
     * @param sql Query
     * @return Value, {@code null} when there is no row
     * @throws Exception On error
     */
    private String value(final String sql) throws Exception {
        try (Connection conn = AsyncApiTestBase.sharedDs().getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql);
            ResultSet rows = stmt.executeQuery()) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    /**
     * Config of a file repository on a filesystem storage.
     * @param path Storage root
     * @return Repository config
     */
    private static JsonObject fileRepo(final String path) {
        return new JsonObject().put(
            "repo",
            new JsonObject()
                .put("type", "file")
                .put("storage", new JsonObject().put("type", "fs").put("path", path))
        );
    }
}
