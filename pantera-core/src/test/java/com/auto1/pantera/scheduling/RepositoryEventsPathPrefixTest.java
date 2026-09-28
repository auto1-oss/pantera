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
package com.auto1.pantera.scheduling;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.Headers;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that upload events carry the artifact's real storage key.
 *
 * <p>This is the shared path behind generic-file and conan uploads. Its
 * display name flattens separators into dots and cannot be reversed, so
 * without the key the tree browser has nothing to navigate to and lands on
 * the repository root.</p>
 *
 * @since 2.2.8
 */
final class RepositoryEventsPathPrefixTest {

    @Test
    @DisplayName("A file-repo upload records the real key, not just the dotted name")
    void fileUploadCarriesTheStorageKey() {
        final Queue<ArtifactEvent> queue = new ConcurrentLinkedDeque<>();
        new RepositoryEvents("file", "services", queue).addUploadEventByKey(
            new Key.From("wkda/services/thing/1.0.0-SNAPSHOT/thing-1.0.0-20210414.085244-1.pom"),
            64L, Headers.EMPTY
        );
        final ArtifactEvent event = queue.poll();
        MatcherAssert.assertThat(
            "The real key must reach the index",
            event.pathPrefix(),
            new IsEqual<>(
                "wkda/services/thing/1.0.0-SNAPSHOT/thing-1.0.0-20210414.085244-1.pom"
            )
        );
        MatcherAssert.assertThat(
            "The display name stays flattened",
            event.artifactName(),
            new IsEqual<>(
                "wkda.services.thing.1.0.0-SNAPSHOT.thing-1.0.0-20210414.085244-1.pom"
            )
        );
    }

    @Test
    @DisplayName("The repository-name prefix is stripped from the recorded key")
    void stripsTheRepositoryPrefix() {
        final Queue<ArtifactEvent> queue = new ConcurrentLinkedDeque<>();
        new RepositoryEvents("file", "services", queue).addUploadEventByKey(
            new Key.From("services/wkda/thing-1.0.jar"), 8L, Headers.EMPTY
        );
        MatcherAssert.assertThat(
            "The key is repo-relative, matching what the tree browser addresses",
            queue.poll().pathPrefix(), new IsEqual<>("wkda/thing-1.0.jar")
        );
    }

    @Test
    @DisplayName("A non-file repo keeps its key verbatim as both name and prefix")
    void nonFileRepoKeepsTheKeyVerbatim() {
        final Queue<ArtifactEvent> queue = new ConcurrentLinkedDeque<>();
        new RepositoryEvents("conan", "conan-local", queue).addUploadEventByKey(
            new Key.From("pkg/1.0/_/_/0/export/conanfile.py"), 16L, Headers.EMPTY
        );
        final ArtifactEvent event = queue.poll();
        MatcherAssert.assertThat(
            "conan names are already real keys and must not be flattened",
            event.artifactName(), new IsEqual<>("pkg/1.0/_/_/0/export/conanfile.py")
        );
        MatcherAssert.assertThat(
            "the key is recorded for browse as well",
            event.pathPrefix(), new IsEqual<>("pkg/1.0/_/_/0/export/conanfile.py")
        );
    }
}
