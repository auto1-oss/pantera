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
package com.auto1.pantera.docker.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.docker.Blob;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.ManifestVariant;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.Tags;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.docker.asto.BlobSource;
import com.auto1.pantera.docker.asto.Uploads;
import com.auto1.pantera.docker.cache.CacheManifests;
import com.auto1.pantera.docker.http.DigestHeader;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.headers.Accept;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WS4-docker.7: the Docker proxy must key its manifest cache by the negotiated
 * {@code Accept}-variant. Two clients requesting the same tag with different
 * acceptable media types must each receive their own representation — a
 * v2-manifest client must never be served a cached OCI index, and vice versa.
 */
final class ProxyManifestAcceptVariantTest {

    /**
     * A single-arch Docker v2 manifest.
     */
    private static final byte[] V2_MANIFEST = (
        "{\"mediaType\":\"" + Manifest.MANIFEST_SCHEMA2 + "\","
            + "\"config\":{\"digest\":\"sha256:c0ffee\"},\"layers\":[]}"
    ).getBytes();

    /**
     * A multi-arch OCI image index for the same tag.
     */
    private static final byte[] OCI_INDEX = (
        "{\"mediaType\":\"" + Manifest.MANIFEST_OCI_INDEX + "\",\"manifests\":[]}"
    ).getBytes();

    private static final ManifestVariant VARIANT_V2 =
        ManifestVariant.fromAccept(List.of(Manifest.MANIFEST_SCHEMA2));

    private static final ManifestVariant VARIANT_INDEX =
        ManifestVariant.fromAccept(List.of(Manifest.MANIFEST_OCI_INDEX));

    /**
     * {@link ProxyManifests} must forward the client's negotiated {@code Accept}
     * upstream so the registry returns the variant the client asked for, rather
     * than a fixed superset. Proven by a fake remote that content-negotiates on
     * the inbound {@code Accept} header.
     */
    @Test
    void proxyForwardsNegotiatedAcceptUpstream() {
        final AtomicReference<List<String>> lastAccept = new AtomicReference<>();
        final ProxyManifests proxy = new ProxyManifests(
            (line, headers, body) -> {
                final List<String> accept = new Accept(headers).values();
                lastAccept.set(accept);
                final byte[] bytes;
                if (accept.contains(Manifest.MANIFEST_OCI_INDEX)) {
                    bytes = OCI_INDEX;
                } else {
                    bytes = V2_MANIFEST;
                }
                return ResponseBuilder.ok()
                    .header(new DigestHeader(new Digest.Sha256(bytes)))
                    .body(bytes)
                    .completedFuture();
            },
            "library/img"
        );
        final ManifestReference tag = ManifestReference.fromTag("latest");
        final Manifest v2 = proxy.get(tag, VARIANT_V2).join().orElseThrow();
        MatcherAssert.assertThat(
            "v2 Accept must be forwarded upstream verbatim",
            lastAccept.get(),
            new IsEqual<>(List.of(Manifest.MANIFEST_SCHEMA2))
        );
        MatcherAssert.assertThat(
            "an Accept of manifest.v2 must yield the v2 manifest",
            v2.mediaType(),
            new IsEqual<>(Manifest.MANIFEST_SCHEMA2)
        );
        final Manifest index = proxy.get(tag, VARIANT_INDEX).join().orElseThrow();
        MatcherAssert.assertThat(
            "index Accept must be forwarded upstream verbatim",
            lastAccept.get(),
            new IsEqual<>(List.of(Manifest.MANIFEST_OCI_INDEX))
        );
        MatcherAssert.assertThat(
            "an Accept of oci.index must yield the OCI image index",
            index.mediaType(),
            new IsEqual<>(Manifest.MANIFEST_OCI_INDEX)
        );
    }

