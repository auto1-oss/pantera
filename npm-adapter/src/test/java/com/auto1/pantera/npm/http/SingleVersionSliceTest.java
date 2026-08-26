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
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.ClientBaseUrlSettings;
import com.auto1.pantera.http.headers.ClientBaseUrlSettingsRegistry;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.npm.PerVersionLayout;
import java.net.URI;
import java.util.List;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link SingleVersionSlice} (WS4-npm.7): {@code GET /<pkg>/<version>}
 * and {@code GET /<pkg>/latest} for hosted repositories.
 */
final class SingleVersionSliceTest {

    private Storage storage;

    private PerVersionLayout layout;

    @AfterEach
    void tearDown() {
        ClientBaseUrlSettingsRegistry.uninstall();
    }

    @BeforeEach
    void init() throws Exception {
        this.storage = new InMemoryStorage();
        this.layout = new PerVersionLayout(this.storage);
        final Key pkg = new Key.From("simple-npm-project");
        this.layout.addVersion(
            pkg, "1.0.0",
            Json.createObjectBuilder()
                .add("name", "simple-npm-project")
                .add("version", "1.0.0")
                .add("dist", Json.createObjectBuilder()
                    .add("tarball", "http://oldhost/simple-npm-project/-/simple-npm-project-1.0.0.tgz")
                    .build())
                .build()
        ).toCompletableFuture().join();
        this.layout.mergeDistTags(
            pkg, Json.createObjectBuilder().add("latest", "1.0.0").build()
        ).toCompletableFuture().join();
        this.layout.addVersion(
            pkg, "1.1.0-beta.1",
            Json.createObjectBuilder()
                .add("name", "simple-npm-project")
                .add("version", "1.1.0-beta.1")
                .add("dist", Json.createObjectBuilder()
                    .add("tarball", "http://oldhost/simple-npm-project/-/simple-npm-project-1.1.0-beta.1.tgz")
                    .build())
                .build()
        ).toCompletableFuture().join();
        this.layout.mergeDistTags(
            pkg, Json.createObjectBuilder().add("beta", "1.1.0-beta.1").build()
        ).toCompletableFuture().join();
    }

    @Test
    void resolvesExplicitVersion() throws Exception {
        final JsonObject body = this.get("/simple-npm-project/1.0.0");
        MatcherAssert.assertThat(
            body.getString("version"),
            new IsEqual<>("1.0.0")
        );
    }

    @Test
    void rewritesTarballUrlToPantera() throws Exception {
        final JsonObject body = this.get("/simple-npm-project/1.0.0");
        MatcherAssert.assertThat(
            "the served tarball URL points at Pantera, not whatever host was stored",
            body.getJsonObject("dist").getString("tarball"),
            new IsEqual<>(
                "http://pantera.local/simple-npm-project/-/simple-npm-project-1.0.0.tgz"
            )
        );
    }

    @Test
    void resolvesLatestThroughDistTagsSidecar() throws Exception {
        final JsonObject body = this.get("/simple-npm-project/latest");
        MatcherAssert.assertThat(
            body.getString("version"),
            new IsEqual<>("1.0.0")
        );
    }

    @Test
    void resolvesCustomTag() throws Exception {
        final JsonObject body = this.get("/simple-npm-project/beta");
        MatcherAssert.assertThat(
            body.getString("version"),
            new IsEqual<>("1.1.0-beta.1")
        );
    }

