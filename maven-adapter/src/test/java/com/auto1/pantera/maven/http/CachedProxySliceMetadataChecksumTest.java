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
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.cooldown.metadata.CooldownMetadataService;
import com.auto1.pantera.cooldown.metadata.MetadataFilter;
import com.auto1.pantera.cooldown.metadata.MetadataParser;
import com.auto1.pantera.cooldown.metadata.MetadataRewriter;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.ProxyCacheConfig;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Checksum sidecars of {@code maven-metadata.xml} on a cooldown-filtering
 * Maven proxy must be the digest of the exact (filtered) metadata bytes the
 * proxy serves — never the metadata XML itself and never upstream's
 * checksum of the unfiltered file.
 *
 * @since 2.2.9
 */
final class CachedProxySliceMetadataChecksumTest {

    /**
     * Upstream (unfiltered) metadata.
     */
    private static final String UPSTREAM =
        "<metadata><versions><version>1.0</version><version>2.0</version></versions></metadata>";

    /**
     * What the cooldown filter serves.
     */
    private static final String FILTERED =
        "<metadata><versions><version>1.0</version></versions></metadata>";

    /**
     * Metadata path.
     */
    private static final String PATH = "/com/example/lib/maven-metadata.xml";

    @ParameterizedTest
    @CsvSource({"sha1,SHA-1", "md5,MD5", "sha256,SHA-256", "sha512,SHA-512"})
    void checksumAfterMetadataIsDigestOfServedBody(final String ext, final String algo)
        throws Exception {
        final List<String> upstreamPaths = new CopyOnWriteArrayList<>();
        final CachedProxySlice slice = slice(upstream(upstreamPaths));
        final byte[] served = slice.response(
            new RequestLine(RqMethod.GET, PATH), Headers.EMPTY, Content.EMPTY
        ).join().body().asBytes();
        final Response sum = slice.response(
            new RequestLine(RqMethod.GET, PATH + "." + ext), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "served metadata is the filtered body",
            new String(served, StandardCharsets.UTF_8), new IsEqual<>(FILTERED)
        );
        MatcherAssert.assertThat(
            "checksum status",
            sum.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "checksum is the digest of the served metadata body",
            new String(sum.body().asBytes(), StandardCharsets.UTF_8),
            new IsEqual<>(hex(algo, served))
        );
    }

    @Test
    void coldChecksumIsDigestOfFilteredBodyNotUpstreamChecksum() throws Exception {
        final List<String> upstreamPaths = new CopyOnWriteArrayList<>();
        final Response sum = slice(upstream(upstreamPaths)).response(
            new RequestLine(RqMethod.GET, PATH + ".sha1"), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "cold checksum is the digest of the filtered metadata",
            new String(sum.body().asBytes(), StandardCharsets.UTF_8),
            new IsEqual<>(hex("SHA-1", FILTERED.getBytes(StandardCharsets.UTF_8)))
        );
        MatcherAssert.assertThat(
            "upstream's checksum of the unfiltered file is never fetched",
            upstreamPaths, new IsEqual<>(List.of(PATH))
        );
    }

    @Test
    void checksumOfMissingMetadataIsNotFound() {
        final Slice missing = (line, headers, body) ->
            ResponseBuilder.notFound().completedFuture();
        MatcherAssert.assertThat(
            slice(missing).response(
                new RequestLine(RqMethod.GET, PATH + ".sha1"), Headers.EMPTY, Content.EMPTY
            ).join().status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
    }

    private static CachedProxySlice slice(final Slice upstream) {
        return new CachedProxySlice(
            upstream,
            (cacheKey, supplier, control) -> CompletableFuture.completedFuture(Optional.empty()),
            Optional.of(new LinkedList<>()), "maven_proxy",
            "https://repo.maven.apache.org/maven2", "maven-proxy",
            NoopCooldownService.INSTANCE, noopInspector(), Optional.of(new InMemoryStorage()),
            ProxyCacheConfig.defaults(),
            new MetadataCache(Duration.ofMinutes(1)),
            new FixedFilterService()
        );
    }

    /**
     * Upstream serving the unfiltered metadata, and upstream's checksum of
     * it for sidecars; records every path it was asked for.
     */
    private static Slice upstream(final List<String> paths) {
        final byte[] raw = UPSTREAM.getBytes(StandardCharsets.UTF_8);
        return (line, headers, body) -> {
            final String path = line.uri().getPath();
            paths.add(path);
            if (path.endsWith(".sha1")) {
                return ResponseBuilder.ok()
                    .body(hex("SHA-1", raw).getBytes(StandardCharsets.UTF_8))
                    .completedFuture();
            }
            return ResponseBuilder.ok().body(raw).completedFuture();
        };
    }

    private static String hex(final String algo, final byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algo).digest(data));
        } catch (final java.security.NoSuchAlgorithmException ex) {
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
     * Metadata service that always serves {@link #FILTERED}.
     */
    private static final class FixedFilterService implements CooldownMetadataService {
        @Override
        public <T> CompletableFuture<byte[]> filterMetadata(
            final String repoType, final String repoName, final String packageName,
            final byte[] rawMetadata, final MetadataParser<T> parser,
            final MetadataFilter<T> filter, final MetadataRewriter<T> rewriter
        ) {
            return CompletableFuture.completedFuture(
                FILTERED.getBytes(StandardCharsets.UTF_8)
            );
        }

        @Override
        public <T> CompletableFuture<byte[]> filterMetadata(
            final String repoType, final String repoName, final String var,
            final String packageName, final byte[] rawMetadata,
            final MetadataParser<T> parser, final MetadataFilter<T> filter,
            final MetadataRewriter<T> rewriter, final AuditContext ctx,
            final String owner
        ) {
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
            return "fixed";
        }
    }
}
