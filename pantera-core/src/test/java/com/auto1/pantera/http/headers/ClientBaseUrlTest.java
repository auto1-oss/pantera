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
import org.hamcrest.core.IsNot;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

final class ClientBaseUrlTest {

    @AfterEach
    void tearDown() {
        ClientBaseUrlSettingsRegistry.uninstall();
    }

    @Test
    void derivesBaseFromHostAndPath() {
        MatcherAssert.assertThat(
            new ClientBaseUrl(Headers.from("Host", "reg.example.com"))
                .derive("/test_prefix/api/npm/npm_group/pnpm", "/pnpm"),
            new IsEqual<>(Optional.of("http://reg.example.com/test_prefix/api/npm/npm_group"))
        );
    }

    @Test
    void honoursForwardedSchemeHostAndPrefixWhenTrusted() {
        final Headers headers = new Headers()
            .add("Host", "internal:8080")
            .add("X-Forwarded-Proto", "https")
            .add("X-Forwarded-Host", "reg.example.com")
            .add("X-Forwarded-Prefix", "/artifactory");
        MatcherAssert.assertThat(
            new ClientBaseUrl(headers, true).derive("/api/npm/npm_group/pnpm", "/pnpm"),
            new IsEqual<>(Optional.of("https://reg.example.com/artifactory/api/npm/npm_group"))
        );
    }

    @Test
    void takesFirstValueOfCommaListedForwardedProtoWhenTrusted() {
        final Headers headers = new Headers()
            .add("Host", "reg.example.com")
            .add("X-Forwarded-Proto", "https, http");
        MatcherAssert.assertThat(
            new ClientBaseUrl(headers, true).origin(),
            new IsEqual<>("https://reg.example.com")
        );
    }

    @Test
    void ignoresForwardedHostAndProtoWhenNotTrusted() {
        final Headers headers = new Headers()
            .add("Host", "reg.example.com")
            .add("X-Forwarded-Host", "evil.example.com")
            .add("X-Forwarded-Proto", "https");
        MatcherAssert.assertThat(
            new ClientBaseUrl(headers, false).origin(),
            new IsEqual<>("http://reg.example.com")
        );
    }

    @Test
    void ignoresForwardedPrefixWhenNotTrusted() {
        final Headers headers = new Headers()
            .add("Host", "reg.example.com")
            .add("X-Forwarded-Prefix", "/artifactory");
        MatcherAssert.assertThat(
            new ClientBaseUrl(headers, false).derive("/api/npm/npm_group/pnpm", "/pnpm"),
            new IsEqual<>(Optional.of("http://reg.example.com/api/npm/npm_group"))
        );
    }

