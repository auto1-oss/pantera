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
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The browse pages carry a hash-based {@code Content-Security-Policy}
 * that allowlists exactly their own inline blocks. These tests recompute
 * the SHA-256 of the inline content actually emitted in the HTML and
 * assert the CSP header advertises those exact hashes — any drift between
 * the emitted bytes and the hashed constants (which makes browsers render
 * the listing unstyled again) fails here.
 *
 * @since 2.2.1
 */
final class BrowsePageCspTest {

    @Test
    void filesystemBrowsePageDeclaresHashesMatchingItsInlineBlocks(
        @TempDir final Path tmp
    ) throws Exception {
        Files.createDirectories(tmp.resolve("com/example"));
        Files.writeString(tmp.resolve("com/example/artifact.jar"), "jar-bytes");
        final Response response = new FileSystemBrowseSlice(new FileStorage(tmp))
            .response(new RequestLine(RqMethod.GET, "/"), Headers.EMPTY, Content.EMPTY)
            .toCompletableFuture().join();
        MatcherAssert.assertThat(
            "browse response must be OK",
            response.status(),
            new IsEqual<>(RsStatus.OK)
        );
        final String html = new String(
            response.body().asBytesFuture().join(), StandardCharsets.UTF_8
        );
        final String csp = header(response, "Content-Security-Policy");
        MatcherAssert.assertThat(
            "CSP must allowlist the emitted style block by hash",
            csp,
            new StringContains("style-src 'sha256-" + sha256(inline(html, "style")) + "'")
        );
        MatcherAssert.assertThat(
            "CSP must allowlist the emitted script block by hash",
            csp,
            new StringContains("script-src 'sha256-" + sha256(inline(html, "script")) + "'")
        );
        MatcherAssert.assertThat(
            "everything else stays same-origin",
            csp,
            new StringContains("default-src 'self'")
        );
        MatcherAssert.assertThat(
            "inline event handlers are blocked by hash CSP and must not be emitted",
            html.contains("onclick="),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "sort controls must carry data-sort attributes for listener binding",
            html,
            new StringContains("data-sort=\"name\"")
        );
        MatcherAssert.assertThat(
            "sort listeners must be bound inside the hashed script block",
            inline(html, "script"),
            new StringContains("addEventListener")
        );
    }

    @Test
    void streamingBrowsePageDeclaresHashMatchingItsStyleBlock() {
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(
            new Key.From("dir/file.txt"),
            new Content.From("data".getBytes(StandardCharsets.UTF_8))
        ).join();
        final Response response = new StreamingBrowseSlice(storage)
            .response(new RequestLine(RqMethod.GET, "/"), Headers.EMPTY, Content.EMPTY)
            .toCompletableFuture().join();
        MatcherAssert.assertThat(
            "browse response must be OK",
            response.status(),
            new IsEqual<>(RsStatus.OK)
        );
        final String html = new String(
            response.body().asBytesFuture().join(), StandardCharsets.UTF_8
        );
        final String csp = header(response, "Content-Security-Policy");
        MatcherAssert.assertThat(
            "CSP must allowlist the emitted style block by hash",
            csp,
            new IsEqual<>(
                "default-src 'self'; style-src 'sha256-"
                    + sha256(inline(html, "style")) + "'"
            )
        );
        MatcherAssert.assertThat(
            "inline style attributes are blocked by hash CSP and must not be emitted",
            html.contains(" style=\""),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "streaming page carries no script",
            html.contains("<script>"),
            new IsEqual<>(false)
        );
    }

    @Test
    void constantsRoundTripTheirOwnHashes() {
        MatcherAssert.assertThat(
            "FS CSP embeds the hash of the FS style constant",
            BrowsePageCsp.FS_CSP,
            new StringContains("'sha256-" + sha256(BrowsePageCsp.FS_STYLE) + "'")
        );
        MatcherAssert.assertThat(
            "FS CSP embeds the hash of the FS script constant",
            BrowsePageCsp.FS_CSP,
            new StringContains("'sha256-" + sha256(BrowsePageCsp.FS_SCRIPT) + "'")
        );
        MatcherAssert.assertThat(
            "streaming CSP embeds the hash of the streaming style constant",
            BrowsePageCsp.STREAMING_CSP,
            new StringContains("'sha256-" + sha256(BrowsePageCsp.STREAMING_STYLE) + "'")
        );
        MatcherAssert.assertThat(
            "the FS script must not reintroduce inline onclick attributes",
            BrowsePageCsp.FS_SCRIPT.contains("onclick="),
            new IsNot<>(new IsEqual<>(true))
        );
    }

    /**
     * Extract the inner text of the first {@code <tag>...</tag>} block —
     * exactly the bytes a CSP hash source covers.
     *
     * @param html Full page
     * @param tag Tag name without brackets
     * @return Inner block content
     */
    private static String inline(final String html, final String tag) {
        final String open = "<" + tag + ">";
        final int start = html.indexOf(open) + open.length();
        final int end = html.indexOf("</" + tag + ">", start);
        if (start < open.length() || end < 0) {
            throw new IllegalStateException("no <" + tag + "> block in page");
        }
        return html.substring(start, end);
    }

    /**
     * Standard-base64 SHA-256 as used in CSP hash sources.
     *
     * @param text Block content
     * @return Base64 digest
     */
    private static String sha256(final String text) {
        try {
            return Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8))
            );
        } catch (final java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * First value of a response header.
     *
     * @param response Response
     * @param name Header name (case-insensitive)
     * @return Header value
     */
    private static String header(final Response response, final String name) {
        return response.headers().stream()
            .filter(h -> h.getKey().equalsIgnoreCase(name))
            .map(java.util.Map.Entry::getValue)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(name + " header missing"));
    }
}
