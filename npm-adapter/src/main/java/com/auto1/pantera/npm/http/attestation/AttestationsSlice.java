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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonValue;
import org.slf4j.MDC;

/**
 * {@code GET /-/npm/v1/attestations/&lt;spec&gt;} — serves a previously
 * stored {@code npm publish --provenance} bundle back to the client.
 * {@code &lt;spec&gt;} is {@code &lt;name&gt;@&lt;version&gt;} (the package name may
 * itself contain {@code @} for a scoped package, so the reference is split
 * on the LAST {@code @}).
 *
 * @since 2.3.0
 */
public final class AttestationsSlice implements Slice {

    /**
     * Matches the path up to and including {@code /-/npm/v1/attestations/},
     * capturing everything after it as the spec.
     */
    private static final Pattern SPEC_PATTERN = Pattern.compile(
        ".*/-/npm/v1/attestations/(?<spec>.+)$"
    );

    /**
     * Attestation bundle store.
     */
    private final AttestationStore attestations;

    /**
     * Repository name (audit only).
     */
    private final String repoName;

    /**
     * Ctor.
     *
     * @param attestations Attestation bundle store
     * @param repoName Repository name
     */
    public AttestationsSlice(final AttestationStore attestations, final String repoName) {
        this.attestations = attestations;
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
        return body.asBytesFuture().thenCompose(ignored -> {
            final Optional<NameVersion> parsed = parseSpec(line.uri().getPath());
            if (parsed.isEmpty()) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.badRequest()
                        .jsonBody("{\"error\":\"malformed attestation spec\"}")
                        .build()
                );
            }
            final NameVersion spec = parsed.get();
            return this.attestations.read(spec.name(), spec.version()).thenApply(bundle -> {
                AuditLogger.resolution(ctx, "npm", this.repoName, spec.name(), owner, List.of());
                return bundle.map(AttestationsSlice::serve).orElseGet(() -> notFound(spec));
            });
        });
    }

    private static Response serve(final byte[] bundle) {
        final JsonValue parsed = tryParse(bundle);
        final JsonObject envelope;
        if (parsed instanceof JsonObject obj && obj.containsKey("attestations")) {
            envelope = obj;
        } else {
            final JsonObject wrapped = Json.createObjectBuilder()
                .add("bundle", parsed)
                .build();
            envelope = Json.createObjectBuilder()
                .add("attestations", Json.createArrayBuilder().add(wrapped).build())
                .build();
        }
        return ResponseBuilder.ok().jsonBody(envelope).build();
    }

    private static JsonValue tryParse(final byte[] bundle) {
        try (JsonReader reader = Json.createReader(
            new java.io.ByteArrayInputStream(bundle)
        )) {
            return reader.readValue();
        } catch (final javax.json.JsonException ex) {
            return Json.createValue(new String(bundle, StandardCharsets.UTF_8));
        }
    }

    private static Response notFound(final NameVersion spec) {
        return ResponseBuilder.notFound()
            .jsonBody(String.format(
                "{\"error\":\"no attestations found for %s@%s\"}", spec.name(), spec.version()
            ))
            .build();
    }

    private static Optional<NameVersion> parseSpec(final String path) {
        final Matcher matcher = SPEC_PATTERN.matcher(path);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        final String spec = URLDecoder.decode(matcher.group("spec"), StandardCharsets.UTF_8);
        final int at = spec.lastIndexOf('@');
        if (at <= 0 || at == spec.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(new NameVersion(spec.substring(0, at), spec.substring(at + 1)));
    }

    /**
     * Parsed {@code name@version} attestation spec.
     *
     * @param name Package name
     * @param version Version
     */
    private record NameVersion(String name, String version) {
    }
}
