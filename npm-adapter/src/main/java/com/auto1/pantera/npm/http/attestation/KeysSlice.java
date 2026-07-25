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
package com.auto1.pantera.npm.http.attestation;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.npm.security.NpmSigningKeys;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;
import javax.json.JsonObject;
import org.slf4j.MDC;

/**
 * {@code GET /-/npm/v1/keys} — serves the registry's own package-signing
 * public key so {@code npm audit signatures} can verify {@code
 * dist.signatures} entries written at publish time ({@link
 * com.auto1.pantera.npm.security.NpmPackageSigner}).
 *
 * <p>Response shape mirrors the public npm registry's keys endpoint:
 * {@code {"keys":[{"expires":null,"keyid":...,"keytype":"ecdsa-sha2-nistp256",
 * "scheme":"ecdsa-sha2-nistp256","key":"&lt;base64 DER SPKI&gt;"}]}}.</p>
 *
 * @since 2.3.0
 */
public final class KeysSlice implements Slice {

    /**
     * Registry signing keypair source.
     */
    private final NpmSigningKeys keys;

    /**
     * Repository name (audit only).
     */
    private final String repoName;

    /**
     * Ctor.
     *
     * @param keys Registry signing keypair source
     * @param repoName Repository name
     */
    public KeysSlice(final NpmSigningKeys keys, final String repoName) {
        this.keys = keys;
        this.repoName = repoName;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        RequestContextHeaders.bindToMdc(headers);
        final AuditContext ctx = new AuditContext(
            MDC.get(EcsMdc.TRACE_ID), MDC.get(EcsMdc.CLIENT_IP)
        );
        final String owner = new Login(headers).getValue();
        return body.asBytesFuture().thenCompose(
            ignored -> this.keys.publicKey().thenApply(pair -> {
                AuditLogger.resolution(ctx, "npm", this.repoName, "-/npm/v1/keys", owner, List.of());
                final JsonObject key = Json.createObjectBuilder()
                    .add("expires", javax.json.JsonValue.NULL)
                    .add("keyid", pair.keyId())
                    .add("keytype", "ecdsa-sha2-nistp256")
                    .add("scheme", "ecdsa-sha2-nistp256")
                    .add("key", pair.publicKeyBase64())
                    .build();
                final JsonObject out = Json.createObjectBuilder()
                    .add("keys", Json.createArrayBuilder().add(key).build())
                    .build();
                return ResponseBuilder.ok().jsonBody(out).build();
            }).toCompletableFuture()
        );
    }
}
