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
package com.auto1.pantera.api.v1;

import io.vertx.core.http.HttpMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression test for the {@code /api/v1/*} JWT filter's public-route
 * exemption.
 *
 * <p>Before 2.2.9 the filter skipped JWT validation for any path that merely
 * CONTAINED {@code /artifact/download-direct} (or ended with an auth suffix),
 * so an unrelated protected route whose path embedded that substring — e.g.
 * a PyPI yank on a repository named {@code artifact} and a package named
 * {@code download-direct} — ran with no authentication at all. The exemption
 * is now an exact method + path-shape allowlist.</p>
 *
 * @since 2.2.9
 */
final class PublicApiRoutesTest {

    @Test
    void embeddedDownloadDirectSubstringIsNotExempt() {
        MatcherAssert.assertThat(
            "a protected route that merely embeds the download-direct substring "
                + "must still require JWT",
            PublicApiRoutes.exempt(
                HttpMethod.POST,
                "/api/v1/pypi/repositories/artifact/packages/download-direct/yank"
            ),
            new IsEqual<>(false)
        );
    }

    @Test
    void trailingAuthSuffixOnAnotherRouteIsNotExempt() {
        MatcherAssert.assertThat(
            "a route that merely ends with /auth/token must not be exempt",
            PublicApiRoutes.exempt(HttpMethod.POST, "/api/v1/repositories/x/auth/token"),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "a route that merely ends with /health must not be exempt",
            PublicApiRoutes.exempt(HttpMethod.GET, "/api/v1/repositories/x/health"),
            new IsEqual<>(false)
        );
    }

    @Test
    void genuinePublicRoutesAreExempt() {
        MatcherAssert.assertThat(
            "the real HMAC-authenticated download-direct route is exempt",
            PublicApiRoutes.exempt(
                HttpMethod.GET, "/api/v1/repositories/my-repo/artifact/download-direct"
            ),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "password login is exempt",
            PublicApiRoutes.exempt(HttpMethod.POST, "/api/v1/auth/token"),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "SSO provider list is exempt",
            PublicApiRoutes.exempt(HttpMethod.GET, "/api/v1/auth/providers"),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "SSO redirect is exempt",
            PublicApiRoutes.exempt(HttpMethod.GET, "/api/v1/auth/providers/okta/redirect"),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "SSO callback is exempt",
            PublicApiRoutes.exempt(HttpMethod.POST, "/api/v1/auth/callback"),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "health is exempt",
            PublicApiRoutes.exempt(HttpMethod.GET, "/api/v1/health"),
            new IsEqual<>(true)
        );
    }

    @Test
    void wrongMethodOnAPublicPathIsNotExempt() {
        MatcherAssert.assertThat(
            "DELETE on the download-direct path is not a public route",
            PublicApiRoutes.exempt(
                HttpMethod.DELETE, "/api/v1/repositories/my-repo/artifact/download-direct"
            ),
            new IsEqual<>(false)
        );
    }
}
