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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.LogSanitizer;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import io.reactivex.Flowable;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-S02 pinning test: outbound proxy requests MUST NOT carry inbound
 * {@code Authorization} / {@code Cookie} / {@code X-API-Key} /
 * {@code X-Auth-Token} / {@code Proxy-Authorization} headers, and the
 * shared {@link LogSanitizer} must redact those names in log output.
 *
 * <p>The upstream-forwarding test captures the headers seen by the upstream
 * stub via an {@link AtomicReference}. The sanitisation test asserts the
 * {@link LogSanitizer} masks every name listed above. Together they pin
 * the credential-leak threat model: the inbound user's token must never
 * leave Pantera, in any direction (forward to upstream, or written to a
 * log line).
 *
 * @since 2.2.0
 */
final class BaseCachedProxySliceAuthorizationStrippingTest {

    /** Synthetic secret embedded in test inputs; must never appear in any output. */
    private static final String SECRET_TOKEN =
        "eyJhbGciOiJIUzI1NiJ9.test-secret-token-xyz.signature";

    @Test
    @DisplayName("upstream NEVER receives inbound Authorization / Cookie / "
        + "X-API-Key / X-Auth-Token / Proxy-Authorization headers")
    void upstreamNeverReceivesSensitiveHeaders() throws Exception {
        final Storage storage = new InMemoryStorage();
        final AtomicReference<Headers> captured = new AtomicReference<>();
        final Slice capturingUpstream = (line, headers, body) -> {
            captured.set(headers);
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .body("payload".getBytes(StandardCharsets.UTF_8))
                    .build()
            );
        };
        final AuthStrippingTestSlice slice = new AuthStrippingTestSlice(
            capturingUpstream, storage
        );
        final Headers inbound = Headers.from("User-Agent", "Apache-Maven/3.9.6")
            .copy()
            .add("Authorization", "Bearer " + SECRET_TOKEN)
            .add("Cookie", "session=" + SECRET_TOKEN)
            .add("X-API-Key", SECRET_TOKEN)
            .add("X-Auth-Token", SECRET_TOKEN)
            .add("Proxy-Authorization", "Basic " + SECRET_TOKEN);

        final Response resp = slice.response(
            new RequestLine(RqMethod.GET, "/com/example/foo/1.0/foo-1.0.jar"),
            inbound,
            Content.EMPTY
        ).get();
        // Drain so we don't leak the body publisher.
        resp.body().asBytesFuture().get();

