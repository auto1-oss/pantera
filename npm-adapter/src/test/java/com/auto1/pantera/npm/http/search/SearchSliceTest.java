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
package com.auto1.pantera.npm.http.search;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.index.ArtifactDocument;
import java.time.Instant;
import java.util.List;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link SearchSlice}.
 *
 * <p>Uses {@link FakeArtifactIndex} rather than a database — proves
 * {@code SearchSlice} correctly wires text/size/from into the shared
 * {@link com.auto1.pantera.index.ArtifactIndex} and maps the returned
 * documents into the npm {@code /-/v1/search} response schema, without a
 * live DB (unit test doctrine — no Docker/DB in {@code *Test.java}).</p>
 *
 * @since 1.1
 */
final class SearchSliceTest {

    /**
     * Repository name results are scoped to.
     */
    private static final String REPO = "npm-hosted";

    /**
     * Fake index backing the slice under test.
     */
    private FakeArtifactIndex index;

    /**
     * Test slice.
     */
    private SearchSlice slice;

    @BeforeEach
    void setUp() {
        this.index = new FakeArtifactIndex(
            List.of(
                SearchSliceTest.doc("express", "4.18.2"),
                SearchSliceTest.doc("lodash", "4.17.21"),
                SearchSliceTest.doc("express-session", "1.17.3")
            )
        );
        this.slice = new SearchSlice(this.index, SearchSliceTest.REPO);
    }

    @Test
    void findsPackagesByName() {
        final Response response = this.response("GET /-/v1/search?text=express HTTP/1.1");
        MatcherAssert.assertThat(
            "Response status is OK",
            response.status(),
            new IsEqual<>(RsStatus.OK)
        );
        final JsonObject body = SearchSliceTest.jsonBody(response);
        MatcherAssert.assertThat(
            "Both express packages are returned",
            body.getInt("total"),
            new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "Scoped to this repository",
            this.index.lastRepoName,
            new IsEqual<>(SearchSliceTest.REPO)
        );
        MatcherAssert.assertThat(
            "Scoped to the npm repo type",
            this.index.lastRepoType,
            new IsEqual<>("npm")
        );
    }

    @Test
    void mapsFirstResultToNpmObjectSchema() {
        final Response response = this.response("GET /-/v1/search?text=lodash HTTP/1.1");
        final JsonObject pkg = SearchSliceTest.jsonBody(response)
            .getJsonArray("objects").getJsonObject(0).getJsonObject("package");
        MatcherAssert.assertThat(
            "Package name is mapped",
            pkg.getString("name"),
            new IsEqual<>("lodash")
        );
        MatcherAssert.assertThat(
            "Package version is mapped",
            pkg.getString("version"),
            new IsEqual<>("4.17.21")
        );
    }

    @Test
    void requiresQueryParameter() {
        final Response response = this.response("GET /-/v1/search HTTP/1.1");
        MatcherAssert.assertThat(
            "Response status is BAD_REQUEST",
            response.status(),
            new IsEqual<>(RsStatus.BAD_REQUEST)
        );
    }

    @Test
    void returnsEmptyForNoMatches() {
        final Response response = this.response("GET /-/v1/search?text=nonexistent HTTP/1.1");
        MatcherAssert.assertThat(
            "Response status is OK",
            response.status(),
            new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "No matches reported",
            SearchSliceTest.jsonBody(response).getInt("total"),
            new IsEqual<>(0)
        );
    }

    @Test
    void honoursSizeAndFromForPagination() {
        final Response response = this.response(
            "GET /-/v1/search?text=express&size=1&from=1 HTTP/1.1"
        );
        MatcherAssert.assertThat(
            "One result returned per the size/from window",
            SearchSliceTest.jsonBody(response).getJsonArray("objects").size(),
            new IsEqual<>(1)
        );
    }

    /**
     * Drive the slice for a raw request line, consuming the body per the
     * reactive-bodies rule.
     *
     * @param requestLine Raw HTTP request line
     * @return The slice's response
     */
    private Response response(final String requestLine) {
        return this.slice.response(
            RequestLine.from(requestLine), Headers.EMPTY, Content.EMPTY
        ).join();
    }

    /**
     * Build a test artifact document for a given npm package name/version.
     *
     * @param name Package name
     * @param version Package version
     * @return Artifact document
     */
    private static ArtifactDocument doc(final String name, final String version) {
        return new ArtifactDocument(
            "npm", SearchSliceTest.REPO, name, name, version, 100L, Instant.now(), "tester"
        );
    }

    /**
     * Parse a response body as JSON.
     *
     * @param response Response to read
     * @return Parsed JSON object
     */
    private static JsonObject jsonBody(final Response response) {
        return Json.createReader(
            new java.io.StringReader(response.body().asString())
        ).readObject();
    }
}
