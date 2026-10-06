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

package com.auto1.pantera.composer.http.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.composer.AstoRepository;
import com.auto1.pantera.composer.ComposerBaseUrl;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ClientBaseUrl;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Served proxy metadata roots its dist links at the base resolved for the
 * request, whatever base the cached document was rewritten under.
 */
final class ComposerProxyClientBaseTest {

    /**
     * Package under test.
     */
    private static final String PKG = "vendor/package";

    /**
     * Cached document as the proxy stores it: version 1.0 was rewritten
     * under a base the proxy is no longer reached by, version 2.0 is as
     * upstream sent it.
     */
    private static final String CACHED = "{\"packages\":{\"" + PKG + "\":{"
        + "\"1.0\":{\"version\":\"1.0\",\"dist\":{\"type\":\"zip\","
        + "\"url\":\"http://legacy.example:8080/php_proxy/dist/vendor/package/1.0.zip\","
        + "\"original_url\":\"https://up.example/v1.zip\",\"shasum\":\"abc\"}},"
        + "\"2.0\":{\"version\":\"2.0\",\"dist\":{\"type\":\"zip\","
        + "\"url\":\"https://up.example/v2.zip\"}}}}}";

    @Test
    void cachedDistsAreReRootedAtTheStampedBase(@TempDir final Path dir) {
        final JsonObject served = ComposerProxyClientBaseTest.json(
            ComposerProxyClientBaseTest.serve(
                dir, Optional.of("http://legacy.example:8080/php_proxy"),
                Headers.from(ClientBaseUrl.HEADER, "https://packages.example.com/php_group")
            )
        ).getJsonObject("packages").getJsonObject(PKG);
        MatcherAssert.assertThat(
            "a dist rewritten earlier is re-rooted",
            served.getJsonObject("1.0").getJsonObject("dist").getString("url"),
            new IsEqual<>("https://packages.example.com/php_group/dist/vendor/package/1.0.zip")
        );
        MatcherAssert.assertThat(
            "its upstream URL is kept",
            served.getJsonObject("1.0").getJsonObject("dist").getString("original_url"),
            new IsEqual<>("https://up.example/v1.zip")
        );
        MatcherAssert.assertThat(
            "other dist fields are kept",
            served.getJsonObject("1.0").getJsonObject("dist").getString("shasum"),
            new IsEqual<>("abc")
        );
        MatcherAssert.assertThat(
            "an upstream dist is rewritten under the same base",
            served.getJsonObject("2.0").getJsonObject("dist").getString("url"),
            new IsEqual<>("https://packages.example.com/php_group/dist/vendor/package/2.0.zip")
        );
    }

    @Test
    void configuredUrlPinsTheBaseWhenNothingIsStamped(@TempDir final Path dir) {
        MatcherAssert.assertThat(
            ComposerProxyClientBaseTest.json(
                ComposerProxyClientBaseTest.serve(
                    dir, Optional.of("http://pinned.example/php_proxy"), new Headers().add("Host", "h")
                )
            ).getJsonObject("packages").getJsonObject(PKG)
                .getJsonObject("2.0").getJsonObject("dist").getString("url"),
            new IsEqual<>("http://pinned.example/php_proxy/dist/vendor/package/2.0.zip")
        );
    }

    @Test
    void requestOriginIsUsedWithoutUrlOrStamp(@TempDir final Path dir) {
        MatcherAssert.assertThat(
            ComposerProxyClientBaseTest.json(
                ComposerProxyClientBaseTest.serve(
                    dir, Optional.empty(), new Headers().add("Host", "packages.example.com")
                )
            ).getJsonObject("packages").getJsonObject(PKG)
                .getJsonObject("1.0").getJsonObject("dist").getString("url"),
            new IsEqual<>("http://packages.example.com/php_proxy/dist/vendor/package/1.0.zip")
        );
    }

    @Test
    void responseVariesByHost(@TempDir final Path dir) {
        MatcherAssert.assertThat(
            ComposerProxyClientBaseTest.serve(dir, Optional.empty(), new Headers().add("Host", "h"))
                .headers().single("Vary").getValue(),
            new IsEqual<>("Host")
        );
    }

    /**
     * Serve the cached p2 document of {@link #PKG} from a fresh file storage.
     *
     * @param dir Storage directory
     * @param configured Configured {@code url:}, or empty
     * @param headers Request headers
     * @return Response
     */
    private static Response serve(
        final Path dir, final Optional<String> configured, final Headers headers
    ) {
        final Storage storage = new FileStorage(dir);
        storage.save(
            new Key.From(PKG + ".json"), new Content.From(CACHED.getBytes(StandardCharsets.UTF_8))
        ).join();
        final Slice upstream = (line, hdrs, body) -> CompletableFuture.failedFuture(
            new AssertionError("upstream must not be called on cache hit")
        );
        return new CachedProxySlice(
            upstream,
            new AstoRepository(storage),
            new FromStorageCache(storage),
            Optional.empty(),
            "php_proxy",
            new ComposerBaseUrl(configured, "php_proxy"),
            "https://packagist.org"
        ).response(
            new RequestLine(RqMethod.GET, "/p2/" + PKG + ".json"), headers, Content.EMPTY
        ).join();
    }

    /**
     * Parse a response body.
     *
     * @param response Response
     * @return JSON object
     */
    private static JsonObject json(final Response response) {
        return Json.createReader(
            new StringReader(new String(response.body().asBytes(), StandardCharsets.UTF_8))
        ).readObject();
    }
}