        MatcherAssert.assertThat(
            "captured a forwarded request",
            captured.get() != null, new IsEqual<>(true)
        );
        final Headers forwarded = captured.get();
        assertHeaderAbsent(forwarded, "Authorization");
        assertHeaderAbsent(forwarded, "Cookie");
        assertHeaderAbsent(forwarded, "X-API-Key");
        assertHeaderAbsent(forwarded, "X-Auth-Token");
        assertHeaderAbsent(forwarded, "Proxy-Authorization");
        // Sanity: User-Agent (whitelisted) IS forwarded.
        MatcherAssert.assertThat(
            "User-Agent IS forwarded (whitelisted)",
            !forwarded.find("User-Agent").isEmpty(), new IsEqual<>(true)
        );
    }

    @Test
    @DisplayName("LogSanitizer redacts every sensitive header in T-S02 list")
    void logSanitizerRedactsAllSensitiveHeaders() {
        final Headers raw = Headers.from("Authorization", "Bearer " + SECRET_TOKEN)
            .copy()
            .add("Cookie", "session=" + SECRET_TOKEN)
            .add("X-API-Key", SECRET_TOKEN)
            .add("X-Auth-Token", SECRET_TOKEN)
            .add("Proxy-Authorization", "Basic " + SECRET_TOKEN)
            .add("Content-Type", "application/json");

        final Headers sanitized = LogSanitizer.sanitizeHeaders(raw);

        assertHeaderRedacted(sanitized, "Authorization");
        assertHeaderRedacted(sanitized, "Cookie");
        assertHeaderRedacted(sanitized, "X-API-Key");
        assertHeaderRedacted(sanitized, "X-Auth-Token");
        assertHeaderRedacted(sanitized, "Proxy-Authorization");
        // Sanity: non-sensitive Content-Type passes through.
        MatcherAssert.assertThat(
            "non-sensitive Content-Type passes through unmodified",
            sanitized.values("Content-Type").stream().findFirst().orElse(""),
            new IsEqual<>("application/json")
        );
    }

    @Test
    @DisplayName("LogSanitizer.sanitizeMessage redacts Bearer tokens in log strings")
    void logSanitizerRedactsBearerInMessages() {
        final String raw = "Outbound request to upstream with header "
            + "Authorization: Bearer " + SECRET_TOKEN;
        final String redacted = LogSanitizer.sanitizeMessage(raw);
        MatcherAssert.assertThat(
            "raw message must not contain the secret after sanitisation",
            redacted.contains(SECRET_TOKEN), new IsEqual<>(false)
        );
    }

    @Test
    @DisplayName("LogSanitizer.sanitizeAuthHeader produces no trace of secret token")
    void sanitizeAuthHeaderProducesNoTraceOfSecret() {
        final String result = LogSanitizer.sanitizeAuthHeader(
            "Bearer " + SECRET_TOKEN
        );
        MatcherAssert.assertThat(
            result.contains(SECRET_TOKEN), new IsEqual<>(false)
        );
    }

    /**
     * Assert a header name is absent from the forwarded set. Uses
     * case-insensitive comparison because the slice-level header sets
     * preserve casing but matching must not depend on case.
     *
     * @param headers Headers to inspect
     * @param name Header name to confirm absent
     */
    private static void assertHeaderAbsent(final Headers headers, final String name) {
        final List<Header> matches = headers.find(name);
        // Also check via a manual case-insensitive sweep — find() already
        // does this but we want a belt-and-braces check that catches a
        // future refactor that changes find()'s casing semantics.
        boolean present = !matches.isEmpty();
        for (final Header header : headers) {
            if (header.getKey().toLowerCase(Locale.ROOT)
                .equals(name.toLowerCase(Locale.ROOT))) {
                present = true;
                break;
            }
        }
        MatcherAssert.assertThat(
            "header '" + name + "' must be absent from upstream request",
            present, new IsEqual<>(false)
        );
    }

    /**
     * Assert a header name is present in the sanitised set and that its
     * value does NOT contain the synthetic secret token.
     *
     * @param headers Sanitised headers
     * @param name Header name to inspect
     */
    private static void assertHeaderRedacted(final Headers headers, final String name) {
        final List<String> values = headers.values(name);
        MatcherAssert.assertThat(
            "sanitised header '" + name + "' present",
            !values.isEmpty(), new IsEqual<>(true)
        );
        final String value = values.get(0);
        MatcherAssert.assertThat(
            "sanitised value of '" + name + "' must not contain the secret",
            value.contains(SECRET_TOKEN), new IsEqual<>(false)
        );
    }

    /**
     * Concrete subclass under test. All paths cacheable so requests flow
     * through the cache-miss path where {@code upstreamHeaders()} is called
     * before forwarding to the captured upstream stub.
     */
    private static final class AuthStrippingTestSlice extends BaseCachedProxySlice {

        AuthStrippingTestSlice(final Slice upstream, final Storage storage) {
            super(
                upstream,
                buildCache(storage),
                "test-repo", "test", "https://upstream.example",
                Optional.of(storage),
                Optional.empty(),
                ProxyCacheConfig.defaults()
            );
        }

        @Override
        protected boolean isCacheable(final String path) {
            return true;
        }

        private static Cache buildCache(final Storage storage) {
            return new FromStorageCache(storage);
        }
    }

    /** Suppresses unused-import lint warnings on {@link Flowable}. */
    @SuppressWarnings("unused")
    private static Flowable<Object> referenceFlowable() {
        return Flowable.empty();
    }
}
