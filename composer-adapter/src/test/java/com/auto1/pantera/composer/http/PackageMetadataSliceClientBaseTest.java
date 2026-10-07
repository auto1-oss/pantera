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
package com.auto1.pantera.composer.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.composer.AstoRepository;
import com.auto1.pantera.composer.ComposerBaseUrl;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.headers.ClientBaseUrl;
import com.auto1.pantera.http.headers.ClientBaseUrlSettings;
import com.auto1.pantera.http.headers.ClientBaseUrlSettingsRegistry;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Served metadata of a hosted Composer repository carries links under the
 * base resolved for the request, not the base frozen into storage.
 */
final class PackageMetadataSliceClientBaseTest {

    /**
     * Stored dist URL, frozen under a host the repository is no longer reached by.
     */
    private static final String STORED =
        "https://legacy.example.com/artifactory/php-api/artifacts/acme/api/1.0/acme-api-1.0.zip";

    /**
     * Path of the archive inside the repository.
     */
    private static final String ARCHIVE = "/artifacts/acme/api/1.0/acme-api-1.0.zip";

    @AfterEach
    void tearDown() {
        ClientBaseUrlSettingsRegistry.uninstall();
    }

    @Test
    void stampedHeaderBeatsConfiguredUrl() throws Exception {
        MatcherAssert.assertThat(
            PackageMetadataSliceClientBaseTest.distOf(
                this.p2(
                    Headers.from(ClientBaseUrl.HEADER, "https://packages.example.com/php_group"),
                    Optional.of("http://legacy.example.com/artifactory")
                )
            ),
            new IsEqual<>("https://packages.example.com/php_group" + ARCHIVE)
        );
    }

    @Test
    void configuredUrlUsedWhenNoHeaderStamped() throws Exception {
        MatcherAssert.assertThat(
            PackageMetadataSliceClientBaseTest.distOf(
                this.p2(
                    new Headers().add("Host", "h"),
                    Optional.of("http://configured.example.com/artifactory")
                )
            ),
            new IsEqual<>("http://configured.example.com/artifactory/php-api" + ARCHIVE)
        );
    }

    @Test
    void requestOriginUsedWhenNeitherUrlNorStampIsPresent() throws Exception {
        MatcherAssert.assertThat(
            PackageMetadataSliceClientBaseTest.distOf(
                this.p2(new Headers().add("Host", "packages.example.com"), Optional.empty())
            ),
            new IsEqual<>("http://packages.example.com/php-api" + ARCHIVE)
        );
    }

    @Test
    void twoHostsGetTheirOwnDistUrlsFromOneRepository() throws Exception {
        final String first = PackageMetadataSliceClientBaseTest.distOf(
            this.p2(
                Headers.from(ClientBaseUrl.HEADER, "https://packages.example.com/php-api"),
                Optional.empty()
            )
        );
        final String second = PackageMetadataSliceClientBaseTest.distOf(
            this.p2(
                Headers.from(ClientBaseUrl.HEADER, "https://legacy.example.com/artifactory/php-api"),
                Optional.empty()
            )
        );
        MatcherAssert.assertThat(
            "first host must get its own base",
            first,
            new IsEqual<>("https://packages.example.com/php-api" + ARCHIVE)
        );
        MatcherAssert.assertThat(
            "second host must get its own base from the same repository",
            second,
            new IsEqual<>("https://legacy.example.com/artifactory/php-api" + ARCHIVE)
        );
    }

    @Test
    void responseVariesByHost() throws Exception {
        MatcherAssert.assertThat(
            this.p2(new Headers().add("Host", "h"), Optional.empty())
                .headers().single("Vary").getValue(),
            new IsEqual<>("Host")
        );
    }

    @Test
    void responseOmitsVaryWhenCanonicalBaseUrlIsSet() throws Exception {
        ClientBaseUrlSettingsRegistry.install(
            () -> new ClientBaseUrlSettings(false, List.of(), "http://canonical.example.com")
        );
        MatcherAssert.assertThat(
            this.p2(
                Headers.from(ClientBaseUrl.HEADER, "http://canonical.example.com/php-api"),
                Optional.empty()
            ).headers().find("Vary").isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    void rootMetadataUrlFollowsTheRequest() throws Exception {
        final Storage storage = PackageMetadataSliceClientBaseTest.storage();
        final Response response = new PackageMetadataSlice(
            new AstoRepository(storage, Optional.of("http://legacy.example.com/artifactory"), Optional.of("php-api")),
            new ComposerBaseUrl(Optional.empty(), "php-api")
        ).response(
            new RequestLine(RqMethod.GET, "/packages.json"),
            Headers.from(ClientBaseUrl.HEADER, "https://packages.example.com/php-api"),
            Content.EMPTY
        ).get();
        MatcherAssert.assertThat(
            PackageMetadataSliceClientBaseTest.json(response).getString("metadata-url"),
            new IsEqual<>("https://packages.example.com/php-api/p2/%package%.json")
        );
    }

    @Test
    void invalidStoredMetadataIsServedAsStored() throws Exception {
        final Storage storage = new InMemoryStorage();
        storage.save(
            new Key.From("p2", "acme", "api.json"),
            new Content.From("not json".getBytes(StandardCharsets.UTF_8))
        ).join();
        MatcherAssert.assertThat(
            new PackageMetadataSlice(
                new AstoRepository(storage), new ComposerBaseUrl(Optional.empty(), "php-api")
            ).response(
                new RequestLine(RqMethod.GET, "/p2/acme/api.json"), Headers.EMPTY, Content.EMPTY
            ).get().body().asString(),
            new IsEqual<>("not json")
        );
    }

    /**
     * Request the p2 file of {@code acme/api}.
     *
     * @param headers Request headers
     * @param configured Configured {@code url:}, or empty
     * @return Response
     * @throws Exception On failure
     */
    private Response p2(final Headers headers, final Optional<String> configured) throws Exception {
        return new PackageMetadataSlice(
            new AstoRepository(PackageMetadataSliceClientBaseTest.storage()),
            new ComposerBaseUrl(configured, "php-api")
        ).response(new RequestLine(RqMethod.GET, "/p2/acme/api.json"), headers, Content.EMPTY).get();
    }

    /**
     * @return Storage holding the p2 file of {@code acme/api} with a stored dist URL
     */
    private static Storage storage() {
        final Storage storage = new InMemoryStorage();
        storage.save(
            new Key.From("p2", "acme", "api.json"),
            new Content.From(
                ("{\"packages\":{\"acme/api\":{\"1.0\":{\"name\":\"acme/api\",\"version\":\"1.0\","
                    + "\"dist\":{\"type\":\"zip\",\"url\":\"" + STORED + "\"}}}}}")
                    .getBytes(StandardCharsets.UTF_8)
            )
        ).join();
        return storage;
    }

    /**
     * @param response p2 response
     * @return Served dist URL of version 1.0
     */
    private static String distOf(final Response response) {
        return PackageMetadataSliceClientBaseTest.json(response)
            .getJsonObject("packages").getJsonObject("acme/api").getJsonObject("1.0")
            .getJsonObject("dist").getString("url");
    }

    /**
     * @param response Response
     * @return Body as JSON object
     */
    private static JsonObject json(final Response response) {
        return Json.createReader(new StringReader(response.body().asString())).readObject();
    }
}
