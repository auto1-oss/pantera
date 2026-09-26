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
package com.auto1.pantera.nuget.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Accepts the NuGet API key as a credential.
 *
 * <p>{@code dotnet nuget push --api-key <key>} sends the key in the
 * {@code X-NuGet-ApiKey} header and no {@code Authorization} header. When a
 * request carries the API key and no {@code Authorization}, this slice
 * presents the key as a {@code Bearer} token, so the repository's normal
 * credential check validates it as a Pantera token (API or access token).
 * An explicit {@code Authorization} header always wins. The slice grants
 * nothing itself: a forged key fails the downstream token validation.</p>
 *
 * <p>It must wrap the repository <em>outside</em> the anonymous-access gate,
 * which only looks at {@code Authorization}.</p>
 *
 * @since 2.2.9
 */
public final class NuGetApiKeySlice implements Slice {

    /**
     * NuGet API key header.
     */
    static final String API_KEY = "X-NuGet-ApiKey";

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * Ctor.
     * @param origin Origin slice
     */
    public NuGetApiKeySlice(final Slice origin) {
        this.origin = origin;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final List<String> keys = headers.values(NuGetApiKeySlice.API_KEY);
        final Headers effective;
        if (headers.values(Authorization.NAME).isEmpty()
            && !keys.isEmpty() && !keys.get(0).isBlank()) {
            effective = headers.copy().add(new Authorization.Bearer(keys.get(0).trim()));
        } else {
            effective = headers;
        }
        return this.origin.response(line, effective, body);
    }
}
