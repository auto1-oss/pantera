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
package com.auto1.pantera.auth.oidc;

import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.trace.TraceHeaders;
import java.io.StringReader;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;

/**
 * {@link JwkSource} backed by an OIDC provider's JWKS endpoint.
 *
 * <p>Keys are fetched over HTTP (same JDK client + trace headers the
 * existing IdP token-exchange and Okta clients use — an identity-provider
 * control-plane call, not artifact-proxy traffic) and cached for a bounded
 * TTL. An unknown {@code kid} triggers at most one refresh per lookup so
 * provider key rotation works without a restart; a fetch failure yields no
 * key, and the verifier fails closed. Only RSA keys with {@code use}
 * absent or {@code sig} are admitted.</p>
 *
 * @since 2.2.9
 */
public final class HttpJwkSource implements JwkSource {

    /**
     * How long a fetched key set is reused before re-fetching.
     */
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    /**
     * Cached key set with its fetch time.
     * @param keys kid → key
     * @param fetchedNanos Fetch instant
     */
    private record KeySet(Map<String, RSAPublicKey> keys, long fetchedNanos) {
    }

    private final URI jwksUri;
    private final HttpClient http;
    private final AtomicReference<KeySet> cache = new AtomicReference<>();

    /**
     * Ctor.
     * @param jwksUri The provider's JWKS endpoint
     */
    public HttpJwkSource(final URI jwksUri) {
        this.jwksUri = jwksUri;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @Override
    public Optional<RSAPublicKey> key(final String kid) {
        KeySet set = this.cache.get();
        final boolean stale = set == null
            || System.nanoTime() - set.fetchedNanos() > CACHE_TTL.toNanos();
        if (stale || !set.keys().containsKey(kid)) {
            // Stale, or a kid we have not seen (rotation): refresh once.
            set = this.refresh();
        }
        return set == null ? Optional.empty() : Optional.ofNullable(set.keys().get(kid));
    }

    private KeySet refresh() {
        try {
            final HttpRequest request = withTrace(
                HttpRequest.newBuilder().uri(this.jwksUri)
                    .timeout(Duration.ofSeconds(10)).GET()
            ).build();
            final HttpResponse<String> resp = this.http.send(
                request, HttpResponse.BodyHandlers.ofString()
            );
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("JWKS fetch returned HTTP " + resp.statusCode());
            }
            final KeySet fresh = new KeySet(parse(resp.body()), System.nanoTime());
            this.cache.set(fresh);
            return fresh;
        } catch (final Exception ex) {
            EcsLogger.error("com.auto1.pantera.auth")
                .message("OIDC JWKS fetch failed; id_token verification will fail closed")
                .eventCategory("authentication")
                .eventAction("oidc_jwks_fetch")
                .eventOutcome("failure")
                .field("url.full", this.jwksUri.toString())
                .error(ex)
                .field("log.source", "application")
                .log();
            return null; // NOPMD ReturnEmptyCollectionRatherThanNull - KeySet is a record, not a collection; null = fetch failed (verifier fails closed)
        }
    }

    private static Map<String, RSAPublicKey> parse(final String body) throws Exception {
        final JsonObject doc;
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
            doc = reader.readObject();
        }
        final Map<String, RSAPublicKey> keys = new HashMap<>();
        final JsonArray arr = doc.getJsonArray("keys");
        if (arr == null) {
            return Collections.emptyMap();
        }
        final KeyFactory factory = KeyFactory.getInstance("RSA");
        for (int idx = 0; idx < arr.size(); idx = idx + 1) {
            final JsonObject jwk = arr.getJsonObject(idx);
            final String use = jwk.getString("use", "sig");
            if (!"RSA".equals(jwk.getString("kty", "")) || !"sig".equals(use)
                || !jwk.containsKey("kid") || !jwk.containsKey("n") || !jwk.containsKey("e")) {
                continue;
            }
            final BigInteger modulus = new BigInteger(
                1, Base64.getUrlDecoder().decode(jwk.getString("n"))
            );
            final BigInteger exponent = new BigInteger(
                1, Base64.getUrlDecoder().decode(jwk.getString("e"))
            );
            keys.put(
                jwk.getString("kid"),
                (RSAPublicKey) factory.generatePublic(new RSAPublicKeySpec(modulus, exponent))
            );
        }
        return keys;
    }

    private static HttpRequest.Builder withTrace(final HttpRequest.Builder builder) {
        final String[] hdrs = TraceHeaders.httpClientHeaders();
        HttpRequest.Builder out = builder;
        for (int idx = 0; idx < hdrs.length; idx += 2) {
            out = out.header(hdrs[idx], hdrs[idx + 1]);
        }
        return out;
    }
}
