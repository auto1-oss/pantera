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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exploit-regression test: the filesystem directory browser must not list a
 * directory outside the repository's storage root. Before 2.2.9
 * {@code basePath.resolve(key)} had no normalization / containment, so a
 * request path with parent segments returned an HTML listing (names, sizes,
 * modification times) of arbitrary process-readable directories on a default
 * {@code type: fs} repository.
 *
 * @since 2.2.9
 */
final class FileSystemBrowseSliceContainmentTest {

    @TempDir
    private Path temp;

    @Test
    void browseCannotEscapeStorageRoot() throws Exception {
        final Path base = Files.createDirectories(this.temp.resolve("repo-root"));
        final Path secret = Files.createDirectories(this.temp.resolve("secret"));
        Files.writeString(secret.resolve("passwords.txt"), "top-secret");
        final Storage storage = new FileStorage(base);
        final FileSystemBrowseSlice slice = new FileSystemBrowseSlice(storage);
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/../secret"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "a traversal browse must not return a 2xx directory listing of a sibling dir",
            response.status().code() >= 400, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the escaped directory's file names must not leak into the response body",
            response.body().asString().contains("passwords.txt"),
            new IsEqual<>(false)
        );
    }

    @Test
    void containedBrowseStillWorks() {
        final Storage storage = new FileStorage(this.temp);
        storage.save(
            new com.auto1.pantera.asto.Key.From("dir/a.txt"),
            new Content.From("x".getBytes(StandardCharsets.UTF_8))
        ).join();
        final FileSystemBrowseSlice slice = new FileSystemBrowseSlice(storage);
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/dir"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "a normal contained directory browse must still succeed",
            response.status().code(), new IsEqual<>(200)
        );
    }
}
