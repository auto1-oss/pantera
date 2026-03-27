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
package com.auto1.pantera.docker.asto;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.ExampleStorage;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.Tags;
import com.auto1.pantera.docker.error.InvalidManifestException;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.docker.misc.Pagination;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsInstanceOf;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.json.Json;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Tests for {@link AstoManifests}.
 */
final class AstoManifestsTest {

    /**
     * Blobs used in tests.
     */
    private Blobs blobs;

    /**
     * Repository manifests being tested.
     */
    private AstoManifests manifests;

    @BeforeEach
    void setUp() {
        final Storage storage = new ExampleStorage();
        this.blobs = new Blobs(storage);
        this.manifests = new AstoManifests(storage, this.blobs, "my-alpine");
    }

    @Test
    @Timeout(5)
    void shouldReadManifest() {
        final byte[] manifest = this.manifest(ManifestReference.from("1"));
        MatcherAssert.assertThat(manifest.length, Matchers.equalTo(528));
    }

    @Test
    @Timeout(5)
    void shouldReadNoManifestIfAbsent() throws Exception {
        final Optional<Manifest> manifest = this.manifests.get(ManifestReference.from("2")).get();
        MatcherAssert.assertThat(manifest.isPresent(), new IsEqual<>(false));
    }

    @Test
    @Timeout(5)
    void shouldReadAddedManifest() {
        final Digest config = this.blobs.put(new TrustedBlobSource("config".getBytes())).join();
        final Digest layer = this.blobs.put(new TrustedBlobSource("layer".getBytes())).join();
        final byte[] data = this.getJsonBytes(config, layer, "my-type");
        final ManifestReference ref = ManifestReference.fromTag("some-tag");
        final Manifest manifest = this.manifests.put(ref, new Content.From(data)).join();
        MatcherAssert.assertThat(this.manifest(ref), new IsEqual<>(data));
        MatcherAssert.assertThat(
            this.manifest(ManifestReference.from(manifest.digest())),
            Matchers.is(data)
        );
    }

    @Test
    @Timeout(5)
    void shouldInferMediaTypeWhenEmptyOnPut() {
        final Digest config = this.blobs.put(new TrustedBlobSource("config".getBytes())).join();
        final Digest layer = this.blobs.put(new TrustedBlobSource("layer".getBytes())).join();
        final byte[] data = this.getJsonBytes(config, layer, "");
        final Manifest manifest = this.manifests.put(
            ManifestReference.fromTag("ddd"),
            new Content.From(data)
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "Manifest with config+layers should infer OCI v1 media type",
            manifest.mediaType(),
            new IsEqual<>(Manifest.MANIFEST_OCI_V1)
        );
    }

    @Test
    @Timeout(5)
    void shouldFailPutManifestIfMediaTypeUnrecognizable() {
        final byte[] data = Json.createObjectBuilder()
            .add("schemaVersion", 2)
            .build().toString().getBytes();
        final CompletionStage<Manifest> future = this.manifests.put(
            ManifestReference.fromTag("bad"),
            new Content.From(data)
        );
        final CompletionException exception = Assertions.assertThrows(
            CompletionException.class,
            () -> future.toCompletableFuture().join()
        );
        MatcherAssert.assertThat(
            "Exception cause should be instance of InvalidManifestException",
            exception.getCause(),
            new IsInstanceOf(InvalidManifestException.class)
        );
    }

    @Test
    @Timeout(5)
    void shouldFailPutInvalidManifest() {
        final CompletionStage<Manifest> future = this.manifests.put(
            ManifestReference.from("ttt"),
            Content.EMPTY
        );
        final CompletionException exception = Assertions.assertThrows(
            CompletionException.class,
            () -> future.toCompletableFuture().join()
        );
        MatcherAssert.assertThat(
            exception.getCause(),
            new IsInstanceOf(InvalidManifestException.class)
        );
    }

    @Test
    @Timeout(5)
    void shouldReadTags() {
        final Tags tags = this.manifests.tags(Pagination.empty())
            .toCompletableFuture().join();
        MatcherAssert.assertThat(
            tags.json().asString(),
            Matchers.is("{\"name\":\"my-alpine\",\"tags\":[\"1\",\"latest\"]}")
        );
    }

    private byte[] manifest(final ManifestReference ref) {
        return this.manifests.get(ref)
            .thenApply(res -> res.orElseThrow().content())
            .thenCompose(Content::asBytesFuture)
            .join();
    }

    private byte[] getJsonBytes(Digest config, Digest layer, String mtype) {
        return Json.createObjectBuilder()
            .add(
                "config",
                Json.createObjectBuilder().add("digest", config.string())
            )
            .add("mediaType", mtype)
            .add(
                "layers",
                Json.createArrayBuilder()
                    .add(
                        Json.createObjectBuilder().add("digest", layer.string())
                    )
                    .add(
                        Json.createObjectBuilder()
                            .add("digest", "sha256:123")
                            .add("urls", Json.createArrayBuilder().add("https://pantera.com/"))
                    )
            )
            .build().toString().getBytes();
    }
}
