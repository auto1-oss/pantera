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
package com.auto1.pantera.rpm.misc;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ext.ContentDigest;
import com.auto1.pantera.asto.ext.Digests;
import com.auto1.pantera.asto.ext.KeyLastPart;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Completable;
import io.reactivex.Flowable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Copy rpms from one storage to another filtering by digests.
 */
public final class RpmByDigestCopy {

    /**
     * Storage to copy from.
     */
    private final Storage from;

    /**
     * Key to copy from.
     */
    private final Key key;

    /**
     * Content hex digests to exclude.
     */
    private final List<String> digests;

    /**
     * Digest algorithm.
     */
    private final Digests algorithm;

    /**
     * Ctor.
     * @param from Storage to copy from
     * @param key Key to copy from
     * @param digests Content digests to exclude
     * @param algorithm Digest algorithm
     */
    public RpmByDigestCopy(
        final Storage from, final Key key, final List<String> digests,
        final Digests algorithm
    ) {
        this.from = from;
        this.digests = digests;
        this.key = key;
        this.algorithm = algorithm;
    }

    /**
     * Ctor.
     * @param from Storage to copy from
     * @param key Key to copy from
     * @param digests Content digests to exclude
     */
    public RpmByDigestCopy(final Storage from, final Key key, final List<String> digests) {
        this(from, key, digests, Digests.SHA256);
    }

    /**
     * Copy rpm to destination storage filtering by digest.
     * @param dest Destination
     * @return Completable copy operation
     */
    Completable copy(final Storage dest) {
        return SingleInterop.fromFuture(this.from.list(this.key))
            .flatMapPublisher(Flowable::fromIterable)
            .filter(item -> item.string().endsWith(".rpm"))
            .flatMapCompletable(
                rpm -> Completable.fromFuture(
                    this.from.value(rpm).thenCompose(content -> this.handleRpm(dest, rpm, content))
                )
            );
    }

    /**
     * Handle rpm: calc its digest and check whether it's present in digests list, save if to
     * storage is necessary.
     * @param dest Where to copy
     * @param rpm Rpm file key
     * @param content Rpm content
     * @return CompletionStage action
     */
    private CompletionStage<Void> handleRpm(
        final Storage dest, final Key rpm, final Content content
    ) {
        return content.asBytesFuture().thenCompose(
            source -> new ContentDigest(new Content.From(source), this.algorithm)
                .hex().thenCompose(
                    hex -> {
                        final CompletableFuture<Void> res;
                        if (this.digests.contains(hex)) {
                            res = CompletableFuture.allOf();
                        } else {
                            res = dest.save(
                                new Key.From(new KeyLastPart(rpm).get()), new Content.From(source)
                            );
                        }
                        return res;
                    }
                )
            );
    }
}
