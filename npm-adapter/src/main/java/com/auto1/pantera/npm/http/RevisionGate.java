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

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.npm.misc.PackumentRevision;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.json.Json;

/**
 * Optimistic-concurrency gate of the npm unpublish requests: a destructive
 * change runs only when the client sends the package's current
 * {@link PackumentRevision} (the {@code _rev} of the packument it read) as
 * the {@code /-rev/<rev>} path segment. An absent, literal
 * {@code undefined} or malformed revision answers 428, a stale one 409.
 *
 * @since 2.2.9
 */
final class RevisionGate {

    /**
     * The revision segment of a request path.
     */
    private static final Pattern REV = Pattern.compile("/-rev/([^/]*)");

    /**
     * Storage holding the packages.
     */
    private final Storage storage;

    /**
     * Ctor.
     * @param storage Storage holding the packages
     */
    RevisionGate(final Storage storage) {
        this.storage = storage;
    }

    /**
     * The revision a request path carries, empty when it has none.
     * @param path Request path
     * @return Revision
     */
    static String revision(final String path) {
        final Matcher matcher = RevisionGate.REV.matcher(path);
        final String sent;
        if (matcher.find()) {
            sent = matcher.group(1);
        } else {
            sent = "";
        }
        return sent;
    }

    /**
     * Run the change only when the supplied revision matches the package's
     * current one; otherwise answer 428 (unusable revision) or 409 (stale).
     * @param pkg Package name whose revision is checked
     * @param sent Revision supplied by the client
     * @param action Change to run on a match
     * @return Response
     */
    CompletableFuture<Response> whenCurrent(
        final String pkg, final String sent, final Supplier<CompletableFuture<Response>> action
    ) {
        final CompletableFuture<Response> result;
        if (sent.isEmpty() || "undefined".equals(sent) || sent.indexOf('-') < 1) {
            result = ResponseBuilder.from(RsStatus.PRECONDITION_REQUIRED)
                .header("X-Pantera-Reason", "revision_required")
                .jsonBody(
                    RevisionGate.error(
                        "revision required: read _rev from the packument and send it as"
                            + " /-rev/<rev>"
                    )
                )
                .completedFuture();
        } else {
            result = new PackumentRevision(this.storage, pkg).value().thenCompose(
                current -> {
                    final CompletableFuture<Response> answer;
                    if (current.equals(sent)) {
                        answer = action.get();
                    } else {
                        answer = ResponseBuilder.from(RsStatus.CONFLICT)
                            .header("X-Pantera-Reason", "revision_mismatch")
                            .jsonBody(
                                RevisionGate.error(
                                    "revision mismatch: the package changed since its"
                                        + " packument was read; read _rev again"
                                )
                            )
                            .completedFuture();
                    }
                    return answer;
                }
            );
        }
        return result;
    }

    /**
     * npm-style error body.
     * @param message Reason
     * @return JSON text
     */
    private static String error(final String message) {
        return Json.createObjectBuilder().add("error", message).build().toString();
    }
}
