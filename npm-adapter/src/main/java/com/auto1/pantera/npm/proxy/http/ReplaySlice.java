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
package com.auto1.pantera.npm.proxy.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.RequestTrace;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.npm.proxy.NpmProxy;
import io.reactivex.Flowable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Sends a Troubleshoot replay through a proxy that stores nothing.
 *
 * <p>A replay ({@link RequestTrace}) asks what a client would get. It is a
 * diagnosis and must leave the repository as it found it: a tarball or a
 * packument the replay stored would enter the search index and change which
 * member a group asks from then on. A traced request is therefore served by
 * a slice built for it over {@link NpmProxy#replay()}: the same code as for
 * a client, on a proxy that reads the cache, asks the upstream and writes
 * nothing. What the replay fetched is freed when its answer has been read.
 * Any other request goes to the client slice as it is.</p>
 *
 * @since 2.2.15
 */
final class ReplaySlice implements Slice {

    /**
     * Slice that serves clients.
     */
    private final Slice client;

    /**
     * The proxy of the repository.
     */
    private final NpmProxy npm;

    /**
     * The slice of a replay, over the proxy of that replay.
     */
    private final Function<NpmProxy, Slice> replays;

    /**
     * Ctor.
     *
     * @param client Slice that serves clients
     * @param npm The proxy of the repository
     * @param replays Builds the slice of a replay over the proxy of that replay
     */
    ReplaySlice(
        final Slice client, final NpmProxy npm, final Function<NpmProxy, Slice> replays
    ) {
        this.client = client;
        this.npm = npm;
        this.replays = replays;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final CompletableFuture<Response> result;
        if (RequestTrace.instance().traced(headers)) {
            final NpmProxy.Replay replay = this.npm.replay();
            result = this.replays.apply(replay).response(line, headers, body)
                .whenComplete(
                    (response, error) -> {
                        if (error != null) {
                            replay.release();
                        }
                    }
                )
                .thenApply(
                    response -> new Response(
                        response.status(),
                        response.headers(),
                        new Content.From(
                            response.body().size(),
                            Flowable.fromPublisher(response.body()).doFinally(replay::release),
                            response.body().lastModified()
                        )
                    )
                );
        } else {
            result = this.client.response(line, headers, body);
        }
        return result;
    }
}
