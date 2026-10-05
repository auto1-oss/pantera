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
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.settings.RepoPathRemoval;
import com.auto1.pantera.test.RecordingIndex;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link ArtifactDeletion}.
 *
 * @since 2.2.10
 */
final class ArtifactDeletionTest {

    /**
     * Repository name.
     */
    private static final String REPO = "files";

    @Test
    void deletesAFileAndItsIndexRow() {
        final Storage storage = ArtifactDeletionTest.storage("lib/a.bin", "lib/b.bin");
        final RecordingIndex index = new RecordingIndex()
            .row(ArtifactDeletionTest.REPO, "lib/a.bin")
            .row(ArtifactDeletionTest.REPO, "lib/b.bin");
        final StorageMetaCache meta = new StorageMetaCache();
        meta.put(ArtifactDeletionTest.REPO, "lib/a.bin", 1L, null);
        MatcherAssert.assertThat(
            "the file was found",
            new ArtifactDeletion(index, meta).delete(
                ArtifactDeletionTest.REPO, "file", storage, "lib/a.bin",
                RepoPathRemoval.Mode.AUTO
            ).join(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "only the other file is left",
            storage.list(Key.ROOT).join().stream().map(Key::string).toList(),
            new IsEqual<>(List.of("lib/b.bin"))
        );
        MatcherAssert.assertThat(
            "only the other row is left",
            index.rows(), new IsEqual<>(Set.of(ArtifactDeletionTest.REPO + "|lib/b.bin"))
        );
        MatcherAssert.assertThat(
            "the tree view forgot the file",
            meta.get(ArtifactDeletionTest.REPO, "lib/a.bin"), new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void deletesADirectorySubtreeButNotItsStringPrefixSibling() {
        final Storage storage = ArtifactDeletionTest.storage(
            "com/acme/lib/1.0/lib-1.0.jar", "com/acme/lib-extra/1.0/lib-extra-1.0.jar"
        );
        final StorageMetaCache meta = new StorageMetaCache();
        meta.put(ArtifactDeletionTest.REPO, "com/acme/lib/1.0/lib-1.0.jar", 1L, null);
        MatcherAssert.assertThat(
            "the directory was found",
            new ArtifactDeletion(new RecordingIndex(), meta).delete(
                ArtifactDeletionTest.REPO, "file", storage, "com/acme/lib",
                RepoPathRemoval.Mode.AUTO
            ).join(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the sibling sharing the string prefix stays",
            storage.list(Key.ROOT).join().stream().map(Key::string).toList(),
            new IsEqual<>(List.of("com/acme/lib-extra/1.0/lib-extra-1.0.jar"))
        );
        MatcherAssert.assertThat(
            "the tree view forgot the subtree",
            meta.get(ArtifactDeletionTest.REPO, "com/acme/lib/1.0/lib-1.0.jar"),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void fileModeNeverDeletesADirectory() {
        final Storage storage = ArtifactDeletionTest.storage("lib/a.bin");
        MatcherAssert.assertThat(
            "a directory is not a file",
            new ArtifactDeletion(new RecordingIndex(), new StorageMetaCache()).delete(
                ArtifactDeletionTest.REPO, "file", storage, "lib", RepoPathRemoval.Mode.FILE
            ).join(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the file under it stays",
            storage.exists(new Key.From("lib/a.bin")).join(), new IsEqual<>(true)
        );
    }

    @Test
    void aPathNeitherStoredNorIndexedIsNotFound() {
        MatcherAssert.assertThat(
            new ArtifactDeletion(new RecordingIndex(), new StorageMetaCache()).delete(
                ArtifactDeletionTest.REPO, "file", new InMemoryStorage(), "nothing/here",
                RepoPathRemoval.Mode.AUTO
            ).join(),
            new IsEqual<>(false)
        );
    }

    @Test
    void aStaleIndexRowAloneCountsAsFound() {
        final RecordingIndex index = new RecordingIndex()
            .row(ArtifactDeletionTest.REPO, "gone/a.bin");
        MatcherAssert.assertThat(
            "the stale row was found",
            new ArtifactDeletion(index, new StorageMetaCache()).delete(
                ArtifactDeletionTest.REPO, "file", new InMemoryStorage(), "gone/a.bin",
                RepoPathRemoval.Mode.AUTO
            ).join(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the stale row is removed",
            index.rows(), new IsEqual<>(Set.of())
        );
    }

    @Test
    void deletingAMavenVersionPrunesItsMetadata() {
        final Storage storage = ArtifactDeletionTest.storage(
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
        new ArtifactDeletion(new RecordingIndex(), new StorageMetaCache()).delete(
            "mvn", "maven", storage, "com/qa/lib/0.0.2", RepoPathRemoval.Mode.FOLDER
        ).join();
        final String meta = storage.value(new Key.From("com/qa/lib/maven-metadata.xml"))
            .join().asString();
        MatcherAssert.assertThat(
            "the deleted version left the metadata",
            meta.contains("0.0.2"), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the other version stays",
            meta, new StringContains("<version>0.0.1</version>")
        );
    }

    @Test
    void refusesTraversal() {
        final ArtifactDeletion deletion =
            new ArtifactDeletion(new RecordingIndex(), new StorageMetaCache());
        MatcherAssert.assertThat(
            "a dot-dot segment is unsafe",
            deletion.unsafe("a/../other-repo/x"), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "a plain path is safe",
            deletion.unsafe("a/b/c.jar"), new IsEqual<>(false)
        );
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
}
