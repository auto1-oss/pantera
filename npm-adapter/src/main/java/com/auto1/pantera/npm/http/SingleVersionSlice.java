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
package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.ClientBaseUrl;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.npm.PerVersionLayout;
import com.auto1.pantera.npm.Tarballs;
import com.auto1.pantera.npm.misc.MetadataETag;

import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import org.slf4j.MDC;

/**
 * {@code GET /<pkg>/<version>} and {@code GET /<pkg>/latest} for hosted npm
 * repositories — returns a single version's manifest object (not the whole
 * packument), with the tarball URL rewritten to point back at Pantera, the
 * same way {@code DownloadPackageSlice} rewrites the full packument.
 *
 * <p>{@code latest} — or any other dist-tag name — is resolved through the
 * durable {@code .dist-tags.json} sidecar via {@link PerVersionLayout}
 * (WS4-npm.3): a literal version string is tried first, so a version that
 * happens to share a name with a tag always resolves to itself.</p>
 *
 * @since 2.3.0
 */
public final class SingleVersionSlice implements Slice {

    /**
     * Base URL for tarball rewriting.
     */
    private final URL base;

    /**
     * Storage backing the repository.
     */
    private final Storage storage;

    /**
     * Repository name (audit only).
     */
    private final String repoName;

    /**
     * Ctor.
     *
     * @param base Base URL
     * @param storage Storage
     * @param repoName Repository name
     */
    public SingleVersionSlice(final URL base, final Storage storage, final String repoName) {
        this.base = base;
        this.storage = storage;
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
            final Optional<PackageRef> parsed = parse(line.uri().getPath());
            if (parsed.isEmpty()) {
                return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
            }
            final PackageRef ref = parsed.get();
            final Key packageKey = new Key.From(ref.pkg());
            final PerVersionLayout layout = new PerVersionLayout(this.storage);
            final Optional<String> clientETag = extractClientETag(headers);
            return layout.hasVersions(packageKey).thenCompose(has -> {
                if (!has) {
                    return CompletableFuture.completedFuture(notFound(ref));
                }
                return this.resolve(layout, packageKey, ref.ref()).thenApply(versionJson -> {
                    AuditLogger.resolution(ctx, "npm", this.repoName, ref.pkg(), owner, List.of());
                    return versionJson.isEmpty()
                        ? notFound(ref)
                        : this.serve(versionJson, clientETag, headers);
                });
            }).toCompletableFuture();
        });
    }

    /**
     * Resolve a version-or-tag reference: an exact per-version file wins;
     * otherwise the reference is looked up in the merged dist-tags map
     * (covers {@code latest} and any custom tag).
     *
     * @param layout Per-version layout
     * @param packageKey Package key
     * @param ref Version string or tag name
     * @return Completion stage with the resolved version's JSON, or an
     *  empty object when neither a matching version nor tag exists
     */
    private CompletionStage<JsonObject> resolve(
        final PerVersionLayout layout, final Key packageKey, final String ref
    ) {
        return layout.readVersion(packageKey, ref).thenCompose(direct -> {
            if (!direct.isEmpty()) {
                return CompletableFuture.completedFuture(direct);
            }
            return layout.generateMetaJson(packageKey).thenCompose(meta -> {
                final JsonObject tags = meta.getJsonObject("dist-tags");
                if (tags != null && tags.containsKey(ref)) {
                    return layout.readVersion(packageKey, tags.getString(ref));
                }
                return CompletableFuture.completedFuture(Json.createObjectBuilder().build());
            });
        });
    }

    /**
     * Build the client-facing response for a resolved version manifest:
     * strip the internal {@code _publishTime} marker, rewrite the tarball
     * URL, compute an ETag, and honour a matching {@code If-None-Match}.
     *
     * @param versionJson Resolved per-version manifest
     * @param clientETag Client's If-None-Match value, if present
     * @param headers Request headers
     * @return Response
     */
    private Response serve(
        final JsonObject versionJson, final Optional<String> clientETag, final Headers headers
    ) {
        final JsonObjectBuilder builder = Json.createObjectBuilder(versionJson);
        builder.remove("_publishTime");
        if (versionJson.containsKey("dist") && versionJson.getJsonObject("dist").containsKey("tarball")) {
            final String prefix = new ClientBaseUrl(headers).stamped()
                .orElseGet(() -> this.base.toString());
            final String rewritten = Tarballs.rewriteTarball(
                versionJson.getJsonObject("dist").getString("tarball"), prefix
            );
            builder.add(
                "dist",
                Json.createObjectBuilder(versionJson.getJsonObject("dist"))
                    .add("tarball", rewritten)
                    .build()
            );
        }
        final String responseBody = builder.build().toString();
        final String etag = new MetadataETag(responseBody).calculate();
        final String vary = new ClientBaseUrl(headers).varyHeaderValue();
        if (clientETag.isPresent() && clientETag.get().equals(etag)) {
            return ResponseBuilder.from(RsStatus.NOT_MODIFIED)
                .header("ETag", etag)
                .header("Cache-Control", "public, max-age=300")
                .varyHeader(vary)
                .build();
        }
        return ResponseBuilder.ok()
            .header("Content-Type", "application/json; charset=utf-8")
            .header("ETag", etag)
            .header("Cache-Control", "public, max-age=300")
            .varyHeader(vary)
            .jsonBody(responseBody)
            .build();
    }

    /**
     * Honest 404 body naming both the package and the unresolved reference.
     *
     * @param ref Parsed package/reference pair
     * @return 404 response
     */
    private static Response notFound(final PackageRef ref) {
        return ResponseBuilder.notFound()
            .jsonBody(String.format(
                "{\"error\":\"version not found: %s\",\"package\":\"%s\"}", ref.ref(), ref.pkg()
            ))
            .build();
    }

    /**
     * Split {@code /<pkg>/<ref>} (optionally scoped) into package key and
     * version-or-tag reference. Only shapes the router already restricted
     * this slice to (see {@code NpmSlice}'s route ordering) are accepted;
     * anything else is treated defensively as unmatched.
     *
     * @param rawPath Decoded request path
     * @return Parsed package/reference pair, or empty if the shape is
     *  unexpected (defensive; the router pattern should already exclude this)
     */
    private static Optional<PackageRef> parse(final String rawPath) {
        final String trimmed = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        final String[] segments = trimmed.split("/");
        final String pkg;
        final String ref;
        if (segments.length == 2 && !segments[0].startsWith("@")) {
            pkg = segments[0];
            ref = segments[1];
        } else if (segments.length == 3 && segments[0].startsWith("@")) {
            pkg = segments[0] + "/" + segments[1];
            ref = segments[2];
        } else {
            return Optional.empty();
        }
        if (ref.isEmpty() || "-".equals(ref)) {
            return Optional.empty();
        }
        return Optional.of(
            new PackageRef(
                URLDecoder.decode(pkg, StandardCharsets.UTF_8),
                URLDecoder.decode(ref, StandardCharsets.UTF_8)
            )
        );
    }

    /**
     * Extract client ETag from If-None-Match header.
     *
     * @param headers Request headers
     * @return Optional ETag value
     */
    private static Optional<String> extractClientETag(final Headers headers) {
        return headers.stream()
            .filter(h -> "If-None-Match".equalsIgnoreCase(h.getKey()))
            .map(Header::getValue)
            .map(etag -> etag.startsWith("W/") ? etag.substring(2) : etag)
            .map(etag -> etag.replaceAll("\"", ""))
            .findFirst();
    }

    /**
     * Parsed package name / version-or-tag reference pair.
     */
    private static final class PackageRef {

        /**
         * Package name (URL-decoded).
         */
        private final String pkgName;

        /**
         * Version string or tag name (URL-decoded).
         */
        private final String reference;

        /**
         * Ctor.
         *
         * @param pkgName Package name
         * @param reference Version string or tag name
         */
        PackageRef(final String pkgName, final String reference) {
            this.pkgName = pkgName;
            this.reference = reference;
        }

        /**
         * @return Package name
         */
        String pkg() {
            return this.pkgName;
        }

        /**
         * @return Version string or tag name
         */
        String ref() {
            return this.reference;
        }
    }
}