    @Test
    void returnsNotFoundForUnknownVersion() {
        MatcherAssert.assertThat(
            new SingleVersionSlice(url(), this.storage, "npm-local").response(
                new RequestLine(RqMethod.GET, "/simple-npm-project/9.9.9"),
                Headers.EMPTY, Content.EMPTY
            ).join().status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
    }

    @Test
    void returnsNotFoundForUnknownPackage() {
        MatcherAssert.assertThat(
            new SingleVersionSlice(url(), this.storage, "npm-local").response(
                new RequestLine(RqMethod.GET, "/never-published/1.0.0"),
                Headers.EMPTY, Content.EMPTY
            ).join().status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
    }

    @Test
    void stripsInternalPublishTimeMarker() throws Exception {
        final JsonObject body = this.get("/simple-npm-project/1.0.0");
        MatcherAssert.assertThat(
            "the internal _publishTime marker never reaches the client",
            body.containsKey("_publishTime"),
            new IsEqual<>(false)
        );
    }

    @Test
    void carriesVaryHostSoASharedCacheCannotCrossServeIt() {
        // I2: the served dist.tarball depends on the request's Host (or the
        // internal stamped-base header derived from it), so a shared cache
        // must not treat requests from different clients as interchangeable.
        final String vary = new SingleVersionSlice(url(), this.storage, "npm-local").response(
            new RequestLine(RqMethod.GET, "/simple-npm-project/1.0.0"),
            Headers.EMPTY, Content.EMPTY
        ).join().headers().single("Vary").getValue();
        MatcherAssert.assertThat(vary, new IsEqual<>("Host"));
    }

    @Test
    void omitsVaryEntirelyWhenCanonicalBaseUrlIsSet() {
        // Once a canonical base URL is configured, Host no longer
        // participates in deriving the served tarball URL, so
        // ClientBaseUrl#varyHeaderValue() returns "" -- the response must
        // omit Vary entirely rather than send a malformed "Vary: ".
        ClientBaseUrlSettingsRegistry.install(
            () -> new ClientBaseUrlSettings(false, List.of(), "http://canonical.example.com")
        );
        final Response response = new SingleVersionSlice(url(), this.storage, "npm-local").response(
            new RequestLine(RqMethod.GET, "/simple-npm-project/1.0.0"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            response.headers().find("Vary").isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    void notModifiedResponseStillCarriesVaryWhenCanonicalBaseUrlIsUnset() {
        final SingleVersionSlice slice = new SingleVersionSlice(url(), this.storage, "npm-local");
        final Response first = slice.response(
            new RequestLine(RqMethod.GET, "/simple-npm-project/1.0.0"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        final String etag = first.headers().single("ETag").getValue();
        final Response revalidated = slice.response(
            new RequestLine(RqMethod.GET, "/simple-npm-project/1.0.0"),
            Headers.from("If-None-Match", etag), Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "304 on a matching If-None-Match", revalidated.status(), new IsEqual<>(RsStatus.NOT_MODIFIED)
        );
        MatcherAssert.assertThat(
            "304 carries the same Vary as the 200 it revalidates",
            revalidated.headers().single("Vary").getValue(),
            new IsEqual<>("Host")
        );
    }

    @Test
    void notModifiedResponseOmitsVaryWhenCanonicalBaseUrlIsSet() {
        final SingleVersionSlice slice = new SingleVersionSlice(url(), this.storage, "npm-local");
        final Response first = slice.response(
            new RequestLine(RqMethod.GET, "/simple-npm-project/1.0.0"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        final String etag = first.headers().single("ETag").getValue();
        ClientBaseUrlSettingsRegistry.install(
            () -> new ClientBaseUrlSettings(false, List.of(), "http://canonical.example.com")
        );
        final Response revalidated = slice.response(
            new RequestLine(RqMethod.GET, "/simple-npm-project/1.0.0"),
            Headers.from("If-None-Match", etag), Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "304 on a matching If-None-Match", revalidated.status(), new IsEqual<>(RsStatus.NOT_MODIFIED)
        );
        MatcherAssert.assertThat(
            "a 304 must omit Vary too when nothing varies the response",
            revalidated.headers().find("Vary").isEmpty(),
            new IsEqual<>(true)
        );
    }

    private JsonObject get(final String path) throws Exception {
        final String responseBody = new SingleVersionSlice(url(), this.storage, "npm-local").response(
            new RequestLine(RqMethod.GET, path),
            Headers.EMPTY, Content.EMPTY
        ).join().body().asString();
        return Json.createReader(new java.io.StringReader(responseBody)).readObject();
    }

    private static java.net.URL url() {
        try {
            return URI.create("http://pantera.local").toURL();
        } catch (final Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
