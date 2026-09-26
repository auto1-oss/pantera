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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownReason;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.cooldown.metadata.AllVersionsBlockedException;
import com.auto1.pantera.cooldown.metadata.CooldownMetadataService;
import com.auto1.pantera.cooldown.metadata.MetadataFilter;
import com.auto1.pantera.cooldown.metadata.MetadataParser;
import com.auto1.pantera.cooldown.metadata.MetadataRewriter;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.ProxyCacheConfig;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.maven.cooldown.MavenCooldownResponseFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Cooldown keying and metadata-filter regressions on the Maven/Gradle proxy:
 * the (artifact, version) key every primary file of a version is gated
 * under, the package name + envelope variant snapshot metadata is filtered
 * under, the marker on the all-versions-blocked 403, and the absence of any
 * private filtered-bytes cache that cooldown events cannot reach.
 *
 * @since 2.2.9
 */
final class CachedProxySliceCooldownKeyTest {

    /**
     * Upstream publish date served on primary GETs.
     */
    private static final String LAST_MODIFIED = "Wed, 21 Oct 2015 07:28:00 GMT";

    @BeforeEach
    void init() {
        CooldownResponseRegistry.instance()
            .register("maven-proxy", new MavenCooldownResponseFactory());
    }

    @ParameterizedTest
    @CsvSource({
        "com/example/mylib/1.0/mylib-1.0.jar,com.example.mylib,1.0",
        "com/example/mylib/1.0/mylib-1.0.pom,com.example.mylib,1.0",
        "com/example/mylib/1.0/mylib-1.0.module,com.example.mylib,1.0",
        "com/example/mylib/1.0/mylib-1.0.war,com.example.mylib,1.0",
        "com/example/mylib/1.0/mylib-1.0.aar,com.example.mylib,1.0",
        "com/example/mylib/1.0/mylib-1.0-sources.jar,com.example.mylib,1.0",
        "com/example/mylib/1.0/mylib-1.0-javadoc.jar,com.example.mylib,1.0",
        "com/example/mylib/1.0/mylib-1.0-tests.jar,com.example.mylib,1.0",
        "com/example/my-lib/2.1.0/my-lib-2.1.0-sources.jar,com.example.my-lib,2.1.0",
        "/com/example/mylib/1.0/mylib-1.0.module,com.example.mylib,1.0",
        "com/example/my-lib/1.0-SNAPSHOT/my-lib-1.0-20260519.090000-1.jar,"
            + "com.example.my-lib,1.0-20260519.090000-1",
        "com/example/my-lib/1.0-SNAPSHOT/my-lib-1.0-20260519.090000-12-sources.jar,"
            + "com.example.my-lib,1.0-20260519.090000-12",
        "com/example/my-lib/1.0-SNAPSHOT/my-lib-1.0-20260519.090000-1.module,"
            + "com.example.my-lib,1.0-20260519.090000-1",
        "com/example/a-b-c/1.0-rc-1-SNAPSHOT/a-b-c-1.0-rc-1-20260519.090000-3.pom,"
            + "com.example.a-b-c,1.0-rc-1-20260519.090000-3",
        "com/example/my-lib/1.0-SNAPSHOT/my-lib-1.0-SNAPSHOT.jar,"
            + "com.example.my-lib,1.0-SNAPSHOT",
        "com/example/my-lib/1.0-SNAPSHOT/my-lib-1.0-SNAPSHOT-sources.jar,"
            + "com.example.my-lib,1.0-SNAPSHOT"
    })
    void everyPrimaryFileOfAVersionSharesOneCooldownKey(
        final String path, final String artifact, final String version
    ) {
        final Optional<CooldownRequest> req = slice(
            okUpstream(new byte[0]), NoopCooldownService.INSTANCE, null
        ).buildCooldownRequest(path, Headers.EMPTY);
        MatcherAssert.assertThat(
            "cooldown request present for " + path,
            req.isPresent(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "artifact key for " + path,
            req.get().artifact(), new IsEqual<>(artifact)
        );
        MatcherAssert.assertThat(
            "version key for " + path,
            req.get().version(), new IsEqual<>(version)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "com/example/mylib/1.0/mylib-1.0.jar.sha1",
        "com/example/mylib/1.0/mylib-1.0.jar.md5",
        "com/example/mylib/1.0/mylib-1.0.jar.sha256",
        "com/example/mylib/1.0/mylib-1.0.jar.sha512",
        "com/example/mylib/1.0/mylib-1.0.jar.asc",
        "com/example/mylib/1.0/mylib-1.0.module.sha1",
        "com/example/mylib/1.0/mylib-1.0-sources.jar.asc",
        "com/example/mylib/maven-metadata.xml",
        "com/example/mylib/1.0-SNAPSHOT/maven-metadata.xml",
        "com/example/mylib/1.0/",
        "mylib-1.0.jar"
    })
    void sidecarsAndMetadataAreNotGated(final String path) {
        MatcherAssert.assertThat(
            slice(okUpstream(new byte[0]), NoopCooldownService.INSTANCE, null)
                .buildCooldownRequest(path, Headers.EMPTY)
                .isPresent(),
            new IsEqual<>(false)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/com/example/mylib/1.0/mylib-1.0.module",
        "/com/example/mylib/1.0/mylib-1.0-sources.jar",
        "/com/example/mylib/1.0/mylib-1.0-javadoc.jar"
    })
    void gradleModuleAndClassifierJarsAreBlockedUnderTheVersionKey(final String path) {
        final List<CooldownRequest> seen = new CopyOnWriteArrayList<>();
        final InMemoryStorage storage = new InMemoryStorage();
        final CachedProxySlice slice = new CachedProxySlice(
            okUpstream("fresh-bytes".getBytes(StandardCharsets.UTF_8)),
            (cacheKey, supplier, control) -> supplier.get(),
            Optional.of(new LinkedList<>()),
            "maven_proxy",
            "https://repo.maven.apache.org/maven2",
            "maven-proxy",
            new BlockingService(seen),
            noopInspector(),
            Optional.of(storage),
            ProxyCacheConfig.withCooldown(),
            null
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "a blocked version's " + path + " must be refused",
            response.status(), new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "the gate evaluated the main jar's (artifact, version) key",
            seen.stream()
                .map(r -> r.artifact() + ":" + r.version())
                .toList(),
            new IsEqual<>(List.of("com.example.mylib:1.0"))
        );
        MatcherAssert.assertThat(
            "the refused bytes are never cached",
            storage.exists(new Key.From(path.substring(1))).join(),
            new IsEqual<>(false)
        );
    }

    @Test
    void timestampedSnapshotVersionSurvivesHyphenatedArtifactId() {
        MatcherAssert.assertThat(
            CachedProxySlice.extractSnapshotVersion(
                "org/foo/my-lib/1.0-SNAPSHOT/my-lib-1.0-20260519.090000-1.jar"
            ),
            new IsEqual<>(Optional.of("1.0-20260519.090000-1"))
        );
    }

    @Test
    void metadataFilterRunsOnEveryRequestSoUnblocksAreVisible() {
        final AtomicInteger calls = new AtomicInteger();
        final RecordingMetadataService service = new RecordingMetadataService(
            (pkg, raw) -> CompletableFuture.completedFuture(
                ("<metadata>filtered-" + calls.incrementAndGet() + "</metadata>")
                    .getBytes(StandardCharsets.UTF_8)
            )
        );
        final CachedProxySlice slice = slice(
            okUpstream("<metadata>upstream</metadata>".getBytes(StandardCharsets.UTF_8)),
            NoopCooldownService.INSTANCE, service
        );
        final String path = "/com/example/lib/maven-metadata.xml";
        slice.response(new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY)
            .join().body().asBytes();
        final Response second = slice.response(
            new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "second request re-runs the envelope-cached filter (whose cache "
                + "cooldown events invalidate) instead of a private byte cache",
            new String(second.body().asBytes(), StandardCharsets.UTF_8),
            new IsEqual<>("<metadata>filtered-2</metadata>")
        );
        MatcherAssert.assertThat(
            "filter invoked once per request",
            calls.get(), new IsEqual<>(2)
        );
    }

    @Test
    void snapshotMetadataIsFilteredUnderTheArtifactPackageWithItsOwnVariant() {
        final RecordingMetadataService service = new RecordingMetadataService(
            (pkg, raw) -> CompletableFuture.completedFuture(raw)
        );
        slice(
            okUpstream("<metadata/>".getBytes(StandardCharsets.UTF_8)),
            NoopCooldownService.INSTANCE, service
        ).response(
            new RequestLine(
                RqMethod.GET, "/com/example/my-lib/1.0-SNAPSHOT/maven-metadata.xml"
            ),
            Headers.EMPTY, Content.EMPTY
        ).join().body().asBytes();
        MatcherAssert.assertThat(
            "package name matches the block rows snapshot downloads create",
            service.pkg.get(), new IsEqual<>("com.example.my-lib")
        );
        MatcherAssert.assertThat(
            "snapshot-level envelope does not collide with the artifact-level one",
            service.variant.get(), new IsEqual<>("snapshot-1.0-SNAPSHOT")
        );
    }

    @Test
    void allVersionsBlockedCarriesTheCooldownMarker() {
        final RecordingMetadataService service = new RecordingMetadataService(
            (pkg, raw) -> CompletableFuture.failedFuture(
                new AllVersionsBlockedException(pkg, Set.of("1.0"))
            )
        );
        final Response resp = slice(
            okUpstream("<metadata/>".getBytes(StandardCharsets.UTF_8)),
            NoopCooldownService.INSTANCE, service
        ).response(
            new RequestLine(RqMethod.GET, "/com/example/lib/maven-metadata.xml"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "status is 403",
            resp.status(), new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "403 carries the cooldown marker so groups relay it",
            resp.headers().values("X-Pantera-Cooldown"),
            new IsEqual<>(List.of("all-blocked"))
        );
    }

    private static CachedProxySlice slice(
        final Slice upstream, final CooldownService cooldown,
        final CooldownMetadataService metadata
    ) {
        return new CachedProxySlice(
            upstream,
            (cacheKey, supplier, control) -> CompletableFuture.completedFuture(Optional.empty()),
            Optional.of(new LinkedList<>()), "maven_proxy",
            "https://repo.maven.apache.org/maven2", "maven-proxy",
            cooldown, noopInspector(), Optional.of(new InMemoryStorage()),
            ProxyCacheConfig.defaults(),
            new MetadataCache(Duration.ofMinutes(1)),
            metadata
        );
    }

    /**
     * Upstream serving {@code data} (with a Last-Modified) for primaries and
     * the matching SHA-1 for {@code .sha1} sidecars.
     */
    private static Slice okUpstream(final byte[] data) {
        final String sha1 = sha1Hex(data);
        return (line, headers, body) -> {
            if (line.uri().getPath().endsWith(".sha1")) {
                return ResponseBuilder.ok()
                    .body(sha1.getBytes(StandardCharsets.UTF_8))
                    .completedFuture();
            }
            return ResponseBuilder.ok()
                .header("Last-Modified", LAST_MODIFIED)
                .body(data)
                .completedFuture();
        };
    }

    private static String sha1Hex(final byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static CooldownInspector noopInspector() {
        return new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(
                final String artifact, final String version
            ) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(
                final String artifact, final String version
            ) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
    }

    /**
     * Cooldown service that blocks everything and records the requests.
     */
    private static final class BlockingService implements CooldownService {
        private final List<CooldownRequest> seen;

        BlockingService(final List<CooldownRequest> seen) {
            this.seen = seen;
        }

        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector inspector
        ) {
            return this.evaluateWithKnownDate(request, Optional.empty());
        }

        @Override
        public CompletableFuture<CooldownResult> evaluateWithKnownDate(
            final CooldownRequest request, final Optional<Instant> known
        ) {
            this.seen.add(request);
            return CompletableFuture.completedFuture(
                CooldownResult.blocked(
                    new CooldownBlock(
                        request.repoType(), request.repoName(), request.artifact(),
                        request.version(), CooldownReason.FRESH_RELEASE,
                        Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600),
                        List.of()
                    )
                )
            );
        }

        @Override
        public CompletableFuture<Void> unblock(
            final String rtype, final String rname, final String art,
            final String ver, final String actor
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> unblockAll(
            final String rtype, final String rname, final String actor
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<CooldownBlock>> activeBlocks(
            final String rtype, final String rname
        ) {
            return CompletableFuture.completedFuture(List.of());
        }
    }

    /**
     * Metadata service recording the package name and envelope variant it
     * was asked to filter under, delegating the result to a function.
     */
    private static final class RecordingMetadataService implements CooldownMetadataService {
        private final java.util.function.BiFunction<String, byte[], CompletableFuture<byte[]>> fn;
        private final AtomicReference<String> pkg = new AtomicReference<>();
        private final AtomicReference<String> variant = new AtomicReference<>();

        RecordingMetadataService(
            final java.util.function.BiFunction<String, byte[], CompletableFuture<byte[]>> fn
        ) {
            this.fn = fn;
        }

        @Override
        public <T> CompletableFuture<byte[]> filterMetadata(
            final String repoType, final String repoName, final String packageName,
            final byte[] rawMetadata, final MetadataParser<T> parser,
            final MetadataFilter<T> filter, final MetadataRewriter<T> rewriter
        ) {
            this.pkg.set(packageName);
            this.variant.compareAndSet(null, "default");
            return this.fn.apply(packageName, rawMetadata);
        }

        @Override
        public <T> CompletableFuture<byte[]> filterMetadata(
            final String repoType, final String repoName, final String var,
            final String packageName, final byte[] rawMetadata,
            final MetadataParser<T> parser, final MetadataFilter<T> filter,
            final MetadataRewriter<T> rewriter, final AuditContext ctx,
            final String owner
        ) {
            this.variant.set(var);
            return this.filterMetadata(
                repoType, repoName, packageName, rawMetadata, parser, filter, rewriter
            );
        }

        @Override
        public void invalidate(final String repoType, final String repoName, final String pkg) {
            // not used
        }

        @Override
        public void invalidateAll(final String repoType, final String repoName) {
            // not used
        }

        @Override
        public void clearAll() {
            // not used
        }

        @Override
        public String stats() {
            return "recording";
        }
    }
}
