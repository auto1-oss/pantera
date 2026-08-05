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
package com.auto1.pantera.http.headers;

import com.auto1.pantera.http.Headers;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.util.Optional;

final class ClientBaseUrlTest {

    @Test
    void derivesBaseFromHostAndPath() {
        MatcherAssert.assertThat(
            new ClientBaseUrl(Headers.from("Host", "reg.example.com"))
                .derive("/test_prefix/api/npm/npm_group/pnpm", "/pnpm"),
            new IsEqual<>(Optional.of("http://reg.example.com/test_prefix/api/npm/npm_group"))
        );
    }

    @Test
    void honoursForwardedSchemeHostAndPrefix() {
        final Headers headers = new Headers()
            .add("Host", "internal:8080")
            .add("X-Forwarded-Proto", "https")
            .add("X-Forwarded-Host", "reg.example.com")
            .add("X-Forwarded-Prefix", "/artifactory");
        MatcherAssert.assertThat(
            new ClientBaseUrl(headers).derive("/api/npm/npm_group/pnpm", "/pnpm"),
            new IsEqual<>(Optional.of("https://reg.example.com/artifactory/api/npm/npm_group"))
        );
    }

    @Test
    void takesFirstValueOfCommaListedForwardedProto() {
        final Headers headers = new Headers()
            .add("Host", "reg.example.com")
            .add("X-Forwarded-Proto", "https, http");
        MatcherAssert.assertThat(
            new ClientBaseUrl(headers).origin(),
            new IsEqual<>("https://reg.example.com")
        );
    }

    @Test
    void returnsEmptyWhenRemainderIsNotASuffix() {
        MatcherAssert.assertThat(
            new ClientBaseUrl(Headers.from("Host", "h"))
                .derive("/test_prefix/npm_group/pnpm", "/something-else"),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void wholePathIsTheBaseWhenRemainderIsRootOrEmpty() {
        final ClientBaseUrl base = new ClientBaseUrl(Headers.from("Host", "h"));
        MatcherAssert.assertThat(
            "root remainder keeps the whole path",
            base.derive("/test_prefix/npm_group", "/"),
            new IsEqual<>(Optional.of("http://h/test_prefix/npm_group"))
        );
        MatcherAssert.assertThat(
            "empty remainder keeps the whole path",
            base.derive("/test_prefix/npm_group", ""),
            new IsEqual<>(Optional.of("http://h/test_prefix/npm_group"))
        );
    }

    @Test
    void readsAlreadyStampedHeader() {
        MatcherAssert.assertThat(
            new ClientBaseUrl(Headers.from(ClientBaseUrl.HEADER, "https://h/npm_group")).stamped(),
            new IsEqual<>(Optional.of("https://h/npm_group"))
        );
    }
}
