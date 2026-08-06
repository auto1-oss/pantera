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
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.npm.PerVersionLayout;
import com.auto1.pantera.security.policy.Policy;

import java.io.StringReader;
import java.net.URI;
import java.net.URL;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;
import javax.json.JsonObject;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Routing regressions in {@link NpmSlice} for a LOCAL repository.
 *
 * <p><b>B1 (HIGH):</b> the single-version route pattern
 * {@code ^/(@[^/]+/)?[^/]+/[^/]+$} let its optional scope group not
 * participate, so {@code [^/]+/[^/]+} alone matched a bare 2-segment scoped
 * PACKAGE NAME ({@code /@scope/pkg}) as if it were package+version. {@link
 * SingleVersionSlice#response} correctly refused that shape and 404'd
 * instead of letting the request fall through to the packument route below
 * it. Fixed by tightening the route regex to mirror {@code
 * SingleVersionSlice}'s own accepted shapes exactly, so the router and the
 * parser agree.</p>
 *
 * <p><b>B4 (MEDIUM):</b> the registry-root route was registered with the
 * hardcoded literal path {@code "/npm"} instead of the bare root
 * {@code TrimPathSlice} actually leaves once the repository-name segment is
 * stripped -- unreachable except by the accident of a repository literally
 * named {@code npm} plus a client sending a spurious extra {@code /npm}
 * segment.</p>
 */
final class NpmSliceRoutingTest {

    /**
     * Bearer token this test's {@link TokenAuthentication} double accepts.
     */
    private static final String TOKEN = "npm-routing-test-token";

    /**
     * Storage backing both a scoped and an unscoped published package.
     */
    private Storage storage;

    @BeforeEach
    void publishFixtures() throws Exception {
        this.storage = new InMemoryStorage();
        final PerVersionLayout layout = new PerVersionLayout(this.storage);
        this.publish(layout, new Key.From("plain-pkg"), "plain-pkg");
        this.publish(layout, new Key.From("@scope/scoped-pkg"), "@scope/scoped-pkg");
    }

    @Test
    void unscopedPackumentIsServed() throws Exception {
        final JsonObject body = this.getJson("/plain-pkg");
        MatcherAssert.assertThat(
            "a bare package path serves the packument (a versions map), "
                + "not a single-version manifest",
            body.containsKey("versions"),
            new IsEqual<>(true)
        );
    }

    @Test
    void unscopedSingleVersionIsServed() throws Exception {
        final JsonObject body = this.getJson("/plain-pkg/1.0.0");
        MatcherAssert.assertThat(body.getString("version"), new IsEqual<>("1.0.0"));
    }

    @Test
    void scopedPackumentIsServedNotFourOhFour() throws Exception {
        final JsonObject body = this.getJson("/@scope/scoped-pkg");
        MatcherAssert.assertThat(
            "a bare scoped-package path (2 segments, no version) must serve "
                + "the packument, not fall into the single-version route and 404",
            body.containsKey("versions"),
            new IsEqual<>(true)
        );
    }

    @Test
    void scopedSingleVersionIsServed() throws Exception {
        final JsonObject body = this.getJson("/@scope/scoped-pkg/1.0.0");
        MatcherAssert.assertThat(body.getString("version"), new IsEqual<>("1.0.0"));
    }

    @Test
    void registryRootIsReachable() throws Exception {
        final JsonObject body = this.getJson("/");
        MatcherAssert.assertThat(
            "the repository root must answer with Pantera's own registry "
                + "info, not 404",
            body.getBoolean("pantera"),
            new IsEqual<>(true)
        );
    }

    private JsonObject getJson(final String path) throws Exception {
        final NpmSlice slice = new NpmSlice(
            NpmSliceRoutingTest.baseUrl(), this.storage, Policy.FREE,
            NpmSliceRoutingTest.permissiveAuth(), "npm-local", Optional.empty()
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, path),
            Headers.from(new Authorization.Bearer(NpmSliceRoutingTest.TOKEN)),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            String.format("GET %s must resolve, not 404", path),
            response.status(),
            new IsEqual<>(RsStatus.OK)
        );
        return Json.createReader(new StringReader(response.body().asString())).readObject();
    }

    private void publish(
        final PerVersionLayout layout, final Key pkg, final String name
    ) throws Exception {
        layout.addVersion(
            pkg, "1.0.0",
            Json.createObjectBuilder()
                .add("name", name)
                .add("version", "1.0.0")
                .add("dist", Json.createObjectBuilder()
                    .add("tarball", String.format("http://oldhost/%s/-/x-1.0.0.tgz", name))
                    .build())
                .build()
        ).toCompletableFuture().join();
        layout.mergeDistTags(
            pkg, Json.createObjectBuilder().add("latest", "1.0.0").build()
        ).toCompletableFuture().join();
    }

    private static TokenAuthentication permissiveAuth() {
        return token -> CompletableFuture.completedFuture(
            NpmSliceRoutingTest.TOKEN.equals(token)
                ? Optional.of(new AuthUser("routing-tester", "test"))
                : Optional.empty()
        );
    }

    private static URL baseUrl() {
        try {
            return URI.create("http://pantera.local").toURL();
        } catch (final Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
