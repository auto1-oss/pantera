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
package com.auto1.pantera.http.cache;

import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;

/**
 * The answer of a proxy to a Troubleshoot replay for a file its upstream
 * has and the proxy does not hold: the status a client would get, without
 * the file.
 *
 * <p>A replay ({@link com.auto1.pantera.http.log.RequestTrace}) is a
 * diagnosis. It must leave a repository as it found it: a file the replay
 * stored would enter the search index and change which member a group
 * asks from then on. A proxy therefore asks its upstream as it would for
 * a client, and answers with this instead of storing what it got. A proxy
 * that can hand the upstream's body on without storing it may do that
 * instead.</p>
 *
 * @since 2.2.15
 */
public final class NotStored {

    /**
     * The answer.
     *
     * @return A 200 with a note in place of the file
     */
    public Response response() {
        return ResponseBuilder.ok()
            .textBody("The upstream has this file. A diagnostic request does not store it.")
            .build();
    }
}
