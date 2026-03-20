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
package com.auto1.pantera.http.client.auth;

import com.auto1.pantera.http.Headers;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Authenticator for HTTP requests.
 *
 * @since 0.3
 */
public interface Authenticator {

    /**
     * Anonymous authorization. Always returns empty headers set.
     */
    Authenticator ANONYMOUS = ignored -> CompletableFuture.completedFuture(Headers.EMPTY);

    /**
     * Get authorization headers.
     *
     * @param headers Headers with requirements for authorization.
     * @return Authorization headers.
     */
    CompletionStage<Headers> authenticate(Headers headers);
}
