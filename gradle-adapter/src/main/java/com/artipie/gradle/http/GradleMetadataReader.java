/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.gradle.http;

import com.artipie.asto.Content;
import com.artipie.asto.Remaining;
import com.artipie.http.Headers;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import com.jcabi.log.Logger;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;

import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Reads Gradle metadata and POM files from remote repository.
 *
 * @since 1.0
 */
final class GradleMetadataReader {

    private final Slice remote;

    GradleMetadataReader(final Slice remote) {
        this.remote = remote;
    }

    CompletableFuture<Optional<String>> readModuleMetadata(final String artifact, final String version) {
        final String path = GradlePathBuilder.modulePath(artifact, version);
        return this.remote.response(
            new RequestLine(RqMethod.GET, path),
            Headers.EMPTY,
            Content.EMPTY
        ).thenCompose(response -> {
            if (!response.status().success()) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            return bodyBytes(response.body())
                .thenApply(bytes -> Optional.of(new String(bytes, StandardCharsets.UTF_8)));
        });
    }

    CompletableFuture<Optional<String>> readPom(final String artifact, final String version) {
        final String path = GradlePathBuilder.pomPath(artifact, version);
        return this.remote.response(
            new RequestLine(RqMethod.GET, path),
            Headers.EMPTY,
            Content.EMPTY
        ).thenCompose(response -> {
            if (!response.status().success()) {
                Logger.warn(this, "Failed to fetch POM %s: %s", path, response.status());
                return CompletableFuture.completedFuture(Optional.empty());
            }
            return bodyBytes(response.body())
                .thenApply(bytes -> Optional.of(new String(bytes, StandardCharsets.UTF_8)));
        });
    }

    private static CompletableFuture<byte[]> bodyBytes(final org.reactivestreams.Publisher<ByteBuffer> body) {
        return Flowable.fromPublisher(body)
            .reduce(new ByteArrayOutputStream(), (stream, buffer) -> {
                try {
                    stream.write(new Remaining(buffer).bytes());
                    return stream;
                } catch (final java.io.IOException error) {
                    throw new UncheckedIOException(error);
                }
            })
            .map(ByteArrayOutputStream::toByteArray)
            .onErrorReturnItem(new byte[0])
            .to(SingleInterop.get())
            .toCompletableFuture();
    }
}
