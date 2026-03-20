/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.manifest;

import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.error.InvalidManifestException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsIterableContaining;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tests for {@link Manifest}.
 */
class ManifestTest {

    @Test
    void shouldReadMediaType() {
        final Manifest manifest = new Manifest(
            new Digest.Sha256("123"),
            "{\"mediaType\":\"something\"}".getBytes()
        );
        Assertions.assertEquals(manifest.mediaType(), "something");
    }

    @Test
    void shouldFailWhenMediaTypeIsAbsent() {
        final Manifest manifest = new Manifest(
            new Digest.Sha256("123"),
            "{\"abc\":\"123\"}".getBytes()
        );
        Assertions.assertThrows(
            InvalidManifestException.class,
            manifest::mediaType
        );
    }

    @Test
    void shouldInferOciManifestWhenMediaTypeAbsent() {
        final Manifest manifest = new Manifest(
            new Digest.Sha256("123"),
            Json.createObjectBuilder()
                .add("schemaVersion", 2)
                .add("config", Json.createObjectBuilder()
                    .add("mediaType", "application/vnd.oci.image.config.v1+json")
                    .add("digest", "sha256:abc"))
                .add("layers", Json.createArrayBuilder()
                    .add(Json.createObjectBuilder()
                        .add("digest", "sha256:def")))
                .build().toString().getBytes()
        );
        Assertions.assertEquals(Manifest.MANIFEST_OCI_V1, manifest.mediaType());
    }

    @Test
    void shouldInferOciIndexWhenMediaTypeAbsent() {
        final Manifest manifest = new Manifest(
            new Digest.Sha256("123"),
            Json.createObjectBuilder()
                .add("schemaVersion", 2)
                .add("manifests", Json.createArrayBuilder()
                    .add(Json.createObjectBuilder()
                        .add("digest", "sha256:abc")
                        .add("mediaType", "application/vnd.oci.image.manifest.v1+json")))
                .build().toString().getBytes()
        );
        Assertions.assertEquals(Manifest.MANIFEST_OCI_INDEX, manifest.mediaType());
    }

    @Test
    void shouldReadConfig() {
        final String digest = "sha256:def";
        final Manifest manifest = new Manifest(
            new Digest.Sha256("123"),
            Json.createObjectBuilder().add(
                "config",
                Json.createObjectBuilder().add("digest", digest)
            ).build().toString().getBytes()
        );
        MatcherAssert.assertThat(
            manifest.config().string(),
            new IsEqual<>(digest)
        );
    }

    @Test
    void shouldReadLayerDigests() {
        final String[] digests = {"sha256:123", "sha256:abc"};
        final Manifest manifest = new Manifest(
            new Digest.Sha256("12345"),
            Json.createObjectBuilder().add(
                "layers",
                Json.createArrayBuilder(
                    Stream.of(digests)
                        .map(dig -> Collections.singletonMap("digest", dig))
                        .collect(Collectors.toList())
                )
            ).build().toString().getBytes()
        );
        MatcherAssert.assertThat(
            manifest.layers().stream()
                .map(ManifestLayer::digest)
                .map(Digest::string)
                .collect(Collectors.toList()),
            Matchers.containsInAnyOrder(digests)
        );
    }

    @Test
    void shouldReadLayerUrls() throws Exception {
        final String url = "https://pantera.com/";
        final Manifest manifest = new Manifest(
            new Digest.Sha256("123"),
            Json.createObjectBuilder().add(
                "layers",
                Json.createArrayBuilder().add(
                    Json.createObjectBuilder()
                        .add("digest", "sha256:12345")
                        .add(
                            "urls",
                            Json.createArrayBuilder().add(url)
                        )
                )
            ).build().toString().getBytes()
        );
        MatcherAssert.assertThat(
            manifest.layers().stream()
                .flatMap(layer -> layer.urls().stream())
                .collect(Collectors.toList()),
            new IsIterableContaining<>(new IsEqual<>(URI.create(url).toURL()))
        );
    }

    @Test
    void shouldFailWhenLayersAreAbsent() {
        final Manifest manifest = new Manifest(
            new Digest.Sha256("123"),
            "{\"any\":\"value\"}".getBytes()
        );
        Assertions.assertThrows(
            InvalidManifestException.class,
            manifest::layers
        );
    }

    @Test
    void shouldDetectManifestListAndReturnNoLayers() {
        final Manifest manifest = new Manifest(
            new Digest.Sha256("123"),
            Json.createObjectBuilder()
                .add("mediaType", Manifest.MANIFEST_LIST_SCHEMA2)
                .add(
                    "manifests",
                    Json.createArrayBuilder()
                        .add(Json.createObjectBuilder().add("digest", "sha256:abc"))
                ).build().toString().getBytes()
        );
        Assertions.assertTrue(manifest.isManifestList());
        Assertions.assertTrue(manifest.layers().isEmpty());
    }

    @Test
    void shouldReadDigest() {
        final String digest = "sha256:123";
        final Manifest manifest = new Manifest(
            new Digest.FromString(digest),
            "{ \"schemaVersion\": 2 }".getBytes()
        );
        Assertions.assertEquals(manifest.digest().string(), digest);
    }

    @Test
    void shouldReadContent() {
        final byte[] data = "{ \"schemaVersion\": 2 }".getBytes();
        final Manifest manifest = new Manifest(
            new Digest.Sha256("123"),
            data
        );
        Assertions.assertArrayEquals(data, manifest.content().asBytes());
    }

    /**
     * Create new set from items.
     * @param items Items
     * @return Unmodifiable hash set
     */
    private static Set<? extends String> hashSet(final String... items) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(items)));
    }
}
