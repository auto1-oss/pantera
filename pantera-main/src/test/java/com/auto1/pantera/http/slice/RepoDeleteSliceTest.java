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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.api.v1.ArtifactDeletion;
import com.auto1.pantera.api.v1.StorageMetaCache;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.CombinedAuthzSliceWrap;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.PolicyByUsername;
import com.auto1.pantera.test.RecordingIndex;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link RepoDeleteSlice}.
 *
 * @since 2.2.10
 */
final class RepoDeleteSliceTest {

    /**
     * Repository name.
     */
    private static final String REPO = "my-files";

    @Test
    void deletesAFile() {
        final Storage storage = RepoDeleteSliceTest.storage("lib/a.bin", "lib/b.bin");
        final RecordingIndex index = new RecordingIndex().row(RepoDeleteSliceTest.REPO, "lib/a.bin");
        MatcherAssert.assertThat(
            "the delete answers 204",
            RepoDeleteSliceTest.delete(storage, index, "file", "/lib/a.bin"),
            new IsEqual<>(204)
        );
        MatcherAssert.assertThat(
            "the file is gone, the other stays",
            RepoDeleteSliceTest.keys(storage), new IsEqual<>(List.of("lib/b.bin"))
        );
        MatcherAssert.assertThat(
            "the index row of the path is removed",
            index.removals(), new IsEqual<>(List.of(RepoDeleteSliceTest.REPO + "|lib/a.bin"))
        );
    }

    @Test
    void deletesAFolderSubtree() {
        final Storage storage = RepoDeleteSliceTest.storage(
            "lib/1.0/a.bin", "lib/1.0/b.bin", "lib/1.0-extra/c.bin"
        );
        MatcherAssert.assertThat(
            "the delete answers 204",
            RepoDeleteSliceTest.delete(storage, new RecordingIndex(), "file", "/lib/1.0/"),
            new IsEqual<>(204)
        );
        MatcherAssert.assertThat(
            "only the string-prefix sibling stays",
            RepoDeleteSliceTest.keys(storage), new IsEqual<>(List.of("lib/1.0-extra/c.bin"))
        );
    }

    @Test
    void answers404WhenNothingIsThere() {
        MatcherAssert.assertThat(
            RepoDeleteSliceTest.delete(
                new InMemoryStorage(), new RecordingIndex(), "file", "/nothing/here.bin"
            ),
            new IsEqual<>(404)
        );
    }

    @Test
    void refusesTraversalAndTheRoot() {
        final Storage storage = RepoDeleteSliceTest.storage("lib/a.bin");
        MatcherAssert.assertThat(
            "a dot-dot path is refused",
            RepoDeleteSliceTest.delete(storage, new RecordingIndex(), "file", "/lib/../lib/a.bin"),
            new IsEqual<>(400)
        );
        MatcherAssert.assertThat(
            "an encoded dot-dot path is refused",
            RepoDeleteSliceTest.delete(storage, new RecordingIndex(), "file", "/lib/%2e%2e/x"),
            new IsEqual<>(400)
        );
        MatcherAssert.assertThat(
            "the repository root is refused",
            RepoDeleteSliceTest.delete(storage, new RecordingIndex(), "file", "/"),
            new IsEqual<>(400)
        );
        MatcherAssert.assertThat(
            "nothing was deleted",
            RepoDeleteSliceTest.keys(storage), new IsEqual<>(List.of("lib/a.bin"))
        );
    }

    @Test
    void deletingAMavenVersionPrunesItsMetadata() {
        final Storage storage = RepoDeleteSliceTest.storage(
            "com/qa/lib/0.0.1/lib-0.0.1.jar", "com/qa/lib/0.0.2/lib-0.0.2.jar"
        );
        storage.save(
            new Key.From("com/qa/lib/maven-metadata.xml"),
            new Content.From(
                ("<metadata><groupId>com.qa</groupId><artifactId>lib</artifactId><versioning>"
                    + "<latest>0.0.2</latest><release>0.0.2</release><versions>"
                    + "<version>0.0.1</version><version>0.0.2</version></versions>"
                    + "</versioning></metadata>").getBytes(StandardCharsets.UTF_8)
            )
        ).join();
        MatcherAssert.assertThat(
            "the delete answers 204",
            RepoDeleteSliceTest.delete(storage, new RecordingIndex(), "maven", "/com/qa/lib/0.0.2"),
            new IsEqual<>(204)
        );
        final String meta = storage.value(new Key.From("com/qa/lib/maven-metadata.xml"))
            .join().asString();
        MatcherAssert.assertThat(
            "the deleted version left maven-metadata.xml",
            meta.contains("0.0.2"), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the other version stays in maven-metadata.xml",
            meta, new StringContains("<version>0.0.1</version>")
        );
    }

    @Test
    void requiresTheDeletePermission() {
        final Storage storage = RepoDeleteSliceTest.storage("lib/a.bin");
        final Authentication basic = (user, pwd) -> "secret".equals(pwd)
            ? Optional.of(new AuthUser(user, "test")) : Optional.empty();
        final Slice gated = new CombinedAuthzSliceWrap(
            new RepoDeleteSlice(
                RepoDeleteSliceTest.REPO, "file", storage,
                new ArtifactDeletion(new RecordingIndex(), new StorageMetaCache())
            ),
            basic,
            token -> CompletableFuture.completedFuture(Optional.<AuthUser>empty()),
            new OperationControl(
                new PolicyByUsername("alice"),
                new AdapterBasicPermission(RepoDeleteSliceTest.REPO, Action.Standard.DELETE)
            )
        );
        MatcherAssert.assertThat(
            "a user without the delete permission is forbidden",
            gated.response(
                new RequestLine(RqMethod.DELETE, "/lib/a.bin"),
                Headers.from(new Authorization.Basic("bob", "secret")),
                Content.EMPTY
            ).join().status().code(),
            new IsEqual<>(403)
        );
        MatcherAssert.assertThat(
            "the forbidden delete left the file",
            RepoDeleteSliceTest.keys(storage), new IsEqual<>(List.of("lib/a.bin"))
        );
        MatcherAssert.assertThat(
            "a user with the delete permission deletes",
            gated.response(
                new RequestLine(RqMethod.DELETE, "/lib/a.bin"),
                Headers.from(new Authorization.Basic("alice", "secret")),
                Content.EMPTY
            ).join().status().code(),
            new IsEqual<>(204)
        );
    }

    /**
     * Send a DELETE through the slice.
     * @param storage Storage
     * @param index Index
     * @param type Repository type
     * @param path Request path
     * @return Status code
     */
    private static int delete(
        final Storage storage, final RecordingIndex index, final String type, final String path
    ) {
        final Response rsp = new RepoDeleteSlice(
            RepoDeleteSliceTest.REPO, type, storage,
            new ArtifactDeletion(index, new StorageMetaCache())
        ).response(
            new RequestLine(RqMethod.DELETE, path), Headers.EMPTY,
            new Content.From("ignored".getBytes(StandardCharsets.UTF_8))
        ).join();
        return rsp.status().code();
    }

    /**
     * Storage holding the given keys.
     * @param keys Keys
     * @return Storage
     */
    private static Storage storage(final String... keys) {
        final Storage storage = new InMemoryStorage();
        for (final String key : keys) {
            storage.save(new Key.From(key), new Content.From(new byte[] {1})).join();
        }
        return storage;
    }

    /**
     * Sorted keys of a storage.
     * @param storage Storage
     * @return Keys
     */
    private static List<String> keys(final Storage storage) {
        return storage.list(Key.ROOT).join().stream().map(Key::string).sorted().toList();
    }
}
