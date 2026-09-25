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
package com.auto1.pantera.settings.repo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Specification of {@link FsStorageRootPolicy}: inline {@code fs} storage
 * roots submitted through the REST repository API must resolve under an
 * approved base directory.
 *
 * <p>Before 2.2.9 a principal holding only repository CREATE/UPDATE could
 * submit {@code {"type":"fs","path":"/"}} and Pantera mounted the host root
 * as a browsable, downloadable repository — the JWT private key under
 * {@code /etc/pantera/keys}, {@code /proc/self/environ}, everything the
 * service user could read.</p>
 *
 * @since 2.2.9
 */
final class FsStorageRootPolicyTest {

    private static final FsStorageRootPolicy POLICY =
        new FsStorageRootPolicy(List.of(Path.of("/var/pantera/data")));

    @Test
    void vertxFileStorageIsCheckedLikeFs() {
        // B12: only "fs" was checked; "vertx-file" is the same local
        // filesystem and mounted any host path.
        final JsonObject repo = Json.createObjectBuilder().add(
            "storage", Json.createObjectBuilder().add("type", "vertx-file").add("path", "/etc")
        ).build();
        MatcherAssert.assertThat(
            POLICY.rejectStorage(repo).isPresent(), new IsEqual<>(true)
        );
    }

