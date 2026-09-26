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
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.files.FilesSlice;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.CombinedAuthzSliceWrap;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.hamcrest.core.StringStartsWith;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A local {@code file} repository wired the way production wires it
 * ({@link FilesSlice} inside a gated {@link BrowsableSlice}) must keep
 * serving the HTML directory index to browsers while curl-style clients
 * get the plain-text listing for a trailing-slash path.
 *
 * @since 2.2.9
 */
final class FilesSliceBrowserListingTest {

    /**
     * Accept header a real browser sends for a navigation.
     */
    private static final String BROWSER_ACCEPT =
        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

    /**
     * Repository name.
     */
    private static final String REPO = "files";

    /**
     * Slice under test.
     */
    private Slice slice;

    @BeforeEach
    void setUp() {
        final Storage storage = new InMemoryStorage();
        storage.save(
            new Key.From("dir", "b.bin"),
            new Content.From("x".getBytes(StandardCharsets.UTF_8))
        ).join();
        final Authentication basic = (user, pwd) ->
            Optional.of(new AuthUser(user, "test"));
        final UnaryOperator<Slice> gate = browse ->
            new CombinedAuthzSliceWrap(
                browse,
                basic,
                token -> CompletableFuture.completedFuture(Optional.<AuthUser>empty()),
                new OperationControl(
                    Policy.FREE,
                    new AdapterBasicPermission(
                        FilesSliceBrowserListingTest.REPO, Action.Standard.READ
                    )
                )
            );
        this.slice = new BrowsableSlice(
            new FilesSlice(
                storage, Policy.FREE, basic, FilesSliceBrowserListingTest.REPO,
                Optional.empty()
            ),
            storage,
            gate
        );
    }

    @Test
    void browserGetsHtmlIndexForTrailingSlashPath() {
        final Response rsp = this.get(
            new Header("Accept", FilesSliceBrowserListingTest.BROWSER_ACCEPT)
        );
        MatcherAssert.assertThat(
            "browser directory GET answers 200",
            rsp.status().code(), new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "browser directory GET is rendered as HTML, not a text/plain dump",
            rsp.headers().values("Content-Type").get(0),
            new StringStartsWith("text/html")
        );
    }

    @Test
    void curlWithoutAcceptGetsPlainTextListing() {
        final Response rsp = this.get();
        MatcherAssert.assertThat(
            "curl directory GET is a plain-text listing",
            rsp.headers().values("Content-Type").get(0),
            new StringStartsWith("text/plain")
        );
        MatcherAssert.assertThat(
            "plain-text listing names the stored key",
            new String(rsp.body().asBytes(), StandardCharsets.UTF_8),
            new StringContains("dir/b.bin")
        );
    }

    @Test
    void curlWithWildcardAcceptGetsPlainTextListing() {
        MatcherAssert.assertThat(
            this.get(new Header("Accept", "*/*"))
                .headers().values("Content-Type").get(0),
            new StringStartsWith("text/plain")
        );
    }

    private Response get(final Header... extra) {
        final Headers headers = Headers.from(new Authorization.Basic("alice", "secret"));
        for (final Header hdr : extra) {
            headers.add(hdr);
        }
        return this.slice.response(
            new RequestLine(RqMethod.GET, "/dir/"), headers, Content.EMPTY
        ).join();
    }
}
