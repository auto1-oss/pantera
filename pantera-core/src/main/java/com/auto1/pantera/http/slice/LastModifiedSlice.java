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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ValueNotFoundException;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.LastModified;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Gives every served representation a {@code Last-Modified} header.
 *
 * <p>Generic HTTP clients (CI tool installers, {@code curl -z},
 * {@code wget -N}) need the header on a download and fail or re-download
 * without it. An adapter that dates its own response is left alone. Otherwise
 * a {@code GET} / {@code HEAD} answered 200 or 206 is dated with the time the
 * requested object was last written to the repository storage, or with the
 * time of the request when the path is not a stored object: a proxy that is
 * storing the artifact while it streams it, or a generated document.</p>
 *
 * <p>Only the header is added: status, the other headers (the
 * {@code Content-Length} of a {@code HEAD} answer among them) and the body
 * pass through. A storage failure never fails the response, and a storage
 * that does not answer within {@link #PATIENCE} never holds it: the response
 * is then dated with the time of the request.</p>
 *
 * <p>The lookup is one storage metadata read (a {@code HEAD} on an object
 * store) made after the origin answered, so a hot serve path that already
 * holds the object's metadata should date its own response and skip it.</p>
 *
 * @since 2.2.12
 */
public final class LastModifiedSlice implements Slice {

    /**
     * How long a response waits for the storage time of its object.
     */
    private static final Duration PATIENCE = Duration.ofMillis(500);

    /**
     * Wrapped slice.
     */
    private final Slice origin;

    /**
     * Repository storage, empty for a repository without one (a group).
     */
    private final Optional<Storage> storage;

    /**
     * Storage key of a request path, empty when the path names none.
     */
    private final Function<String, Optional<Key>> keys;

    /**
     * Clock for the time of the request.
     */
    private final Clock clock;

    /**
     * How long a response waits for the storage time of its object.
     */
    private final Duration patience;

    /**
     * Ctor.
     * @param origin Wrapped slice
     * @param storage Repository storage, empty when the repository has none
     * @param keys Storage key of a request path
     */
    public LastModifiedSlice(
        final Slice origin, final Optional<Storage> storage,
        final Function<String, Optional<Key>> keys
    ) {
        this(origin, storage, keys, Clock.systemUTC(), LastModifiedSlice.PATIENCE);
    }

    /**
     * Ctor.
     * @param origin Wrapped slice
     * @param storage Repository storage, empty when the repository has none
     * @param keys Storage key of a request path
     * @param clock Clock for the time of the request
     * @param patience How long a response waits for the storage time
     */
    LastModifiedSlice(
        final Slice origin, final Optional<Storage> storage,
        final Function<String, Optional<Key>> keys, final Clock clock,
        final Duration patience
    ) {
        this.origin = origin;
        this.storage = storage;
        this.keys = keys;
        this.clock = clock;
        this.patience = patience;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final CompletableFuture<Response> response =
            this.origin.response(line, headers, body);
        final CompletableFuture<Response> res;
        if (line.method() == RqMethod.GET || line.method() == RqMethod.HEAD) {
            res = response.thenCompose(
                resp -> {
                    final CompletableFuture<Response> dated;
                    if (LastModifiedSlice.undated(resp)) {
                        dated = this.modified(line.uri().getPath()).thenApply(
                            modified -> new Response(
                                resp.status(),
                                resp.headers().copy().add(modified),
                                resp.body()
                            )
                        );
                    } else {
                        dated = CompletableFuture.completedFuture(resp);
                    }
                    return dated;
                }
            );
        } else {
            res = response;
        }
        return res;
    }

    /**
     * Whether the response serves a representation and carries no date yet.
     * @param resp Response
     * @return True when the header has to be added
     */
    private static boolean undated(final Response resp) {
        return (resp.status() == RsStatus.OK || resp.status() == RsStatus.PARTIAL_CONTENT)
            && resp.headers().find(LastModified.NAME).isEmpty();
    }

    /**
     * The date of the requested object: its storage time, never later than
     * now; the time of the request when it is not a stored object or its
     * storage time cannot be read in time.
     * @param path Request path
     * @return Header, never a failed future
     */
    private CompletableFuture<LastModified> modified(final String path) {
        final Instant now = this.clock.instant();
        final LastModified request = new LastModified(now);
        // Composed from a completed stage so that anything thrown while the
        // key is resolved or the storage is called ends in the fallback
        // below instead of failing the response.
        return CompletableFuture.completedFuture(path)
            .thenCompose(target -> this.stored(target, now))
            .completeOnTimeout(request, this.patience.toMillis(), TimeUnit.MILLISECONDS)
            .exceptionally(
                err -> {
                    if (!LastModifiedSlice.missing(err)) {
                        EcsLogger.debug("com.auto1.pantera.http")
                            .message(
                                "Storage time unavailable; Last-Modified is the request time"
                            )
                            .eventCategory("file")
                            .eventAction("last_modified_lookup")
                            .eventOutcome("failure")
                            .field("url.path", path)
                            .error(err)
                            .field("log.source", "application")
                            .log();
                    }
                    return request;
                }
            );
    }

    /**
     * The date of the object a request path names.
     * @param path Request path
     * @param now Time of the request, the date when the path names no
     *  object of a storage
     * @return Header; fails when the object is missing or unreadable
     */
    private CompletableFuture<LastModified> stored(final String path, final Instant now) {
        final Optional<Key> key = this.storage.flatMap(ignored -> this.keys.apply(path));
        final CompletableFuture<LastModified> res;
        if (key.isPresent()) {
            res = this.storage.get().metadata(key.get())
                .thenApply(meta -> new LastModified(meta, now));
        } else {
            res = CompletableFuture.completedFuture(new LastModified(now));
        }
        return res;
    }

    /**
     * Whether a metadata failure only says the path is not a stored object.
     * @param err Failure
     * @return True for a missing key
     */
    private static boolean missing(final Throwable err) {
        Throwable cause = err;
        boolean res = false;
        while (cause != null && !res) {
            res = cause instanceof ValueNotFoundException;
            cause = cause.getCause();
        }
        return res;
    }
}
