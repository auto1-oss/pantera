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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.PathClash;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.log.EcsLogger;
import java.util.concurrent.CompletionException;

/**
 * The {@code 409 Conflict} answer to an upload whose key clashes with the
 * storage tree: a file stands where a directory is needed (at any depth),
 * or a directory stands where the file should go.
 *
 * <p>Shared by every upload slice so a clash is one client error with one
 * log line (a WARN, no stack trace) whatever the format.</p>
 *
 * @since 2.2.9
 */
public final class PathClashResponse {

    /**
     * Uploaded key.
     */
    private final Key key;

    /**
     * Ctor.
     * @param key Uploaded key
     */
    public PathClashResponse(final Key key) {
        this.key = key;
    }

    /**
     * Answer a failed upload: {@code 409} for a path clash, any other
     * failure propagates unchanged.
     * @param err Upload failure
     * @return Conflict response
     */
    public Response recover(final Throwable err) {
        final Throwable clash = new PathClash(err).cause().orElse(null);
        if (clash == null) {
            if (err instanceof RuntimeException rte) {
                throw rte;
            }
            throw new CompletionException(err);
        }
        return this.conflict(clash.getClass().getSimpleName());
    }

    /**
     * Log and build the conflict answer.
     * @param reason Why the path clashes
     * @return Conflict response
     */
    public Response conflict(final String reason) {
        EcsLogger.warn("com.auto1.pantera.http")
            .message("Upload rejected: the path clashes with an existing file or directory")
            .eventCategory("file")
            .eventAction("artifact_upload")
            .eventOutcome("failure")
            .field("event.reason", reason)
            .field("file.path", this.key.string())
            .field("http.response.status_code", 409)
            .field("log.source", "application")
            .log();
        return ResponseBuilder.from(RsStatus.CONFLICT)
            .textBody("Conflict: the path clashes with an existing file or directory")
            .build();
    }
}
