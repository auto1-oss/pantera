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
import com.auto1.pantera.asto.streams.ContentAsStream;
import com.auto1.pantera.debian.Config;
import com.auto1.pantera.debian.metadata.*;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.KeyFromPath;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class DeleteSlice implements Slice {
    private final Storage asto;
    private final Config config;

    public DeleteSlice(final Storage asto, final Config config) {
        this.asto = asto;
        this.config = config;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final Key key = new KeyFromPath(line.uri().getPath());

        return this.asto.exists(key).thenCompose(
            exists -> {
                if (exists) {
                    return this.asto.value(key)
                        .thenCompose(
                                content -> new ContentAsStream<String>(content)
                                        .process(input -> new Control.FromInputStream(input).asString())
                        )
                        .thenCompose(
                            control -> {
                                final List<String> common = new ControlField.Architecture().value(control)
                                    .stream().filter(item -> this.config.archs().contains(item))
                                    .toList();

                                final CompletableFuture<Response> res;
                                CompletionStage<Void> upd = this.removeFromIndexes(key, control, common);

                                res = upd.thenCompose(
                                    nothing -> this.asto.delete(key)).thenApply(
                                    nothing -> ResponseBuilder.ok().build()
                                ).toCompletableFuture();

                                return res;
                            }
                        );
                }
                return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
            }
        );
    }

    private CompletionStage<Void> removeFromIndexes(final Key key, final String control,
                                                  final List<String> archs) {
        final Release release = new Release.Asto(this.asto, this.config);
        return new PackagesItem.Asto(this.asto).format(control, key).thenCompose(
            item -> CompletableFuture.allOf(
                archs.stream().map(
                    arc -> String.format(
                        "dists/%s/main/binary-%s/Packages.gz",
                        this.config.codename(), arc
                    )
                ).map(
                    index -> new UniquePackage(this.asto)
                        .delete(Collections.singletonList(item), new Key.From(index))
                        .thenCompose(
                            nothing -> release.update(new Key.From(index))
                        )
                ).toArray(CompletableFuture[]::new)
            ).thenCompose(
                nothing -> new InRelease.Asto(this.asto, this.config).generate(release.key())
            )
        );
    }
}
