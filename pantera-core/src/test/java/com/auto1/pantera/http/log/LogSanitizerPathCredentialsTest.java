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
package com.auto1.pantera.http.log;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.AllOf;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Test;

/**
 * Credentials carried in URL paths and userinfo must never reach the logs.
 *
 * <p>npm logout sends {@code DELETE /-/user/token/<token>}, conda clients put
 * the token in {@code /t/<token>/}, and some clients embed
 * {@code user:password@} in upstream URLs.</p>
 */
final class LogSanitizerPathCredentialsTest {

    /**
     * A JWT-shaped token (header.payload.signature).
     */
    private static final String JWT =
        "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9"
            + ".eyJzdWIiOiJhbGljZSIsInR5cGUiOiJhcGkifQ"
            + ".c2lnbmF0dXJlLXZhbHVlLWhlcmUtX18tLQ";

    @Test
    void redactsNpmLogoutTokenSegment() {
        final String out = LogSanitizer.sanitizeUrl(
            "/test_prefix/api/npm_group/-/user/token/" + JWT
        );
        MatcherAssert.assertThat(
            out,
            new IsEqual<>("/test_prefix/api/npm_group/-/user/token/***REDACTED***")
        );
    }

    @Test
    void redactsOpaqueNpmLogoutToken() {
        MatcherAssert.assertThat(
            LogSanitizer.sanitizeUrl("/npm/-/user/token/npm_abcdef0123456789"),
            new IsEqual<>("/npm/-/user/token/***REDACTED***")
        );
    }

    @Test
    void redactsNpmTokensApiSegment() {
        MatcherAssert.assertThat(
            LogSanitizer.sanitizeUrl("/npm/-/npm/v1/tokens/token/abcdef0123456789"),
            new IsEqual<>("/npm/-/npm/v1/tokens/token/***REDACTED***")
        );
    }

    @Test
    void redactsCondaPathToken() {
        MatcherAssert.assertThat(
            LogSanitizer.sanitizeUrl(
                "/test_prefix/api/conda/t/" + JWT + "/noarch/repodata.json.zst"
            ),
            new IsEqual<>(
                "/test_prefix/api/conda/t/***REDACTED***/noarch/repodata.json.zst"
            )
        );
    }

    @Test
    void redactsOpaqueCondaTicket() {
        MatcherAssert.assertThat(
            LogSanitizer.sanitizeUrl("/conda/t/0f8c3b1e9d2a4c7b8e6f/linux-64/x.conda"),
            new IsEqual<>("/conda/t/***REDACTED***/linux-64/x.conda")
        );
    }

    @Test
    void keepsShortTSegmentInMavenPath() {
        final String path = "/maven/org/t/1.0/t-1.0.jar";
        MatcherAssert.assertThat(
            LogSanitizer.sanitizeUrl(path),
            new IsEqual<>(path)
        );
    }

    @Test
    void redactsJwtAnywhereInPath() {
        final String out = LogSanitizer.sanitizeUrl("/files/" + JWT + "/a.txt");
        MatcherAssert.assertThat(
            out,
            new IsEqual<>("/files/***REDACTED***/a.txt")
        );
    }

    @Test
    void redactsJwtFragmentsSplitAcrossThePath() {
        final int dot = JWT.indexOf('.');
        MatcherAssert.assertThat(
            LogSanitizer.sanitizeUrl(
                "/t/" + JWT.substring(0, dot) + "/test_prefix/api/conda"
                    + JWT.substring(dot) + "/noarch/repodata.json"
            ),
            new IsEqual<>(
                "/t/***REDACTED***/test_prefix/api/conda.***REDACTED***/noarch/repodata.json"
            )
        );
    }

    @Test
    void redactsLoneJwtPayloadSegment() {
        final String payload = JWT.split("\\.")[1];
        MatcherAssert.assertThat(
            LogSanitizer.sanitizeText("repository conda." + payload + " not found"),
            new IsEqual<>("repository conda.***REDACTED*** not found")
        );
    }

    @Test
    void keepsWordsThatMerelyContainEyj() {
        final String path = "/maven/com/monkeyJumperLibraries/1.0/monkeyJumperLibraries-1.0.jar";
        MatcherAssert.assertThat(
            LogSanitizer.sanitizeUrl(path),
            new IsEqual<>(path)
        );
    }

    @Test
    void redactsUserinfo() {
        final String out = LogSanitizer.sanitizeUrl(
            "https://alice:s3cr3t@repo.example.com/path/a.jar"
        );
        MatcherAssert.assertThat(
            out,
            new AllOf<>(
                new IsNot<>(new StringContains("s3cr3t")),
                new StringContains("https://***REDACTED***@repo.example.com/path/a.jar")
            )
        );
    }

    @Test
    void keepsDockerDigestPath() {
        final String path = "/v2/lib/app/blobs/sha256:"
            + "a3ed95caeb02ffe68cdd9fd84406680ae93d633cb16422d00e8a7c22955b46d4";
        MatcherAssert.assertThat(
            LogSanitizer.sanitizeUrl(path),
            new IsEqual<>(path)
        );
    }

    @Test
    void redactsJwtInMessage() {
        MatcherAssert.assertThat(
            LogSanitizer.sanitizeMessage(
                "Request path /npm/-/user/token/" + JWT + " was not matched"
            ),
            new IsNot<>(new StringContains(JWT))
        );
    }
}
