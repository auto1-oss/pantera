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
package com.auto1.pantera.npm.proxy.http;

import com.auto1.pantera.asto.Concatenation;
import com.auto1.pantera.asto.Remaining;
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.cooldown.metadata.AllVersionsBlockedException;
import com.auto1.pantera.cooldown.metadata.CooldownMetadataService;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.npm.Tarballs;
import com.auto1.pantera.npm.cooldown.NpmMetadataFilter;
import com.auto1.pantera.npm.cooldown.NpmMetadataParser;
import com.auto1.pantera.npm.cooldown.NpmMetadataRewriter;
import com.auto1.pantera.npm.misc.MetadataETag;
import com.auto1.pantera.npm.proxy.NpmProxy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hu.akarnokd.rxjava2.interop.SingleInterop;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Resolves {@code GET /<pkg>/<version-or-tag>} for npm proxy repositories
 * against the (cooldown-filtered) packument, and emits a single version's
 * manifest with its tarball URL rewritten to point back at Pantera.
 *
 * <p>Before this class, proxy repositories answered this endpoint with a
 * {@code {name, modified}} stub that still 200s — enough to satisfy older
 * npm/yarn clients that only sniff the shape, but not corepack, which reads
 * {@code dist.tarball} out of the response and fails when it is absent. This
 * class replaces the stub with a real per-version manifest, resolved the same
 * way {@code com.auto1.pantera.npm.http.SingleVersionSlice} does for hosted
 * repositories.</p>
 *
 * <p><b>Resolution goes through the packument, never a passthrough.</b>
 * Proxying {@code /<pkg>/<version>} straight to the upstream registry would
 * bypass cooldown filtering entirely — a version blocked by cooldown must
 * 404 here exactly as it is hidden from the full packument.</p>
 *
 * @since 2.3.0
 */
public final class VersionManifestResolver {

    /**
     * NPM Proxy facade.
     */
    private final NpmProxy npm;

    /**
     * Cooldown metadata filtering service; {@code null} when cooldown is
     * not wired for this repository.
     */
    private final CooldownMetadataService cooldownMetadata;

    /**
     * Repository type (e.g. {@code "npm"}); {@code null} when cooldown is
     * not wired for this repository.
     */
    private final String repoType;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * Ctor.
     *
     * @param npm NPM Proxy facade
     * @param cooldownMetadata Cooldown metadata filtering service, or
     *  {@code null} if cooldown is not wired for this repository
     * @param repoType Repository type, or {@code null} if cooldown is not
     *  wired for this repository
     * @param repoName Repository name
     */
    public VersionManifestResolver(
        final NpmProxy npm,
        final CooldownMetadataService cooldownMetadata,
        final String repoType,
        final String repoName
    ) {
        this.npm = npm;
        this.cooldownMetadata = cooldownMetadata;
        this.repoType = repoType;
        this.repoName = repoName;
    }

    /**
     * Split {@code <pkg>/<ref>} into package and version-or-tag reference.
     *
     * <p>npm package names cannot contain {@code /} unless scoped, so the
     * split is exact: two segments with a leading {@code @} are a scoped
     * <em>package name</em> ({@code @types/node}), while two segments without
     * one are package + reference ({@code pnpm/11.5.1}).</p>
     *
     * @param rawPath Package path, with or without a leading slash
     * @return Parsed pair, or empty when the path is a plain packument request
     */
    static Optional<PackageRef> parse(final String rawPath) {
        Optional<PackageRef> result = Optional.empty();
        if (rawPath != null && !rawPath.isEmpty()) {
            final String trimmed;
            if (rawPath.startsWith("/")) {
                trimmed = rawPath.substring(1);
            } else {
                trimmed = rawPath;
            }
            final String[] segments = trimmed.split("/");
            String pkg = null;
            String ref = null;
            if (segments.length == 2 && !segments[0].startsWith("@")) {
                pkg = segments[0];
                ref = segments[1];
            } else if (segments.length == 3 && segments[0].startsWith("@")) {
                pkg = segments[0] + "/" + segments[1];
                ref = segments[2];
            }
            if (pkg != null && !ref.isEmpty() && !"-".equals(ref)) {
                result = Optional.of(
                    new PackageRef(
                        URLDecoder.decode(pkg, StandardCharsets.UTF_8),
                        URLDecoder.decode(ref, StandardCharsets.UTF_8)
                    )
                );
            }
        }
        return result;
    }