    /**
     * The proxy cache must store each {@code Accept}-variant under its own key
     * and serve each back independently. The offline read (origin down) is the
     * decisive proof: without variant keying both variants would resolve to the
     * single last-written cache entry and cross-serve.
     */
    @Test
    void cacheKeysManifestByAcceptVariantAndServesEachOffline() throws Exception {
        final ManifestReference tag = ManifestReference.fromTag("latest");
        // The two variants must resolve to distinct cache keys.
        MatcherAssert.assertThat(
            "distinct Accept-variants must produce distinct cache keys for one tag",
            tag.withVariant(VARIANT_V2).link().string(),
            new IsNot<>(new IsEqual<>(tag.withVariant(VARIANT_INDEX).link().string()))
        );
        final Repo cache = new AstoDocker("registry", new InMemoryStorage()).repo("img-cache");
        final AtomicBoolean online = new AtomicBoolean(true);
        final Repo origin = new NegotiatingOrigin(
            online,
            Map.of(
                Manifest.MANIFEST_SCHEMA2, V2_MANIFEST,
                Manifest.MANIFEST_OCI_INDEX, OCI_INDEX
            )
        );
        final CacheManifests manifests = new CacheManifests(
            "library/img", origin, cache, Optional.empty(), "docker-proxy", Optional.empty()
        );
        // Warm both variants while the origin is online.
        MatcherAssert.assertThat(
            "online v2 fetch resolves to the v2 manifest",
            manifests.get(tag, VARIANT_V2).join().orElseThrow().mediaType(),
            new IsEqual<>(Manifest.MANIFEST_SCHEMA2)
        );
        awaitCached(cache, tag.withVariant(VARIANT_V2));
        MatcherAssert.assertThat(
            "online index fetch resolves to the OCI index",
            manifests.get(tag, VARIANT_INDEX).join().orElseThrow().mediaType(),
            new IsEqual<>(Manifest.MANIFEST_OCI_INDEX)
        );
        awaitCached(cache, tag.withVariant(VARIANT_INDEX));
        // Take the origin offline; each variant must now be served from its own
        // cached key — never the other variant's bytes.
        online.set(false);
        MatcherAssert.assertThat(
            "offline v2 request must serve the cached v2 variant, not the index",
            manifests.get(tag, VARIANT_V2).join().orElseThrow().mediaType(),
            new IsEqual<>(Manifest.MANIFEST_SCHEMA2)
        );
        MatcherAssert.assertThat(
            "offline index request must serve the cached index variant, not the v2 manifest",
            manifests.get(tag, VARIANT_INDEX).join().orElseThrow().mediaType(),
            new IsEqual<>(Manifest.MANIFEST_OCI_INDEX)
        );
    }

    /**
     * Polls until the fire-and-forget cache write for {@code ref} lands.
     */
    private static void awaitCached(final Repo cache, final ManifestReference ref)
        throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (cache.manifests().get(ref).join().isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("cache entry never materialised for " + ref.link());
            }
            Thread.sleep(50L);
        }
    }

    /**
     * Origin repo that content-negotiates a manifest on the requested variant's
     * acceptable media types, and goes dark (empty) when taken offline.
     */
    private static final class NegotiatingOrigin implements Repo {

        private final AtomicBoolean online;

        private final Map<String, byte[]> byMediaType;

        private NegotiatingOrigin(final AtomicBoolean online, final Map<String, byte[]> byMediaType) {
            this.online = online;
            this.byMediaType = byMediaType;
        }

        @Override
        public Layers layers() {
            return new EmptyLayers();
        }

        @Override
        public Manifests manifests() {
            return new Manifests() {
                @Override
                public CompletableFuture<Manifest> put(final ManifestReference ref, final Content content) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public CompletableFuture<Optional<Manifest>> get(final ManifestReference ref) {
                    return this.get(ref, ManifestVariant.any());
                }

                @Override
                public CompletableFuture<Optional<Manifest>> get(
                    final ManifestReference ref, final ManifestVariant variant
                ) {
                    Optional<Manifest> found = Optional.empty();
                    if (NegotiatingOrigin.this.online.get()) {
                        for (final Map.Entry<String, byte[]> entry
                            : NegotiatingOrigin.this.byMediaType.entrySet()) {
                            if (variant.mediaTypes().contains(entry.getKey())) {
                                found = Optional.of(
                                    new Manifest(new Digest.Sha256(entry.getValue()), entry.getValue())
                                );
                                break;
                            }
                        }
                    }
                    return CompletableFuture.completedFuture(found);
                }

                @Override
                public CompletableFuture<Tags> tags(final Pagination pagination) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public CompletableFuture<Void> delete(final ManifestReference ref) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public Uploads uploads() {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Layers stub — the variant-caching path never touches it in this test
     * (events/inspector absent), but Repo requires an implementation.
     */
    private static final class EmptyLayers implements Layers {

        @Override
        public CompletableFuture<Digest> put(final BlobSource source) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> mount(final Blob blob) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Optional<Blob>> get(final Digest digest) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<Void> delete(final Digest digest) {
            throw new UnsupportedOperationException();
        }
    }
}
