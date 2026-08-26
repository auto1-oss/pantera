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
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import java.util.concurrent.CompletableFuture;

/**
 * Refuses to serve Pantera-internal reserved storage keys that live in the
 * same repository storage the raw {@code .*\.json$} content route serves.
 * Returns {@code 404} (not {@code 403}) so the files' very existence is not
 * revealed.
 *
 * <p><strong>Why this exists:</strong> the registry package-signing keypair is
 * persisted at {@code .registry-keys.json} and the {@code
 * StorageUserRepository}/{@code StorageTokenRepository} persist user records
 * (incl. bcrypt password hashes) and API tokens under {@code _users/} /
 * {@code _tokens/} — all in the repository's own {@link
 * com.auto1.pantera.asto.Storage}. Without this guard {@code GET
 * /&lt;repo&gt;/.registry-keys.json} would hand out the registry's ECDSA
 * <em>private</em> signing key (letting any reader forge {@code
 * dist.signatures}), and {@code GET /&lt;repo&gt;/_users/&lt;name&gt;.json}
 * would hand out a bcrypt hash for offline cracking. This slice is wired as
 * the FIRST route rule so it shadows every content route for these fixed,
 * Pantera-defined internal keys, before auth even runs.</p>
 *
 * @since 2.3.0
 */
public final class ReservedKeyGuardSlice implements Slice {

    /**
     * Full-path regex (post repo-prefix trim) of the reserved internal keys
     * that must never be served over the content routes. A fixed,
     * Pantera-defined set — not user-controlled — so an exact-match denylist
     * is robust here.
     */
    static final String RESERVED_PATH =
        "^/(\\.registry-keys\\.json|_users(/.*)?|_tokens(/.*)?)$";

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        return body.asBytesFuture().handle(
            (ignored, err) -> ResponseBuilder.notFound().build()
        );
    }
}
