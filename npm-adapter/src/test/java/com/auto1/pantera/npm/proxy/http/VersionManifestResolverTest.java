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
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.cooldown.metadata.AllVersionsBlockedException;
import com.auto1.pantera.cooldown.metadata.CooldownMetadataService;
import com.auto1.pantera.cooldown.metadata.MetadataFilter;
import com.auto1.pantera.cooldown.metadata.MetadataParser;
import com.auto1.pantera.cooldown.metadata.MetadataRewriter;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.npm.proxy.NpmProxy;
import com.auto1.pantera.npm.proxy.model.NpmPackage;
import io.reactivex.Maybe;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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

    // ---- resolve() coverage -------------------------------------------------
    //
    // parse() and emit() are covered above; resolve() is the method that
    // stitches together the Rx packument fetch, the cooldown filter, and
    // emit() itself. Its branching (empty upstream packument / cooldown
    // disabled / filter success / all-versions-blocked / generic
    // filter-error fallback) is exactly where a swapped
    // emit(filtered, ...) <-> emit(raw, ...) typo would hide undetected, so
    // each branch below is driven through resolve() directly rather than
    // through emit().

    /**
     * RAW (unfiltered) fixture: two versions, 11.5.1 (would be
     * cooldown-blocked in the filtered scenarios below) and 11.4.0 (always
     * allowed).
     */
    private static final byte[] RAW_PACKUMENT = ("""
        {"name":"pnpm","dist-tags":{"latest":"11.5.1"},"versions":{
          "11.5.1":{"name":"pnpm","version":"11.5.1",
            "dist":{"tarball":"https://registry.npmjs.org/pnpm/-/pnpm-11.5.1.tgz",
                    "shasum":"abc"}},
          "11.4.0":{"name":"pnpm","version":"11.4.0",
            "dist":{"tarball":"https://registry.npmjs.org/pnpm/-/pnpm-11.4.0.tgz",
                    "shasum":"def"}}}}
        """).getBytes(StandardCharsets.UTF_8);

    /**
     * Post-cooldown-filter fixture: the blocked version (11.5.1) is removed
     * and {@code dist-tags.latest} is re-pointed at 11.4.0.
     */
    private static final byte[] FILTERED_PACKUMENT = ("""
        {"name":"pnpm","dist-tags":{"latest":"11.4.0"},"versions":{
          "11.4.0":{"name":"pnpm","version":"11.4.0",
            "dist":{"tarball":"https://registry.npmjs.org/pnpm/-/pnpm-11.4.0.tgz",
                    "shasum":"def"}}}}
        """).getBytes(StandardCharsets.UTF_8);

    @Test
    void resolveWithNoCooldownEmitsRawPackument() throws Exception {
        final VersionManifestResolver resolver = new VersionManifestResolver(
            new FakeNpmProxy(RAW_PACKUMENT), null, null, "npm-proxy"
        );
        final Response response = resolver.resolve(
            "pnpm", "11.5.1", "https://h/api/npm/npm_proxy", Optional.empty(),
            AuditContext.NONE, "owner"
        ).get();
        MatcherAssert.assertThat(
            "cooldown-disabled path resolves 200 against the raw packument",
            response.status(),
            new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "served tarball matches the raw (unfiltered) entry",
            tarballOf(response),
            new IsEqual<>("https://h/api/npm/npm_proxy/pnpm/-/pnpm-11.5.1.tgz")
        );
    }

    @Test
    void resolveWithEmptyUpstreamPackumentReturns404() throws Exception {
        final VersionManifestResolver resolver = new VersionManifestResolver(
            new FakeNpmProxy(new byte[0]), null, null, "npm-proxy"
        );
        final Response response = resolver.resolve(
            "pnpm", "11.5.1", "https://h/api/npm/npm_proxy", Optional.empty(),
            AuditContext.NONE, "owner"
        ).get();
        MatcherAssert.assertThat(
            "a package that does not exist upstream 404s before any filtering",
            response.status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
    }

    @Test
    void resolveWithCooldownFilterServesFilteredNotRaw() throws Exception {
        // Filtered bytes lack version 11.5.1 (blocked); raw bytes still have
        // it. A 404 here is only reachable if resolve() emitted from the
        // FILTERED bytes -- a swapped emit(raw, ...) call would 200 instead.
        final VersionManifestResolver resolver = new VersionManifestResolver(
            new FakeNpmProxy(RAW_PACKUMENT),
            new StubCooldownMetadataService(CompletableFuture.completedFuture(FILTERED_PACKUMENT)),
            "npm", "npm-proxy"
        );
        final Response response = resolver.resolve(
            "pnpm", "11.5.1", "https://h/api/npm/npm_proxy", Optional.empty(),
            AuditContext.NONE, "owner"
        ).get();
        MatcherAssert.assertThat(
            "the cooldown-blocked version is absent from the filtered packument",
            response.status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
    }

    @Test
    void resolveWithAllVersionsBlockedReturns404() throws Exception {
        final CompletableFuture<byte[]> failed = new CompletableFuture<>();
        failed.completeExceptionally(
            new AllVersionsBlockedException("pnpm", Set.of("11.5.1", "11.4.0"))
        );
        final VersionManifestResolver resolver = new VersionManifestResolver(
            new FakeNpmProxy(RAW_PACKUMENT),
            new StubCooldownMetadataService(failed),
            "npm", "npm-proxy"
        );
        final Response response = resolver.resolve(
            "pnpm", "11.5.1", "https://h/api/npm/npm_proxy", Optional.empty(),
            AuditContext.NONE, "owner"
        ).get();
        MatcherAssert.assertThat(
            "all-versions-blocked maps to 404, not a 5xx or a stale stub",
            response.status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
    }

    @Test
    void resolveFallsBackToRawEmitOnGenericFilterError() throws Exception {
        // A non-AllVersionsBlockedException filter failure must fall back to
        // the RAW bytes -- 200 with version 11.5.1 is only reachable if the
        // fallback really emitted from raw (the filtered fixture would have
        // 404'd, same as the success-path test above).
        final CompletableFuture<byte[]> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("cooldown backend unavailable"));
        final VersionManifestResolver resolver = new VersionManifestResolver(
            new FakeNpmProxy(RAW_PACKUMENT),
            new StubCooldownMetadataService(failed),
            "npm", "npm-proxy"
        );
        final Response response = resolver.resolve(
            "pnpm", "11.5.1", "https://h/api/npm/npm_proxy", Optional.empty(),
            AuditContext.NONE, "owner"
        ).get();
        MatcherAssert.assertThat(
            "generic filter error falls back to serving the raw packument",
            response.status(),
            new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "fallback serves the raw (still-blocked) version, not a filtered 404",
            tarballOf(response),
            new IsEqual<>("https://h/api/npm/npm_proxy/pnpm/-/pnpm-11.5.1.tgz")
        );
    }

    /**
     * {@link NpmProxy} test double returning a fixed packument (or none, for
     * an empty array) without touching real storage or a remote client —
     * both {@code getPackageMetadataOnly} and {@code getPackageContentStream}
     * are the only two methods {@link VersionManifestResolver} calls.
     */
    private static final class FakeNpmProxy extends NpmProxy {

        /**
         * Packument bytes to serve; empty simulates "package does not exist
         * upstream".
         */
        private final byte[] packument;

        FakeNpmProxy(final byte[] packument) {
            super(new InMemoryStorage(), new SliceSimple(ResponseBuilder.notFound().build()));
            this.packument = packument;
        }

        @Override
        public Maybe<NpmPackage.Metadata> getPackageMetadataOnly(final String name) {
            final Maybe<NpmPackage.Metadata> result;
            if (this.packument.length == 0) {
                result = Maybe.empty();
            } else {
                result = Maybe.just(
                    new NpmPackage.Metadata("2020-05-13T16:30:30+01:00", OffsetDateTime.now())
                );
            }
            return result;
        }

        @Override
        public Maybe<Content> getPackageContentStream(final String name) {
            return Maybe.just(new Content.From(this.packument));
        }
    }

    /**
     * {@link CooldownMetadataService} test double returning a caller-supplied
     * (successful or failed) future, so tests can drive resolve()'s
     * filter-success / all-blocked / generic-error branches without a real
     * cooldown database. Mirrors {@code DownloadPackageSliceCooldownEtagTest}'s
     * {@code FixedFilterService}.
     */
    private static final class StubCooldownMetadataService implements CooldownMetadataService {

        /**
         * Future to return from every {@code filterMetadata} call.
         */
        private final CompletableFuture<byte[]> result;

        StubCooldownMetadataService(final CompletableFuture<byte[]> result) {
            this.result = result;
        }

        @Override
        public <T> CompletableFuture<byte[]> filterMetadata(
            final String repoType,
            final String repoName,
            final String packageName,
            final byte[] rawMetadata,
            final MetadataParser<T> parser,
            final MetadataFilter<T> filter,
            final MetadataRewriter<T> rewriter
        ) {
            return this.result;
        }

        @Override
        public <T> CompletableFuture<byte[]> filterMetadata(
            final String repoType,
            final String repoName,
            final String packageName,
            final byte[] rawMetadata,
            final MetadataParser<T> parser,
            final MetadataFilter<T> filter,
            final MetadataRewriter<T> rewriter,
            final AuditContext ctx,
            final String owner
        ) {
            return this.result;
        }

        @Override
        public void invalidate(final String repoType, final String repoName, final String packageName) {
            // no-op stub
        }

        @Override
        public void invalidateAll(final String repoType, final String repoName) {
            // no-op stub
        }

        @Override
        public void clearAll() {
            // no-op stub
        }

        @Override
        public String stats() {
            return "StubCooldownMetadataService";
        }
    }
}
