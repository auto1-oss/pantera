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
package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.headers.ClientBaseUrl;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.apache.commons.io.IOUtils;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.StringReader;
import java.net.URI;
import java.net.URL;

/**
 * I3 regression coverage: {@code npm.http.DownloadPackageSlice} is the LOCAL
 * npm packument route — hit directly for a stand-alone local repository, and
 * hit as a group member's own slice when a group walks to a local member.
 * Before this fix it rewrote tarball URLs against this repository's
 * configured {@code url:} unconditionally, ignoring the {@code
 * X-Pantera-Client-Base} header {@code SliceByPath} stamps for the
 * repository the client actually addressed — exactly the bug Mechanism A
 * (WS8) exists to fix, still live on the most common {@code npm install}
 * path (a group whose winning member is local).
 *
 * <p>Mirrors {@code npm.proxy.http.DownloadPackageSliceClientBaseTest}: drives
 * a real packument request through the slice and asserts on the served
 * {@code dist.tarball} value and {@code Vary} header, rather than widening
 * any private method for direct access.</p>
 */
final class DownloadPackageSliceClientBaseTest {

    /**
     * Package the fixture packument lives under.
     */
    private static final String PKG = "@hello/simple-npm-project";

    /**
     * Relative tarball path stored in the fixture packument for version
     * 1.0.1.
     */
    private static final String TARBALL_SUFFIX =
        "/@hello/simple-npm-project/-/@hello/simple-npm-project-1.0.1.tgz";

    @Test
    void stampedHeaderBeatsConfiguredUrl() throws Exception {
        // A group stamps its own base; the local member's configured url:
        // must not win.
        final Response response = this.responseFor(
            Headers.from(ClientBaseUrl.HEADER, "https://h/api/npm/npm_group")
        );
        MatcherAssert.assertThat(
            tarballOf(response),
            new IsEqual<>("https://h/api/npm/npm_group" + TARBALL_SUFFIX)
        );
    }

    @Test
    void configuredUrlUsedWhenNoHeaderStamped() throws Exception {
        final Response response = this.responseFor(new Headers().add("Host", "h"));
        MatcherAssert.assertThat(
            tarballOf(response),
            new IsEqual<>("http://localhost:8081/npm_local" + TARBALL_SUFFIX)
        );
    }

    @Test
    void stampedHeaderResponseCarriesVary() throws Exception {
        final Response response = this.responseFor(
            Headers.from(ClientBaseUrl.HEADER, "https://h/api/npm/npm_group")
        );
        MatcherAssert.assertThat(
            response.headers().single("Vary").getValue(),
            new IsEqual<>("Host")
        );
    }

    /**
     * Drive a packument request through a freshly-seeded slice.
     *
     * @param headers Request headers
     * @return Response
     * @throws Exception On any I/O failure building the response
     */
    private Response responseFor(final Headers headers) throws Exception {
        final Storage storage = new InMemoryStorage();
        storage.save(
            new Key.From("@hello", "simple-npm-project", "meta.json"),
            new Content.From(
                IOUtils.resourceToByteArray("/storage/@hello/simple-npm-project/meta.json")
            )
        ).join();
        final DownloadPackageSlice slice = new DownloadPackageSlice(
            DownloadPackageSliceClientBaseTest.url("http://localhost:8081/npm_local"), storage
        );
        return slice.response(
            new RequestLine(RqMethod.GET, "/" + PKG), headers, Content.EMPTY
        ).get();
    }

    /**
     * Extract {@code dist.tarball} for version 1.0.1 from a packument
     * response body.
     *
     * @param response Response to read
     * @return Tarball URL
     */
    private static String tarballOf(final Response response) {
        final String body = response.body().asString();
        final JsonObject packument = Json.createReader(new StringReader(body)).readObject();
        return packument.getJsonObject("versions").getJsonObject("1.0.1")
            .getJsonObject("dist").getString("tarball");
    }

    /**
     * @param value Absolute URL string
     * @return Parsed URL
     * @throws Exception On a malformed URL
     */
    private static URL url(final String value) throws Exception {
        return URI.create(value).toURL();
    }
}
