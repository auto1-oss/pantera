/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto;

import com.auto1.pantera.asto.fs.VertxFileStorage;
import io.vertx.reactivex.core.Vertx;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for VertxFileStorage hierarchical listing.
 * Verifies that list(Key, String) uses DirectoryStream for immediate children only.
 *
 * @since 0.1
 */
final class VertxFileStorageHierarchicalListTest {

    /**
     * Vert.x instance shared across tests.
     */
    private static final Vertx VERTX = Vertx.vertx();

    /**
     * Temporary directory for test storage.
     */
    @TempDir
    private Path temp;

    @Test
    void listsImmediateChildrenOnly() {
        final Storage storage = new VertxFileStorage(this.temp, VERTX);
        storage.save(new Key.From("com/google/guava/file.jar"), Content.EMPTY).join();
        storage.save(new Key.From("com/apache/commons/file.jar"), Content.EMPTY).join();
        storage.save(new Key.From("com/README.md"), Content.EMPTY).join();
        final ListResult result = storage.list(new Key.From("com"), "/").join();
        MatcherAssert.assertThat(
            "Should have 1 file (README.md)",
            result.files(),
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Should have 2 directories (google/, apache/)",
            result.directories(),
            Matchers.hasSize(2)
        );
        MatcherAssert.assertThat(
            "File key should be com/README.md",
            result.files().stream()
                .map(Key::string)
                .collect(Collectors.toList()),
            Matchers.hasItem("com/README.md")
        );
        final java.util.List<String> dirNames = result.directories().stream()
            .map(Key::string)
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Directories should contain google prefixed with com/",
            dirNames,
            Matchers.hasItem(
                Matchers.either(Matchers.equalTo("com/google/"))
                    .or(Matchers.equalTo("com/google"))
            )
        );
        MatcherAssert.assertThat(
            "Directories should contain apache prefixed with com/",
            dirNames,
            Matchers.hasItem(
                Matchers.either(Matchers.equalTo("com/apache/"))
                    .or(Matchers.equalTo("com/apache"))
            )
        );
    }

    @Test
    void returnsEmptyForNonExistentPrefix() {
        final Storage storage = new VertxFileStorage(this.temp, VERTX);
        final ListResult result = storage.list(new Key.From("nonexistent"), "/").join();
        MatcherAssert.assertThat(
            "Should be empty for non-existent prefix",
            result.isEmpty(),
            Matchers.is(true)
        );
    }

    @Test
    void listsRootLevel() {
        final Storage storage = new VertxFileStorage(this.temp, VERTX);
        storage.save(new Key.From("file1.txt"), Content.EMPTY).join();
        storage.save(new Key.From("dir1/nested.txt"), Content.EMPTY).join();
        storage.save(new Key.From("dir2/nested.txt"), Content.EMPTY).join();
        final ListResult result = storage.list(Key.ROOT, "/").join();
        MatcherAssert.assertThat(
            "Should have at least 1 file at root",
            result.files(),
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "File at root should be file1.txt",
            result.files().stream()
                .map(Key::string)
                .collect(Collectors.toList()),
            Matchers.hasItem("file1.txt")
        );
        final java.util.List<String> rootDirNames = result.directories().stream()
            .map(Key::string)
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Directories should contain dir1 (and possibly .tmp)",
            rootDirNames,
            Matchers.hasItem(
                Matchers.either(Matchers.equalTo("dir1/"))
                    .or(Matchers.equalTo("dir1"))
            )
        );
        MatcherAssert.assertThat(
            "Directories should contain dir2 (and possibly .tmp)",
            rootDirNames,
            Matchers.hasItem(
                Matchers.either(Matchers.equalTo("dir2/"))
                    .or(Matchers.equalTo("dir2"))
            )
        );
        MatcherAssert.assertThat(
            "Should have at least 2 directories at root",
            result.directories().size(),
            Matchers.greaterThanOrEqualTo(2)
        );
    }

    @AfterAll
    static void tearDown() {
        VERTX.close();
    }
}
