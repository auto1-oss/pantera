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
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
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
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

/**
 * Slice-level regression coverage for the reported corepack bug: a proxy (or
 * group) npm repository must answer {@code GET /<pkg>/<version>} with a real
 * version manifest — resolved via {@link VersionManifestResolver} — never the
 * {@code {name, modified}} stub that used to silently {@code 200}. Drives
 * {@link DownloadPackageSlice} directly against an {@link InMemoryStorage}
 * seeded with a packument fixture, mirroring
 * {@code DownloadPackageSliceClientBaseTest}'s direct-call style so the
 * served tarball URL is deterministic without standing up an HTTP server.
 */
final class DownloadPackageSliceSingleVersionTest {

    /**
     * Client-facing base stamped on every request, standing in for the base
     * an outer {@code SliceByPath}/group layer would set.
     */
    private static final String TARBALL_PREFIX = "https://h/api/npm/npm_proxy";

    /**
     * Packument fixture for an unscoped package, two versions, matching the
     * one used in {@code VersionManifestResolverTest}.
     */
    private static final byte[] PNPM_PACKUMENT = ("""
        {"name":"pnpm","dist-tags":{"latest":"11.5.1"},"versions":{
          "11.5.1":{"name":"pnpm","version":"11.5.1",
            "dist":{"tarball":"https://registry.npmjs.org/pnpm/-/pnpm-11.5.1.tgz",
                    "shasum":"abc"}},
          "11.4.0":{"name":"pnpm","version":"11.4.0",
            "dist":{"tarball":"https://registry.npmjs.org/pnpm/-/pnpm-11.4.0.tgz",
                    "shasum":"def"}}}}
        """).getBytes(StandardCharsets.UTF_8);

    /**
     * Packument fixture for a scoped package — proves the {@code @scope/name}
     * vs {@code pkg/ref} ambiguity is resolved correctly at the slice level,
     * not just inside {@link VersionManifestResolver#parse}.
     */
    private static final byte[] TYPES_NODE_PACKUMENT = ("""
        {"name":"@types/node","dist-tags":{"latest":"22.0.0"},"versions":{
          "22.0.0":{"name":"@types/node","version":"22.0.0",
            "dist":{"tarball":"https://registry.npmjs.org/@types/node/-/node-22.0.0.tgz",
                    "shasum":"xyz"}}}}
        """).getBytes(StandardCharsets.UTF_8);

    @Test
    void getVersionReturnsRealManifestNotTheStub() throws Exception {
        final JsonObject manifest = DownloadPackageSliceSingleVersionTest.get(
            "pnpm", PNPM_PACKUMENT, "/pnpm/11.5.1"
        );
        MatcherAssert.assertThat(
            "version field carries the requested version",
            manifest.getString("version"),
            new IsEqual<>("11.5.1")
        );
        MatcherAssert.assertThat(
            "tarball is rewritten to the stamped client-facing base",
            manifest.getJsonObject("dist").getString("tarball"),
            new IsEqual<>(TARBALL_PREFIX + "/pnpm/-/pnpm-11.5.1.tgz")
        );
        // The reported production bug: corepack got a bare {name, modified}
        // stub, 200 OK, with no dist/tarball at all. A real manifest must
        // never collapse to exactly that two-key shape.
        MatcherAssert.assertThat(
            "must not be the reported {name, modified} stub",
            manifest.keySet().equals(Set.of("name", "modified")),
            new IsEqual<>(false)
        );
    }

    @Test
    void getBarePackageStillReturnsFullPackument() throws Exception {
        final JsonObject packument = DownloadPackageSliceSingleVersionTest.get(
            "pnpm", PNPM_PACKUMENT, "/pnpm"
        );
        MatcherAssert.assertThat(
            "packument path is untouched: the versions map is still served",
            packument.containsKey("versions"),
            new IsEqual<>(true)
        );
    }

    @Test
    void getScopedPackageStillReturnsFullPackument() throws Exception {
        final JsonObject packument = DownloadPackageSliceSingleVersionTest.get(
            "@types/node", TYPES_NODE_PACKUMENT, "/@types/node"
        );
        MatcherAssert.assertThat(
            "scoped-package ambiguity is resolved at slice level: still the packument",
            packument.containsKey("versions"),
            new IsEqual<>(true)
        );
    }

    @Test
    void getUnknownVersionReturns404() throws Exception {
        final Storage storage = new InMemoryStorage();
        DownloadPackageSliceSingleVersionTest.seed(storage, "pnpm", PNPM_PACKUMENT);
        final Response response = DownloadPackageSliceSingleVersionTest.sliceFor(storage)
            .response(
                new RequestLine(RqMethod.GET, "/pnpm/9.9.9"),
                Headers.from(ClientBaseUrl.HEADER, TARBALL_PREFIX),
                Content.EMPTY
            ).get();
        MatcherAssert.assertThat(
            "unresolvable version 404s",
            response.status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
    }

    /**
     * Seed storage with a package, drive a request through a freshly-built
     * slice, assert a {@code 200}, and return the parsed JSON body.
     *
     * @param pkg Package name to seed
     * @param packument Packument fixture bytes
     * @param requestPath Request path
     * @return Parsed response body
     * @throws Exception On any I/O failure building the response
     */
    private static JsonObject get(
        final String pkg, final byte[] packument, final String requestPath
    ) throws Exception {
        final Storage storage = new InMemoryStorage();
        DownloadPackageSliceSingleVersionTest.seed(storage, pkg, packument);
        final Response response = DownloadPackageSliceSingleVersionTest.sliceFor(storage)
            .response(
                new RequestLine(RqMethod.GET, requestPath),
                Headers.from(ClientBaseUrl.HEADER, TARBALL_PREFIX),
                Content.EMPTY
            ).get();
        MatcherAssert.assertThat(
            "status code",
            response.status(),
            new IsEqual<>(RsStatus.OK)
        );
        final String body = response.body().asStringFuture().get();
        return Json.createReader(new StringReader(body)).readObject();
    }

    /**
     * Build a slice over the given storage, with a stub remote (404 for
     * anything not pre-seeded) — mirrors
     * {@code DownloadPackageSliceClientBaseTest}'s wiring.
     *
     * @param storage Backing storage
     * @return Slice under test
     */
    private static DownloadPackageSlice sliceFor(final Storage storage) {
        return new DownloadPackageSlice(
            new NpmProxy(storage, new SliceSimple(ResponseBuilder.notFound().build())),
            new PackagePath(""),
            Optional.empty()
        );
    }

    /**
     * Seed the upstream packument + refresh metadata a package needs to
     * serve the full-metadata path (matches {@code DownloadPackageSliceTest}).
     *
     * @param storage Backing storage
     * @param pkg Package name
     * @param packument Packument fixture bytes
     */
    private static void seed(final Storage storage, final String pkg, final byte[] packument) {
        storage.save(
            new Key.From(pkg + "/meta.json"),
            new Content.From(packument)
        ).join();
        storage.save(
            new Key.From(pkg + "/meta.meta"),
            new Content.From(
                Json.createObjectBuilder()
                    .add("last-modified", "2020-05-13T16:30:30+01:00")
                    .add("last-refreshed", "2020-05-13T16:30:30+01:00")
                    .build()
                    .toString()
                    .getBytes(StandardCharsets.UTF_8)
            )
        ).join();
    }
}
