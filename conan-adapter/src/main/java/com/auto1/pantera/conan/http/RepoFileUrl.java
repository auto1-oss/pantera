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
package com.auto1.pantera.conan.http;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.ClientBaseUrl;

/**
 * Absolute URL of a repository file as the client must request it.
 *
 * <p>On the main port the repository lives under
 * {@code <origin>[/<prefix>][/api]/<repo>}; the base stamped by the
 * routing layer ({@link ClientBaseUrl#HEADER}) carries all of that. A
 * dedicated port has no stamp (the header is scrubbed there) and serves the
 * repository at its root, so the request origin is the base.</p>
 *
 * @since 2.2.9
 */
final class RepoFileUrl {

    /**
     * Repository base without a trailing slash.
     */
    private final String base;

    /**
     * Ctor.
     * @param headers Request headers
     */
    RepoFileUrl(final Headers headers) {
        final ClientBaseUrl client = new ClientBaseUrl(headers);
        this.base = RepoFileUrl.trimmed(client.stamped().orElseGet(client::origin));
    }

    /**
     * URL of a repository-relative path.
     * @param path Repository-relative path, with or without a leading slash
     * @return Absolute URL
     */
    String of(final String path) {
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') {
            start += 1;
        }
        return String.join("/", this.base, path.substring(start));
    }

    /**
     * Drop trailing slashes.
     * @param value Base URL
     * @return Base without trailing slashes
     */
    private static String trimmed(final String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end -= 1;
        }
        return value.substring(0, end);
    }
}