    /**
     * Resolve one version-or-tag reference against the (cooldown-filtered)
     * packument and emit its manifest.
     *
     * <p>Resolution goes through the packument rather than proxying
     * {@code /<pkg>/<version>} upstream verbatim, because a passthrough would
     * bypass cooldown filtering entirely — a blocked version must 404 here.</p>
     *
     * @param pkg Package name
     * @param ref Version string or dist-tag name
     * @param tarballPrefix Client-facing base for the tarball URL
     * @param clientETag Client's If-None-Match value, if any
     * @param auditCtx Audit context captured before the async hop
     * @param owner Request owner
     * @return Response
     */
    CompletableFuture<Response> resolve(
        final String pkg, final String ref, final String tarballPrefix,
        final Optional<String> clientETag, final AuditContext auditCtx, final String owner
    ) {
        return this.packumentBytes(pkg).thenCompose(raw -> {
            final CompletableFuture<Response> result;
            if (raw.length == 0) {
                result = CompletableFuture.completedFuture(
                    VersionManifestResolver.notFound(pkg, ref)
                );
            } else if (this.cooldownMetadata == null || this.repoType == null) {
                AuditLogger.resolution(auditCtx, this.repoType, this.repoName, pkg, owner, List.of());
                result = CompletableFuture.completedFuture(
                    this.emit(raw, pkg, ref, tarballPrefix, clientETag)
                );
            } else {
                result = this.cooldownMetadata.filterMetadata(
                    this.repoType, this.repoName, pkg, raw,
                    new NpmMetadataParser(), new NpmMetadataFilter(), new NpmMetadataRewriter(),
                    auditCtx, owner
                ).handle((filtered, ex) -> {
                    final Response response;
                    if (ex == null) {
                        response = this.emit(filtered, pkg, ref, tarballPrefix, clientETag);
                    } else if (VersionManifestResolver.allBlocked(ex, pkg)) {
                        response = VersionManifestResolver.notFound(pkg, ref);
                    } else {
                        response = this.emit(raw, pkg, ref, tarballPrefix, clientETag);
                    }
                    return response;
                });
            }
            return result;
        });
    }

    /**
     * Fetch the raw upstream packument bytes for a package. Rx interop
     * copied verbatim from {@code DownloadPackageSlice.resolveLatestFromRaw}
     * (the shape shared with {@code serveLatestManifest}): concatenate the
     * content stream into a single byte array, then bridge the RxJava
     * {@code Maybe} to a {@code CompletableFuture}, defaulting to an empty
     * array (rather than never completing) when the package does not exist.
     *
     * @param pkg Package name
     * @return Future resolving to the raw packument bytes, or an empty array
     *  if the package does not exist upstream
     */
    private CompletableFuture<byte[]> packumentBytes(final String pkg) {
        return this.npm.getPackageMetadataOnly(pkg)
            .flatMap(metadata -> this.npm.getPackageContentStream(pkg)
                .flatMap(contentStream -> {
                    final long contentSize = contentStream.size().orElse(-1L);
                    return Concatenation.withSize(contentStream, contentSize)
                        .single()
                        .map(buf -> new Remaining(buf).bytes())
                        .toMaybe();
                })
            )
            .toSingle(new byte[0])
            .to(SingleInterop.get())
            .toCompletableFuture();
    }

    /**
     * Walk the cause chain for {@link AllVersionsBlockedException}, logging
     * the standard {@code all_versions_blocked} record on a hit (mirroring
     * {@code DownloadPackageSlice.serveLatestManifest}, ":697-715").
     *
     * @param ex Throwable raised by {@link CooldownMetadataService#filterMetadata}
     * @param pkg Package name, for the log record
     * @return true if the cause chain contains {@link AllVersionsBlockedException}
     */
    private static boolean allBlocked(final Throwable ex, final String pkg) {
        Throwable cause = ex;
        boolean blocked = false;
        while (cause != null) {
            if (cause instanceof AllVersionsBlockedException) {
                EcsLogger.info("com.auto1.pantera.npm")
                    .message("All versions blocked by cooldown (version resolver)")
                    .eventCategory("database")
                    .eventAction("all_versions_blocked")
                    .field("package.name", pkg)
                    .field("log.source", "application")
                    .log();
                blocked = true;
                break;
            }
            cause = cause.getCause();
        }
        return blocked;
    }

