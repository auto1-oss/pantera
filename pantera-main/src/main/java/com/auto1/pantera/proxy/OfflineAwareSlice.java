/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Offline-aware slice wrapper.
 * When offline mode is enabled, returns 503 for requests that would hit upstream,
 * serving only from local cache.
 *
 * @since 1.20.13
 */
public final class OfflineAwareSlice implements Slice {

    /**
     * Wrapped proxy slice.
     */
    private final Slice origin;

    /**
     * Offline flag.
     */
    private final AtomicBoolean offline;

    /**
     * Ctor.
     * @param origin Wrapped slice
     */
    public OfflineAwareSlice(final Slice origin) {
        this.origin = Objects.requireNonNull(origin, "origin");
        this.offline = new AtomicBoolean(false);
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        if (this.offline.get()) {
            return CompletableFuture.completedFuture(
                ResponseBuilder.unavailable()
                    .textBody("Repository is in offline mode")
                    .build()
            );
        }
        return this.origin.response(line, headers, body);
    }

    /**
     * Enable offline mode.
     */
    public void goOffline() {
        this.offline.set(true);
    }

    /**
     * Disable offline mode.
     */
    public void goOnline() {
        this.offline.set(false);
    }

    /**
     * Check if offline mode is enabled.
     * @return True if offline
     */
    public boolean isOffline() {
        return this.offline.get();
    }
}
