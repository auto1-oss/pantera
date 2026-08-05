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
package com.auto1.pantera.npm.proxy.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.headers.ClientBaseUrl;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.npm.proxy.NpmProxy;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.StringReader;
import java.net.URI;
import java.net.URL;
import java.util.Optional;

/**
 * Regression coverage for the tarball-prefix precedence in
 * {@link DownloadPackageSlice}: the base stamped by {@code SliceByPath} for
 * the repository the client actually addressed MUST win over this
 * repository's configured {@code url:} — otherwise a group member hands out
 * its own URLs instead of the group's, and strict clients (corepack) reject
 * the mismatch. Falling back to the request's own origin (honouring
 * forwarding headers) is the last resort, for repositories with no
 * configured {@code url:} at all.
 *
 * <p>Drives a real packument request through the slice (mirroring
 * {@code DownloadPackageSliceTest} / {@code DownloadPackageSliceCooldownEtagTest}'s
 * {@code InMemoryStorage} + stub-remote {@code NpmProxy} wiring) and asserts
 * on the served {@code dist.tarball} value, rather than widening the private
 * {@code getTarballPrefix} for direct access.</p>
 */
final class DownloadPackageSliceClientBaseTest {

    /**
     * Package the fixture packument lives under.
     */
    private static final String PKG = "@hello/simple-npm-project";

    /**
     * Relative tarball path stored in the fixture packument for version
     * 1.0.1 — {@code ClientContent} prepends the resolved prefix to this.
     */
    private static final String TARBALL_SUFFIX =
        "/@hello/simple-npm-project/-/@hello/simple-npm-project-1.0.1.tgz";

    @Test
    void stampedHeaderBeatsConfiguredUrl() throws Exception {
        // A group stamps its own base; the member's configured url: must not win.
        MatcherAssert.assertThat(
            this.tarballFor(
                Headers.from(ClientBaseUrl.HEADER, "https://h/api/npm/npm_group"),
                Optional.of(url("http://localhost:8081/npm_proxy"))
            ),
            new IsEqual<>("https://h/api/npm/npm_group" + TARBALL_SUFFIX)
        );
    }

    @Test
    void configuredUrlUsedWhenNoHeaderStamped() throws Exception {
        MatcherAssert.assertThat(
            this.tarballFor(
                Headers.from("Host", "h"),
                Optional.of(url("http://localhost:8081/npm_proxy"))
            ),
            new IsEqual<>("http://localhost:8081/npm_proxy" + TARBALL_SUFFIX)
        );
    }

    @Test
    void hostFallbackHonoursForwardedProto() throws Exception {
        final Headers headers = new Headers()
            .add("Host", "reg.example.com")
            .add("X-Forwarded-Proto", "https");
        MatcherAssert.assertThat(
            this.tarballFor(headers, Optional.empty()),
            new IsEqual<>("https://reg.example.com" + TARBALL_SUFFIX)
        );
    }

    /**
     * Drive a packument request through a freshly-seeded slice and return
     * the served {@code dist.tarball} for version 1.0.1.
     *
     * @param headers Request headers
     * @param baseUrl This repository's configured {@code url:}, if any
     * @return Served tarball URL
     * @throws Exception On any I/O failure building the response
     */
    private String tarballFor(final Headers headers, final Optional<URL> baseUrl) throws Exception {
        final Storage storage = new InMemoryStorage();
        this.saveFilesToStorage(storage);
        final DownloadPackageSlice slice = new DownloadPackageSlice(
            new NpmProxy(storage, new SliceSimple(ResponseBuilder.notFound().build())),
            new PackagePath(""),
            baseUrl
        );
        final String body = slice.response(
            new RequestLine(RqMethod.GET, "/" + PKG),
            headers,
            Content.EMPTY
        ).get().body().asString();
        final JsonObject packument = Json.createReader(new StringReader(body)).readObject();
        return packument.getJsonObject("versions").getJsonObject("1.0.1")
            .getJsonObject("dist").getString("tarball");
    }

    /**
     * Seed the upstream packument + refresh metadata the proxy needs to
     * serve the full-metadata path (matches {@code DownloadPackageSliceTest}).
     *
     * @param storage Backing storage
     */
    private void saveFilesToStorage(final Storage storage) {
        final String metajsonpath = PKG + "/meta.json";
        storage.save(
            new Key.From(metajsonpath),
            new Content.From(
                new TestResource(String.format("storage/%s", metajsonpath)).asBytes()
            )
        ).join();
        storage.save(
            new Key.From("@hello", "simple-npm-project", "meta.meta"),
            new Content.From(
                Json.createObjectBuilder()
                    .add("last-modified", "2020-05-13T16:30:30+01:00")
                    .add("last-refreshed", "2020-05-13T16:30:30+01:00")
                    .build()
                    .toString()
                    .getBytes()
            )
        ).join();
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
