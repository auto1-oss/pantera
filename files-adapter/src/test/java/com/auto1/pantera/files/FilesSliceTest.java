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
package com.auto1.pantera.files;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.security.policy.Policy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FilesSlice} serving and listing.
 *
 * @since 2.2.9
 */
final class FilesSliceTest {

    /**
     * Size of the stored blob.
     */
    private static final int SIZE = 3000;

    /**
     * Storage.
     */
    private Storage storage;

    /**
     * Slice under test.
     */
    private Slice slice;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        this.storage.save(
            new Key.From("dir", "b.bin"), new Content.From(new byte[FilesSliceTest.SIZE])
        ).join();
        this.slice = new FilesSlice(
            this.storage, Policy.FREE, (username, password) -> Optional.of(new AuthUser(username, "test")),
            FilesSlice.ANY_REPO, Optional.empty()
        );
    }

    @Test
    void headReportsStoredContentLength() {
        MatcherAssert.assertThat(
            this.head("/dir/b.bin").headers().values("Content-Length"),
            new IsEqual<>(List.of(String.valueOf(FilesSliceTest.SIZE)))
        );
    }

    @Test
    void headWithMetaReportsStoredContentLength() {
        MatcherAssert.assertThat(
            this.head("/dir/b.bin?meta=true").headers().values("Content-Length"),
            new IsEqual<>(List.of(String.valueOf(FilesSliceTest.SIZE)))
        );
    }

    @Test
    void trailingSlashGetListsWithoutAcceptHeader() {
        final Response rsp = this.get("/dir/", FilesSliceTest.auth());
        MatcherAssert.assertThat(
            "Directory GET without Accept answers 200",
            rsp.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "Directory GET without Accept is a plain-text listing",
            rsp.headers().values("Content-Type"),
            new IsEqual<>(List.of("text/plain"))
        );
        MatcherAssert.assertThat(
            "Listing names the stored key",
            new String(rsp.body().asBytes(), StandardCharsets.UTF_8),
            new IsEqual<>("dir/b.bin")
        );
    }

    @Test
    void rootGetWithWildcardAcceptLists() {
        MatcherAssert.assertThat(
            this.get(
                "/", FilesSliceTest.auth().copy().add("Accept", "*/*")
            ).status(),
            new IsEqual<>(RsStatus.OK)
        );
    }

    @Test
    void browserDirectoryGetIsLeftToTheHtmlIndex() {
        MatcherAssert.assertThat(
            this.get(
                "/dir/",
                FilesSliceTest.auth().copy().add(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            ).status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
    }

    @Test
    void fileGetStillServesTheBlob() {
        MatcherAssert.assertThat(
            this.get("/dir/b.bin", FilesSliceTest.auth()).body().asBytes().length,
            new IsEqual<>(FilesSliceTest.SIZE)
        );
    }

    @Test
    void htmlListingLinksKeepTheRepositorySegment() {
        final String html = new String(
            this.get(
                "/dir/",
                FilesSliceTest.auth().copy()
                    .add("Accept", "text/html")
                    .add("X-FullPath", "/myrepo/dir/")
            ).body().asBytes(),
            StandardCharsets.UTF_8
        );
        MatcherAssert.assertThat(
            html,
            new StringContains("<a href=\"/myrepo/dir/b.bin\">dir/b.bin</a>")
        );
    }

    @Test
    void htmlListingLinksKeepTheClientPrefix() {
        final String html = new String(
            this.get(
                "/dir/",
                FilesSliceTest.auth().copy()
                    .add("Accept", "text/html")
                    .add("X-FullPath", "/myrepo/dir/")
                    .add("X-Original-Path", "/test_prefix/api/myrepo/dir/")
            ).body().asBytes(),
            StandardCharsets.UTF_8
        );
        MatcherAssert.assertThat(
            html,
            new StringContains("<a href=\"/test_prefix/api/myrepo/dir/b.bin\">")
        );
    }

    @Test
    void htmlListingEncodesAndEscapesNames() {
        this.storage.save(
            new Key.From("odd", "sp ace#<b>.txt"), new Content.From(new byte[1])
        ).join();
        final String html = new String(
            this.get(
                "/odd/",
                FilesSliceTest.auth().copy()
                    .add("Accept", "text/html")
                    .add("X-FullPath", "/myrepo/odd/")
            ).body().asBytes(),
            StandardCharsets.UTF_8
        );
        MatcherAssert.assertThat(
            html,
            new StringContains(
                "<a href=\"/myrepo/odd/sp%20ace%23%3Cb%3E.txt\">odd/sp ace#&lt;b&gt;.txt</a>"
            )
        );
    }

    /**
     * Basic credentials of a test user.
     * @return Headers
     */
    private static Headers auth() {
        return Headers.from(new Authorization.Basic("alice", "secret"));
    }

    /**
     * Issue a GET.
     * @param path Path
     * @param headers Headers
     * @return Response
     */
    private Response get(final String path, final Headers headers) {
        return this.slice.response(
            new RequestLine(RqMethod.GET, path), headers, Content.EMPTY
        ).join();
    }

    /**
     * Issue a HEAD.
     * @param path Path
     * @return Response
     */
    private Response head(final String path) {
        return this.slice.response(
            new RequestLine(RqMethod.HEAD, path), FilesSliceTest.auth(), Content.EMPTY
        ).join();
    }
}
