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
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.headers.ClientBaseUrl;

/**
 * Absolute URL of a repository file as the client must request it.
 *
 * <p>On the main port the repository lives under
 * {@code <origin>[/<prefix>][/api]/<repo>}; the base stamped by the
 * routing layer ({@link ClientBaseUrl#HEADER}) carries all of that. A
 * dedicated port has no stamp (the header is scrubbed there) and serves the
 * repository at its root, so the base is {@code http://<Host>}, echoing the
 * address the client used. The main-port origin settings (canonical base
 * URL, host allowlist) are deliberately not applied there: they describe the
 * main port, and Conan only sends its credentials to URLs under the remote's
 * own URL, so a URL on any other origin fails the authenticated download
 * and upload.</p>
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
        this.base = RepoFileUrl.trimmed(
            new ClientBaseUrl(headers).stamped().orElseGet(
                () -> String.join(
                    "", "http://",
                    headers.find("Host").stream().findFirst()
                        .map(Header::getValue).orElse("localhost")
                )
            )
        );
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
