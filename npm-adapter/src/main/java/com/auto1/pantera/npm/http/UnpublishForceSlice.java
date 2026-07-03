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
package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.npm.PackageNameFromUrl;
import com.auto1.pantera.scheduling.ArtifactEvent;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slice to handle `npm unpublish` command requests.
 * Request line to this slice looks like `/[<@scope>/]pkg/-rev/undefined`.
 * It unpublishes the whole package or a single version of package
 * when only one version is published.
 */
final class UnpublishForceSlice implements Slice {
    /**
     * Endpoint request line pattern.
     */
    static final Pattern PTRN = Pattern.compile("/.*/-rev/.*$");

    /**
     * Abstract Storage.
     */
    private final Storage storage;

    /**
     * Artifact events queue.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Repository name.
     */
    private final String rname;

    /**
     * Ctor.
     * @param storage Abstract storage
     * @param events Events queue
     * @param rname Repository name
     */
    UnpublishForceSlice(final Storage storage, final Optional<Queue<ArtifactEvent>> events,
        final String rname) {
        this.storage = storage;
        this.events = events;
        this.rname = rname;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String uri = line.uri().getPath();
        final Matcher matcher = UnpublishForceSlice.PTRN.matcher(uri);
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        return body.asBytesFuture().thenCompose(ignored -> {
            if (matcher.matches()) {
                final String pkg = new PackageNameFromUrl(
                    String.format(
                        "%s %s %s", line.method(),
                        uri.substring(0, uri.indexOf("/-rev/")),
                        line.version()
                    )
                ).value();
                CompletableFuture<Void> res = this.storage.deleteAll(new Key.From(pkg));
                if (this.events.isPresent()) {
                    res = res.thenRun(
                        () -> this.events.map(
                            queue -> queue.add( // ok: unbounded ConcurrentLinkedDeque (ArtifactEvent queue)
                                new ArtifactEvent(UploadSlice.REPO_TYPE, this.rname, pkg)
                            )
                        )
                    );
                }
                return res.thenApply(nothing -> ResponseBuilder.ok().build());
            }
            return ResponseBuilder.badRequest().completedFuture();
        });
    }
}
