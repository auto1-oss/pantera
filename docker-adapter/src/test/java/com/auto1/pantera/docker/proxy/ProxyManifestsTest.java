/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Catalog;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.http.DigestHeader;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import org.hamcrest.MatcherAssert;
import org.hamcrest.collection.IsEmptyIterable;
import org.hamcrest.core.StringStartsWith;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests for {@link ProxyManifests}.
 */
class ProxyManifestsTest {

    @Test
    void shouldGetManifest() {
        final byte[] data = "{ \"schemaVersion\": 2 }".getBytes();
        final String digest = "sha256:123";
        final Optional<Manifest> found = new ProxyManifests(
            (line, headers, body) -> {
                if (!line.toString().startsWith("GET /v2/test/manifests/abc ")) {
                    throw new IllegalArgumentException();
                }
                return ResponseBuilder.ok()
                    .header(new DigestHeader(new Digest.FromString(digest)))
                    .body(data)
                    .completedFuture();
            }, "test"
        ).get(ManifestReference.from("abc")).toCompletableFuture().join();
        Assertions.assertTrue(found.isPresent());
        final Manifest manifest = found.orElseThrow();
        Assertions.assertEquals(digest, manifest.digest().string());
        final Content content = manifest.content();
        Assertions.assertArrayEquals(data, content.asBytes());
        Assertions.assertEquals(Optional.of((long) data.length), content.size());

    }

    @Test
    void shouldGetEmptyWhenNotFound() {
        final Optional<Manifest> found = new ProxyManifests(
            (line, headers, body) -> {
                if (!line.toString().startsWith("GET /v2/my-test/manifests/latest ")) {
                    throw new IllegalArgumentException();
                }
                return ResponseBuilder.notFound().completedFuture();
            }, "my-test"
        ).get(ManifestReference.from("latest")).toCompletableFuture().join();
        Assertions.assertFalse(found.isPresent());
    }

    @Test
    void shouldSendRequestCatalogFromRemote() {
        final String name = "my-alpine";
        final int limit = 123;
        final AtomicReference<RequestLine> cline = new AtomicReference<>();
        final AtomicReference<Iterable<Header>> cheaders;
        cheaders = new AtomicReference<>();
        final AtomicReference<byte[]> cbody = new AtomicReference<>();
        new ProxyDocker(
            "test_registry",
            (line, headers, body) -> {
                cline.set(line);
                cheaders.set(headers);
                return new Content.From(body).asBytesFuture().thenApply(
                    bytes -> {
                        cbody.set(bytes);
                        return ResponseBuilder.ok().build();
                    }
                );
            }
        ).catalog(Pagination.from(name, limit)).join();
        MatcherAssert.assertThat(
            "Sends expected line to remote",
            cline.get().toString(),
            new StringStartsWith(String.format("GET /v2/_catalog?n=%d&last=%s ", limit, name))
        );
        MatcherAssert.assertThat(
            "Sends no headers to remote",
            cheaders.get(),
            new IsEmptyIterable<>()
        );
        Assertions.assertEquals(0, cbody.get().length, "Sends no body to remote");
    }

    @Test
    void shouldReturnCatalogFromRemote() {
        final byte[] bytes = "{\"repositories\":[\"one\",\"two\"]}".getBytes();
        Assertions.assertArrayEquals(
            bytes,
            new ProxyDocker(
                "test_registry",
                (line, headers, body) -> ResponseBuilder.ok().body(bytes).completedFuture()
            ).catalog(Pagination.empty()).thenCompose(
                catalog -> catalog.json().asBytesFuture()
            ).join()
        );
    }

    @Test
    void shouldFailReturnCatalogWhenRemoteRespondsWithNotOk() {
        final CompletionStage<Catalog> stage = new ProxyDocker(
            "test_registry",
            (line, headers, body) -> ResponseBuilder.notFound().completedFuture()
        ).catalog(Pagination.empty());
        Assertions.assertThrows(
            Exception.class,
            () -> stage.toCompletableFuture().join()
        );
    }
}
