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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.api.v1.ArtifactDeletion;
import com.auto1.pantera.api.v1.StorageMetaCache;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cache.NegativeCacheConfig;
import com.auto1.pantera.cooldown.metadata.ProxyMetadataRevalidators;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.cache.NegativeCacheKey;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.test.RecordingIndex;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link ProxyEvictSlice} and {@link ProxyPathCaches}.
 *
 * @since 2.2.10
 */
final class ProxyEvictSliceTest {

    /**
     * Repository name.
     */
    private static final String REPO = "maven-central";

    /**
     * Repository type.
     */
    private static final String TYPE = "maven-proxy";

    @Test
    void evictsACachedArtifactAndItsCachesWithoutCallingTheProxy() {
        final Storage cache = ProxyEvictSliceTest.storage(
            "com/qa/lib/1.0/lib-1.0.jar", "com/qa/lib/1.0/lib-1.0.jar.sha1",
            "com/qa/lib/1.0/lib-1.0.jar.pantera-meta.json", "com/qa/lib/2.0/lib-2.0.jar"
        );
        final RecordingIndex index = new RecordingIndex()
            .row(ProxyEvictSliceTest.REPO, "com/qa/lib/1.0")
            .row(ProxyEvictSliceTest.REPO, "com/qa/lib/2.0");
        final NegativeCache negative = new NegativeCache(new NegativeCacheConfig());
        final NegativeCacheKey missing = NegativeCacheKey.fromPath(
            ProxyEvictSliceTest.REPO, ProxyEvictSliceTest.TYPE, "com/qa/lib/1.0/lib-1.0.jar"
        );
        negative.cacheNotFound(missing);
        final List<String> envelopes = new CopyOnWriteArrayList<>();
        final List<String> hooks = new CopyOnWriteArrayList<>();
        final AtomicInteger upstream = new AtomicInteger();
        final Slice proxy = (line, headers, body) -> {
            upstream.incrementAndGet();
            return ResponseBuilder.ok().completedFuture();
        };
        final Slice slice = new DeleteRoutingSlice(
            proxy,
            ProxyEvictSliceTest.evict(cache, index, negative, envelopes, hooks)
        );
        MatcherAssert.assertThat(
            "the eviction answers 204",
            ProxyEvictSliceTest.delete(slice, "/com/qa/lib/1.0/lib-1.0.jar"),
            new IsEqual<>(204)
        );
        MatcherAssert.assertThat(
            "the jar and its sidecars are gone, the other version stays",
            ProxyEvictSliceTest.keys(cache),
            new IsEqual<>(List.of("com/qa/lib/2.0/lib-2.0.jar"))
        );
        MatcherAssert.assertThat(
            "the version row of the emptied version directory is removed",
            index.rows(),
            new IsEqual<>(java.util.Set.of(ProxyEvictSliceTest.REPO + "|com/qa/lib/2.0"))
        );
        MatcherAssert.assertThat(
            "the negative cache entry of the path is dropped",
            negative.isKnown404(missing), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the filtered-metadata envelopes of the artifact are invalidated",
            envelopes.contains(ProxyEvictSliceTest.TYPE + "|com.qa.lib"), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the proxy's maven-metadata cache of the artifact is invalidated",
            hooks.contains("com/qa/lib"), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the proxy (and so the upstream) is never called",
            upstream.get(), new IsEqual<>(0)
        );
    }

    @Test
    void evictingMavenMetadataInvalidatesTheMetadataCache() {
        final List<String> hooks = new CopyOnWriteArrayList<>();
        MatcherAssert.assertThat(
            "metadata lives in the metadata cache only, its eviction still answers 204",
            ProxyEvictSliceTest.delete(
                ProxyEvictSliceTest.evict(
                    new InMemoryStorage(), new RecordingIndex(),
                    new NegativeCache(new NegativeCacheConfig()),
                    new CopyOnWriteArrayList<>(), hooks
                ),
                "/com/qa/lib/maven-metadata.xml"
            ),
            new IsEqual<>(204)
        );
        MatcherAssert.assertThat(
            "the hook was asked to drop the artifact's metadata",
            hooks.contains("com/qa/lib"), new IsEqual<>(true)
        );
    }

    @Test
    void evictsAFolder() {
        final Storage cache = ProxyEvictSliceTest.storage(
            "com/qa/lib/1.0/lib-1.0.jar", "com/qa/lib/1.0/lib-1.0.pom", "com/qa/other/1.0/o.jar"
        );
        MatcherAssert.assertThat(
            "the eviction answers 204",
            ProxyEvictSliceTest.delete(
                ProxyEvictSliceTest.evict(
                    cache, new RecordingIndex(), new NegativeCache(new NegativeCacheConfig()),
                    new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>()
                ),
                "/com/qa/lib/"
            ),
            new IsEqual<>(204)
        );
        MatcherAssert.assertThat(
            "only the other artifact stays",
            ProxyEvictSliceTest.keys(cache), new IsEqual<>(List.of("com/qa/other/1.0/o.jar"))
        );
    }