    @Test
    void aliasBlockIsChecked() {
        MatcherAssert.assertThat(
            "an alias definition is a storage block and is checked the same way",
            POLICY.rejectBlock(
                Json.createObjectBuilder().add("type", "fs").add("path", "/etc").build()
            ).isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "a non-local storage block is not a filesystem path",
            POLICY.rejectBlock(
                Json.createObjectBuilder().add("type", "s3").add("bucket", "b").build()
            ).isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void hostRootIsRejected() {
        MatcherAssert.assertThat(
            "the filesystem root must never become a repository",
            POLICY.reject("/").isPresent(), new IsEqual<>(true)
        );
    }

    @Test
    void pathOutsideTheBaseIsRejected() {
        MatcherAssert.assertThat(
            "/etc/pantera/keys is outside the approved base",
            POLICY.reject("/etc/pantera/keys").isPresent(), new IsEqual<>(true)
        );
    }

    @Test
    void traversalOutOfTheBaseIsRejected() {
        MatcherAssert.assertThat(
            "dot-dot segments must be normalised before the containment check",
            POLICY.reject("/var/pantera/data/../../../etc").isPresent(), new IsEqual<>(true)
        );
    }

    @Test
    void relativePathIsRejected() {
        MatcherAssert.assertThat(
            "a relative path resolves against the process cwd — never approved",
            POLICY.reject("data/repo").isPresent(), new IsEqual<>(true)
        );
    }

    @Test
    void pathUnderTheBaseIsAllowed() {
        MatcherAssert.assertThat(
            "the documented data directory and its children are fine",
            POLICY.reject("/var/pantera/data/npm-local").isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the base itself is fine",
            POLICY.reject("/var/pantera/data").isPresent(), new IsEqual<>(false)
        );
    }

    @Test
    void siblingWithSamePrefixIsRejected() {
        MatcherAssert.assertThat(
            "/var/pantera/data-evil is not under /var/pantera/data (segment, not string, prefix)",
            POLICY.reject("/var/pantera/data-evil").isPresent(), new IsEqual<>(true)
        );
    }

    @Test
    void repoStorageObjectIsValidated() {
        final JsonObject repo = Json.createObjectBuilder()
            .add("type", "file")
            .add("storage", Json.createObjectBuilder().add("type", "fs").add("path", "/"))
            .build();
        MatcherAssert.assertThat(
            "an inline fs storage block naming an unapproved root must be rejected",
            POLICY.rejectStorage(repo).isPresent(), new IsEqual<>(true)
        );
    }

    @Test
    void nonFsAndAliasStorageAreNotAffected() {
        final JsonObject s3 = Json.createObjectBuilder()
            .add("type", "file")
            .add("storage", Json.createObjectBuilder().add("type", "s3").add("bucket", "b"))
            .build();
        final JsonObject alias = Json.createObjectBuilder()
            .add("type", "file")
            .add("storage", "default")
            .build();
        MatcherAssert.assertThat("s3 storage is out of scope",
            POLICY.rejectStorage(s3).isPresent(), new IsEqual<>(false));
        MatcherAssert.assertThat("an alias reference is admin-defined, not a raw path",
            POLICY.rejectStorage(alias).isPresent(), new IsEqual<>(false));
    }

    @Test
    void configuredRootsAreHonoured() {
        final FsStorageRootPolicy custom = FsStorageRootPolicy.parse("/srv/a:/srv/b");
        MatcherAssert.assertThat("first configured root",
            custom.reject("/srv/a/x").isPresent(), new IsEqual<>(false));
        MatcherAssert.assertThat("second configured root",
            custom.reject("/srv/b/y").isPresent(), new IsEqual<>(false));
        MatcherAssert.assertThat("the default is no longer implied",
            custom.reject("/var/pantera/data").isPresent(), new IsEqual<>(true));
    }

    /**
     * A symlink planted under an approved root must not smuggle the target
     * directory in: the check follows the deepest existing ancestor to its
     * real location and re-applies containment there.
     * @param tmp Scratch directory
     * @throws IOException On filesystem failure
     */
    @Test
    void symlinkUnderTheRootPointingOutsideIsRejected(@TempDir final Path tmp)
        throws IOException {
        final Path root = Files.createDirectory(tmp.resolve("root"));
        final Path outside = Files.createDirectory(tmp.resolve("outside"));
        final Path link = root.resolve("link");
        Files.createSymbolicLink(link, outside);
        final FsStorageRootPolicy policy = new FsStorageRootPolicy(List.of(root));
        MatcherAssert.assertThat(
            "the symlink itself resolves outside the root",
            policy.reject(link.toString()).isPresent(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "a not-yet-existing child of the symlink resolves outside too",
            policy.reject(link.resolve("repo").toString()).isPresent(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "a genuine (possibly not yet created) child of the root is fine",
            policy.reject(root.resolve("npm-local").toString()).isPresent(),
            new IsEqual<>(false)
        );
    }

    // ─── rejectOverlap (2.2.9 storage-path overlap, finding #17) ───

    @Test
    void overlapAllowsRepositoriesSharingTheExactSameRoot() {
        // The default shared-root model: every repository points at the same
        // approved root and is namespaced by its own name — never a collision.
        MatcherAssert.assertThat(
            "equal storage paths (shared root, namespaced by name) do not overlap",
            POLICY.rejectOverlap(
                "/var/pantera/data",
                Map.of("maven", "/var/pantera/data", "npm", "/var/pantera/data")
            ).isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void overlapAllowsSiblingDirectories() {
        MatcherAssert.assertThat(
            "sibling storage directories do not overlap",
            POLICY.rejectOverlap(
                "/var/pantera/data",
                Map.of("sec", "/var/pantera/security", "repo", "/var/pantera/repo")
            ).isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void overlapRejectsAPathNestedInsideAnotherRepositorysStorage() {
        // The attack: repo "com" points inside repo "victim"'s root, so its
        // tree lands under the victim's namespace.
        final var clash = POLICY.rejectOverlap(
            "/var/pantera/data/victim", Map.of("victim", "/var/pantera/data")
        );
        MatcherAssert.assertThat(
            "a path nested inside another repository's storage is rejected",
            clash.isPresent(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the rejection names the conflicting repository",
            clash.get().contains("victim"), new IsEqual<>(true)
        );
    }

    @Test
    void overlapRejectsAPathContainingAnotherRepositorysStorage() {
        MatcherAssert.assertThat(
            "a path that contains another repository's storage is rejected",
            POLICY.rejectOverlap(
                "/var/pantera/data", Map.of("child", "/var/pantera/data/child")
            ).isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void overlapTreatsPathBoundariesElementWise() {
        // /var/pantera/data must NOT be treated as a parent of
        // /var/pantera/database (string-prefix, not a path-element parent).
        MatcherAssert.assertThat(
            "a shared string prefix that is not a path-element parent does not overlap",
            POLICY.rejectOverlap(
                "/var/pantera/data", Map.of("db", "/var/pantera/database")
            ).isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void overlapNormalisesTrailingSlashesToEqual() {
        MatcherAssert.assertThat(
            "a trailing slash does not make an equal path look nested",
            POLICY.rejectOverlap(
                "/var/pantera/data/", Map.of("other", "/var/pantera/data")
            ).isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void overlapWithNoOtherRepositoriesIsAllowed() {
        MatcherAssert.assertThat(
            "the first repository has nothing to overlap",
            POLICY.rejectOverlap("/var/pantera/data", Map.of()).isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void overlapFindsTheConflictAmongManyRepositories() {
        final var clash = POLICY.rejectOverlap(
            "/var/pantera/data/x",
            Map.of(
                "ok-a", "/srv/other-root",
                "ok-b", "/var/pantera/security",
                "victim", "/var/pantera/data"
            )
        );
        MatcherAssert.assertThat(
            "the one nesting conflict among several repositories is found",
            clash.isPresent(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "and it names the conflicting repository",
            clash.get().contains("victim"), new IsEqual<>(true)
        );
    }
}