    @Test
    void defaultConstructorDoesNotTrustForwardedHeadersByDefault() {
        // Without mutating the environment: the 1-arg constructor's
        // behaviour must match the 2-arg constructor's explicit
        // trustForwarded=false for identical headers, proving the default
        // is "don't trust" regardless of ambient PANTERA_TRUST_FORWARDED_HEADERS.
        final Headers headers = new Headers()
            .add("Host", "reg.example.com")
            .add("X-Forwarded-Host", "evil.example.com")
            .add("X-Forwarded-Proto", "https")
            .add("X-Forwarded-Prefix", "/artifactory");
        MatcherAssert.assertThat(
            new ClientBaseUrl(headers).derive("/api/npm/npm_group/pnpm", "/pnpm"),
            new IsEqual<>(new ClientBaseUrl(headers, false).derive("/api/npm/npm_group/pnpm", "/pnpm"))
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

    /**
     * Proves the hot-reload requirement directly: the SAME registered
     * supplier reference stays installed for the whole test, but its
     * resolved value changes between two constructions of {@link
     * ClientBaseUrl} — nothing is re-installed, restarted, or
     * reconstructed. This is exactly what happens in production between an
     * admin PUT (which calls {@code loader.invalidate()}) and the next
     * request: the very next {@code new ClientBaseUrl(headers)} observes it.
     */
    @Test
    void oneArgConstructorPicksUpASettingsChangeWithoutReinstalling() {
        final AtomicReference<ClientBaseUrlSettings> live =
            new AtomicReference<>(ClientBaseUrlSettings.defaults());
        ClientBaseUrlSettingsRegistry.install(live::get);
        final Headers headers = new Headers()
            .add("Host", "internal:8080")
            .add("X-Forwarded-Proto", "https")
            .add("X-Forwarded-Host", "reg.example.com");
        MatcherAssert.assertThat(
            "before the change, forwarded headers are not trusted (default)",
            new ClientBaseUrl(headers).origin(),
            new IsEqual<>("http://internal:8080")
        );
        live.set(new ClientBaseUrlSettings(true, List.of()));
        MatcherAssert.assertThat(
            "after the change — same installed supplier, new resolved value — "
                + "a freshly constructed instance honours forwarded headers",
            new ClientBaseUrl(headers).origin(),
            new IsEqual<>("https://reg.example.com")
        );
    }

    @Test
    void oneArgConstructorFallsBackToDefaultsWhenNothingIsInstalled() {
        MatcherAssert.assertThat(
            new ClientBaseUrl(Headers.from("Host", "reg.example.com")).origin(),
            new IsEqual<>("http://reg.example.com")
        );
    }

    @Test
    void hostNotOnTheAllowlistIsNeverEmittedAsTheOrigin() {
        ClientBaseUrlSettingsRegistry.install(
            () -> new ClientBaseUrlSettings(false, List.of("good.example.com"))
        );
        final String origin = new ClientBaseUrl(Headers.from("Host", "evil.tld")).origin();
        MatcherAssert.assertThat(
            "a Host absent from the allowlist must never be reflected into the origin",
            origin, new IsNot<>(new StringContains("evil.tld"))
        );
        MatcherAssert.assertThat(
            "disallowed Host falls back exactly like an absent one",
            origin, new IsEqual<>("http://localhost")
        );
    }

    @Test
    void hostOnTheAllowlistIsUsedNormallyCaseInsensitively() {
        ClientBaseUrlSettingsRegistry.install(
            () -> new ClientBaseUrlSettings(false, List.of("Reg.Example.com"))
        );
        MatcherAssert.assertThat(
            new ClientBaseUrl(Headers.from("Host", "reg.example.com")).origin(),
            new IsEqual<>("http://reg.example.com")
        );
    }

    @Test
    void emptyAllowlistIsPermissive() {
        ClientBaseUrlSettingsRegistry.install(() -> new ClientBaseUrlSettings(false, List.of()));
        MatcherAssert.assertThat(
            new ClientBaseUrl(Headers.from("Host", "anything.example.com")).origin(),
            new IsEqual<>("http://anything.example.com")
        );
    }

    @Test
    void allowlistAlsoGatesTheHostFallbackWhenForwardedHostIsAbsentButTrusted() {
        // trustForwarded=true but no X-Forwarded-Host on the request: origin()
        // falls back to Host, which must still respect the allowlist.
        ClientBaseUrlSettingsRegistry.install(
            () -> new ClientBaseUrlSettings(true, List.of("good.example.com"))
        );
        final Headers headers = new Headers()
            .add("Host", "evil.tld")
            .add("X-Forwarded-Proto", "https");
        MatcherAssert.assertThat(
            new ClientBaseUrl(headers).origin(),
            new IsEqual<>("https://localhost")
        );
    }

    /**
     * The explicit 2-argument constructor is the deterministic, test-only
     * escape hatch: it never consults {@link ClientBaseUrlSettingsRegistry},
     * so a restrictive allowlist installed for other tests/production has no
     * effect on it.
     */
    @Test
    void twoArgConstructorNeverConsultsTheAllowlist() {
        ClientBaseUrlSettingsRegistry.install(
            () -> new ClientBaseUrlSettings(false, List.of("good.example.com"))
        );
        MatcherAssert.assertThat(
            new ClientBaseUrl(Headers.from("Host", "evil.tld"), false).origin(),
            new IsEqual<>("http://evil.tld")
        );
    }

    // --- Canonical base URL setting (fixwave-h, 2.3.0) ---

    /**
     * Topology 1: a bare origin, no global path prefix in the request.
     */
    @Test
    void canonicalBaseUrlComposesWithABarePathWhenNoGlobalPrefix() {
        ClientBaseUrlSettingsRegistry.install(
            () -> new ClientBaseUrlSettings(false, List.of(), "http://localhost:9999")
        );
        MatcherAssert.assertThat(
            new ClientBaseUrl(Headers.from("Host", "reg.example.com"))
                .derive("/npm_group/pnpm", "/pnpm"),
            new IsEqual<>(Optional.of("http://localhost:9999/npm_group"))
        );
    }

    /**
     * Topology 2: a bare origin, request carries the global prefix + the
     * {@code /api/<type>/<name>} route style -- both must be preserved in
     * the composed URL even though only the origin comes from the setting.
     */
    @Test
    void canonicalBaseUrlPreservesGlobalPrefixAndApiRouteStyle() {
        ClientBaseUrlSettingsRegistry.install(
            () -> new ClientBaseUrlSettings(false, List.of(), "http://localhost:9999")
        );
        MatcherAssert.assertThat(
            new ClientBaseUrl(Headers.from("Host", "reg.example.com"))
                .derive("/test_prefix/api/npm/npm_group/pnpm", "/pnpm"),
            new IsEqual<>(Optional.of("http://localhost:9999/test_prefix/api/npm/npm_group"))
        );
    }

    /**
     * Topology 3: the canonical setting itself carries a path prefix, which
     * must be prepended ahead of the derived repository path.
     */
    @Test
    void canonicalBaseUrlWithAPathPrefixIsPrependedAheadOfTheRepoPath() {
        ClientBaseUrlSettingsRegistry.install(
            () -> new ClientBaseUrlSettings(false, List.of(), "https://reg.example.com/artifactory")
        );
        MatcherAssert.assertThat(
            new ClientBaseUrl(Headers.from("Host", "reg.example.com"))
                .derive("/npm_group/pnpm", "/pnpm"),
            new IsEqual<>(Optional.of("https://reg.example.com/artifactory/npm_group"))
        );
    }

    /**
     * Guards against doubling: when the deployment's global path prefix
     * happens to equal the canonical setting's own path prefix, the derived
     * repository path already carries it -- the composed URL must not
     * prepend it a second time.
     */
    @Test
    void canonicalBaseUrlPrefixIsNotDoubledWhenTheDerivedPathAlreadyCarriesIt() {
        ClientBaseUrlSettingsRegistry.install(
            () -> new ClientBaseUrlSettings(false, List.of(), "https://reg.example.com/test_prefix")
        );
        MatcherAssert.assertThat(
            new ClientBaseUrl(Headers.from("Host", "reg.example.com"))
                .derive("/test_prefix/api/npm/npm_group/pnpm", "/pnpm"),
            new IsEqual<>(Optional.of("https://reg.example.com/test_prefix/api/npm/npm_group"))
        );
    }

    /**
     * The exact reported bug: nginx's {@code $host} strips the port, so
     * Pantera used to see a portless {@code Host} and emit a portless URL.
     * With the canonical setting present, {@code Host} is not consulted at
     * all -- the setting's own port survives regardless of what {@code Host}
     * carries.
     */
    @Test
    void portlessHostIsIgnoredWhenCanonicalBaseUrlIsSetSoThePortSurvives() {
        ClientBaseUrlSettingsRegistry.install(
            () -> new ClientBaseUrlSettings(false, List.of(), "http://localhost:9999")
        );
        MatcherAssert.assertThat(
            new ClientBaseUrl(Headers.from("Host", "localhost"))
                .derive("/npm_group/pnpm", "/pnpm"),
            new IsEqual<>(Optional.of("http://localhost:9999/npm_group"))
        );
    }

    /**
     * Enforcement, not filtering: even {@code X-Forwarded-*} (which would
     * otherwise win when trusted) is ignored once a canonical base URL is
     * set -- the setting is consulted first, unconditionally.
     */
    @Test
    void forwardedHeadersAreIgnoredTooWhenCanonicalBaseUrlIsSetEvenIfTrusted() {
        ClientBaseUrlSettingsRegistry.install(
            () -> new ClientBaseUrlSettings(true, List.of(), "http://localhost:9999")
        );
        final Headers headers = new Headers()
            .add("Host", "internal:8080")
            .add("X-Forwarded-Proto", "https")
            .add("X-Forwarded-Host", "evil.example.com")
            .add("X-Forwarded-Prefix", "/hijacked");
        MatcherAssert.assertThat(
            new ClientBaseUrl(headers).derive("/npm_group/pnpm", "/pnpm"),
            new IsEqual<>(Optional.of("http://localhost:9999/npm_group"))
        );
    }

    @Test
    void varyIsHostWhenCanonicalBaseUrlIsUnset() {
        MatcherAssert.assertThat(
            new ClientBaseUrl(Headers.from("Host", "reg.example.com")).varyHeaderValue(),
            new IsEqual<>("Host")
        );
    }

    /**
     * Nothing left to vary by: Host/X-Forwarded-* are not consulted at all
     * once a canonical base URL is set, so the response must not claim they
     * still influence it -- a stale Vary is a cache-poisoning vector.
     */
    @Test
    void varyIsEmptyWhenCanonicalBaseUrlIsSet() {
        ClientBaseUrlSettingsRegistry.install(
            () -> new ClientBaseUrlSettings(true, List.of(), "http://localhost:9999")
        );
        MatcherAssert.assertThat(
            new ClientBaseUrl(Headers.from("Host", "reg.example.com")).varyHeaderValue(),
            new IsEqual<>("")
        );
    }
}
