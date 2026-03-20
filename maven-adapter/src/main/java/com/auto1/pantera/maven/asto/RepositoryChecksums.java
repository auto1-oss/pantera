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
package com.auto1.pantera.maven.asto;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ext.ContentDigest;
import com.auto1.pantera.asto.ext.Digests;
import com.auto1.pantera.asto.rx.RxStorageWrapper;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Observable;
import org.apache.commons.lang3.tuple.ImmutablePair;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Checksums for Maven artifact.
 */
public final class RepositoryChecksums {

    /**
     * Supported checksum algorithms.
     */
    private static final Set<String> SUPPORTED_ALGS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("sha512", "sha256", "sha1", "md5"))
    );

    /**
     * Repository storage.
     */
    private final Storage repo;

    /**
     * Repository checksums.
     * @param repo Repository storage
     */
    public RepositoryChecksums(final Storage repo) {
        this.repo = repo;
    }

    /**
     * Checksums of artifact.
     * @param artifact Artifact {@link Key}
     * @return Checksums future
     */
    public CompletionStage<? extends Map<String, String>> checksums(final Key artifact) {
        final RxStorageWrapper rxsto = new RxStorageWrapper(this.repo);
        return rxsto.list(artifact).flatMapObservable(Observable::fromIterable)
            .filter(key -> SUPPORTED_ALGS.contains(extension(key)))
            .flatMapSingle(
                // Use non-blocking RxFuture.single instead of blocking SingleInterop.fromFuture
                item -> com.auto1.pantera.asto.rx.RxFuture.single(
                    this.repo.value(item).thenCompose(Content::asStringFuture)
                        .thenApply(hash -> new ImmutablePair<>(extension(item), hash))
                )
            ).reduce(
                new HashMap<String, String>(),
                (map, hash) -> {
                    map.put(hash.getKey(), hash.getValue());
                    return map;
                }
            ).to(SingleInterop.get());
    }

    /**
     * Calculates and generates artifact checksum files.
     * @param artifact Artifact
     * @return Completable action
     */
    public CompletionStage<Void> generate(final Key artifact) {
        return CompletableFuture.allOf(
            SUPPORTED_ALGS.stream().map(
                alg -> this.repo.value(artifact).thenCompose(
                    content -> new ContentDigest(
                        content, Digests.valueOf(alg.toUpperCase(Locale.US))
                    ).hex().thenCompose(
                        hex -> this.repo.save(
                            new Key.From(String.format("%s.%s", artifact.string(), alg)),
                            new Content.From(hex.getBytes(StandardCharsets.UTF_8))
                        )
                    )
                )
            ).toArray(CompletableFuture[]::new)
        );
    }

    /**
     * Key extension.
     * @param key Key
     * @return Extension string
     */
    private static String extension(final Key key) {
        final String src = key.string();
        return src.substring(src.lastIndexOf('.') + 1).toLowerCase(Locale.US);
    }
}
