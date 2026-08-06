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

import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonObject;

final class VersionManifestResolverTest {

    /**
     * Fixture packument for the {@code emit} tests: two versions of
     * {@code pnpm}, with {@code dist-tags.latest} pointing at {@code 11.5.1}.
     */
    private static final byte[] PACKUMENT = ("""
        {"name":"pnpm","dist-tags":{"latest":"11.5.1"},"versions":{
          "11.5.1":{"name":"pnpm","version":"11.5.1",
            "dist":{"tarball":"https://registry.npmjs.org/pnpm/-/pnpm-11.5.1.tgz",
                    "shasum":"abc"}},
          "11.4.0":{"name":"pnpm","version":"11.4.0",
            "dist":{"tarball":"https://registry.npmjs.org/pnpm/-/pnpm-11.4.0.tgz",
                    "shasum":"def"}}}}
        """).getBytes(StandardCharsets.UTF_8);

    /**
     * Resolver with cooldown disabled, exercising only {@code emit}
     * directly — {@code npm} is never touched by {@code emit}.
     */
    private static final VersionManifestResolver RESOLVER =
        new VersionManifestResolver(null, null, null, null);

    @Test
    void parsesUnscopedPackageAndVersion() {
        final VersionManifestResolver.PackageRef ref =
            VersionManifestResolver.parse("pnpm/11.5.1").orElseThrow();
        MatcherAssert.assertThat("package", ref.pkg(), new IsEqual<>("pnpm"));
        MatcherAssert.assertThat("reference", ref.ref(), new IsEqual<>("11.5.1"));
    }

    @Test
    void parsesScopedPackageAndVersion() {
        final VersionManifestResolver.PackageRef ref =
            VersionManifestResolver.parse("@types/node/22.0.0").orElseThrow();
        MatcherAssert.assertThat("package", ref.pkg(), new IsEqual<>("@types/node"));
        MatcherAssert.assertThat("reference", ref.ref(), new IsEqual<>("22.0.0"));
    }

    @Test
    void treatsScopedPackageWithoutVersionAsAPackument() {
        // THE ambiguity: /@types/node is a package name, not (pkg=@types, ref=node).
        MatcherAssert.assertThat(
            VersionManifestResolver.parse("@types/node").isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void treatsBarePackageAsAPackument() {
        MatcherAssert.assertThat(
            VersionManifestResolver.parse("pnpm").isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void rejectsDashAndEmptyReferences() {
        MatcherAssert.assertThat(
            "dash", VersionManifestResolver.parse("pnpm/-").isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "empty", VersionManifestResolver.parse("pnpm/").isPresent(), new IsEqual<>(false)
        );
    }

    @Test
    void emitsUnscopedVersionWithRewrittenTarball() throws Exception {
        final Response response = RESOLVER.emit(
            PACKUMENT, "pnpm", "11.5.1", "https://h/api/npm/npm_group", Optional.empty()
        );
        MatcherAssert.assertThat(
            "status", response.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "tarball rewritten to the client-facing prefix",
            tarballOf(response),
            new IsEqual<>("https://h/api/npm/npm_group/pnpm/-/pnpm-11.5.1.tgz")
        );
    }

    @Test
    void emitResolvesDistTagToVersionManifestWithRewrittenTarball() throws Exception {
        // This is the /latest upstream-URL leak fix (spec S2.D): the dist-tag
        // must resolve to the same rewritten (Pantera-rooted) tarball as the
        // literal version, never the raw registry.npmjs.org URL.
        final Response response = RESOLVER.emit(
            PACKUMENT, "pnpm", "latest", "https://h/api/npm/npm_group", Optional.empty()
        );
        MatcherAssert.assertThat(
            "status", response.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "dist-tag resolves through versions[distTags[ref]] with the tarball rewritten",
            tarballOf(response),
            new IsEqual<>("https://h/api/npm/npm_group/pnpm/-/pnpm-11.5.1.tgz")
        );
    }

    @Test
    void emitReturns404ForUnknownVersionNotTheLegacyStub() throws Exception {
        // Regression guard for the reported corepack bug: an unresolvable
        // version must 404 with an honest body, never the old {name,
        // modified} stub that silently 200s.
        final Response response = RESOLVER.emit(
            PACKUMENT, "pnpm", "9.9.9", "https://h/api/npm/npm_group", Optional.empty()
        );
        MatcherAssert.assertThat(
            "status", response.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        final String body = response.body().asStringFuture().get();
        MatcherAssert.assertThat(
            "not the legacy {name, modified} stub",
            body.contains("\"modified\""),
            new IsEqual<>(false)
        );
    }

    @Test
    void emitHonoursMatchingIfNoneMatchWith304() throws Exception {
        final String prefix = "https://h/api/npm/npm_group";
        final Response first = RESOLVER.emit(
            PACKUMENT, "pnpm", "11.5.1", prefix, Optional.empty()
        );
        final String etag = first.headers().single("ETag").getValue();
        final Response revalidated = RESOLVER.emit(
            PACKUMENT, "pnpm", "11.5.1", prefix, Optional.of(etag)
        );
        MatcherAssert.assertThat(
            "304 on a matching If-None-Match",
            revalidated.status(),
            new IsEqual<>(RsStatus.NOT_MODIFIED)
        );
        MatcherAssert.assertThat(
            "ETag echoed on the 304",
            revalidated.headers().single("ETag").getValue(),
            new IsEqual<>(etag)
        );
        MatcherAssert.assertThat(
            "no body on a 304",
            revalidated.body().asBytesFuture().get().length,
            new IsEqual<>(0)
        );
    }

    @Test
    void differentTarballPrefixesProduceDifferentETags() {
        final Response first = RESOLVER.emit(
            PACKUMENT, "pnpm", "11.5.1", "https://h1/api/npm/npm_group", Optional.empty()
        );
        final Response second = RESOLVER.emit(
            PACKUMENT, "pnpm", "11.5.1", "https://h2/api/npm/npm_group", Optional.empty()
        );
        MatcherAssert.assertThat(
            first.headers().single("ETag").getValue().equals(
                second.headers().single("ETag").getValue()
            ),
            new IsEqual<>(false)
        );
    }

    /**
     * Extract {@code dist.tarball} from an {@code emit} response body.
     *
     * @param response Response to read
     * @return Tarball URL
     * @throws Exception if the body future fails
     */
    private static String tarballOf(final Response response) throws Exception {
        final String body = response.body().asStringFuture().get();
        final JsonObject json = Json.createReader(new StringReader(body)).readObject();
        return json.getJsonObject("dist").getString("tarball");
    }
}
