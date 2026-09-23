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
package com.auto1.pantera.debian.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.debian.Config;
import com.auto1.pantera.debian.metadata.InRelease;
import com.auto1.pantera.debian.metadata.Release;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.zip.GZIPOutputStream;

/**
 * Release slice decorator.
 * Checks, whether Release index exists and creates it if necessary. The
 * Release index lists a Packages index for every configured component and
 * architecture, so a missing one is created empty: apt fails the whole
 * update on a Packages index the Release promises but the server does not
 * have (before the first package of that architecture is uploaded).
 */
public final class ReleaseSlice implements Slice {

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * Abstract storage.
     */
    private final Storage storage;

    /**
     * Repository release index.
     */
    private final Release release;

    /**
     * Repository InRelease index.
     */
    private final InRelease inrelease;

    /**
     * Packages indexes the Release lists.
     */
    private final List<Key> indexes;

    /**
     * @param origin Origin
     * @param asto Storage
     * @param release Release index
     * @param inrelease InRelease index
     */
    public ReleaseSlice(final Slice origin, final Storage asto, final Release release,
        final InRelease inrelease) {
        this(origin, asto, release, inrelease, Collections.emptyList());
    }

    /**
     * @param origin Origin
     * @param asto Storage
     * @param config Repository configuration
     */
    public ReleaseSlice(final Slice origin, final Storage asto, final Config config) {
        this(
            origin, asto, new Release.Asto(asto, config), new InRelease.Asto(asto, config),
            ReleaseSlice.packagesIndexes(config)
        );
    }

    /**
     * @param origin Origin
     * @param asto Storage
     * @param release Release index
     * @param inrelease InRelease index
     * @param indexes Packages indexes the Release lists
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    ReleaseSlice(final Slice origin, final Storage asto, final Release release,
        final InRelease inrelease, final List<Key> indexes) {
        this.origin = origin;
        this.release = release;
        this.storage = asto;
        this.inrelease = inrelease;
        this.indexes = indexes;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        return this.storage.exists(this.release.key()).thenCompose(
            exists -> {
                final CompletionStage<Void> ready;
                if (exists && line.uri().getPath().startsWith("/dists/")) {
                    ready = this.createMissingIndexes().thenCompose(this::listInRelease);
                } else if (exists) {
                    ready = CompletableFuture.allOf();
                } else {
                    ready = this.createMissingIndexes()
                        .thenCompose(created -> this.release.create())
                        .thenCompose(nothing -> this.inrelease.generate(this.release.key()));
                }
                return ready.thenCompose(nothing -> this.origin.response(line, headers, body));
            }
        );
    }

    /**
     * Add freshly created Packages indexes to the existing Release index.
     * @param created Created indexes
     * @return Completion action
     */
    private CompletionStage<Void> listInRelease(final List<Key> created) {
        CompletionStage<Void> res = CompletableFuture.allOf();
        if (!created.isEmpty()) {
            for (final Key index : created) {
                res = res.thenCompose(nothing -> this.release.update(index));
            }
            res = res.thenCompose(nothing -> this.inrelease.generate(this.release.key()));
        }
        return res;
    }

    /**
     * Create the configured Packages indexes that do not exist yet, empty.
     * @return Keys of the created indexes
     */
    private CompletionStage<List<Key>> createMissingIndexes() {
        final List<Key> created = Collections.synchronizedList(new ArrayList<>(0));
        return CompletableFuture.allOf(
            this.indexes.stream().map(
                index -> this.storage.exists(index).thenCompose(
                    present -> {
                        final CompletableFuture<Void> res;
                        if (present) {
                            res = CompletableFuture.completedFuture(null);
                        } else {
                            res = this.storage.save(
                                index, new Content.From(ReleaseSlice.emptyGzip())
                            ).thenRun(() -> created.add(index));
                        }
                        return res;
                    }
                )
            ).toArray(CompletableFuture[]::new)
        ).thenApply(nothing -> new ArrayList<>(created));
    }

    /**
     * Packages index keys of every configured component and architecture.
     * @param config Repository configuration
     * @return Keys
     */
    private static List<Key> packagesIndexes(final Config config) {
        final List<Key> res = new ArrayList<>(0);
        for (final String component : config.components()) {
            for (final String arch : config.archs()) {
                res.add(
                    new Key.From(
                        String.format(
                            "dists/%s/%s/binary-%s/Packages.gz",
                            config.codename(), component, arch
                        )
                    )
                );
            }
        }
        return res;
    }

    /**
     * Gzip of no bytes: an empty Packages index.
     * @return Bytes
     */
    private static byte[] emptyGzip() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.finish();
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
    }
}