    @Test
    void answers404WhenNothingIsCached() {
        final Slice slice = new ProxyEvictSlice(
            "files-remote", "file-proxy", Optional.of(new InMemoryStorage()),
            new ArtifactDeletion(new RecordingIndex(), new StorageMetaCache()),
            new ProxyPathCaches(
                "files-remote", "file-proxy", Optional.empty(), (type, pkg) -> { },
                repo -> Optional.empty()
            )
        );
        MatcherAssert.assertThat(
            ProxyEvictSliceTest.delete(slice, "/dir/file.bin"), new IsEqual<>(404)
        );
    }

    @Test
    void answers404WhenTheProxyCachesNothing() {
        final Slice slice = new ProxyEvictSlice(
            "go-remote", "go-proxy", Optional.empty(),
            new ArtifactDeletion(new RecordingIndex(), new StorageMetaCache()),
            new ProxyPathCaches(
                "go-remote", "go-proxy", Optional.empty(), (type, pkg) -> { },
                repo -> Optional.empty()
            )
        );
        MatcherAssert.assertThat(
            ProxyEvictSliceTest.delete(slice, "/example.com/m/@v/v1.0.0.zip"),
            new IsEqual<>(404)
        );
    }

    @Test
    void refusesTraversal() {
        MatcherAssert.assertThat(
            ProxyEvictSliceTest.delete(
                ProxyEvictSliceTest.evict(
                    new InMemoryStorage(), new RecordingIndex(),
                    new NegativeCache(new NegativeCacheConfig()),
                    new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>()
                ),
                "/com/../other/x.jar"
            ),
            new IsEqual<>(400)
        );
    }

    @Test
    void npmEvictionInvalidatesThePackageEnvelope() {
        final List<String> envelopes = new CopyOnWriteArrayList<>();
        final Storage cache = ProxyEvictSliceTest.storage(
            "@qa/pkg/-/@qa/pkg-1.0.0.tgz", "@qa/pkg/-/@qa/pkg-1.0.0.tgz.meta", "@qa/pkg/meta.json"
        );
        final Slice slice = new ProxyEvictSlice(
            "npm-remote", "npm-proxy", Optional.of(cache),
            new ArtifactDeletion(new RecordingIndex(), new StorageMetaCache()),
            new ProxyPathCaches(
                "npm-remote", "npm-proxy", Optional.empty(),
                (type, pkg) -> envelopes.add(type + "|" + pkg), repo -> Optional.empty()
            )
        );
        MatcherAssert.assertThat(
            "the tarball eviction answers 204",
            ProxyEvictSliceTest.delete(slice, "/@qa/pkg/-/@qa/pkg-1.0.0.tgz"),
            new IsEqual<>(204)
        );
        MatcherAssert.assertThat(
            "the tarball and its .meta are gone, the packument stays",
            ProxyEvictSliceTest.keys(cache), new IsEqual<>(List.of("@qa/pkg/meta.json"))
        );
        MatcherAssert.assertThat(
            "the package envelope is invalidated",
            envelopes, new IsEqual<>(List.of("npm-proxy|@qa/pkg"))
        );
    }

    /**
     * Maven-proxy eviction slice with recording fakes.
     * @param cache Cache storage
     * @param index Index
     * @param negative Negative cache
     * @param envelopes Recorded envelope invalidations ({@code type|pkg})
     * @param hooks Recorded metadata-cache invalidations
     * @return Slice
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private static Slice evict(
        final Storage cache, final RecordingIndex index, final NegativeCache negative,
        final List<String> envelopes, final List<String> hooks
    ) {
        final ProxyMetadataRevalidators.Revalidator hook = pkg -> {
            hooks.add(pkg);
            return CompletableFuture.completedFuture("invalidated");
        };
        return new ProxyEvictSlice(
            ProxyEvictSliceTest.REPO, ProxyEvictSliceTest.TYPE, Optional.of(cache),
            new ArtifactDeletion(index, new StorageMetaCache()),
            new ProxyPathCaches(
                ProxyEvictSliceTest.REPO, ProxyEvictSliceTest.TYPE, Optional.of(negative),
                (type, pkg) -> envelopes.add(type + "|" + pkg),
                repo -> Optional.of(hook)
            )
        );
    }

    /**
     * Send a DELETE.
     * @param slice Slice
     * @param path Path
     * @return Status code
     */
    private static int delete(final Slice slice, final String path) {
        return slice.response(
            new RequestLine(RqMethod.DELETE, path), Headers.EMPTY, Content.EMPTY
        ).join().status().code();
    }

    /**
     * Storage holding the given keys.
     * @param keys Keys
     * @return Storage
     */
    private static Storage storage(final String... keys) {
        final Storage storage = new InMemoryStorage();
        for (final String key : keys) {
            storage.save(new Key.From(key), new Content.From(new byte[] {1})).join();
        }
        return storage;
    }

    /**
     * Sorted keys of a storage.
     * @param storage Storage
     * @return Keys
     */
    private static List<String> keys(final Storage storage) {
        return storage.list(Key.ROOT).join().stream().map(Key::string).sorted().toList();
    }
}