    /**
     * Extract a single version's manifest from packument bytes, rewrite its
     * tarball URL, and build the client response.
     *
     * @param packumentBytes (Cooldown-filtered or raw) packument bytes
     * @param pkg Package name
     * @param ref Version string or dist-tag name
     * @param tarballPrefix Client-facing base for the tarball URL
     * @param clientETag Client's If-None-Match value, if any
     * @return Response
     */
    Response emit(
        final byte[] packumentBytes, final String pkg, final String ref,
        final String tarballPrefix, final Optional<String> clientETag
    ) {
        try {
            final ObjectMapper mapper = new ObjectMapper();
            final JsonNode root = mapper.readTree(packumentBytes);
            final Optional<JsonNode> manifest = VersionManifestResolver.manifestFor(root, ref);
            final Response response;
            if (manifest.isEmpty()) {
                response = VersionManifestResolver.notFound(pkg, ref);
            } else {
                final JsonNode copy = manifest.get().deepCopy();
                final JsonNode dist = copy.get("dist");
                if (dist != null && dist.isObject() && dist.has("tarball")) {
                    ((ObjectNode) dist).put(
                        "tarball",
                        Tarballs.rewriteTarball(dist.get("tarball").asText(), tarballPrefix)
                    );
                }
                final byte[] body = mapper.writeValueAsBytes(copy);
                final String etag = new MetadataETag(body).calculate();
                if (clientETag.isPresent() && clientETag.get().equals(etag)) {
                    response = ResponseBuilder.from(RsStatus.NOT_MODIFIED)
                        .header("ETag", etag)
                        .header("Cache-Control", "public, max-age=300")
                        .build();
                } else {
                    response = ResponseBuilder.ok()
                        .header("Content-Type", "application/json; charset=utf-8")
                        .header("ETag", etag)
                        .header("Cache-Control", "public, max-age=300")
                        .body(body)
                        .build();
                }
            }
            return response;
        } catch (final IOException ex) {
            EcsLogger.warn("com.auto1.pantera.npm")
                .message("Failed to resolve version manifest from packument")
                .eventCategory("web")
                .eventAction("version_resolution")
                .eventOutcome("failure")
                .field("package.name", pkg)
                .error(ex)
                .field("log.source", "application")
                .log();
            return VersionManifestResolver.notFound(pkg, ref);
        }
    }

    /**
     * Resolve {@code ref} against a packument's {@code versions} map: a
     * literal version wins; only when it does not match is {@code ref}
     * looked up as a dist-tag name and the tag's target version tried.
     *
     * @param root Parsed packument
     * @param ref Version string or dist-tag name
     * @return The resolved manifest node, or empty if neither a matching
     *  version nor tag exists
     */
    private static Optional<JsonNode> manifestFor(final JsonNode root, final String ref) {
        final JsonNode versions = root.get("versions");
        Optional<JsonNode> result = Optional.empty();
        if (versions != null && versions.isObject()) {
            if (versions.has(ref)) {
                result = Optional.of(versions.get(ref));
            } else {
                final JsonNode distTags = root.get("dist-tags");
                if (distTags != null && distTags.isObject() && distTags.has(ref)) {
                    final String resolved = distTags.get(ref).asText();
                    if (versions.has(resolved)) {
                        result = Optional.of(versions.get(resolved));
                    }
                }
            }
        }
        return result;
    }

    /**
     * Honest 404 body naming both the package and the unresolved reference —
     * matches {@code SingleVersionSlice}'s body shape exactly, and is never
     * the legacy {@code {name, modified}} stub.
     *
     * @param pkg Package name
     * @param ref Version string or dist-tag name
     * @return 404 response
     */
    private static Response notFound(final String pkg, final String ref) {
        return ResponseBuilder.notFound()
            .jsonBody(String.format(
                "{\"error\":\"version not found: %s\",\"package\":\"%s\"}", ref, pkg
            ))
            .build();
    }

    /**
     * Parsed package name / version-or-tag reference pair.
     */
    static final class PackageRef {

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
