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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;

public record Response(RsStatus status, Headers headers, Content body) {

    @Override
    public String toString() {
        return "Response{" +
            "status=" + status +
            ", headers=" + headers +
            ", hasBody=" + body.size().map(s -> s > 0).orElse(false) +
            '}';
    }
}
